# OpenMouse

Open-source Android AccessibilityService that draws a large cross-hair cursor which follows a physical mouse/trackball and auto-clicks by dwelling, for users with tremors or limited fine motor control. Kotlin, no root, Android 7.0+ (API 24+).

## Key rules

- **Keep logic out of the Android classes.** Decision logic lives in pure classes with no Android imports (`DwellMachine`, `MenuGeometry`) and is JVM unit-tested; the Android wrappers (`DwellClicker`, `GestureMenu`, `MouseAccessibilityService`) stay thin pass-throughs. When adding behavior, put the logic in a pure class and test it there, then wrap it.
- **Pure cores never read the clock or Android APIs.** `DwellMachine.poll(nowMs)` takes time as a parameter; only `DwellClicker` reads the monotonic `SystemClock.uptimeMillis()`. The dwell machine starts *locked* and re-locks after each click and after any live config change, so it must not fire until the cursor moves past `moveThresholdPx`.
- **Two input paths by API level; never lock out the touchscreen.** API 34+ uses a permanently non-touchable overlay + `service.onMotionEvent`; API 24-33 uses a touchable `CursorView`. Any overlay/input change must preserve both, keep `CursorView.onTouchEvent` ignoring non-`SOURCE_MOUSE` events, and keep the ref-counted passthrough + idle watchdogs so a dead/disconnected mouse can never freeze touch.
- **Inject touches only through `runGesture`.** Every touch-injecting gesture (tap, double-tap, long-press, drag, swipe, scroll) goes through `runGesture` so the overlay passes it through to the app below. Navigation actions (Back/Home/Recent/Notifications/Quick Settings) use `performGlobalAction()` and must not.
- **One screen-space coordinate everywhere.** The overlay spans the full display with `layoutInDisplayCutoutMode = ALWAYS`; the same `(x, y)` is drawn, hit-tested against the menu, and handed to `dispatchGesture()`. Do not drop the cutout mode or the full-screen layout flags, or every injected tap lands at a fixed offset from the cross-hair.
- **Settings are live, no Save button.** Controls write straight to `OpenMouseSettings` (clamps every value to its range); the service's `OnSharedPreferenceChangeListener` calls `applySettings()`. A new setting means: add a clamped accessor in `OpenMouseSettings` and wire it into `applySettings()`.
- **No em dashes** in code, comments, or docs. Use a period, colon, or parentheses.

## Stack & layout

- Kotlin Android AccessibilityService. AGP 9.0.1 (built-in Kotlin, no separate plugin), Gradle wrapper 9.2.1, JDK 17, min/target/compile SDK 24/36/36, namespace `io.github.owenpkent.openmouse`. Deps via `gradle/libs.versions.toml`.
- `app/src/main/java/io/github/owenpkent/openmouse/` - `service/`, `cursor/`, `click/`, `menu/`, `gesture/`, `settings/`.
- `app/src/test/java/.../{click,menu}/` - JVM unit tests (`DwellMachineTest`, `MenuGeometryTest`).
- `docs/ARCHITECTURE.md` - read first. `platform/` + `Android.bp` - AOSP/LineageOS system-app build (not day-to-day, unvalidated). `fastlane/` - F-Droid/Play metadata.

## Build, run, test

- Build: `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`).
- Test: `./gradlew test` (or `./gradlew testDebugUnitTest`).
- Lint: `./gradlew lint`. Run `./gradlew test lint` before opening a PR.
- No device: boot an API 34+ AVD, install, grant the `ACCESS_RESTRICTED_SETTINGS` appop, and enable the service over adb (exact commands in CONTRIBUTING.md). A headless emulator cannot drive a real mouse, so the cursor pipeline is covered by unit tests, not the emulator.

## Conventions

- Kotlin official style (`kotlin.code.style=official`, ktlint defaults). Comment the *why*, not the *what*; match surrounding code.
- Add a gesture via the recipe in CONTRIBUTING.md: extend `GestureAction` -> add a string + `GestureMenu` `items` row -> add the injection method to `GestureDispatcher` -> handle it in `MouseAccessibilityService.performMode()` (decide one-shot vs sticky; two-point gestures use the `pendingX/pendingY` start-point flow) -> dispatch via `runGesture` -> unit-test any extracted pure logic.

## When to ask

- Anything widening the trust surface: new permissions, capturing or persisting input, network calls, or collecting user data (OpenMouse stores only local settings and collects nothing). Report suspected tapjacking/injection abuse privately via SECURITY.md, not a public issue.
- Public-facing changes: `applicationId`, `versionName`/`versionCode`, min/target SDK, `res/xml/accessibility_service_config.xml`, or F-Droid/Play metadata under `fastlane/`.
