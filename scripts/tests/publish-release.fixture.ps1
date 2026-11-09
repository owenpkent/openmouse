<#
.SYNOPSIS
    Runs scripts/publish-release.ps1 end to end in a throwaway repository with
    stub tools, and checks how it handles the GitHub Release lookup.

.DESCRIPTION
    Builds a temporary git repository holding a copy of the publish script, a
    minimal app/build.gradle.kts (version 9.9.9), a pinned certificate, a
    keystore.properties placeholder and a prebuilt app-release.apk placeholder,
    tagged v9.9.9. The script under test then runs with -SkipBuild in a child
    process of the same PowerShell as this fixture (so Windows PowerShell 5.1
    tests 5.1), with stubs first on PATH and as the only Android SDK:

      gh         records its arguments; "release view" answers as the case
                 says (exists, absent: "release not found", or a lookup error:
                 "HTTP 401"); "release create" succeeds.
      apksigner  prints the pinned certificate, plus a warning on stderr.
      scripts/verify-release-tag.sh (in the throwaway repository only) accepts
                 the tag and writes a line to stderr. The real check is tested
                 in CI against signed tags.

    Nothing real is built, signed or published: there is no Gradle run, no
    keystore, no remote, and GH_TOKEN is a dummy, so even a gh that bypassed the
    stub could not reach a repository. The publish script itself has no test
    seams; every stub is found the way a real tool would be.

    Cases: a missing release is created once (ordinary, then -Draft); an
    existing release is refused with no create; a failed lookup fails with gh's
    message and no create. Exit 0 when every case passes, 1 otherwise.

      powershell -NoProfile -File scripts\tests\publish-release.fixture.ps1
      pwsh -NoProfile -File scripts/tests/publish-release.fixture.ps1

.PARAMETER Script
    The publish script to test (default: scripts/publish-release.ps1). Point it
    at an older copy to see what the fixture catches.

.PARAMETER Keep
    Keep the temporary directory for inspection.

.PARAMETER ShowOutput
    Print the publish script's output for passing cases too.
#>
param(
    [string]$Script,
    [switch]$Keep,
    [switch]$ShowOutput
)

$ErrorActionPreference = "Stop"

$checkout = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
if (-not $Script) { $Script = Join-Path $checkout "scripts/publish-release.ps1" }
$Script = (Resolve-Path -LiteralPath $Script).ProviderPath
$onWindows = [System.IO.Path]::DirectorySeparatorChar -eq '\'
$hostExe = (Get-Process -Id $PID).Path
$tag = "v9.9.9"
$digest = "0123456789abcdef" * 4

# Runs a native command for the fixture's own setup; stderr is tolerated (see
# Invoke-Native in the publish script) and a nonzero exit throws.
function Invoke-Setup([string]$Command, [string[]]$Arguments) {
    $ErrorActionPreference = "Continue"
    $output = & $Command @Arguments 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$Command $($Arguments -join ' ') exited ${LASTEXITCODE}: $($output -join "`n")" }
}

# Writes text as UTF-8 without a BOM: CRLF line ends for .cmd and .bat, LF for
# the rest (whatever line ends this file was checked out with).
function Write-Text([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $Path) | Out-Null
    $Text = $Text -replace "`r`n", "`n"
    if ($Path.EndsWith(".cmd") -or $Path.EndsWith(".bat")) { $Text = $Text -replace "`n", "`r`n" }
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("openmouse-publish-fixture-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$repo = Join-Path $work "repo"
$stubs = Join-Path $work "stubs"
$sdkTools = Join-Path $work "sdk/build-tools/99.0.0"
$log = Join-Path $work "calls.log"
$failures = 0

try {
    # --- Stubs ---------------------------------------------------------------
    if ($onWindows) {
        Write-Text (Join-Path $stubs "gh.cmd") @"
@echo off
>>"%STUB_LOG%" echo gh %*
if not "%~1"=="release" goto unexpected
if "%~2"=="view" goto view
if "%~2"=="create" goto create
:unexpected
>&2 echo stub gh: unexpected arguments: %*
exit /b 2
:create
echo https://github.com/example/openmouse/releases/tag/%~3
exit /b 0
:view
if "%STUB_GH_VIEW%"=="exists" goto exists
if "%STUB_GH_VIEW%"=="absent" goto absent
>&2 echo HTTP 401: Bad credentials (https://api.github.com/repos/example/openmouse/releases/tags/%~3)
exit /b 1
:exists
echo title:	OpenMouse 9.9.9
exit /b 0
:absent
>&2 echo release not found
exit /b 1
"@
        Write-Text (Join-Path $sdkTools "apksigner.bat") @"
@echo off
>>"%STUB_LOG%" echo apksigner %*
>&2 echo WARNING: META-INF/fixture.txt not protected by signature (stub)
echo Signer #1 certificate SHA-256 digest: $digest
exit /b 0
"@
    } else {
        Write-Text (Join-Path $stubs "gh") @'
#!/bin/sh
{ printf 'gh'; printf ' %s' "$@"; printf '\n'; } >> "$STUB_LOG"
if [ "${1:-}" = release ]; then
  case "${2:-}" in
    create) echo "https://github.com/example/openmouse/releases/tag/$3"; exit 0 ;;
    view)
      case "${STUB_GH_VIEW:-}" in
        exists) printf 'title:\tOpenMouse 9.9.9\n'; exit 0 ;;
        absent) echo 'release not found' >&2; exit 1 ;;
        *) echo "HTTP 401: Bad credentials (https://api.github.com/repos/example/openmouse/releases/tags/$3)" >&2; exit 1 ;;
      esac ;;
  esac
fi
echo "stub gh: unexpected arguments: $*" >&2
exit 2
'@
        Write-Text (Join-Path $sdkTools "apksigner") @"
#!/bin/sh
{ printf 'apksigner'; printf ' %s' "`$@"; printf '\n'; } >> "`$STUB_LOG"
echo 'WARNING: META-INF/fixture.txt not protected by signature (stub)' >&2
echo 'Signer #1 certificate SHA-256 digest: $digest'
exit 0
"@
        Invoke-Setup chmod @("+x", (Join-Path $stubs "gh"), (Join-Path $sdkTools "apksigner"))
    }

    # --- The throwaway repository --------------------------------------------
    New-Item -ItemType Directory -Force (Join-Path $repo "scripts") | Out-Null
    Copy-Item -LiteralPath $Script (Join-Path $repo "scripts/publish-release.ps1") -Force
    Copy-Item -LiteralPath (Join-Path $checkout ".gitignore") (Join-Path $repo ".gitignore")
    Write-Text (Join-Path $repo "scripts/verify-release-tag.sh") @'
#!/bin/sh
echo "fixture: tag verification stubbed" >&2
echo "$1 verifies (fixture stub)"
'@
    Write-Text (Join-Path $repo "app/build.gradle.kts") @'
android {
    defaultConfig {
        versionCode = 99
        versionName = "9.9.9"
    }
}
'@
    Write-Text (Join-Path $repo "release-cert.sha256") "# fixture pin`n$digest`n"
    # Ignored by .gitignore, as in a real checkout.
    Write-Text (Join-Path $repo "keystore.properties") "# fixture placeholder; nothing is signed`n"
    Write-Text (Join-Path $repo "app/build/outputs/apk/release/app-release.apk") "fixture placeholder, not an APK`n"
    Write-Text (Join-Path $work "notes.md") "# OpenMouse 9.9.9`n`nFixture release notes.`n"
    New-Item -ItemType Directory -Force (Join-Path $work "no-hooks") | Out-Null

    $git = @("-C", $repo)
    Invoke-Setup git ($git + @("init", "-q"))
    Invoke-Setup git ($git + @("config", "user.name", "fixture"))
    Invoke-Setup git ($git + @("config", "user.email", "fixture@invalid"))
    Invoke-Setup git ($git + @("config", "core.autocrlf", "false"))
    Invoke-Setup git ($git + @("config", "core.hooksPath", (Join-Path $work "no-hooks")))
    Invoke-Setup git ($git + @("config", "commit.gpgSign", "false"))
    Invoke-Setup git ($git + @("config", "tag.gpgSign", "false"))
    Invoke-Setup git ($git + @("add", "-A"))
    Invoke-Setup git ($git + @("commit", "-q", "-m", "fixture"))
    Invoke-Setup git ($git + @("tag", "-a", $tag, "--cleanup=verbatim", "-F", (Join-Path $work "notes.md")))

    # --- The child's environment ---------------------------------------------
    $env:PATH = $stubs + [System.IO.Path]::PathSeparator + $env:PATH
    $env:ANDROID_HOME = Join-Path $work "sdk"
    $env:ANDROID_SDK_ROOT = $null
    $env:STUB_LOG = $log
    $env:GH_TOKEN = "fixture-not-a-token"
    $env:GH_PROMPT_DISABLED = "1"
    $env:GH_REPO = $null

    function Invoke-Case([string]$Name, [string]$View, [string[]]$Extra, [int]$ExpectExit, [int]$ExpectCreates, [string]$ExpectOutput, [bool]$ExpectDraft) {
        [System.IO.File]::WriteAllText($log, "")
        $env:STUB_GH_VIEW = $View
        $ErrorActionPreference = "Continue"
        $childArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repo "scripts/publish-release.ps1"), "-SkipBuild") + $Extra
        $output = @(& $hostExe @childArgs 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
        $calls = @(Get-Content $log | Where-Object { $_ })
        $views = @($calls | Where-Object { $_ -match "^gh release view $([regex]::Escape($tag))\b" })
        $creates = @($calls | Where-Object { $_ -match "^gh release create $([regex]::Escape($tag))\b" })
        $certChecks = @($calls | Where-Object { $_ -match '^apksigner verify ' })

        $problems = @()
        if ($exitCode -ne $ExpectExit) { $problems += "exit code $exitCode, expected $ExpectExit" }
        if ($certChecks.Count -ne 1) { $problems += "apksigner ran $($certChecks.Count) times, expected once (the run did not reach the certificate check)" }
        if ($views.Count -ne 1) { $problems += "gh release view ran $($views.Count) times, expected once" }
        if ($creates.Count -ne $ExpectCreates) { $problems += "gh release create ran $($creates.Count) times, expected $ExpectCreates" }
        if ($creates.Count -eq 1) {
            $hasDraft = $creates[0] -match '(^|\s)--draft(\s|$)'
            if ($hasDraft -ne $ExpectDraft) { $problems += "gh release create --draft is $hasDraft, expected $ExpectDraft" }
        }
        if ((@($output) -join "`n") -notmatch $ExpectOutput) { $problems += "output does not match /$ExpectOutput/" }

        if ($problems.Count -eq 0) {
            Write-Host "PASS  $Name" -ForegroundColor Green
            if ($ShowOutput) { $output | ForEach-Object { Write-Host "      $_" } }
            return 0
        }
        Write-Host "FAIL  $Name" -ForegroundColor Red
        $problems | ForEach-Object { Write-Host "      $_" }
        Write-Host "      --- publish-release.ps1 output ---"
        $output | ForEach-Object { Write-Host "      $_" }
        Write-Host "      --- stub calls ---"
        $calls | ForEach-Object { Write-Host "      $_" }
        return 1
    }

    Write-Host "publish-release fixture: $Script under PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))"
    $failures += Invoke-Case "missing release: created once" "absent" @() 0 1 "Published v9\.9\.9" $false
    $failures += Invoke-Case "missing release, -Draft: created once as a draft" "absent" @("-Draft") 0 1 "Published v9\.9\.9" $true
    $failures += Invoke-Case "existing release: refused, nothing created" "exists" @() 1 0 "publish-release: a GitHub Release for v9\.9\.9 already exists" $false
    $failures += Invoke-Case "lookup error (HTTP 401): fails with gh's message, nothing created" "error" @() 1 0 "publish-release: could not check .*HTTP 401: Bad credentials" $false

} finally {
    if ($Keep) { Write-Host "kept $work" } else { Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue }
}

if ($failures -gt 0) {
    Write-Host "publish-release fixture: $failures case(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host "publish-release fixture: all cases passed" -ForegroundColor Green
exit 0
