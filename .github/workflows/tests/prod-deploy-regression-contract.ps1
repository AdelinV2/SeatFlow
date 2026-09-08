$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

function Read-RequiredFile([string]$RelativePath) {
    $absolutePath = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Missing required production deploy file: $RelativePath"
    }
    return Get-Content -LiteralPath $absolutePath -Raw
}

function Assert-Matches([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

$provisionScript = Read-RequiredFile 'infra/scripts/ensure-production-databases.sh'
$deployScript = Read-RequiredFile 'infra/scripts/deploy-compose-release.sh'
$startScript = Read-RequiredFile 'infra/scripts/start-compose-release.sh'
$verifyScript = Read-RequiredFile 'infra/scripts/verify-compose-release.sh'

Assert-Matches $provisionScript 'docker-compose\.prod-health\.yml' 'Database provisioning must use the production Compose health contract.'
Assert-Matches $provisionScript 'up -d postgres' 'Database provisioning must start PostgreSQL before checking service databases.'
Assert-Matches $provisionScript 'createdb -U "\$POSTGRES_USER" -O "\$DB_USERNAME"' 'Missing databases must be created with the application role as owner.'
Assert-Matches $provisionScript '(?s)service_databases=\(.*seatflow_user.*seatflow_seatmap.*seatflow_event.*seatflow_reservation.*seatflow_payment.*seatflow_ticket.*seatflow_notification.*seatflow_analytics.*\)' 'Provisioning must cover every database-backed production service, including analytics.'
Assert-Matches $provisionScript 'Production service databases are provisioned' 'Provisioning must finish with an explicit success signal.'

$provisionIndex = $deployScript.IndexOf('ensure-production-databases.sh', [System.StringComparison]::Ordinal)
$migrationIndex = $deployScript.IndexOf('run-production-migrations.sh', [System.StringComparison]::Ordinal)
if ($provisionIndex -lt 0) {
    throw 'Production rollout must call ensure-production-databases.sh.'
}
if ($migrationIndex -lt 0) {
    throw 'Production rollout must call run-production-migrations.sh.'
}
if ($provisionIndex -gt $migrationIndex) {
    throw 'Production databases must be provisioned before Flyway migrations start.'
}

Assert-Matches $startScript 'start_batch 480 analytics-service ai-service' 'Staged startup must explicitly start analytics-service and ai-service.'
Assert-Matches $startScript 'start_batch 180 frontend' 'Frontend startup must remain after backend readiness.'
Assert-Matches $verifyScript '(?s)required_services=\(.*analytics-service ai-service frontend' 'Release verification must continue to require analytics-service and ai-service.'

Write-Host 'SeatFlow production deploy regression contract checks passed.'
