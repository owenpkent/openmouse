<#
.SYNOPSIS
    Build the signed release APK for a verified release tag and publish it as a
    GitHub Release, where sideloaders and the in-app updater find it.

.DESCRIPTION
    Steps (docs/RELEASING.md, "Release checklist"):
      1. Reads versionName and versionCode from app/build.gradle.kts and requires
         the working tree to be clean and checked out at the tag v<versionName>.
      2. Verifies that tag with scripts/verify-release-tag.sh (an SSH signature
         from a key in release-signers, named for the tag, with a message).
      3. Builds assembleRelease. keystore.properties must exist so the APK is
         signed: Android (and the in-app updater) only install an update signed
         with the same certificate as the installed app.
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

.EXAMPLE
    pwsh -File scripts\publish-release.ps1
    pwsh -File scripts\publish-release.ps1 -Draft
#>
param(
    [switch]$Draft,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $repoRoot

function Fail($message) {
    Write-Host "publish-release: $message" -ForegroundColor Red
    exit 1
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

$dirty = git status --porcelain
if ($dirty) { Fail "the working tree is not clean; a release is built from the tag alone" }

$tagCommit = git rev-parse --verify --quiet "$tag^{commit}"
if ($LASTEXITCODE -ne 0 -or -not $tagCommit) { Fail "tag $tag does not exist; tag and sign first (docs/RELEASING.md)" }
$head = git rev-parse HEAD
if ($head -ne $tagCommit) { Fail "HEAD ($head) is not the commit $tag points at ($tagCommit); run `git checkout $tag` first" }

# --- 2. Verify the tag -----------------------------------------------------
$sh = Get-Command sh -ErrorAction SilentlyContinue
if (-not $sh) {
    $gitSh = Join-Path $env:ProgramFiles "Git\bin\sh.exe"
    if (Test-Path $gitSh) { $sh = Get-Command $gitSh } else { Fail "sh.exe not found; install Git for Windows or run scripts/verify-release-tag.sh by hand" }
}
& $sh.Source "scripts/verify-release-tag.sh" $tag
if ($LASTEXITCODE -ne 0) { Fail "$tag does not verify; not building" }

# --- 3. Build the signed release APK ---------------------------------------
if (-not (Test-Path (Join-Path $repoRoot "keystore.properties"))) {
    Fail "keystore.properties is missing, so the APK would be unsigned (see keystore.properties.example)"
}
if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if ($SkipBuild) {
    if (-not (Test-Path $apkPath)) { Fail "-SkipBuild given but $apkPath does not exist" }
    Write-Host "Reusing $apkPath" -ForegroundColor Yellow
} else {
    Write-Host "Building the release APK..." -ForegroundColor Cyan
    & (Join-Path $repoRoot "gradlew.bat") assembleRelease --no-daemon -q
    if ($LASTEXITCODE -ne 0) { Fail "gradlew assembleRelease failed" }
    if (-not (Test-Path $apkPath)) { Fail "expected a signed APK at $apkPath (an unsigned build writes app-release-unsigned.apk)" }
}

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
$notesFile = Join-Path $distDir "openmouse-$tag.notes.md"
git tag -l --format='%(contents)' $tag | Out-File -Encoding utf8 $notesFile

gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) { Fail "a GitHub Release for $tag already exists; delete it or upload the asset with `gh release upload $tag $asset`" }

$args = @("release", "create", $tag, $asset, "$asset.sha256", "--verify-tag", "--title", "OpenMouse $version", "--notes-file", $notesFile)
if ($Draft) { $args += "--draft" }
& gh @args
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed" }
Write-Host "Published $tag" -ForegroundColor Green
