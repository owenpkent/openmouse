# OpenMouse Privacy Policy

_Last updated: 2026-11-09_

OpenMouse is an open-source Android accessibility tool. The short version:
**the app collects nothing. The only thing it ever connects to is GitHub, to
check for a newer version, and only in the APK downloaded from GitHub Releases.
Builds from F-Droid or Google Play never connect to anything.**

## Data collection

None. OpenMouse does not collect, store, or transmit any personal or usage data.
It contains no analytics, no advertising, and no crash reporting.

## Network use

The one network feature is the update check. Which build you have decides
whether it exists at all:

- **F-Droid and Google Play builds** have the update check compiled out. They
  do not even hold the `INTERNET` permission, so they cannot make a network
  connection of any kind, and their store delivers updates.
- **The APK from GitHub Releases** (the "sideload" build) includes it, because
  nothing else would tell you a new version exists.

In the sideload build:

- **Once each time OpenMouse opens**, it asks GitHub whether a newer release
  exists. This is **on by default**. It happens at most once per launch (not
  again on rotation or when you come back to the app while it is still
  running), and you only see anything if a newer version is available. To stop
  it, turn off **Check for updates when OpenMouse opens** under Settings >
  Updates; that choice is kept.
- You can also press **Check for updates now** under Settings > Updates at any
  time.
- Nothing checks in the background, and nothing is scheduled. The accessibility
  service itself never touches the network.
- The check is a single request to `api.github.com` for the latest release of
  `owenpkent/openmouse`. Only if you then choose **Download and install** does
  it fetch the new APK from `github.com` (and GitHub's own download servers).
  Those are the only hosts it will contact.
- It sends no identifiers, no settings and nothing about how you use the app.
  The request carries only the app's name and version as its user agent.
  GitHub sees it the way it sees any web request: your IP address, the time,
  that user agent and the release being asked about, handled under
  [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Nothing else in the app ever connects to the network.

## Your settings

Your preferences (dwell time, cursor size and color, menu side, and so on) are
stored only on your device using Android's private per-app storage. They never
leave the device and are deleted when you uninstall the app.

## Accessibility permission

OpenMouse runs as an Android AccessibilityService. It uses that access solely to:

- draw the on-screen cross-hair cursor and gesture menu, and
- move the cursor with your mouse and perform the taps and gestures you initiate.

It does **not** read, record, or transmit screen content, and it does not request
the "retrieve window content" capability.

## Permissions

The app requests no runtime permissions. F-Droid and Google Play builds declare
no network permission at all (and no permission to install apps). The sideload
build from GitHub Releases declares two install-time permissions, both for the
update check described above:

- `INTERNET`: to contact GitHub for the update check.
- `REQUEST_INSTALL_PACKAGES`: to hand a downloaded update to Android's package
  installer, which asks you before anything is installed. OpenMouse only offers
  an APK that is signed with the same key as the copy you already have.

## Children

The app collects no data from anyone, including children.

## Changes

Any change to this policy will be posted on this page with an updated date.

## Contact

Questions: Owenpkent@gmail.com  
Source code: https://github.com/owenpkent/openmouse
