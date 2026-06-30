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
| `MainActivity` | One-time onboarding and a shortcut into accessibility settings. |

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

## Problem 1: hiding the system pointer

A connected mouse shows the platform pointer. We want only the big cross-hair.
`CursorView.onResolvePointerIcon()` returns a `PointerIcon.TYPE_NULL`, which
hides the hardware pointer whenever it is over our (full-screen) overlay. No
special permission needed; works from API 24.

## Problem 2: our overlay swallows our own taps

This is the subtle one. To capture the mouse, the overlay must be **touchable**.
But a touchable full-screen overlay also intercepts the synthetic taps that
`dispatchGesture()` injects, so the tap never reaches the app underneath.

OpenMouse resolves this in `MouseAccessibilityService.performTap()`:

1. Add `FLAG_NOT_TOUCHABLE` to the overlay and call `updateViewLayout()`, making
   it click-through.
2. Wait one short beat (`TAP_PASSTHROUGH_DELAY_MS`) so WindowManager has
   actually registered the window as non-touchable before the event is injected.
3. Dispatch the tap.
4. In the gesture result callback, remove `FLAG_NOT_TOUCHABLE` to resume
   capturing the mouse.

The `DwellClicker` lock guarantees only one tap is in flight at a time, so there
is no overlap between a tap-in-progress and the next countdown.

### The cleaner future path (Android 14+)

API 34 added `AccessibilityService.onMotionEvent()` plus
`setMotionEventSources()`. With those, the service can *observe* mouse motion
without a touchable overlay at all. That removes both the passthrough dance and
the side effect of capturing finger touch. The plan is to use `onMotionEvent` on
API 34+ and keep the touchable-overlay path as the fallback for 24–33. It is not
wired up yet.

## The dwell state machine

`DwellClicker` keeps an *anchor* (where the cursor came to rest) and a monotonic
`restStartUptime`. A 16 ms ticker computes `progress = elapsed / dwellTime`:

- Movement beyond `moveThresholdPx` re-anchors, restarts the timer, and unlocks.
- When `progress` reaches 1 it fires the click and **locks**.
- While locked, no clicks fire until the cursor moves past the threshold again.

It starts locked so a freshly enabled service never clicks before the user has
moved the mouse. `SystemClock.uptimeMillis()` is used (not wall-clock) so the
timing is immune to clock changes.

## Gesture modes and the menu

A click is not always a single tap. `GestureMenu` holds a `currentMode`
(`TAP`, `DOUBLE_TAP`, or `LONG_PRESS`) plus one-shot navigation entries.

The menu is **not a real View hierarchy**. `CursorView` owns every pointer
event, so a separate touchable button strip underneath it would never receive
hover. Instead the menu is pure data: `CursorView` draws it, and the service
hit-tests the cursor position against it. Both the dwell timer and a physical
left-click funnel into one method:

```
handlePrimaryAction(x, y):
    if cursor is over a menu entry:
        tap mode    -> set currentMode
        Back/Home/Recent -> performGlobalAction(...)
        toggle      -> expand / collapse the strip
    else:
        run the currentMode gesture at (x, y) via dispatchGesture
        reset non-tap modes back to TAP (one-shot)
    lock the dwell clicker until the cursor moves again
```

Navigation actions use `performGlobalAction()` and need no overlay passthrough,
since they do not inject touches into our window. Everything that injects touches
(tap, double-tap, long-press, drag, swipe, scroll) goes through `runGesture`
(the passthrough dance above).

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

## Coordinate space

The overlay uses `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` and top-left
gravity, so the view's `(0, 0)` is the screen's `(0, 0)`. `MotionEvent` view
coordinates therefore match the screen coordinates `dispatchGesture()` expects.
On devices with display cutouts or unusual insets this can drift slightly; a
future settings screen may expose a calibration offset.
