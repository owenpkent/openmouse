# Getting OpenMouse into Android natively

The goal: make dwell-cursor mouse accessibility a built-in part of Android rather
than a third-party app. This is the realistic map, including what will and will
not work.

## The honest reality of mainline AOSP

- AOSP is developed by Google; its feature roadmap is set internally.
- Outside contributions go through Gerrit (android-review.googlesource.com) with
  a signed CLA. Google accepts **bug fixes and small improvements** this way.
- Google **almost never merges large new features from external contributors.**
  A new accessibility subsystem would be designed and built by Google, not
  accepted as an outside patch.

So: do not build a mainline-AOSP feature patch expecting it to be merged. Use the
routes below, which actually reach "native."

## The license advantage (important)

AOSP and LineageOS are **Apache-2.0**. An Apache-2.0 project **cannot** absorb GPL
code. The original EVA Facial Mouse is GPL, so it can never be upstreamed.
OpenMouse is **MIT**, which is Apache-compatible, so its code *can* be contributed
into AOSP / LineageOS. Keeping OpenMouse MIT and clean is exactly what keeps this
door open.

## Realistic routes to "native", best first

1. **LineageOS and other community ROMs (most achievable).** LineageOS is
   community-run and does accept feature contributions through its own Gerrit.
   Bundling dwell-cursor accessibility into LineageOS is far more realistic than
   mainline AOSP. Same idea applies to /e/OS, CalyxOS, GrapheneOS. As a bundled
   **system app** the accessibility service can be pre-granted, avoiding the
   sideload restricted-settings friction entirely.
2. **Advocacy + a Google feature request.** File on issuetracker.google.com under
   the Accessibility component with the use case, evidence of demand (installs,
   user testimonials), and a reference to the closed-source Ease Mouse gap. Enlist
   disability organizations and reach Google's accessibility team. Native features
   often ship after sustained, documented community demand.
3. **Assistive-tech OEMs / vendors.** Companies that ship accessibility-tuned
   Android devices can bundle it natively. A partnership route.
4. **App adoption as the foundation.** Every route above is stronger if OpenMouse
   is already widely used and loved. Ship on F-Droid (then Play), gather users and
   testimonials, then advocate. Adoption is the leverage.

## What "native" changes technically

- **Bundled system app** (LineageOS / OEM): mostly packaging. The accessibility
  service already exists, and preinstalled it is exempt from the restricted-
  settings block. A starting-point kit is already in the repo: the root
  [`Android.bp`](../Android.bp) plus [`platform/`](../platform/README.md). The app
  was made viewBinding-free specifically so Soong can build it.
- **Framework integration**: a dwell-click / cursor feature in the input +
  accessibility stack (`frameworks/base/services/accessibility/...`). Larger, but
  the current app is a working prototype and de-facto spec for it.
- MIT licensing keeps either option mergeable.

## If you still want to attempt mainline AOSP directly

- Sign the individual CLA (cla.developers.google.com), set up the `repo` tool and
  Gerrit, and build AOSP (~400 GB checkout, long builds).
- Start with a tiny, uncontroversial fix to learn the workflow before anything
  ambitious.
- Expect feature acceptance from outside to be very rare.

## Recommended sequence

1. Ship the app (F-Droid first); build a user base and collect testimonials.
2. File the Google accessibility feature request; enlist disability orgs.
3. In parallel, propose and contribute to **LineageOS** as the realistic
   "native on a shipping Android" win.
4. Lean on MIT + a clean codebase as the thing that makes adoption and merging
   easy.
