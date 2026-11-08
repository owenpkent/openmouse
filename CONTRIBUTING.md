# Contributing to OpenMouse

Thanks for helping make Android more usable for people who rely on a mouse or
trackball. Contributions of all sizes are welcome, from typo fixes to new
gestures.

## Getting set up

1. Install **Android Studio** (Koala or newer) or a JDK 17+ and the Android SDK.
2. Open the project folder and let Android Studio sync. The Gradle wrapper is
   committed, so `./gradlew` also works from the command line.
3. Build and test:
   ```bash
   ./gradlew assembleDebug   # build the APK
   ./gradlew test            # run JVM unit tests (JUnit 5)
   ./gradlew lint            # static analysis
   ./gradlew connectedDebugAndroidTest   # UI tests, needs an emulator or device
   ```
   The fuzz targets in `app/src/test/.../fuzz/` are part of `test`, where they
   only replay their seed corpus. To actually fuzz (ten minutes per target):
   ```bash
   JAZZER_FUZZ=1 ./gradlew testDebugUnitTest --tests '*FuzzTest'
   ```
   CI runs the full ten-minute fuzz nightly (`.github/workflows/nightly-fuzz.yml`).

To try a change on hardware: install the debug APK, plug in a mouse (USB OTG or
Bluetooth), and enable **OpenMouse cursor** in Accessibility settings.

### Smoke-testing on an emulator (no device needed)

Much of the app can be verified headlessly. Boot an API 34+ AVD, install, and
enable the service over adb (Android 14+ blocks sideloaded accessibility services
until you grant the restricted-settings appop):

```bash
emulator -avd <name> -no-window -gpu swiftshader_indirect &
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell appops set io.github.owenpkent.openmouse ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
  io.github.owenpkent.openmouse/io.github.owenpkent.openmouse.service.MouseAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys window | grep -i openmouse   # overlay attached, NOT_TOUCHABLE?
adb exec-out screencap -p > shot.png           # cross-hair + menu render?
```

This verifies the overlay, touch passthrough, rendering, and crashes by hand.
What it **cannot** do: drive the cursor. A headless emulator has no mouse device,
and the accessibility framework filters `adb input`-injected events out of
`onMotionEvent`.

### Automated UI tests (emulator or device)

The instrumented suite under `app/src/androidTest/` does all of the above
automatically and also drives the cursor end to end. With an emulator booted (or
a device attached):

```bash
./gradlew connectedDebugAndroidTest
# one class: add -Pandroid.testInstrumentationRunnerArguments.class=io.github.owenpkent.openmouse.CursorPipelineTest
```

The tests enable the service themselves over the shell; no setup is needed.

- `CursorPipelineTest` feeds synthetic mouse events in where real ones arrive:
  the service's `onMotionEvent` on API 34+, the touchable `CursorView` overlay on
  API 24-33 (the tests run in the app's process, which is how they get past the
  input filter). From there everything is real, including the legacy
  click-through passthrough around each injected gesture: the dwell timer, the
  menu, `dispatchGesture`, and a real app window, the debug-only
  `TouchRecorderActivity`, which records where each injected touch landed. That
  is how the suite checks that a tap lands exactly under the cross-hair, that a
  resting pointer does not click twice, and that Pause, Home, double-tap, drag
  and the scroll wheel behave.
- `OverlayTest` (every API level) checks the overlay spans the display with the
  cutout mode, never locks out a finger, follows live settings, and goes away
  with the service.
- `SettingsActivityTest` and `MainActivityTest` cover the two screens (Espresso).

CI runs the suite on API 34 (`onMotionEvent` path) and API 30 (touchable overlay path).
When you add a gesture or menu entry, add a `CursorPipelineTest` case that
drives it through the menu and asserts on `TouchLog`.

Two traps: always get `UiAutomation` with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`
(a plain `getUiAutomation()` unbinds every other accessibility service, OpenMouse
included; use `Device.shell()`), and do not use Espresso clicks while the overlay
is up on API 24-33, where it is touchable until its idle watchdog fires.

## Project shape

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) first. The short version:

- Input enters via `service.onMotionEvent` (API 34+, non-touchable overlay) or
  `CursorView`'s capture (API 24-33, touchable overlay).
- `DwellClicker` decides when a rest is a click; a physical button click is the
  same primary action.
- `MouseAccessibilityService.handlePrimaryAction()` either selects a menu entry
  or performs the current gesture.
- `GestureDispatcher` injects the real input via `dispatchGesture()`.

**Keep logic out of the Android classes.** Decision logic goes in a pure class
(see `DwellMachine`, `MenuGeometry`) so it can be unit tested on the JVM. The
Android wrapper should be a thin pass-through.

## Coding conventions

- Kotlin, official code style (`kotlin.code.style=official`, enforced by ktlint
  defaults in Android Studio).
- Match the surrounding code: comment the *why*, not the *what*.
- No em dashes in code, comments, or docs. Use a period, a colon, or parentheses.
- Run `./gradlew test lint` before opening a PR.

## How to add a new gesture

Most new gestures follow the same path:

1. Add a value to `GestureAction` (in `menu/GestureMenu.kt`).
2. Add a label string in `res/values/strings.xml` and a row in `GestureMenu`'s
   `items` list. The grid resizes itself via `MenuGeometry` (two columns,
   more on a short screen).
3. Add the injection method to `GestureDispatcher`: it builds and returns an
   `Injection` (the `GestureDescription` plus each stroke's start point, in
   stroke order). Use existing methods as a template. The start points must be
   exact: on API 24-33 they are how a gesture the overlay swallowed is told apart
   from one that reached the app.
4. Handle the new mode in `MouseAccessibilityService.performMode()`. Decide
   whether it is one-shot (revert to `TAP`), sticky, or repeating (add it to
   `GestureAction.isRepeating` so a dwell re-arms in place). Two-point gestures use the
   `pendingX/pendingY` start-point flow.
5. If it injects touches, send it with `runGesture(dispatcher.yourGesture(...))`
   so the overlay passes the gesture through to the app below (and, on API
   24-33, retries it if the overlay swallowed it).
6. If you extracted any pure logic, add a unit test under `app/src/test/`. If
   it consumes pointer positions, timestamps or settings, extend the matching
   fuzz target under `app/src/test/.../fuzz/` (or add one) so the nightly
   fuzz run drives it with arbitrary input.

## Reporting issues

Useful bug reports include the device model, Android version, mouse type, and
what you expected versus what happened. Accessibility regressions are treated as
high priority.

## License

By contributing you agree that your contributions are licensed under the
project's [MIT License](LICENSE).
