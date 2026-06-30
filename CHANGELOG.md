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
- Navigation shortcuts: Back, Home, and Recent apps.
- Unit tests for the pure `DwellMachine` and `MenuGeometry` cores.

### Notes

- Built as an `AccessibilityService`; installs on Android 7.0+ with no root.
- Known limitation: while active, the overlay captures finger touch as well as
  the mouse. The Android 14+ `onMotionEvent` path will fix this.
