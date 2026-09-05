# P12-007 legacy-contract gate (PowerShell).
#
# Fails closed when booking-semantic legacy uses reappear. Legitimate
# catalog/audit parent-event references (display-only eventId derived from the
# trusted session context, ticket/scanner showing metadata) are allowlisted by
# scoping each check to the prohibited area instead of banning "eventId" globally.
#
# Usage (from backend/):  powershell -File scripts/check-p12-007-legacy.ps1
# Usage (from repo root): powershell -File backend/scripts/check-p12-007-legacy.ps1

$ErrorActionPreference = 'Stop'

function RepoRoot {
    # NOTE (REV-002): this script lives at <root>/backend/scripts, and
    # backend/AGENTS.md exists as a subsystem rules file. Resolving to the
    # FIRST ancestor with AGENTS.md stopped at backend/, making every
    # FilesUnder scan vacuous (backend/backend/... does not exist) so the
    # gate always passed. Resolve to the HIGHEST ancestor holding AGENTS.md.
    $dir = $PSScriptRoot
    $found = $null
    while ($dir) {
        if (Test-Path (Join-Path $dir 'AGENTS.md')) { $found = $dir }
        $parent = Split-Path $dir -Parent
        if (-not $parent -or $parent -eq $dir) { break }
        $dir = $parent
    }
    if (-not $found) { throw 'Repository root (AGENTS.md) not found.' }
    return $found
}

$root = RepoRoot
$failures = @()

function CodeLines([string]$path) {
    # Returns non-comment source lines so P12-007 explanatory comments that name
    # legacy fields do not trip the gate.
    $lines = Get-Content $path
    $out = @()
    foreach ($line in $lines) {
        $trimmed = $line.TrimStart()
        if ($trimmed.StartsWith('//')) { continue }
        if ($trimmed.StartsWith('*')) { continue }
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed.StartsWith('<!--')) { continue }
        $out += $line
    }
    return $out
}

function CheckPattern([string]$label, [string[]]$files, [string]$pattern) {
    foreach ($file in $files) {
        if (-not (Test-Path $file)) { continue }
        $hits = @(CodeLines $file | Select-String -Pattern $pattern)
        foreach ($hit in $hits) {
            $rel = $file.Replace($root + [IO.Path]::DirectorySeparatorChar, '')
            # NOTE: $script: scope is required here. A bare "$failures +=" inside
            # a function creates a function-local copy (PowerShell assignment
            # scoping), which silently discarded every finding and made this
            # gate always pass. See REV-002.
            $script:failures += "$label :: $rel :: line $($hit.LineNumber): $($hit.Line.Trim())"
        }
    }
}

function FilesUnder([string]$relativeDir, [string]$include) {
    $base = Join-Path $root $relativeDir
    if (-not (Test-Path $base)) { return @() }
    return @(Get-ChildItem $base -Recurse -Include $include -File | Select-Object -ExpandProperty FullName)
}

# 1. event-service main: no event-level schedule field remains.
$eventMain = FilesUnder 'backend/services/event-service/src/main/java' '*.java'
CheckPattern 'event-service legacy schedule' $eventMain 'eventDate|getEventDate|setEventDate|event_date'

# 2. reservation-service main: no compat booking key or mismatch guard.
$resMain = FilesUnder 'backend/services/reservation-service/src/main/java' '*.java'
CheckPattern 'reservation compat booking key' $resMain 'rejectCompatEventMismatch|request\.eventId\(\)'

# 2b. reservation-service main: no booking-semantic event-level instant remains.
# P12-007: seat-map/pricing carry venue layout + pricing only; showing schedule
# lives exclusively on EventSession (trusted SessionBookingContextDto). Any
# eventDate/event_date use in the booking path fails this gate. There is
# currently no allowlisted display-only use in reservation-service main; add
# one only with a comment citing the proven display-only call site.
CheckPattern 'reservation legacy booking instant' $resMain 'eventDate|event_date'

# 3. realtime-service main: old event-scoped topic removed.
$rtMain = FilesUnder 'backend/services/realtime-service/src/main/java' '*.java'
CheckPattern 'realtime legacy topic' $rtMain '/topic/events/|LEGACY_DESTINATION'

# 4. frontend: no legacy schedule writes/reads in event catalog surfaces.
$feModels = @(
    (Join-Path $root 'frontend/src/app/models/event.model.ts'),
    (Join-Path $root 'frontend/src/app/models/admin-event.model.ts'),
    (Join-Path $root 'frontend/src/app/models/seat.model.ts')
)
CheckPattern 'frontend model legacy schedule' $feModels 'eventDate\s*:'
CheckPattern 'frontend reservation booking key' @((Join-Path $root 'frontend/src/app/services/reservation-api.service.ts')) 'eventId\?\s*:'
CheckPattern 'frontend realtime compat alias' @((Join-Path $root 'frontend/src/app/services/websocket.service.ts')) 'connectForEvent'
$feEventFeatures = @()
$feEventFeatures += FilesUnder 'frontend/src/app/features/events' '*.ts'
$feEventFeatures += FilesUnder 'frontend/src/app/features/events' '*.html'
$feEventFeatures += FilesUnder 'frontend/src/app/features/admin/events' '*.ts'
$feEventFeatures += FilesUnder 'frontend/src/app/features/admin/events' '*.html'
$feEventFeatures = @($feEventFeatures | Where-Object { $_ -notlike '*.spec.ts' })
CheckPattern 'frontend event surface legacy schedule' $feEventFeatures '\.eventDate\b'
CheckPattern 'frontend admin legacy schedule write' $feEventFeatures 'formControlName="eventDate"|eventDate:\s*isoDate'

# 5. Booking request construction must not send a legacy eventId.
$seatSelection = Join-Path $root 'frontend/src/app/features/booking/seat-selection/seat-selection.component.ts'
CheckPattern 'frontend booking legacy key' @($seatSelection) 'eventId:\s*this\.seatMap'

if ($failures.Count -gt 0) {
    Write-Output 'P12-007 legacy-contract gate FAILED:'
    foreach ($f in $failures) { Write-Output "  $f" }
    exit 1
}

Write-Output 'P12-007 legacy-contract gate PASSED.'
