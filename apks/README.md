# APKs

Debug builds land here, version-named, so there is a stable place to grab one
for sideloading onto a test device (USB, a file share, a local download link).

## How they get here

The `copyDebugApkToRoot` Gradle task in `app/build.gradle.kts` runs after
`packageDebug`, which both `assembleDebug` and `installDebug` run through, so
building or installing refreshes the copy:

```
apks/openmouse-v<versionName>-debug.apk
```

```bash
./gradlew assembleDebug        # or: ./gradlew installDebug
```

The name tracks `versionName` in `app/build.gradle.kts`. Rebuilding the same
version overwrites its file; bump `versionName` (and `versionCode`) for a new
one.

## Not for releases

These are **debug** builds, signed with the debug key of the machine that built
them (`~/.android/debug.keystore`). They are for testing only:

- A debug APK will not install over a release install from GitHub Releases or
  F-Droid, and a release will not install over it ("App not installed as
  package conflicts with an existing package"). Uninstall first; that loses
  OpenMouse's settings.
- Never attach one to a GitHub Release. Release APKs are built and signed by
  `scripts/publish-release.ps1`, which refuses anything not signed with the
  pinned release certificate. See [docs/SIGNING.md](../docs/SIGNING.md).

## Git

The `.apk` files are gitignored (`*.apk`). Only this README is tracked, so the
folder exists in a fresh clone.
