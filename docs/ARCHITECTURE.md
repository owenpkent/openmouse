# Architecture

OpenMouse is deliberately small. This document explains the moving parts and the
two genuinely tricky problems an Android mouse cursor has to solve.

## Components

| File | Responsibility |
| --- | --- |
| `MouseAccessibilityService` | Lifecycle, wiring, and the overlay window. Owns the others. |
| `CursorView` | The overlay view: draws the cross-hair/menu and captures pointer input. |
| `DwellClicker` | Android timer wrapper that drives `DwellMachine` from `SystemClock`. |
| `DwellMachine` | **Pure** dwell-to-click state machine (unit tested). |
| `GestureMenu` | Menu drawing and cell-to-action mapping. |
| `MenuGeometry` | **Pure** menu layout and hit-testing (unit tested). |
| `GestureDispatcher` | Injects taps, drags, swipes, and scrolls via `dispatchGesture()`. |
| `OpenMouseSettings` | Typed, range-clamped wrapper over SharedPreferences. |
| `MainActivity` | One-time onboarding and a shortcut into accessibility settings. |
| `SettingsActivity` | Settings screen; writes straight to `OpenMouseSettings`. |

Data flows one way: pointer motion enters `CursorView`, becomes `(x, y)` for
`DwellClicker`, and on a dwell becomes a `tap(x, y)` through `GestureDispatcher`.
Countdown progress flows back from `DwellClicker` to `CursorView` for rendering.

## Why an AccessibilityService

Three capabilities are needed and only the accessibility framework grants all
three on stock, unrooted Android:

1. **Draw over other apps.** A `TYPE_ACCESSIBILITY_OVERLAY` window added by an
   accessibility service does not require the `SYSTEM_ALERT_WINDOW` permission.
2. **Receive pointer input anywhere.** A full-screen overlay receives mouse
   hover and touch events regardless of which app is in front.
3. **Inject input into other apps.** `dispatchGesture()` (API 24+) synthesizes
   real taps and swipes. It requires `canPerformGestures="true"` in the service
   config.

## Input capture: two paths

Capturing a mouse without breaking the touchscreen is the central problem, and
the answer depends on the API level.

### Modern path (API 34+) -- the safe one

The overlay is created **permanently non-touchable** (`FLAG_NOT_TOUCHABLE`).
Mouse motion arrives through `AccessibilityService.onMotionEvent()`, enabled by
setting `FLAG_SEND_MOTION_EVENTS` and `motionEventSources = SOURCE_MOUSE` on the
service info. Because the overlay is non-touchable:

- finger touch passes straight through to the app below (no lockout, no stray
  taps moving the cursor);
- the synthetic gestures from `dispatchGesture()` pass through too, so there is
  no passthrough dance and no per-tap latency.

This is verified on the emulator: the overlay reports `inputConfig=NOT_TOUCHABLE`
and a finger tap through it still launches the app underneath.

### Legacy path (API 24-33)

Older devices have no `onMotionEvent`, so the only way to read the mouse is a
**touchable** overlay (`CursorView` captures hover/touch). That has three sharp
edges, each guarded:

1. *Stray finger input.* `CursorView.onTouchEvent` ignores any non-`SOURCE_MOUSE`
   event, so a palm or fingertip can never move the cross-hair or fire a click.
2. *Our overlay swallowing our own gestures.* `runGesture` makes the overlay
   click-through (`FLAG_NOT_TOUCHABLE`) for the duration of each injected gesture,
   then restores it. This is **ref-counted** (overlapping gestures cannot restore
   touchability early) with a **watchdog** (a dropped gesture callback cannot
   leave the overlay stuck non-touchable and freeze the cursor).
3. *Touchscreen lockout.* If no mouse event arrives for a few seconds, an idle
   watchdog drops the overlay to non-touchable so the bare touchscreen works
   again, then samples periodically to detect a returning mouse and re-arm. So
   the device can never be locked out by a dead/disconnected mouse.

### Hiding the system pointer

`CursorView.onResolvePointerIcon()` returns `PointerIcon.TYPE_NULL`, hiding the
hardware pointer while it is over the touchable overlay (the legacy path). On the
modern non-touchable path the platform pointer remains visible; the cross-hair is
drawn at the same point, so they coincide.

## The dwell state machine

`DwellClicker` keeps an *anchor* (where the cursor came to rest) and a monotonic
`restStartUptime`. A 16 ms ticker computes `progress = elapsed / dwellTime`:

- Movement beyond `moveThresholdPx` re-anchors, restarts the timer, and unlocks.
- When `progress` reaches 1 it fires the click and **locks**.
- While locked, no clicks fire until the cursor moves past the threshold again.

It starts locked so a freshly enabled service never clicks before the user has
moved the mouse. `SystemClock.uptimeMillis()` is used (not wall-clock) so the
timing is immune to clock changes. Changing the dwell time live re-baselines the
timer and re-locks, so dragging the dwell slider down cannot fire an instant
click against already-elapsed rest.

## Gesture modes and the menu

A click is not always a single tap. `GestureMenu` holds a `currentMode` (tap,
double-tap, long-press, drag, swipe, or scroll) plus navigation entries
(Back / Home / Recent / Notifications / Quick Settings).

The menu is **not a real View hierarchy**. `CursorView` owns every pointer
event, so a separate touchable button strip underneath it would never receive
hover. Instead the menu is pure data: `CursorView` draws it, and the service
hit-tests the cursor position against it. Both the dwell timer and a physical
left-click funnel into one method:

```
handlePrimaryAction(x, y):
    if cursor is over a menu entry:
        tap/drag/scroll mode -> set currentMode
        Back/Home/Recent/Notifications/QuickSettings -> performGlobalAction(...)
        toggle -> expand / collapse the strip
        (any menu choice also cancels a half-started two-point gesture)
    else:
        run the currentMode gesture at (x, y) via dispatchGesture
        reset non-tap modes back to TAP (one-shot)
    lock the dwell clicker until the cursor moves again
```

Navigation actions use `performGlobalAction()` and need no passthrough, since they
do not inject touches into our window. Everything that injects touches (tap,
double-tap, long-press, drag, swipe, scroll) goes through `runGesture`, which
dispatches directly on the modern path and does the legacy passthrough dance on
API 24-33.

The strip is laid out by the pure `MenuGeometry`: a two-column grid whose toggle
is anchored at its collapsed center (so it does not jump when the grid expands)
and clamped to stay on screen on short/landscape displays. While collapsed, only
the toggle is a hit target, so the rest of the screen clicks through; the
left/right dock setting moves it off a busy edge.

### One-shot vs sticky modes

After it runs, a mode either reverts to `TAP` (one-shot) or stays selected
(sticky):

- **One-shot**: double-tap, long-press, drag, swipe. You do one, then you are
  back to tapping.
- **Sticky**: tap, scroll-up, scroll-down. Scrolling is repeatable, so it stays
  active and each click scrolls again.

### Two-point gestures (drag, swipe)

Drag and swipe need a start and an end. The first primary action records the
start point (and `CursorView` draws a marker there); the second performs the
gesture from start to end. They differ only in stroke duration: a slow stroke
reads as a drag, a fast one as a fling. Selecting a different mode or toggling
the menu cancels a pending start.

`scroll` builds a vertical stroke centered on the cursor, clamped to the display
so it stays on-screen near the edges. Pinch-to-zoom (two simultaneous strokes)
and drag-and-drop pickup (a long-press before the move) are not wired up yet.

## Testability: pure cores

Anything that touches Android (`Handler`, `Canvas`, `WindowManager`,
`dispatchGesture`) is awkward to unit test. So the non-trivial logic is split out
into classes with **no Android imports**, which run as plain JVM tests:

- `DwellMachine` -- the dwell decision logic. Time is a parameter (`poll(nowMs)`),
  not read from `SystemClock`, so a test can drive it tick by tick. `DwellClicker`
  is the only thing that knows about the real clock and the timer.
- `MenuGeometry` -- layout and hit-testing on a plain `Bounds` rectangle (not
  `android.graphics.RectF`, which is not available in JVM tests). `GestureMenu`
  keeps the `Context`, `Paint`, and `Canvas` work.

The pattern to follow when adding behavior: put the logic in a pure class, test
it there, and keep the Android wrapper a straight pass-through. Tests live in
`app/src/test/` and run with `./gradlew test`.

## Settings and live updates

`SettingsActivity` has no Save button. Each control writes straight to
`OpenMouseSettings` (a SharedPreferences wrapper that clamps every value to its
range). The service registers a `OnSharedPreferenceChangeListener` and, on any
change, calls `applySettings()` to push the new values onto the live components:

- dwell time / movement tolerance -> `DwellClicker.configure()`
- cursor size / color -> `CursorView.setCursorScale()` / `setCursorColor()`
- menu side -> `GestureMenu.setDockRight()`
- dwell on/off -> start or stop the dwell ticker (physical clicks still work)

Because the settings screen and the service run in the same process, they share
one SharedPreferences instance, so the listener fires on the main thread and the
view updates are safe. The result is that tuning a slider moves the cursor on
screen immediately.

## Coordinate space

The overlay uses `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, top-left
gravity, and `layoutInDisplayCutoutMode = ALWAYS`, so it spans the entire display
and its `(0, 0)` is the screen's `(0, 0)`. The emulator confirms the frame is
`Rect(0,0 - w,h)`. That means a single coordinate works everywhere: the value
drawn for the cross-hair, hit-tested against the menu, and handed to
`dispatchGesture()` are all the same screen-space point (the modern path reads
`event.rawX/rawY`). Without the cutout mode the window could start below a notch
and every injected tap would land at a fixed offset from the cross-hair.
