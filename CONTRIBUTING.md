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
   ./gradlew test            # run JVM unit tests
   ./gradlew lint            # static analysis
   ```

To try a change on hardware: install the debug APK, plug in a mouse (USB OTG or
Bluetooth), and enable **OpenMouse cursor** in Accessibility settings.

## Project shape

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) first. The short version:

- Input enters `CursorView` (the full-screen overlay).
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
   `items` list. The 2-column grid resizes itself via `MenuGeometry`.
3. Add the injection method to `GestureDispatcher` (build a `GestureDescription`
   with the right strokes). Use existing methods as a template.
4. Handle the new mode in `MouseAccessibilityService.performMode()`. Decide
   whether it is one-shot (revert to `TAP`) or sticky. Two-point gestures use the
   `pendingX/pendingY` start-point flow.
5. If it injects touches, dispatch through `runGesture` so the overlay passes the
   gesture through to the app below.
6. If you extracted any pure logic, add a unit test under `app/src/test/`.

## Reporting issues

Useful bug reports include the device model, Android version, mouse type, and
what you expected versus what happened. Accessibility regressions are treated as
high priority.

## License

By contributing you agree that your contributions are licensed under the
project's [MIT License](LICENSE).
