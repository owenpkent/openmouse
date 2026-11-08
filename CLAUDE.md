# OpenMouse

Open-source Android AccessibilityService that draws a large cross-hair cursor which follows a physical mouse/trackball and auto-clicks by dwelling, for users with tremors or limited fine motor control. Kotlin, no root, Android 7.0+ (API 24+).

## Key rules

- **Keep logic out of the Android classes.** Decision logic lives in pure classes with no Android imports (`DwellMachine`, `MenuGeometry`) and is JVM unit-tested; the Android wrappers (`DwellClicker`, `GestureMenu`, `MouseAccessibilityService`) stay thin pass-throughs. When adding behavior, put the logic in a pure class and test it there, then wrap it.
- **Pure cores never read the clock or Android APIs.** `DwellMachine.poll(nowMs)` takes time as a parameter; only `DwellClicker` reads the monotonic `SystemClock.uptimeMillis()`. The dwell machine starts *locked* and re-locks after each click (re-arm needs `postActionThresholdPx`), after a physical click or pause (ordinary `moveThresholdPx`), and after any live config change. The service passes the menu entry under the cursor as the `restKey` on every move: inside one entry no distance is checked, so entries never re-fire until the pointer leaves; entering an entry arms at once, but leaving one for the app locks until the ordinary distance is cleared (so a slip into a gap, or a menu that moves under a still pointer, never taps the app). After a menu choice or a dock change the service re-feeds the entry under the pointer (`resyncRestKey`) before locking. Pause keeps firing on menu entries, never on the app. Scroll re-arms in place (`rearmInPlace`); everything else locks.
- **Two input paths by API level; never lock out the touchscreen.** API 34+ uses a permanently non-touchable overlay + `service.onMotionEvent`; API 24-33 uses a touchable `CursorView`. Any overlay/input change must preserve both, keep `CursorView.onTouchEvent` ignoring non-`SOURCE_MOUSE` events, and keep the ref-counted passthrough + idle watchdogs so a dead/disconnected mouse can never freeze touch. The click-through change lands asynchronously, so never rely on a fixed wait alone: `CursorView` reports rejected touchscreen DOWNs and `SwallowedGestureTracker` retries a gesture only when every stroke was swallowed and it is still the newest action (never repeat what reached the app).
- **Inject touches only through `runGesture`.** Every touch-injecting gesture (tap, double-tap, long-press, drag, swipe, scroll) goes through `runGesture` so the overlay passes it through to the app below. Navigation actions (Back/Home/Recent/Notifications/Quick Settings) use `performGlobalAction()` and must not.
- **One screen-space coordinate everywhere.** The overlay spans the full display with `layoutInDisplayCutoutMode = ALWAYS`; the same `(x, y)` is drawn, hit-tested against the menu, and handed to `dispatchGesture()`. Do not drop the cutout mode or the full-screen layout flags, or every injected tap lands at a fixed offset from the cross-hair.
- **Settings are live, no Save button.** Controls write straight to `OpenMouseSettings` (clamps every value to its range); the service's `OnSharedPreferenceChangeListener` calls `applySettings()`. A new setting means: add a clamped accessor in `OpenMouseSettings` and wire it into `applySettings()`.
- **No em dashes** in code, comments, or docs. Use a period, colon, or parentheses.

## Stack & layout

- Kotlin Android AccessibilityService. AGP 9.0.1 (built-in Kotlin, no separate plugin), Gradle wrapper 9.2.1, JDK 17, min/target/compile SDK 24/36/36, namespace `io.github.owenpkent.openmouse`. Deps via `gradle/libs.versions.toml`.
- `app/src/main/java/io/github/owenpkent/openmouse/` - `service/`, `cursor/`, `click/`, `menu/`, `gesture/`, `settings/`.
- `app/src/androidTest/` - instrumented UI tests (JUnit 4, Espresso). `CursorPipelineTest` drives the cursor in-process (shell input is filtered): through the service's `onMotionEvent` on API 34+, through the `CursorView` overlay on API 24-33, and reads injected touches back from the debug-only `TouchRecorderActivity` (`app/src/debug/`). Keep the service's `@VisibleForTesting` seam (`connected`, `overlayView`, `menuEntryAt`, `currentMode`, `hasPendingPoint`, `isPaused`) read-only.
- `app/src/test/java/.../{click,menu}/` - JVM unit tests (JUnit 5: `DwellMachineTest`, `MenuGeometryTest`). `.../fuzz/` - Jazzer fuzz targets (`DwellMachineFuzzTest`, `MenuGeometryFuzzTest`); they replay seeds under `gradlew test` and fuzz for real only with `JAZZER_FUZZ=1` (nightly workflow, or the same command locally).
- `docs/ARCHITECTURE.md` - read first. `platform/` + `Android.bp` - AOSP/LineageOS system-app build (not day-to-day, unvalidated). `fastlane/` - F-Droid/Play metadata.

## Build, run, test

- Build: `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`).
- Test: `./gradlew test` (or `./gradlew testDebugUnitTest`). Fuzz locally (ten minutes per target): `JAZZER_FUZZ=1 ./gradlew testDebugUnitTest --tests '*FuzzTest'`.
- Pure classes must stay defined for any input (clamp, never throw): the fuzz targets assert that. When adding logic that takes positions, timestamps or settings, extend the matching fuzz target.
- Lint: `./gradlew lint`. Run `./gradlew test lint` before opening a PR, plus `./gradlew connectedDebugAndroidTest` when touching the service, overlay, menu, gestures or settings screens.
- UI tests: boot an AVD and run `./gradlew connectedDebugAndroidTest`; the tests enable the service themselves. CI runs them on API 30 and 34. Get `UiAutomation` only with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` (use `Device.shell()`), or OpenMouse is unbound mid-test. Manual smoke-test commands are in CONTRIBUTING.md.

## Conventions

- Kotlin official style (`kotlin.code.style=official`, ktlint defaults). Comment the *why*, not the *what*; match surrounding code.
- Add a gesture via the recipe in CONTRIBUTING.md: extend `GestureAction` -> add a string + `GestureMenu` `items` row -> add a `GestureDispatcher` method returning an `Injection` (gesture + exact stroke start points) -> handle it in `MouseAccessibilityService.performMode()` (decide one-shot vs sticky vs repeating via `GestureAction.isRepeating`; two-point gestures use the `pendingX/pendingY` start-point flow) -> send it with `runGesture(injection)` -> unit-test any extracted pure logic.

## When to ask

- Anything widening the trust surface: new permissions, capturing or persisting input, network calls, or collecting user data (OpenMouse stores only local settings and collects nothing). Report suspected tapjacking/injection abuse privately via SECURITY.md, not a public issue.
- Public-facing changes: `applicationId`, `versionName`/`versionCode`, min/target SDK, `res/xml/accessibility_service_config.xml`, or F-Droid/Play metadata under `fastlane/`.
