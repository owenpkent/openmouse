# Releasing / shipping OpenMouse

OpenMouse is already a native Android (Kotlin) app. "Shipping it officially"
means getting it into users' hands through a store, with the release hygiene that
requires. This is the plan.

## Distribution channels

| Channel | Fit | Notes |
| --- | --- | --- |
| **F-Droid** | Excellent | OSS, local-only, no trackers, no ads. The privacy-conscious assistive-tech audience lives here. Lowest-friction first launch. |
| **Google Play** | Good, with care | Widest reach, but AccessibilityService apps get strict review (see below). Needs a privacy policy, Data Safety form, and a signing key. |
| **GitHub Releases (APK)** | Yes, immediately | A signed debug/release APK per tag for sideloading and testers. Already the natural home for the OSS build. |

Recommended order: **GitHub Releases now → F-Droid → Google Play**. Each step
raises the bar; do them in that order.

## The one big risk: Google Play's AccessibilityService policy

Play scrutinizes any app that uses `AccessibilityService`. Apps that use it for
non-accessibility purposes are removed. OpenMouse is a genuine accessibility tool
for people with motor disabilities, which is exactly the sanctioned use, but you
must make that unmistakable:

- **`android:isAccessibilityTool="true"`** is set in the service config (done).
  This declares the service is a bona-fide accessibility tool and exempts it from
  some of the "prominent disclosure" friction.
- In the Play Console, complete the **Accessibility / permissions declaration**
  and describe the disability use case plainly.
- The store listing and the in-app onboarding must both explain what the service
  does and why it needs accessibility access.
- Do **not** add any feature that reads screen content for non-accessibility
  reasons; the config already drops `canRetrieveWindowContent`.

## Pre-ship checklist

- [ ] **Signing**: create a release keystore (keep it safe and backed up) or use
      Play App Signing. F-Droid signs with its own key.
- [ ] **Release build**: turn on `isMinifyEnabled` + shrinkResources for release;
      verify the ProGuard keep rule for the service still holds and the app runs.
- [ ] **Versioning**: bump `versionCode` / `versionName` per release; tag it.
- [ ] **App icon polish**: address the launcher-icon lint (non-square silhouette,
      add a `<monochrome>` layer for themed icons).
- [ ] **Privacy policy**: host a short page (GitHub Pages works). The honest and
      strong story: OpenMouse collects nothing and sends nothing; settings are
      stored locally. Required by Play for accessibility apps.
- [ ] **Data Safety form** (Play): "No data collected / shared."
- [ ] **Localization**: the UI is already string-resourced; translations widen
      reach a lot for an accessibility tool.
- [ ] **Target API**: keep `targetSdk` current (Play enforces a floor).

## Release process

1. Cut a signed APK/AAB from a tagged commit; attach the APK to a GitHub Release.
2. Play: roll through **internal → closed → open** testing tracks before
   production. Recruit real testers (see below) on the closed track.
3. F-Droid: submit metadata to the fdroiddata repo; F-Droid builds from source.
   The ready-to-submit metadata and step-by-step notes are in
   [`docs/fdroid/`](fdroid/README.md).

## After launch

- A privacy-respecting crash signal (or just ask users to file logs) so
  device-specific mouse/overlay bugs surface. Avoid trackers.
- Keep the `CHANGELOG.md` as the source of truth for release notes.
