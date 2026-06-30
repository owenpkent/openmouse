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

> **Status: early MVP.** The cursor follows the mouse and performs dwell clicks.
> The richer feature set (gesture menu, multiple click modes, navigation
> shortcuts) is on the roadmap below.

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
      │  hover / motion events
      ▼
┌─────────────────────────────┐
│ CursorView (overlay)        │  full-screen, draws the cross-hair,
│  - captures pointer motion  │  hides the system pointer
│  - reports x,y              │
└──────────────┬──────────────┘
               │ x,y
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
overlay-touchability dance that lets injected taps reach the app below.

## Features

**Working now**

- Big cross-hair cursor that follows a USB or Bluetooth mouse / trackball
- System pointer hidden so there is only one, highly visible cursor
- Dwell click: rest the pointer and it clicks automatically, with a shrinking
  countdown for feedback
- Standard click: a physical left-button press taps immediately
- On-screen gesture menu (collapsible, docked to the right edge, two-column grid)
- Tap modes: single tap, double-tap, long-press
- Drag and swipe: two-point gestures (click a start point, then an end point)
- Scroll up / down at the cursor
- Navigation shortcuts from the menu: Back, Home, Recent apps

**Roadmap** (mirrors the original's feature set)

- [ ] "Ease click": hold the button through a countdown, with a tremor filter
- [ ] Pinch-to-zoom (two-finger gesture)
- [ ] Drag-and-drop pickup (initial long-press before the drag)
- [ ] Notification-shade shortcut in the menu
- [ ] Settings: dwell time, cursor size/color, move threshold, menu position
- [ ] Use `onMotionEvent` on Android 14+ so finger touch is not captured

## Building

You need **Android Studio** (Koala or newer) or a local JDK 17 + Android SDK.

```bash
# In Android Studio: File ▸ Open ▸ select this folder, then Run ▸ app.
# Or from the command line once the SDK is configured:
./gradlew assembleDebug
```

> **Note on the Gradle wrapper:** this repo intentionally does not commit the
> binary `gradle/wrapper/gradle-wrapper.jar`. Android Studio regenerates it the
> first time you open the project. If you build from the CLI without Android
> Studio, run `gradle wrapper --gradle-version 8.9` once (with a system Gradle
> installed) to create it.

Create a `local.properties` with your SDK path if Android Studio does not:

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
│   ├── service/MouseAccessibilityService.kt   the engine
│   ├── cursor/CursorView.kt            overlay: draws + captures input
│   ├── click/DwellClicker.kt           Android timer wrapper
│   ├── click/DwellMachine.kt           pure dwell logic (unit tested)
│   ├── menu/GestureMenu.kt             menu: drawing + action mapping
│   ├── menu/MenuGeometry.kt            pure layout + hit-testing (unit tested)
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
community. The roadmap items above are good starting points. See
[CONTRIBUTING.md](CONTRIBUTING.md) for setup, coding conventions, and how to add
a new gesture.

## License

[MIT](LICENSE) © 2026 Owen Kent.
