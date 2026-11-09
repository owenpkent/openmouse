# Publishing OpenMouse to Google Play

Play is the "regular app" route. OpenMouse is a normal signed app, so this is
straightforward; the one gate is Play's AccessibilityService policy, which we are
set up to pass.

## One-time setup (your actions)

- A **Google Play Developer account** ($25 one-time). This cannot be automated.
- Enroll in **Play App Signing** (recommended): Google holds the distributed
  signing key; you upload with an *upload key* (the existing
  `openmouse-release.jks`). Keep that upload key.

## Build the upload artifact

Play wants an **App Bundle (.aab)**, not an APK, and it **must** be built with
the in-app updater compiled out:

```bash
./gradlew -PopenmouseUpdater=false bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab  (signed with keystore.properties)
```

Do not upload a bundle built without `-PopenmouseUpdater=false`. The default
(sideload) build checks GitHub Releases on launch and can download and install
a new APK; Play's Device and Network Abuse policy forbids an app from updating
itself by any means other than Play's own update mechanism. With the flag the
Updates section, the launch check and all network code are gone, and Play
delivers updates.

Known gap: the shared manifest still declares `INTERNET` and
`REQUEST_INSTALL_PACKAGES` even in that build. Nothing uses them, but
`REQUEST_INSTALL_PACKAGES` is a restricted permission on Play, so strip it from
the Play build (or be ready to justify it in the Console) before submitting.

## The gate: AccessibilityService policy

Play scrutinizes apps that use `AccessibilityService`. OpenMouse qualifies because
it is a genuine disability tool, and it declares `android:isAccessibilityTool="true"`.
In the Play Console you must complete the **Permissions declaration** for the
accessibility use. Suggested text (adapt as needed):

> OpenMouse is an accessibility tool for people with motor disabilities (tremors,
> limited fine motor control, conditions like cerebral palsy). It uses the
> AccessibilityService API solely to (1) draw an on-screen cursor overlay
> controlled by a physical mouse or trackball, and (2) perform the taps and
> gestures the user initiates, via dispatchGesture. It does not read window
> content or collect data, and the Play build makes no network connections.
> The service is declared with android:isAccessibilityTool="true".

Also ensure prominent disclosure: the store listing and the in-app onboarding
screen both explain what the service does (the onboarding already does).

## Privacy policy

Required. Use the live page: **https://owenpkent.github.io/openmouse/privacy-policy**
(states that nothing is collected).

## Data Safety form

- Data collected: **None**
- Data shared: **None**
- The Play build (`-PopenmouseUpdater=false`) has no update check and makes no
  network connection. No user data is collected, so there is nothing to encrypt
  in transit or delete.

Answer "No data collected or shared." Only the sideload APK on GitHub Releases
checks for updates (once per launch, on by default); the privacy policy
describes that, and says plainly that Play builds never connect to anything.

## Store listing (assets are ready in `fastlane/metadata/android/en-US/`)

- App name: `title.txt` (OpenMouse)
- Short description (<= 80 chars): `short_description.txt`
- Full description: `full_description.txt`
- App icon 512x512: `images/icon.png`
- Feature graphic 1024x500: `images/featureGraphic.png`
- Phone screenshots: `images/phoneScreenshots/` (add a few more; min 2)
- **Category:** Play has no "Accessibility" category; use **Tools** and make the
  accessibility purpose clear in the description.
- Content rating: complete the questionnaire (Everyone).
- Ads: No. Target audience: adults / all ages.

## Release tracks

Go **Internal -> Closed -> Open -> Production**. Use the Closed track to recruit
real testers with motor disabilities before a wide release. Do not jump straight
to Production.

## Optional: automate with fastlane

The `fastlane/metadata` layout here is Play-compatible. Once you set up a Play
service account, `fastlane supply` can push the listing and the AAB. Manual
Console upload is fine to start.

## One caution across channels

The GitHub APK (our release key), F-Droid (F-Droid's key), and Play (Play App
Signing key) are signed differently, so a user should install from **one** source;
switching sources needs an uninstall/reinstall.
