# Changelog

All notable changes to OpenMouse are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it ships a
tagged release.

## [Unreleased]

### Changed

- The gesture menu is now a dock flush with the screen edge, made of 48 dp
  square cells showing drawn symbols instead of words (tap, double tap, hold,
  drag, swipe, scroll, Android's back / home / recents glyphs, a bell, a gear,
  pause / play). Smaller cells, nothing to translate, and the same adaptive
  widening on short screens. Collapsed, only a slim handle stays on the edge;
  the pointer stops at the screen edge, so the handle is easy to reach.
- Entering a menu cell while dwell clicking is locked (after a menu choice, a
  physical click, or the dock re-laying out under a still pointer) no longer
  arms the cell by itself: the pointer must first clear the movement tolerance
  from where it locked. A tremor across a cell edge cannot select the
  neighbour, and opening the dock from its tall handle cannot select a cell
  that lands 1 px from the pointer. Entering a cell during a running countdown
  still arms it at once.

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
- An in-app update check against GitHub Releases, under Settings > Updates.
  Off by default: press "Check for updates now", or turn on the check at
  launch. It only talks to GitHub (the release feed on `api.github.com`, the
  APK from `github.com`, redirects allowed onto GitHub's own hosts only),
  refuses any APK not signed with the same key as the installed copy, and
  leaves the final step to the system installer. The feature compiles out with
  `-PopenmouseUpdater=false`, which the F-Droid recipe uses. This adds the
  `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions; the privacy policy
  describes the one network use.
- An action badge beside the cross-hair names the current mode (double-tap,
  hold, drag, swipe, scroll up or down) whenever it is not a plain tap, so a
  sticky scroll or a drag waiting for its end point is visible where you are
  looking, not only on the menu at the screen edge. It sits up and to the left
  of the cross-hair and flips to the other side near an edge.
- Pause / Resume entry in the gesture menu. Pausing stops dwell clicking on
  the app; the menu keeps working by dwell, so Resume stays reachable without
  a physical button. The menu grid widens on a short screen (a phone in
  landscape) so its last row, Pause / Resume included, is never pushed off
  the bottom.
- A separate "re-arm distance after a click" setting: how far the pointer must
  travel after a dwell click before the next countdown may start, so a shaky
  hand cannot click the same spot twice (the movement tolerance is unchanged).
  Default 20 dp; it never acts narrower than the movement tolerance.
- Scroll up / down repeat while the pointer rests, instead of needing a fresh
  move between notches. The first notch at a rest point waits the full dwell
  time; the notches after it land every scroll repeat interval, a new setting
  (300 ms to 2 s, default 400 ms, the PowerToys Dwell Click default). One notch
  per dwell time was too slow to read a page by.
- Automated UI tests (`gradlew connectedDebugAndroidTest`, run in CI on API 30
  and 34 emulators): the cursor pipeline end to end (a dwell or button click
  lands exactly under the cross-hair, no double click while resting, tremor
  tolerance, Pause, Home, double-tap, drag, scroll wheel, cursor rendering), the
  overlay's safety contract (full display, never locks out the touchscreen,
  live settings, removed with the service), and the settings and main screens.
- Jazzer fuzz targets for the pure `DwellMachine` and `MenuGeometry` cores,
  mirroring the PowerToys Dwell Click fuzz target, plus a nightly GitHub Actions
  fuzz workflow and a CI workflow (build, unit tests, lint).
- Unit tests ported from the PowerToys Dwell Click engine suite (firing poll
  clears the ring, reset locks, timestamps older than the anchor, negative and
  NaN settings), on JUnit 5.

### Changed

- The dwell ticker runs only while a countdown is in progress. Locked after a
  click, paused over the app, or with a mouse button held, the service no
  longer wakes up every 16 ms for nothing; a pointer move or a scroll re-arm
  starts it again. Less battery for the same behaviour.
- The countdown ring stays hidden for the first fifth of a dwell (at most
  250 ms), so it no longer flickers behind a pointer that merely slowed down.
  Only the feedback waits; the click lands exactly when it did before.

### Fixed

- Android 7-13: a dwell click or button click could silently do nothing on a
  slow device. The cursor overlay is made click-through before each injected
  tap, but the system applies that asynchronously; if it had not by the time
  the tap arrived, the overlay took the tap itself. The overlay now notices a
  gesture it swallowed and sends it again after a longer wait (at most twice,
  within 400 ms). Only a gesture that wholly missed the app, and is still the
  latest action, is resent, so nothing that reached the app is repeated. A
  double tap waits a little longer before going out so it is not split in two,
  and a device seen to be slow waits longer for every gesture. Found by the new
  UI tests on an API 30 emulator.
- Android 11-13: the cursor overlay stopped short of the navigation bar, so the
  cursor could not reach it. The overlay now covers the whole display, as it
  already did on Android 14+.
- Android 7-13: after a gesture, the overlay could turn touchable while
  capture was suspended for lack of mouse input (for example a resting pointer
  repeating a scroll), blocking the touchscreen until the next watchdog pass.
- A mouse button held by the user suppresses dwell clicking for the whole hold,
  however the pointer moves, so a dwell can no longer land in the middle of the
  user's own drag or text selection. Letting go locks until the pointer moves.
  Before, only the press locked, and a long enough drag could dwell-click.
- Turning the scroll wheel cancels a running dwell countdown and locks until the
  pointer moves. The content under a resting pointer has changed, so the
  countdown was no longer aimed at what the user chose (the PowerToys Dwell
  Click wheel rule).
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
