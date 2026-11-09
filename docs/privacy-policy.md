# OpenMouse Privacy Policy

_Last updated: 2026-11-04_

OpenMouse is an open-source Android accessibility tool. The short version:
**the app collects nothing, and the only thing it ever connects to is GitHub,
and only when you ask it to check for an update.**

## Data collection

None. OpenMouse does not collect, store, or transmit any personal or usage data.
It contains no analytics, no advertising, and no crash reporting.

## Network use

The one network feature is the optional update check, under Settings > Updates:

- It runs only when you press **Check for updates now**, or when you have
  turned on **Check for updates when OpenMouse opens**. That switch is **off by
  default**; nothing checks in the background, and nothing is scheduled.
- It talks to exactly two hosts, both GitHub's: `api.github.com` to read the
  list of releases, and `github.com` (with GitHub's own download servers) to
  fetch the new APK, and only if you choose **Download and install**.
- It sends no identifiers. The request carries the app's name and version as
  its user agent and nothing else; GitHub sees it the way it sees any download,
  including your IP address, under
  [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
- Builds distributed through F-Droid have this feature compiled out entirely
  and never connect to anything.

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

The app requests no runtime permissions. It declares two install-time
permissions, both for the optional update check described above:

- `INTERNET`: to contact GitHub when you ask for an update check.
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
