# Changelog

All notable changes to OpenMouse are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it ships a
tagged release.

## [Unreleased]

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
