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
| `GestureDispatcher` | Builds taps, drags, swipes, and scrolls as an `Injection` (gesture + stroke start points) and sends them via `dispatchGesture()`. |
| `PassthroughGate` | **Pure** bookkeeping for the legacy overlay's click-through window (unit tested). |
| `SwallowedGestureTracker` | **Pure** detection of gestures the legacy overlay swallowed, and the resend decision (unit tested). |
| `OpenMouseSettings` | Typed, range-clamped wrapper over SharedPreferences. |
| `MainActivity` | One-time onboarding and a shortcut into accessibility settings. |
| `SettingsActivity` | Settings screen; writes straight to `OpenMouseSettings`. |
| `ReleaseFeed` | **Pure** GitHub release parsing, version comparison and URL allow-list (unit tested). |
| `LaunchCheck` | **Pure** rule for the once-per-launch update check: default on, explicit off kept, never when compiled out (unit tested). |
| `UpdateChecker` | Fetches the release feed and APK, checks the signing certificate, hands off to the installer. |
| `UpdatePrompt` | The "new version" dialog shared by Settings and the launch check. |

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
   leave the overlay stuck non-touchable and freeze the cursor). The bookkeeping
   is the pure `PassthroughGate`: the watchdog deadline belongs to the *oldest*
   outstanding gesture and later gestures never push it out, so a repeating
   scroll (one notch per repeat interval) cannot keep postponing recovery after a
   lost callback.

   The window manager applies the click-through change asynchronously, so a
   fixed wait before injecting is a race: on a slow device (the CI emulator
   took over 75 ms) the overlay is still touchable when the gesture arrives,
   takes it, and the tap is lost. So `CursorView` also reports any touchscreen
   DOWN it rejects, and the pure `SwallowedGestureTracker` matches it, first in
   first out, to the oldest outstanding stroke that starts at that point and
   was dispatched no later (each `Injection` carries its stroke start points).
   A short settle after each completion lets a late DOWN arrive, and settled
   gestures stay on record for a second so a late DOWN is absorbed by its own
   gesture, never credited to a newer one. The gesture runs again, after a
   longer wait (24, then 100, 200 ms), only if every one of its strokes was
   swallowed, it is still the newest request (every gesture and every menu
   choice is one), and it is within 400 ms of the first attempt completing.
   The governing rule is **never repeat what reached the app**: a double tap
   that half got through, an action a newer one superseded, or a retry so late
   the user may have clicked again themselves (that click reaches the app
   through the click-through overlay) is dropped rather than sent. Since a
   split gesture is never repaired, a multi-stroke gesture (double tap) waits
   200 ms before its first attempt, and once any swallow is seen the device is
   known to be slow and every first attempt waits 100 ms. A fast device with
   single strokes keeps the original 24 ms. Ending a passthrough restores the idle
   watchdog's choice (click-through while capture is suspended), and the idle
   sample makes the overlay touchable again as soon as the mouse is back.
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

`DwellMachine` keeps an *anchor* (where the cursor came to rest, plus the menu
entry under it, if any) and a monotonic rest start. `DwellClicker` polls it
every 16 ms with `SystemClock.uptimeMillis()`, but only while a countdown is
running (`isCountingDown`: armed, no button held, not paused over the app); an
idle machine, which is most of the time, costs no wake-ups, and every call that
can arm it (a move, a re-arm in place) restarts the ticker. The machine computes
`progress = elapsed / dwellTime`. The rules mirror the PowerToys Dwell Click
engine, and every one of them is biased toward refusing to fire when intent is
unclear (the "Midas touch" problem: a resting pointer looks like an aiming one):

- It starts **locked**, and re-locks after every click, after a physical click,
  after a wheel turn (the content under the resting pointer changed, so the
  countdown is no longer aimed at what the user chose), on pause, after any live
  settings change, and when the ticker restarts. A lock is cleared only by
  deliberate pointer movement; no amount of elapsed time will.
- **A held button suppresses dwelling.** The service reports the button state
  on every pointer event (`buttonState != 0`), and while any button is down the
  machine stays locked whatever the pointer does: a dwell must never cut short
  the user's own drag or selection. Letting go locks until the pointer moves, so
  the spot where the button came up is not clicked by the rest that follows.
- **Two tolerances.** The movement tolerance is the jitter a running countdown
  survives; drift within it leaves the countdown alone, which is what lets a hand
  with a tremor complete a dwell at all. The re-arm distance is how far the
  pointer must travel after a dwell click before a new countdown may start. Only
  the lock a dwell click leaves behind uses it; it is a separate setting so it
  can be widened without making dwelling harder, and it never reads narrower
  than the movement tolerance.
- **Rest keys.** On every move the service hands the machine the menu entry under
  the cursor (or null over the app). While that key is unchanged the entry itself
  is the tolerance: a tremor inside a menu cell, however large, neither restarts
  the countdown nor re-fires the entry. Entering a cell while a countdown is
  running starts the cell's countdown at once: the cell edge is the deliberate
  act.
  Entering one while the machine is locked is not enough on its own; the pointer
  must also clear the lock's tolerance from the lock anchor, since a lock is
  cleared by deliberate movement and nothing else. That is what keeps a tremor
  across a cell edge from selecting the neighbour after a menu choice, and what
  keeps the dock from selecting a cell it laid out beside a still pointer when
  it opened (the collapsed handle is taller than a cell, so opening it can leave
  the pointer in a gap with cells 1 px away). Leaving a cell for the app locks
  and the ordinary distance applies from the rest point, so a slip into the gap
  between cells, or a menu that re-lays out under a still pointer (the strip
  shifts on short screens), never taps the app. After a menu choice, a dock
  change, or a resize (`CursorView` reports every re-layout), the service
  re-feeds the entry now under the pointer before locking, for the same reason.
  This is the PowerToys toolbar rule ("an activated button will not fire again
  until the pointer leaves it").
- **Pause** (the menu's Pause / Resume entry) suspends dwelling on the app but
  not on the menu, so a user who cannot click can still reach Resume. It locks in
  both directions, so time spent paused never counts toward a countdown. The
  service also refuses a dwell that reaches `handlePrimaryAction` over the app
  while paused, so a rest key that went stale between pointer events can never
  tap the app; physical clicks are unaffected.
- **The ring waits, the click does not.** The countdown ring stays hidden for
  the first fifth of a countdown, at most 250 ms (`Poll.ringProgress`). Once the
  machine is armed every brief pause in ordinary movement starts a countdown,
  and a ring drawn from the first poll trailed a pointer that had merely slowed
  down. The dwell itself fires exactly when it always did.
- **Scroll repeats in place.** After a scroll dwell the service re-arms the
  machine at the same anchor instead of waiting for movement. Only the first
  notch at a rest point waits the full dwell time, because that one is the
  decision; the re-armed countdown runs for the scroll repeat interval (400 ms
  by default, a setting), so another notch lands each interval while the
  pointer keeps resting. One notch per dwell time, three lines a second at the
  defaults, was too slow to read a page by. Moving away, or any lock, goes back
  to the full dwell time.

`SystemClock.uptimeMillis()` is used (not wall-clock) so the timing is immune to
clock changes. Changing a setting live re-locks, so dragging the dwell slider
down cannot fire an instant click against already-elapsed rest.

## Gesture modes and the menu

A click is not always a single tap. `GestureMenu` holds a `currentMode` (tap,
double-tap, long-press, drag, swipe, or scroll) plus navigation entries
(Back / Home / Recent / Notifications / Quick Settings) and a Pause / Resume
entry.

The menu is **not a real View hierarchy**. `CursorView` owns every pointer
event, so a separate touchable button strip underneath it would never receive
hover. Instead the menu is pure data: `CursorView` draws it, and the service
hit-tests the cursor position against it. Whenever the current mode is not a
plain tap, `CursorView` also draws an **action badge**, a pill naming the mode
in words (`GestureMenu.labelFor`; the dock itself draws symbols), up and to the left of the cross-hair (flipping to the other side
near an edge; the pure `BadgePlacement` decides where), so a sticky scroll or a
drag waiting for its end point is visible where the user is looking, not only
on the menu at the screen edge. Both the dwell timer and a physical left-click
funnel into one method:

```
handlePrimaryAction(x, y, fromDwell):
    if cursor is over a menu entry:
        tap/drag/scroll mode -> set currentMode
        Back/Home/Recent/Notifications/QuickSettings -> performGlobalAction(...)
        pause -> pause / resume dwell clicking on the app
        toggle -> expand / collapse the strip
        (any menu choice also cancels a half-started two-point gesture)
        lock the dwell machine with the ordinary tolerance; the entry cannot
        re-fire anyway until the cursor leaves it (it is the rest key)
    else if fromDwell and paused:
        re-anchor and lock; nothing reaches the app
    else:
        run the currentMode gesture at (x, y) via dispatchGesture
        reset non-tap modes back to TAP (one-shot)
        dwell + scroll mode -> re-arm in place (repeat while resting)
        dwell + anything else -> already locked with the re-arm distance
        physical click -> lock with the ordinary tolerance
```

Navigation actions use `performGlobalAction()` and need no passthrough, since they
do not inject touches into our window. Everything that injects touches (tap,
double-tap, long-press, drag, swipe, scroll) goes through `runGesture`, which
dispatches directly on the modern path and does the legacy passthrough dance on
API 24-33, including resending a gesture the overlay swallowed (see "Legacy
path" above).

The strip is a dock flush with one screen edge, laid out by the pure
`MenuGeometry`: square glyph cells (symbols, not words, so they stay small and
need no translation) under a toggle that is anchored at its collapsed center
(so it does not jump when the grid expands) and clamped to stay on screen on
short/landscape displays. The grid is two columns wide on a tall screen; when the expanded strip would still run off a
short one (a phone in landscape) it widens a column at a time until it fits or
the screen width runs out, so the last row, Pause / Resume, stays reachable
however the device is held. While collapsed, only a slim handle on the edge is
a hit target (the screen edge stops the pointer, so a narrow target there is
easy to hit with a mouse) and the rest of the screen clicks through; the
left/right dock setting moves it off a busy edge.

### One-shot vs sticky modes

After it runs, a mode either reverts to `TAP` (one-shot) or stays selected
(sticky):

- **One-shot**: double-tap, long-press, drag, swipe. You do one, then you are
  back to tapping.
- **Sticky**: tap, scroll-up, scroll-down. Scrolling is repeatable, so it stays
  active; a scroll dwell also re-arms in place, so holding still keeps
  scrolling one notch per repeat interval after the first full dwell.

The Pause entry is neither: it toggles dwell clicking on the app on and off,
reads "Resume" while paused, and stays reachable by dwell because the machine
keeps firing on menu entries while paused.

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
- `PassthroughGate` -- when the legacy overlay goes click-through and when it
  comes back, including the watchdog deadline.
- `SwallowedGestureTracker` -- matching touches the legacy overlay rejected to
  the strokes they came from, and whether resending is safe. Times and
  positions are parameters, so every race above can be replayed exactly.

The pattern to follow when adding behavior: put the logic in a pure class, test
it there, and keep the Android wrapper a straight pass-through. Tests live in
`app/src/test/` and run with `./gradlew test`.

Every pure class is also fuzzed (Jazzer, under `app/src/test/.../fuzz/`),
the same arrangement PowerToys uses for its Dwell Click engine: the fuzzer
decodes its bytes into event sequences with adversarial coordinates, timestamps
and settings, and invariants are asserted after every poll. That is why the pure
classes clamp degenerate inputs instead of trusting the caller: a dwell time
below 1 ms reads as 1 ms, a negative or NaN threshold as 0, a timestamp older
than the rest anchor as zero elapsed time, and a negative item count or column
count as 0 and 1. Fuzzing is opt-in (`JAZZER_FUZZ=1`); a plain `gradlew test`
only replays the seed corpus. A nightly workflow runs it for real.

## UI tests: the Android wrappers, end to end

The wrappers are covered by instrumented tests in `app/src/androidTest/`
(`gradlew connectedDebugAndroidTest`, on an emulator in CI). The platform filters
shell-injected input out of `onMotionEvent`, so the tests, which run in the app's
process, take the connected service from `MouseAccessibilityService.connected`
(a `@VisibleForTesting` weak reference) and feed synthetic `SOURCE_MOUSE` events
in where real ones arrive: `onMotionEvent` on API 34+, the `CursorView` overlay
(`overlayView`) on API 24-33. Everything after that is production code, so on
the legacy path each injected gesture only reaches the app if the passthrough
made the overlay click-through for it. Injected
touches land in `TouchRecorderActivity`, a non-exported activity in the `debug`
source set (absent from release builds), which logs their screen coordinates so a
test can compare them with where the cross-hair was. The seam is read-only apart
from those entry points: `menuEntryAt`, `currentMode`, `hasPendingPoint` and
`isPaused` let a test find menu entries and observe state, nothing more.

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

## Updates

Sideloading from GitHub Releases is the only live channel, so the app can look
for its own updates there. This is the **only code in OpenMouse that touches
the network**, and it is built to stay narrow:

- **Once per launch, or on request.** `UpdateChecker.check` runs when the user
  presses "Check for updates now" in Settings, or once per process when
  `MainActivity` opens if the launch check is on. It is on by default in builds
  that carry the updater (the sideload APK has no store to tell it about new
  versions); the pure `LaunchCheck` decides, from the build flag, the stored
  switch (absent means the default, an explicit `false` always wins) and the
  per-process latch, and is unit-tested (`LaunchCheckTest`). The launch check is
  quiet: it shows nothing unless a newer release exists. There is no scheduled
  job and no background service, and the accessibility service never touches
  the network.
- **Two hosts.** The feed is `GET api.github.com/repos/owenpkent/openmouse/
  releases/latest` with an `Accept: application/vnd.github+json` header and a
  `OpenMouse/<version>` user agent (GitHub requires one); nothing else is sent.
  The APK may only be fetched from
  `https://github.com/owenpkent/openmouse/releases/download/`
  (`ReleaseFeed.isAllowedDownloadUrl`). Redirects are followed by hand, at most
  five, https only, and each hop must land on `github.com` or a
  `*.githubusercontent.com` host (`isAllowedRedirectHost`); the platform's
  automatic following would go anywhere the server said.
- **Caps.** The feed body is capped at 1 MiB and the APK at 100 MiB; when the
  release lists the asset size, the download must match it exactly. The file
  lands in `cache/updates/` and is exposed to the installer through a
  `FileProvider` scoped to that one folder (`res/xml/file_paths.xml`).
- **Same key or nothing.** Before the installer sees the file,
  `signatureMatches` reads the archive's signing certificates
  (`GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` before) and requires
  the set to equal the installed app's, and the package name to be ours. A
  mismatch deletes the file and tells the user; it is never offered to the
  install flow. The system installer then asks the user itself, and on API 26+
  the app first sends them to grant "install unknown apps" if it has not been.
- **Compile-out.** `BuildConfig.UPDATER_ENABLED` comes from the Gradle property
  `openmouseUpdater` (default true). `-PopenmouseUpdater=false` hides the whole
  Settings section and skips the launch check (R8 then drops the updater code).
  The updater's manifest entries (`INTERNET`, `REQUEST_INSTALL_PACKAGES`, the
  `FileProvider`) live in `app/src/updater/AndroidManifest.xml`, which
  `app/build.gradle.kts` merges in (`sources.manifests.addStaticManifestFile`)
  only when the updater is compiled in, so a flag-off APK declares no network
  permission at all.
  The F-Droid recipe and the Play build both pass it: both stores sign with
  their own key and ship updates themselves, and Play policy forbids an app
  updating itself outside Play. Those builds make no network connection.
- **No retained state.** Every callback checks that the activity that asked
  for it is still alive (`UpdatePrompt.shouldDeliver`: neither finishing nor
  destroyed) and otherwise drops the result. Rotating or leaving Settings
  mid-download therefore never shows a dialog on, or launches the installer
  from, a screen that is gone; the file stays in the cache and the next check
  offers it again.

The decisions (what counts as a release, which version is newer, which URLs
are allowed) live in the pure `ReleaseFeed` and are unit-tested; `UpdateChecker`
is the Android wrapper around sockets, `PackageManager` and intents.

## Coordinate space

The overlay uses `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, top-left
gravity, and `layoutInDisplayCutoutMode = ALWAYS`, so it spans the entire display
and its `(0, 0)` is the screen's `(0, 0)`. The emulator confirms the frame is
`Rect(0,0 - w,h)`. That means a single coordinate works everywhere: the value
drawn for the cross-hair, hit-tested against the menu, and handed to
`dispatchGesture()` are all the same screen-space point (the modern path reads
`event.rawX/rawY`). Without the cutout mode the window could start below a notch
and every injected tap would land at a fixed offset from the cross-hair.
