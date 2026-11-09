<#
.SYNOPSIS
    Build the signed release APK for a verified release tag and publish it as a
    GitHub Release, where sideloaders and the in-app updater find it.

.DESCRIPTION
    Steps (docs/RELEASING.md, "Release checklist"):
      1. Reads versionName and versionCode from app/build.gradle.kts and requires
         the working tree to be clean and checked out at the tag v<versionName>.
      2. Verifies that tag with scripts/verify-release-tag.sh (an SSH signature
         from a key in the release-signers on main, so git fetch origin first;
         named for the tag, with a message).
      3. Builds assembleRelease. keystore.properties must exist so the APK is
         signed: Android (and the in-app updater) only install an update signed
         with the same certificate as the installed app. The built APK must
         verify with apksigner and carry exactly one signer whose SHA-256 is the
         one pinned in release-cert.sha256 (docs/SIGNING.md), so a debug key, a
         new key or the wrong keystore never reaches users.
      4. Writes dist/openmouse-v<version>.apk and its SHA-256, and creates the
         GitHub Release for the tag (gh release create --verify-tag) with the
         tag message as the notes and the APK attached.

    Nothing here bumps a version or writes a tag. Bump versionName and
    versionCode, write the changelog entries (the ReleaseMetadataTest pins them
    to each other), merge, tag and sign, then run this from the tag.

.PARAMETER Draft
    Create the GitHub Release as a draft, to read it over before publishing.

.PARAMETER SkipBuild
    Reuse app/build/outputs/apk/release/app-release.apk from an earlier build
    of the same tag instead of building again.

.PARAMETER CheckApk
    Only check that the given APK is signed with the pinned release certificate
    (step 3's check), then exit: 0 when it is, 1 when it is not. Needs no tag,
    keystore or gh. Use it on a downloaded release asset or a local build.

.EXAMPLE
    powershell -File scripts\publish-release.ps1
    powershell -File scripts\publish-release.ps1 -Draft
    powershell -File scripts\publish-release.ps1 -CheckApk dist\openmouse-v0.1.0.apk

    Windows PowerShell 5.1 (powershell) and PowerShell 7 (pwsh) both work.
    scripts/tests/publish-release.fixture.ps1 runs this script end to end with
    stub tools; run it under the PowerShell you publish with.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Draft,
    [switch]$SkipBuild,
    [string]$CheckApk
)

$ErrorActionPreference = "Stop"

# -CheckApk is relative to where the caller stands, so resolve it (and expand ~)
# before moving to the repository root.
if ($CheckApk) {
    $resolvedApk = Resolve-Path -LiteralPath $CheckApk -ErrorAction SilentlyContinue
    if (-not $resolvedApk) {
        Write-Host "publish-release: $CheckApk does not exist" -ForegroundColor Red
        exit 1
    }
    $CheckApk = $resolvedApk.ProviderPath
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $repoRoot

function Fail($message) {
    Write-Host "publish-release: $message" -ForegroundColor Red
    exit 1
}

# Every native command (git, sh, gradlew, apksigner, gh) runs through here.
# Windows PowerShell 5.1 turns each line a native command writes to a redirected
# stderr into an error record, and with $ErrorActionPreference = "Stop" the
# first one ends the script (NativeCommandError) before $LASTEXITCODE can be
# read, even under *> $null. Several of these commands write to stderr on paths
# that are expected (gh release view of a release not made yet) or harmless
# (Gradle and apksigner warnings), so the preference is Continue for the call
# alone (this function's scope) and the exit code decides. Stdout and stderr
# come back separately; -Echo also prints each line as it arrives, for the
# steps whose output is the progress report.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @(),
        [switch]$Echo
    )
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) { Fail "$Command not found" }
    $ErrorActionPreference = "Continue"
    # PowerShell 7.3+ can also make a nonzero exit an error; callers check it.
    $PSNativeCommandUseErrorActionPreference = $false
    $stdout = New-Object System.Collections.Generic.List[string]
    $stderr = New-Object System.Collections.Generic.List[string]
    & $Command @Arguments 2>&1 | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) {
            $line = $_.ToString()
            $stderr.Add($line)
        } else {
            $line = [string]$_
            $stdout.Add($line)
        }
        if ($Echo) { Write-Host $line }
    }
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Stdout = $stdout.ToArray(); Stderr = $stderr.ToArray() }
}

# What a failed native command said, for a Fail message: its stderr, or its
# stdout when stderr is empty.
function Get-NativeMessage($result) {
    $lines = if ($result.Stderr.Count -gt 0) { $result.Stderr } else { $result.Stdout }
    return (@($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join " | ")
}

# Gradle and apksigner both need a JDK; Android Studio bundles one.
if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}

# --- Release certificate pin -------------------------------------------------
# The one place the expected certificate is written is release-cert.sha256 (one
# 64-hex line, # comments allowed; ReleaseMetadataTest checks its format).
function Get-PinnedCertSha256 {
    $file = Join-Path $repoRoot "release-cert.sha256"
    if (-not (Test-Path $file)) { Fail "release-cert.sha256 is missing; it pins the release signing certificate (docs/SIGNING.md)" }
    $lines = @(Get-Content $file | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") })
    if ($lines.Count -ne 1 -or $lines[0] -cnotmatch '^[0-9a-f]{64}$') {
        Fail "release-cert.sha256 must hold exactly one lowercase 64-hex SHA-256 line"
    }
    return $lines[0]
}

# Candidate SDK folders, in the order the Android Gradle plugin uses: sdk.dir in
# local.properties (a Java properties file, so \: and \\ are escapes), then
# ANDROID_HOME, then ANDROID_SDK_ROOT.
function Get-SdkDirs {
    $dirs = @()
    $localProps = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $dirs += (($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\(.)', '$1').Trim() }
    }
    $dirs += @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    return @($dirs | Where-Object { $_ -and (Test-Path $_) })
}

# apksigner from the newest build-tools that has one, in the first SDK that has
# any.
function Get-Apksigner {
    $sdks = Get-SdkDirs
    if ($sdks.Count -eq 0) { Fail "Android SDK not found; set sdk.dir in local.properties or ANDROID_HOME" }
    $names = @("apksigner.bat", "apksigner")
    foreach ($sdk in $sdks) {
        $buildTools = Join-Path $sdk "build-tools"
        if (-not (Test-Path $buildTools)) { continue }
        $newest = Get-ChildItem $buildTools -Directory |
            Where-Object { $dir = $_.FullName; @($names | Where-Object { Test-Path (Join-Path $dir $_) }).Count -gt 0 } |
            Sort-Object { $v = $null; if ([version]::TryParse(($_.Name -replace '-.*$', ''), [ref]$v)) { $v } else { [version]"0.0" } } |
            Select-Object -Last 1
        if (-not $newest) { continue }
        foreach ($name in $names) {
            $path = Join-Path $newest.FullName $name
            if (Test-Path $path) { return $path }
        }
    }
    Fail "no apksigner in the build-tools of $($sdks -join ', '); install build-tools with the SDK manager"
}

# Refuses (exits 1) unless $apk verifies and has exactly one signer whose
# certificate SHA-256 is the pinned one.
function Assert-ReleaseCert($apk) {
    if (-not (Test-Path $apk)) { Fail "$apk does not exist" }
    $expected = Get-PinnedCertSha256
    $apksigner = Get-Apksigner
    $verify = Invoke-Native $apksigner @("verify", "--print-certs", $apk)
    if ($verify.ExitCode -ne 0) { Fail "apksigner says $apk does not verify (unsigned or damaged); not publishing: $(Get-NativeMessage $verify)" }
    $digests = @($verify.Stdout | ForEach-Object {
        $m = [regex]::Match($_, '^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
        if ($m.Success) { $m.Groups[1].Value.ToLower() }
    })
    if ($digests.Count -ne 1) { Fail "$apk has $($digests.Count) signers; a release has exactly one" }
    if ($digests[0] -ne $expected) {
        Fail ("$apk is signed with certificate SHA-256 $($digests[0]), not the release certificate $expected " +
            "from release-cert.sha256. Users could not update to it. Is keystore.properties pointing at openmouse-release.jks? (docs/SIGNING.md)")
    }
    Write-Host "Signing certificate matches release-cert.sha256 ($expected)" -ForegroundColor Green
}

if ($CheckApk) {
    Assert-ReleaseCert $CheckApk
    exit 0
}

# --- 1. Version and checkout state -----------------------------------------
$gradleText = Get-Content (Join-Path $repoRoot "app\build.gradle.kts") -Raw
$nameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$codeMatch = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
if (-not $nameMatch.Success -or -not $codeMatch.Success) { Fail "could not read versionName / versionCode from app/build.gradle.kts" }
$version = $nameMatch.Groups[1].Value
$versionCode = $codeMatch.Groups[1].Value
$tag = "v$version"
Write-Host "Version $version (versionCode $versionCode), tag $tag" -ForegroundColor Cyan

$status = Invoke-Native git @("status", "--porcelain")
if ($status.ExitCode -ne 0) { Fail "git status failed: $(Get-NativeMessage $status)" }
if (($status.Stdout -join "").Trim()) { Fail "the working tree is not clean; a release is built from the tag alone" }

$tagLookup = Invoke-Native git @("rev-parse", "--verify", "--quiet", "$tag^{commit}")
if ($tagLookup.ExitCode -ne 0 -or $tagLookup.Stdout.Count -eq 0) { Fail "tag $tag does not exist; tag and sign first (docs/RELEASING.md)" }
$tagCommit = $tagLookup.Stdout[0].Trim()
$headLookup = Invoke-Native git @("rev-parse", "HEAD")
if ($headLookup.ExitCode -ne 0 -or $headLookup.Stdout.Count -eq 0) { Fail "git rev-parse HEAD failed: $(Get-NativeMessage $headLookup)" }
$head = $headLookup.Stdout[0].Trim()
if ($head -ne $tagCommit) { Fail "HEAD ($head) is not the commit $tag points at ($tagCommit); run git checkout $tag first" }

# --- 2. Verify the tag -----------------------------------------------------
# Git for Windows does not put sh on PATH by default, so also look where it
# installs (for all users, or per user under LocalAppData) and next to git
# itself (<Git>\cmd\git.exe beside <Git>\bin\sh.exe).
$sh = Get-Command sh -ErrorAction SilentlyContinue
if (-not $sh) {
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles "Git\bin\sh.exe" }
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA "Programs\Git\bin\sh.exe" }
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git -and $git.Source) { $candidates += Join-Path (Split-Path -Parent (Split-Path -Parent $git.Source)) "bin\sh.exe" }
    $gitSh = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($gitSh) { $sh = Get-Command $gitSh } else { Fail "sh.exe not found; install Git for Windows or run scripts/verify-release-tag.sh by hand" }
}
$tagCheck = Invoke-Native $sh.Source @("scripts/verify-release-tag.sh", $tag) -Echo
if ($tagCheck.ExitCode -ne 0) { Fail "$tag does not verify; not building" }

# --- 3. Build the signed release APK ---------------------------------------
if (-not (Test-Path (Join-Path $repoRoot "keystore.properties"))) {
    Fail "keystore.properties is missing, so the APK would be unsigned (see keystore.properties.example)"
}
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if ($SkipBuild) {
    if (-not (Test-Path $apkPath)) { Fail "-SkipBuild given but $apkPath does not exist" }
    Write-Host "Reusing $apkPath" -ForegroundColor Yellow
} else {
    Write-Host "Building the release APK..." -ForegroundColor Cyan
    $build = Invoke-Native (Join-Path $repoRoot "gradlew.bat") @("assembleRelease", "--no-daemon", "-q") -Echo
    if ($build.ExitCode -ne 0) { Fail "gradlew assembleRelease failed" }
    if (-not (Test-Path $apkPath)) { Fail "expected a signed APK at $apkPath (an unsigned build writes app-release-unsigned.apk)" }
}
Assert-ReleaseCert $apkPath

$distDir = Join-Path $repoRoot "dist"
New-Item -ItemType Directory -Force $distDir | Out-Null
$asset = Join-Path $distDir "openmouse-$tag.apk"
Copy-Item $apkPath $asset -Force
$sha = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower()
"$sha  openmouse-$tag.apk" | Out-File -Encoding ascii (Join-Path $distDir "openmouse-$tag.apk.sha256")
Write-Host "APK: $asset" -ForegroundColor Green
Write-Host "SHA-256: $sha"

# --- 4. The GitHub Release ---------------------------------------------------
# The tag message is the release notes, so the same text reaches people reading
# GitHub, the in-app updater, and anyone who verifies the tag.
# %(contents) is the whole tag message with the SSH signature block on the end
# (git 2.53 checked); verify-release-tag.sh has already required that block to
# be the single trailing part, so the notes are everything before it. Written
# without a BOM, which Windows PowerShell's Out-File -Encoding utf8 would add.
$notesFile = Join-Path $distDir "openmouse-$tag.notes.md"
# Decode git's output as UTF-8 whatever the console code page, so non-ASCII
# notes are not garbled.
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try { $notes = Invoke-Native git @("tag", "-l", "--format=%(contents)", $tag) }
finally { [Console]::OutputEncoding = $previousEncoding }
if ($notes.ExitCode -ne 0) { Fail "could not read the message of ${tag}: $(Get-NativeMessage $notes)" }
$message = $notes.Stdout -join "`n"
$cut = $message.IndexOf("-----BEGIN SSH SIGNATURE-----")
if ($cut -ge 0) { $message = $message.Substring(0, $cut) }
$message = $message.TrimEnd()
if (-not $message) { Fail "the tag message of $tag is empty; the release notes come from it" }
# A tag made without --cleanup=verbatim loses every line starting with '#'.
if ($message -notmatch '(?m)^#') {
    Write-Warning "the notes of $tag have no markdown heading; if the notes file had some, the tag was made without --cleanup=verbatim (docs/RELEASING.md)"
}
[System.IO.File]::WriteAllText($notesFile, $message + "`n", (New-Object System.Text.UTF8Encoding($false)))

# Three outcomes. The release exists: refuse. gh says "release not found" (on
# stderr, exit 1): the expected case for a new tag, go on. Anything else
# (network, authentication, rate limit) is a lookup that failed, not an absent
# release, so stop with what gh said rather than create blind.
$lookup = Invoke-Native gh @("release", "view", $tag)
if ($lookup.ExitCode -eq 0) { Fail "a GitHub Release for $tag already exists; delete it or upload the asset with: gh release upload $tag $asset" }
if (@($lookup.Stderr | ForEach-Object { $_.Trim() }) -notcontains "release not found") {
    Fail "could not check whether a GitHub Release for $tag exists (gh release view exited $($lookup.ExitCode)): $(Get-NativeMessage $lookup)"
}

$ghArgs = @("release", "create", $tag, $asset, "$asset.sha256", "--verify-tag", "--title", "OpenMouse $version", "--notes-file", $notesFile)
if ($Draft) { $ghArgs += "--draft" }
$create = Invoke-Native gh $ghArgs -Echo
if ($create.ExitCode -ne 0) { Fail "gh release create failed" }
Write-Host "Published $tag" -ForegroundColor Green
