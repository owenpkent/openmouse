# OpenMouse

An open-source Android cursor for people who use a mouse or trackball instead of
a touchscreen. OpenMouse draws a large cross-hair pointer that follows your
mouse and clicks for you when the pointer rests still, so no buttons are
required. It is built for people with tremors, limited fine motor control, or
conditions like cerebral palsy.

It is a clean-room, open-source clone of the excellent (closed-source)
[Ease Mouse](https://easeapps.xyz/ease-mouse/) by crea_si, rebuilt in Kotlin so
the approach can be studied, forked, and improved. OpenMouse is not affiliated
with or endorsed by Ease Apps.

> **Status: working early version.** The cursor follows the mouse with dwell and
> standard clicks, and an on-screen menu adds double-tap, long-press, drag,
> swipe, scroll, and Back / Home / Recent. The pure logic is unit tested and the
> app builds against the current Android toolchain. The remaining items (tremor
> filter, pinch-to-zoom, a settings screen) are on the roadmap below.

## Why "integrated directly into Android"

OpenMouse is a standard Android **AccessibilityService**, not an overlay hack or
a custom ROM. It hooks into the same accessibility framework that screen readers
use, which is the only stock-Android mechanism that can both observe pointer
input and inject taps/gestures across every app. That means:

- No root.
- No custom Android build.
- Installs on any device running **Android 7.0 (API 24) or newer**.

`AccessibilityService.dispatchGesture()` (the tap-injection API) landed in
Android 7.0, which is exactly why 7.0 is the floor.

## How it works

```
physical mouse
      │  motion events
      ▼
┌─────────────────────────────┐
│ input capture               │  API 34+: service.onMotionEvent (overlay
│  - reports x,y              │  non-touchable, touch passes through)
│                             │  API 24-33: CursorView captures the mouse
└──────────────┬──────────────┘
               │ x,y                (CursorView draws the cross-hair + menu)
               ▼
┌─────────────────────────────┐
│ DwellClicker                │  fires when the cursor rests still
│  - countdown → click        │  for the dwell time; locks until the
│  - progress → cursor        │  cursor moves away again
└──────────────┬──────────────┘
               │ tap(x,y)
               ▼
┌─────────────────────────────┐
│ GestureDispatcher           │  dispatchGesture() injects a real tap
│  via AccessibilityService   │  into the app underneath
└─────────────────────────────┘
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the details, including the
two input-capture paths and how injected gestures reach the app below.

## Features

**Working now**

- Big cross-hair cursor that follows a USB or Bluetooth mouse / trackball
- Dwell click: rest the pointer and it clicks automatically, with a shrinking
  countdown for feedback
- Standard click: a physical left-button press taps immediately
- Scroll wheel scrolls the content under the cursor
- On-screen gesture menu (collapsible, two-column grid, left or right edge)
- Tap modes: single tap, double-tap, long-press
- Drag and swipe: two-point gestures (click a start point, then an end point)
- Scroll up / down at the cursor
- Navigation from the menu: Back, Home, Recent, Notifications, Quick Settings
- Settings screen (applied live): dwell time, movement tolerance, cursor size,
  cursor color, menu side, and a dwell-click on/off toggle
- On Android 14+ the overlay is non-touchable, so the touchscreen keeps working
  normally and a stray finger can never move the cursor or fire a click

**Roadmap** (mirrors the original's feature set)

- [ ] "Ease click": hold the button through a countdown, with a tremor filter
- [ ] Pinch-to-zoom (two-finger gesture)
- [ ] Drag-and-drop pickup (initial long-press before the drag)
- [ ] Hide the system pointer on Android 14+ (only the cross-hair is shown)

## Building

You need **Android Studio** (Koala or newer) or a local JDK 17+ and the Android
SDK. The project uses **AGP 9.0.1** with AGP's built-in Kotlin (no separate
Kotlin plugin), targets **SDK 36**, and the committed Gradle wrapper pins
**Gradle 9.2.1**.

```bash
# In Android Studio: File ▸ Open ▸ select this folder, then Run ▸ app.
# Or from the command line:
./gradlew assembleDebug   # build the APK
./gradlew test            # run the unit tests
```

If Android Studio does not create `local.properties`, add it with your SDK path:

```
sdk.dir=/path/to/Android/Sdk
```

## Enabling OpenMouse on a device

1. Install the app and plug in a mouse (USB OTG or Bluetooth).
2. Open OpenMouse and tap **Open accessibility settings**.
3. Under *Installed services*, enable **OpenMouse cursor** and accept the prompt.
4. Move the mouse: the big cross-hair appears. Hold it still to click.

To stop, turn the service back off in accessibility settings.

## Project layout

```
app/src/main/
├── java/io/github/owenpkent/openmouse/
│   ├── MainActivity.kt                 onboarding + enable button
│   ├── SettingsActivity.kt             settings screen (live-applied)
│   ├── service/MouseAccessibilityService.kt   the engine
│   ├── cursor/CursorView.kt            overlay: draws + captures input
│   ├── click/DwellClicker.kt           Android timer wrapper
│   ├── click/DwellMachine.kt           pure dwell logic (unit tested)
│   ├── menu/GestureMenu.kt             menu: drawing + action mapping
│   ├── menu/MenuGeometry.kt            pure layout + hit-testing (unit tested)
│   ├── settings/OpenMouseSettings.kt   SharedPreferences-backed settings
│   └── gesture/GestureDispatcher.kt    dispatchGesture() wrapper
├── res/xml/accessibility_service_config.xml
└── AndroidManifest.xml

app/src/test/java/io/github/owenpkent/openmouse/   JVM unit tests
├── click/DwellMachineTest.kt
└── menu/MenuGeometryTest.kt
```

## Testing

The Android-coupled classes (the service, the overlay view, the gesture
dispatcher) are deliberately thin. The decision logic they wrap lives in pure
classes with no Android dependencies, so it runs as fast JVM unit tests:

- `DwellMachine` -- the dwell-to-click state machine (clock injected, not read)
- `MenuGeometry` -- the menu's layout and hit-testing

```bash
./gradlew test          # run all unit tests
./gradlew testDebugUnitTest
```

When adding behavior, prefer putting the logic in a pure class and testing it
there, keeping the Android wrapper a straight pass-through.

## Contributing

Issues and pull requests are welcome, especially from the AAC / accessibility
community. The roadmap items above are good starting points.

- [CONTRIBUTING.md](CONTRIBUTING.md) — setup, conventions, emulator testing, and
  how to add a new gesture
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — community standards
- [SECURITY.md](SECURITY.md) — reporting a vulnerability
- [docs/RELEASING.md](docs/RELEASING.md) — the plan for shipping to F-Droid / Play
- [docs/UPSTREAMING.md](docs/UPSTREAMING.md) — the plan for getting it into Android
  natively (AOSP / LineageOS)

## License

[MIT](LICENSE) © 2026 Owen Kent.
