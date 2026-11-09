# Changelog

All notable changes to OpenMouse are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it ships a
tagged release.

## [Unreleased]

### Added

- Engineering gates, the first step of the parity plan in `docs/PARITY.md`:
  CI actions pinned to a commit with credentials dropped after checkout,
  concurrency groups, an unsigned release build plus `lintRelease` so a
  release-only break fails in CI, and one aggregate `Tests` check for branch
  protection. A `Reviewed` commit status (`review-gate.yml`) requires a review
  on the latest commit of every pull request into `main`. A tracked pre-push
  hook runs the unit tests and lint before every push (`./gradlew
  installGitHooks` turns it on), and CONTRIBUTING.md gained a table of every
  gate and what to do when it fails.
- Dependency and code scanning: Gradle dependency locking (`app/gradle.lockfile`,
  checked for freshness in CI), an OSV-Scanner gate that fails a pull request
  only on an advisory it introduces (with a weekly full scan and a self-test
  that plants a known-vulnerable artifact behind result-file symlinks), CodeQL
  for the Kotlin code, and Dependabot for Gradle and GitHub Actions.
- The nightly fuzz run fuzzes each target in its own job from a corpus cached
  between nights, and a finding opens (or comments on) a `Fuzz crash: <target>`
  issue with the input, where it goes, and the end of the log, instead of
  leaving an artifact nobody is told about.
- Release machinery: a release is a signed `vX.Y.Z` tag verified against the
  committed `release-signers` file (`scripts/verify-release-tag.sh`),
  `scripts/publish-release.ps1` builds the signed APK from a verified tag and
  publishes the GitHub Release with the tag message as notes, and a unit test
  pins `versionName` and `versionCode` to the changelog, the fastlane changelog
  and the F-Droid metadata so a half-done bump fails in CI.
- `scripts/verify-release-tag.sh` trusts only the `release-signers` on `main`
  (`refs/remotes/origin/main`, overridable with `OPENMOUSE_SIGNERS_REF`), never
  the copy in the tag being verified, so a tag cannot bring its own key. It
  forces git's SSH backend, requires ssh-keygen's `Good "git" signature for`
  verdict, and accepts only a single SSH signature block at the very end of
  the tag with no OpenPGP or X.509 marker. CI now also checks that a trusted
  key verifies and an unlisted one does not.
- The release signing certificate is pinned: `release-cert.sha256` holds its
  SHA-256, `scripts/publish-release.ps1` refuses to publish an APK that
  `apksigner` does not verify or that has any other signer (`-CheckApk <apk>`
  runs that check alone), and `ReleaseMetadataTest` checks the file's format.
- `docs/SIGNING.md`: the release key and why it is never committed (a public
  key would let anyone ship an "update" to a service that injects taps),
  backups, publishing from a second machine, what a lost key means, and why
  debug, GitHub and F-Droid installs cannot update each other.
- `docs/TROUBLESHOOTING.md`: build errors, installing (package conflicts,
  unknown sources, the Android 13+ "Restricted setting" block), the cursor and
  touchscreen watchdogs, mouse disconnects, and what the in-app updater's
  messages mean.
- `assembleDebug` and `installDebug` copy the debug APK to
  `apks/openmouse-v<versionName>-debug.apk` for sideloading (`apks/README.md`).
- Pause / Resume entry in the gesture menu. Pausing stops dwell clicking on
  the app; the menu keeps working by dwell, so Resume stays reachable without
  a physical button. The menu grid widens on a short screen (a phone in
  landscape) so its last row, Pause / Resume included, is never pushed off
  the bottom.
- A separate "re-arm distance after a click" setting: how far the pointer must
  travel after a dwell click before the next countdown may start, so a shaky
  hand cannot click the same spot twice (the movement tolerance is unchanged).
  Default 20 dp; it never acts narrower than the movement tolerance.
- Scroll up / down repeat while the pointer rests: one notch per dwell period,
  instead of needing a fresh move between notches.
- Jazzer fuzz targets for the pure `DwellMachine` and `MenuGeometry` cores,
  mirroring the PowerToys Dwell Click fuzz target, plus a nightly GitHub Actions
  fuzz workflow and a CI workflow (build, unit tests, lint).
- Unit tests ported from the PowerToys Dwell Click engine suite (firing poll
  clears the ring, reset locks, timestamps older than the anchor, negative and
  NaN settings), on JUnit 5.

### Fixed

- Menu entries no longer re-fire while the pointer stays on them. A tremor
  inside a cell used to restart the countdown and could trigger Back or the
  menu toggle every dwell period; the cell is now the tolerance, and an entry
  re-arms only after the pointer leaves it. Leaving a cell for the app (or the
  gap between cells) locks until the ordinary movement tolerance is cleared, and
  the service re-anchors on the entry under the pointer after a menu choice, a
  dock change, or a resize, so a menu strip that shifts on a short screen or
  moves on rotation cannot tap the app. While paused, a dwell that reaches the
  dispatch point over the app is refused outright, whatever the machine's rest
  key says.
- On API 24-33 the touch-passthrough watchdog is now armed for the oldest
  in-flight gesture and left alone by later ones. A repeating scroll used to
  re-arm it on every notch, so one lost gesture callback could leave the
  overlay click-through (and the cursor unable to see the mouse) for as long
  as the pointer rested. The bookkeeping is a pure, fuzzed `PassthroughGate`.
- A right or middle mouse button press now locks the dwell machine like a left
  press does, on both input paths. On API 34+ a left click is detected from the
  button that changed rather than every button currently held.
- `DwellMachine` stays defined for degenerate input: negative or NaN movement
  thresholds clamp to 0, a dwell time below 1 ms reads as 1 ms, and an elapsed
  time that overflows a Long saturates instead of wrapping (found by fuzzing).
- `MenuGeometry` clamps a negative item count to 0 and fewer than one column to 1
  instead of throwing.

## [0.1.0] - 2026-06-30

First public (pre-)release.

### Added

- Cursor MVP: a large cross-hair that follows a USB or Bluetooth mouse /
  trackball, with the system pointer hidden.
- Dwell click: the cursor clicks automatically when it rests, with a shrinking
  countdown for feedback.
- Standard click: a physical left-button press taps immediately.
- On-screen gesture menu: a collapsible, right-edge, two-column grid.
- Tap modes: single tap, double-tap, and long-press.
- Two-point gestures: drag and swipe, with a start-point marker.
- Scroll up and scroll down at the cursor (repeatable).
- Navigation from the menu: Back, Home, Recent, Notifications, Quick Settings.
- Scroll-wheel support: turning the wheel scrolls the content under the cursor.
- Settings screen, applied live while the cursor is running: dwell time,
  movement tolerance, cursor size, cursor color, menu side, and a dwell-click
  on/off toggle.
- Unit tests for the pure `DwellMachine` and `MenuGeometry` cores.

### Changed

- Input capture reworked after an adversarial review. On Android 14+ the overlay
  is non-touchable and mouse motion is observed via `onMotionEvent`, so finger
  touch passes through and injected gestures are never swallowed. On Android
  7-13 the touchable overlay is hardened: finger input is source-gated, gestures
  are ref-counted with a watchdog, and an idle watchdog frees the touchscreen if
  the mouse goes away.
- The gesture menu's toggle is anchored (no longer jumps on expand) and the strip
  is clamped on screen for short/landscape displays.
- The accessibility-service config no longer requests `canRetrieveWindowContent`
  or a broad event mask (a less alarming enable prompt), and its settings gear
  opens the settings screen.
- Declared `android:isAccessibilityTool="true"` so the service reads as a genuine
  accessibility tool (system UI and Google Play review).
- Release builds are now minified with resource shrinking and use a conditional
  signing config (`keystore.properties`, gitignored).
- Dropped viewBinding (activities use `findViewById`) so the same sources build
  under the AOSP/Soong platform build. Added an `Android.bp` + `platform/`
  packaging kit for preinstalling OpenMouse as a system app in a ROM.

### Fixed

- A stray finger or palm can no longer hijack the cursor or fire a click.
- The touchscreen can no longer be locked out if the mouse disconnects.
- Lowering the dwell time in Settings no longer fires an instant click.
- Gesture coordinates account for the display cutout, so the cross-hair and the
  injected tap stay aligned.
- A half-started drag/swipe is cleared by any menu action.
- The cursor view only redraws on change (no constant 60 fps redraw when idle).
- Settings sliders are labelled for TalkBack; the cross-hair seeds at center so
  it is visible before the first mouse move.

### Notes

- Built as an `AccessibilityService`; installs on Android 7.0+ with no root.
- Toolchain: AGP 9.0.1 (AGP's built-in Kotlin, no standalone Kotlin plugin),
  Gradle 9.2.1, compileSdk 36, minSdk 24. The app builds and all unit tests pass
  via `./gradlew test`. The Gradle wrapper jar is committed.
- The Android-14+ input path is verified on a headless emulator (overlay
  non-touchable, finger touch passes through, no crash). Driving a real mouse
  cursor headlessly is not possible (the emulator has no mouse device and the
  framework filters injected events), so that part is covered by the unit tests
  plus the verified service configuration.
- The repository is community-ready: Code of Conduct, Security policy, and
  issue/PR templates, plus a privacy policy, F-Droid (fastlane) metadata, and
  distribution plans in `docs/RELEASING.md` (F-Droid / Play) and
  `docs/UPSTREAMING.md` (AOSP / LineageOS).

[Unreleased]: https://github.com/owenpkent/openmouse/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/owenpkent/openmouse/releases/tag/v0.1.0
