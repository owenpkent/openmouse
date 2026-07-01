# Submitting OpenMouse to F-Droid

F-Droid builds from source and signs with its own key, so nothing here depends on
the app's release keystore. This folder holds the metadata to submit; the store
text and screenshots come from `fastlane/metadata/android/en-US/`.

## Steps

1. Install `fdroidserver` (the F-Droid build tools) and clone
   https://gitlab.com/fdroid/fdroiddata.
2. Copy `io.github.owenpkent.openmouse.yml` into `metadata/` in that checkout.
3. Lint and test the build recipe locally:
   ```bash
   fdroid lint io.github.owenpkent.openmouse
   fdroid build -v -l io.github.owenpkent.openmouse
   ```
4. Open a merge request against fdroiddata. F-Droid reviewers check the license
   (MIT, fine), that there are no anti-features (none: no ads, no trackers, no
   non-free dependencies), and that it builds reproducibly from the tag.

## Requirements already met

- **Free license** (MIT) and a `LICENSE` file.
- **No non-free dependencies** (androidx + Material only; no Google Play
  Services).
- **Tagged release** (`v0.1.0`) with matching `versionName`/`versionCode`.
- **No trackers, no network** (aids the "no anti-features" review).

## The one real risk: toolchain version

OpenMouse builds with **AGP 9.0.1 / Gradle 9.2.1**, which are very new. F-Droid's
build server must support that AGP for the recipe to build. If it does not yet:

- Wait for F-Droid to catch up, or
- Maintain an F-Droid-compatible build (e.g. a branch pinned to a more
  established AGP 8.x) referenced from the metadata's `commit:`.

Check the current toolchain support before submitting; this is the most likely
reason a first submission would fail to build.

## After acceptance

`UpdateCheckMode: Tags` + `AutoUpdateMode: Version v%v` means F-Droid picks up new
releases automatically when you push a `v<version>` tag whose `versionCode` is
higher. So future releases are just: bump `versionCode`/`versionName`, tag
`vX.Y.Z`, done.
