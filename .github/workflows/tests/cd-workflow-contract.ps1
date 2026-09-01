$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$requiredFiles = @(
    '.github/workflows/cd-staging.yml',
    '.github/workflows/cd-production.yml',
    '.github/actions/setup-gcp-wif/action.yml',
    'infra/scripts/render-runtime-env.sh',
    'infra/scripts/deploy-compose-release.sh',
    'infra/scripts/configure-http-edge.sh',
    'infra/scripts/configure-https-edge.sh',
    'infra/scripts/run-production-migrations.sh',
    'infra/scripts/verify-compose-release.sh',
    'infra/scripts/rollback-compose-release.sh'
)

$contents = @{}
foreach ($relativePath in $requiredFiles) {
    $absolutePath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Missing required CD contract file: $relativePath"
    }
    $contents[$relativePath] = Get-Content -LiteralPath $absolutePath -Raw
}

$allText = ($contents.Values -join "`n")
$forbiddenPatterns = @(
    '(?i)credentials_json\s*:',
    '(?i)activate-service-account',
    '(?i)--key-file',
    '(?i)service[_-]?account.*\.json',
    '(?i)flyway\s+(clean|undo|repair)',
    '(?i)gcloud\s+run\s+',
    '(?i)gcloud\s+sql\s+',
    '(?i)gcloud\s+container\s+',
    '(?i)gcloud\s+redis\s+',
    '(?i)terraform\s+destroy',
    '(?i)SEATFLOW_IMAGE_TAG\s*[:=]\s*latest'
)

foreach ($pattern in $forbiddenPatterns) {
    if ($allText -match $pattern) {
        throw "Forbidden CD pattern detected: $pattern"
    }
}

function Assert-Matches([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

$staging = $contents['.github/workflows/cd-staging.yml']
$production = $contents['.github/workflows/cd-production.yml']
$wifAction = $contents['.github/actions/setup-gcp-wif/action.yml']
$renderScript = $contents['infra/scripts/render-runtime-env.sh']
$deployScript = $contents['infra/scripts/deploy-compose-release.sh']
$httpEdgeScript = $contents['infra/scripts/configure-http-edge.sh']
$httpsEdgeScript = $contents['infra/scripts/configure-https-edge.sh']
$migrationScript = $contents['infra/scripts/run-production-migrations.sh']
$rollbackScript = $contents['infra/scripts/rollback-compose-release.sh']

Assert-Matches $staging '(?m)^\s*id-token:\s*write\s*$' 'Develop CD must request id-token: write.'
Assert-Matches $staging '(?m)^\s*contents:\s*read\s*$' 'Develop CD must restrict contents to read.'
Assert-Matches $staging 'staging-\$\{\{ github\.sha \}\}' 'Develop CD must publish the staging-SHA convenience tag.'
Assert-Matches $staging 'config --quiet' 'Develop CD must validate the production Compose configuration.'
Assert-Matches $staging 'terraform.+validate' 'Develop CD must validate Terraform.'

Assert-Matches $production '(?m)^\s*id-token:\s*write\s*$' 'Production CD must request id-token: write.'
Assert-Matches $production '(?m)^\s*contents:\s*read\s*$' 'Production CD must restrict contents to read.'
Assert-Matches $production '(?m)^\s*environment:\s*production\s*$' 'Production deployment must use the protected production Environment.'
Assert-Matches $production '--tunnel-through-iap' 'Production deployment must use IAP.'
Assert-Matches $production 'gcloud compute ssh' 'Production deployment must use authenticated GCP SSH.'
Assert-Matches $production '\$\{\{ github\.sha \}\}' 'Production deployment must select the immutable Git SHA.'
Assert-Matches $production 'infra/systemd' 'Production release bundle must include the Prometheus systemd units.'

Assert-Matches $wifAction 'google-github-actions/auth@v3' 'The reusable action must use the official WIF auth action.'
Assert-Matches $wifAction 'workload_identity_provider:' 'The reusable action must require a WIF provider.'
Assert-Matches $wifAction 'service_account:' 'The reusable action must impersonate the deploy service account.'

Assert-Matches $renderScript 'metadata\.google\.internal' 'Runtime secrets must use the VM metadata identity.'
Assert-Matches $renderScript 'secretmanager\.googleapis\.com' 'Runtime secrets must come from Secret Manager.'
Assert-Matches $renderScript 'install.+0600' 'Runtime secret files must be mode 0600.'
Assert-Matches $renderScript 'Fresh Prometheus scrape token is missing' 'Runtime rendering must require a freshly refreshed Prometheus token.'
Assert-Matches $deployScript 'docker compose' 'Deployment must use Docker Compose.'
Assert-Matches $deployScript 'run-production-migrations\.sh' 'Deployment must run the explicit migration stage.'
Assert-Matches $deployScript 'rollback-compose-release\.sh' 'Deployment must invoke image rollback on failure.'
Assert-Matches $deployScript 'configure-http-edge\.sh' 'Deployment must configure the internal/pre-DNS HTTP edge before rollout verification.'
Assert-Matches $deployScript 'configure-https-edge\.sh' 'Deployment must attempt HTTPS only after the application rollout is healthy.'
Assert-Matches $deployScript 'edge_status.+-eq 10' 'Deployment must treat the explicit DNS-not-ready edge status as a deferred cutover, not an application failure.'
Assert-Matches $httpEdgeScript '127\.0\.0\.1:8080' 'The host HTTP edge must proxy to the loopback-only frontend listener.'
Assert-Matches $httpsEdgeScript 'metadata\.google\.internal' 'HTTPS cutover must compare DNS against the VM external IP.'
Assert-Matches $httpsEdgeScript 'exit 10' 'HTTPS cutover must expose a distinct DNS-not-ready status.'
Assert-Matches $httpsEdgeScript 'certbot certonly' 'HTTPS cutover must provision the certificate only after DNS is ready.'
Assert-Matches $migrationScript 'migrations-\$\{image_tag\}\.done' 'Migrations must be release-idempotent.'
Assert-Matches $migrationScript 'docker-compose\.prod-health\.yml' 'Migration dependencies must use the same production health override as rollout verification.'
Assert-Matches $migrationScript 'verify_flyway_history' 'Migration completion must verify Flyway history in PostgreSQL.'
Assert-Matches $migrationScript 'verify_required_schema' 'Migration completion must verify required service tables in PostgreSQL.'
Assert-Matches $migrationScript 'Successfully applied \[0-9\]\+ migration' 'Migration completion must require an explicit Flyway applied-migration signal.'
Assert-Matches $migrationScript 'application_services=\(' 'Migration startup must quiesce application containers on the single production VM.'
if ($migrationScript -match '\|Started \.\*Application') {
    throw 'A generic Spring Started Application log line must never count as migration success.'
}
Assert-Matches $rollbackScript 'database schema was not changed' 'Rollback must explicitly preserve forward database state.'

# Secret-bearing values must never be printed to stdout/stderr. The only allowed
# matching output statement is the deliberate write into the root-only temporary
# runtime environment file, which is installed as mode 0600 immediately after.
$secretOutputPattern = '^\s*(echo|printf)\s+[^\r\n]*(POSTGRES_PASSWORD|DB_PASSWORD|REDIS_PASSWORD|STRIPE_API_KEY|STRIPE_WEBHOOK_SECRET|RESEND_API_KEY|GRAFANA_ADMIN_PASSWORD|ACCESS_TOKEN)'
foreach ($line in ($allText -split "`r?`n")) {
    if ($line -match $secretOutputPattern -and $line -notmatch '>>\s*"\$\{temp_runtime\}"\s*$') {
        throw "A secret-bearing variable may be written directly to logs: $line"
    }
}

$prodCompose = Get-Content -LiteralPath (Join-Path $repositoryRoot 'docker/docker-compose.prod.yml') -Raw
$flywayDisabledCount = ([regex]::Matches($prodCompose, 'SPRING_FLYWAY_ENABLED:\s*"false"')).Count
if ($flywayDisabledCount -ne 7) {
    throw "Expected Flyway startup to be disabled for exactly seven database-backed services; found $flywayDisabledCount."
}

Write-Host 'SeatFlow CD workflow contract checks passed.'
