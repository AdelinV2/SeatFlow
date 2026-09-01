[CmdletBinding()]
param(
    [string]$GrafanaUrl = $(if ([string]::IsNullOrWhiteSpace($env:GRAFANA_URL)) { 'http://localhost:3000' } else { $env:GRAFANA_URL }),
    [string]$PrometheusUrl = $(if ([string]::IsNullOrWhiteSpace($env:PROMETHEUS_URL)) { 'http://localhost:9090' } else { $env:PROMETHEUS_URL }),
    [switch]$SkipConnectivity
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$dashboardRoot = Join-Path $repoRoot 'docker\grafana\dashboards'
$dashboardFiles = @(
    '01-seatflow-executive-and-business.json',
    '02-microservices-sre-and-red-health.json',
    '03-kafka-and-outbox-pipeline.json',
    '04-security-and-auth-audit.json'
)
$manifestPath = Join-Path $dashboardRoot 'dashboard-schema-version.json'
$datasourcePath = Join-Path $repoRoot 'docker\grafana\provisioning\datasources\datasource.yml'
$providerPath = Join-Path $repoRoot 'docker\grafana\provisioning\dashboards\dashboard-provider.yml'

function Assert-Valid {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw "Dashboard validation failed: $Message"
    }
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    Assert-Valid (Test-Path -LiteralPath $Path -PathType Leaf) "Missing JSON file: $Path"
    try {
        return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
    }
    catch {
        throw "Dashboard validation failed: invalid JSON in $Path. $($_.Exception.Message)"
    }
}

function Get-LeafPanels {
    param([Parameter(Mandatory = $false)]$Panels)

    foreach ($panel in @($Panels)) {
        $childPanels = $panel.PSObject.Properties['panels']
        if ($null -ne $childPanels -and $null -ne $childPanels.Value) {
            Get-LeafPanels -Panels $childPanels.Value
        }
        else {
            $panel
        }
    }
}

function Get-QueryText {
    param([Parameter(Mandatory = $true)]$Target)

    $parts = @()
    foreach ($propertyName in @('expr', 'query', 'rawSql')) {
        $property = $Target.PSObject.Properties[$propertyName]
        if ($null -ne $property -and $null -ne $property.Value) {
            $parts += [string]$property.Value
        }
    }
    return ($parts -join "`n")
}

Write-Host "Validating SeatFlow Grafana dashboard assets in $dashboardRoot"

$manifest = Read-JsonFile -Path $manifestPath
Assert-Valid ($manifest.manifestVersion -eq 1) 'dashboard manifestVersion must be 1.'
Assert-Valid ($manifest.dashboardSchemaVersion -eq 39) 'dashboardSchemaVersion must be 39.'
Assert-Valid ($manifest.folderUid -eq 'seatflow-production') 'dashboard manifest folderUid is not stable.'

$manifestDashboards = @($manifest.dashboards)
Assert-Valid ($manifestDashboards.Count -eq $dashboardFiles.Count) 'Dashboard manifest must list exactly four dashboards.'

$manifestByFile = @{}
$manifestUids = @{}
foreach ($entry in $manifestDashboards) {
    Assert-Valid (-not [string]::IsNullOrWhiteSpace([string]$entry.file)) 'Manifest dashboard file is empty.'
    Assert-Valid (-not [string]::IsNullOrWhiteSpace([string]$entry.uid)) "Manifest UID is empty for $($entry.file)."
    Assert-Valid (-not $manifestByFile.ContainsKey([string]$entry.file)) "Duplicate dashboard file in manifest: $($entry.file)."
    Assert-Valid (-not $manifestUids.ContainsKey([string]$entry.uid)) "Duplicate dashboard UID in manifest: $($entry.uid)."
    $manifestByFile[[string]$entry.file] = $entry
    $manifestUids[[string]$entry.uid] = [string]$entry.file
}

$datasourceText = Get-Content -LiteralPath $datasourcePath -Raw
$provisionedDatasourceUids = @('seatflow-prometheus', 'seatflow-tempo', 'seatflow-logs')
foreach ($datasourceUid in $provisionedDatasourceUids) {
    Assert-Valid ($datasourceText -match "(?m)^\s*uid:\s*$([regex]::Escape($datasourceUid))\s*$") "Datasource UID is not provisioned: $datasourceUid"
}
Assert-Valid ($datasourceText -match 'tracesToLogsV2') 'Tempo datasource is missing tracesToLogsV2 configuration.'
Assert-Valid ($datasourceText -match 'derivedFields') 'Loki datasource is missing derivedFields configuration.'
Assert-Valid ($datasourceText -match 'key:\s*service\.name') 'Tempo tracesToLogsV2 must map service.name tag.'
Assert-Valid ($datasourceText -match 'value:\s*service_name') 'Tempo tracesToLogsV2 must map to Loki service_name label.'
Assert-Valid ($datasourceText -match 'trace\\\.id') 'Datasource trace correlation must be keyed by trace.id.'
Assert-Valid ($datasourceText -match 'datasourceUid:\s*seatflow-logs') 'Tempo must link to seatflow-logs.'
Assert-Valid ($datasourceText -match 'datasourceUid:\s*seatflow-tempo') 'Loki must link to seatflow-tempo.'

$providerText = Get-Content -LiteralPath $providerPath -Raw
Assert-Valid ($providerText -match "(?m)^\s*folder:\s*'SeatFlow Production'\s*$") 'Dashboard provider folder is not SeatFlow Production.'
Assert-Valid ($providerText -match '(?m)^\s*folderUid:\s*seatflow-production\s*$') 'Dashboard provider folderUid is not stable.'
Assert-Valid ($providerText -match '(?m)^\s*path:\s*/var/lib/grafana/dashboards\s*$') 'Dashboard provider path is incorrect.'
Assert-Valid ($providerText -match '(?m)^\s*updateIntervalSeconds:\s*30\s*$') 'Dashboard provider update interval must be 30 seconds.'
Assert-Valid ($providerText -match '(?m)^\s*disableDeletion:\s*true\s*$') 'Dashboard provider must disable deletion.'
Assert-Valid ($providerText -match '(?m)^\s*allowUiUpdates:\s*false\s*$') 'Dashboard provider must disable UI updates.'
Assert-Valid ($providerText -match '(?m)^\s*editable:\s*false\s*$') 'Dashboard provider must be read-only.'

$seenDashboardUids = @{}
$seenPanelIds = @{}
$queryCount = 0
$forbiddenLabelPattern = '(?i)(userId|reservationId|paymentId|ticketId|traceId|clientIp|user_id|reservation_id|payment_id|ticket_id|trace_id|client_ip)'
$allowedTemplateVariables = @('application', 'environment', 'event_type', 'status', 'payment_method')

foreach ($dashboardFile in $dashboardFiles) {
    Assert-Valid ($manifestByFile.ContainsKey($dashboardFile)) "Dashboard is not listed in manifest: $dashboardFile"
    $path = Join-Path $dashboardRoot $dashboardFile
    $dashboard = Read-JsonFile -Path $path
    $expectedManifestEntry = $manifestByFile[$dashboardFile]
    $uid = [string]$dashboard.uid

    Assert-Valid (-not [string]::IsNullOrWhiteSpace($uid)) "$dashboardFile has no UID."
    Assert-Valid ($uid -eq [string]$expectedManifestEntry.uid) "$dashboardFile UID does not match the manifest."
    Assert-Valid (-not $seenDashboardUids.ContainsKey($uid)) "Duplicate dashboard UID: $uid"
    $seenDashboardUids[$uid] = $dashboardFile
    Assert-Valid ($dashboard.editable -eq $false) "$dashboardFile must set editable=false."
    Assert-Valid ([string]$dashboard.timezone -eq 'UTC') "$dashboardFile must use UTC timezone."
    Assert-Valid ([int]$dashboard.schemaVersion -eq [int]$manifest.dashboardSchemaVersion) "$dashboardFile schemaVersion must be 39."
    $tags = @($dashboard.tags | ForEach-Object { [string]$_ })
    Assert-Valid ($tags -contains 'seatflow') "$dashboardFile is missing the seatflow tag."
    Assert-Valid ($tags -contains 'phase-10') "$dashboardFile is missing the phase-10 tag."

    foreach ($variable in @($dashboard.templating.list)) {
        $variableName = [string]$variable.name
        Assert-Valid ($allowedTemplateVariables -contains $variableName) "Unsupported template variable '$variableName' in $dashboardFile."
    }

    foreach ($panel in @(Get-LeafPanels -Panels $dashboard.panels)) {
        Assert-Valid ($null -ne $panel.id) "$dashboardFile contains a panel without an ID."
        $panelId = [string]$panel.id
        Assert-Valid (-not $seenPanelIds.ContainsKey($panelId)) "Duplicate panel ID $panelId in $dashboardFile and $($seenPanelIds[$panelId])."
        $seenPanelIds[$panelId] = $dashboardFile

        foreach ($target in @($panel.targets)) {
            $queryCount++
            $targetDatasource = $target.PSObject.Properties['datasource']
            Assert-Valid ($null -ne $targetDatasource) "$dashboardFile panel $panelId has a target without a datasource UID."
            Assert-Valid ($targetDatasource.Value -isnot [string]) "$dashboardFile panel $panelId uses a datasource display name instead of a UID."
            $targetUid = [string]$targetDatasource.Value.uid
            Assert-Valid ($provisionedDatasourceUids -contains $targetUid) "$dashboardFile panel $panelId references unknown datasource UID '$targetUid'."

            $queryText = Get-QueryText -Target $target
            Assert-Valid (-not [string]::IsNullOrWhiteSpace($queryText)) "$dashboardFile panel $panelId has an empty query target."
            Assert-Valid ($queryText -notmatch $forbiddenLabelPattern) "$dashboardFile panel $panelId query contains a forbidden high-cardinality label."
        }
    }
}

Assert-Valid ($seenDashboardUids.Count -eq $dashboardFiles.Count) 'Dashboard UIDs are not unique.'
Assert-Valid ($queryCount -gt 0) 'No query targets were found in the dashboards.'

if (-not $SkipConnectivity) {
    function Invoke-Endpoint {
        param(
            [Parameter(Mandatory = $true)][string]$Uri,
            [Parameter(Mandatory = $false)][hashtable]$Headers = @{}
        )

        try {
            return (Invoke-RestMethod -Uri $Uri -Method Get -Headers $Headers -TimeoutSec 10)
        }
        catch {
            throw "Dashboard validation failed: GET $Uri was not reachable. $($_.Exception.Message)"
        }
    }

    $grafanaHealth = Invoke-Endpoint -Uri "$($GrafanaUrl.TrimEnd('/'))/api/health"
    Assert-Valid ([string]$grafanaHealth.database -eq 'ok') "Grafana /api/health did not report database=ok."

    $grafanaUser = if ([string]::IsNullOrWhiteSpace($env:GRAFANA_ADMIN_USER)) { 'admin' } else { $env:GRAFANA_ADMIN_USER }
    $grafanaPassword = if ([string]::IsNullOrWhiteSpace($env:GRAFANA_ADMIN_PASSWORD)) { 'admin' } else { $env:GRAFANA_ADMIN_PASSWORD }
    $basicToken = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$grafanaUser`:$grafanaPassword"))
    $grafanaHeaders = @{ Authorization = "Basic $basicToken" }
    $search = Invoke-Endpoint -Uri "$($GrafanaUrl.TrimEnd('/'))/api/search?type=dash-db" -Headers $grafanaHeaders
    $searchUids = @($search | ForEach-Object { [string]$_.uid })
    foreach ($uid in $seenDashboardUids.Keys) {
        Assert-Valid ($searchUids -contains $uid) "Grafana /api/search did not return provisioned dashboard UID '$uid'."
    }

    $prometheusResult = Invoke-Endpoint -Uri "$($PrometheusUrl.TrimEnd('/'))/api/v1/query?query=up"
    Assert-Valid ([string]$prometheusResult.status -eq 'success') 'Prometheus /api/v1/query?query=up did not return status=success.'
}
else {
    Write-Host 'Connectivity checks skipped by -SkipConnectivity.'
}

Write-Host "Dashboard validation passed: $($dashboardFiles.Count) dashboards, $($seenPanelIds.Count) unique panels, $queryCount query targets."
