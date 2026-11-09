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

Full Play Console steps, the exact permissions-declaration text, and the Data
Safety answers are in [`docs/play/`](play/README.md).

## Pre-ship checklist

- [x] **Signing**: the release keystore exists and signed v0.1.0; its
      certificate is pinned in `release-cert.sha256`. Backups, publishing from
      a second machine, and what a lost key means are in
      [`SIGNING.md`](SIGNING.md). Play would add Play App Signing; F-Droid
      signs with its own key.
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

## Signed release tags

A release is a tag `vX.Y.Z` on `main`, signed by the owner with an SSH key
listed in [`release-signers`](../release-signers) at the repository root.
Nothing else marks a commit as "meant to run": F-Droid builds the commit the tag
names (`UpdateCheckMode: Tags` in the metadata), the GitHub Release is created
for the tag, and `scripts/verify-release-tag.sh` is what anyone, the publish
script included, runs before trusting one.

A tag verifies when it is an annotated tag named for itself, carries a message
(the message is the release notes), and carries exactly one SSH signature from a
key in `release-signers`. `git verify-tag` is pointed at that file alone, so a
key that is merely in the owner's git configuration does not count.

### One-time key setup

Done once, by the owner, on the machine releases are made from. The key may be
the same one that signs the owner's other projects; `release-signers` carries
the Polaris release key for that reason.

```sh
ssh-keygen -t ed25519 -f ~/.ssh/openmouse-release -C openmouse-release
git config gpg.format ssh
git config user.signingkey ~/.ssh/openmouse-release.pub
printf 'openmouse-release namespaces="git" %s\n' "$(cut -d' ' -f1,2 ~/.ssh/openmouse-release.pub)" >> release-signers
git add release-signers && git commit -m "chore(release): allow the release key"
```

Check that signing and verifying work before the first release:

```sh
git tag -s test-signing -m "test"
scripts/verify-release-tag.sh test-signing   # fails on the name; that is expected
git -c gpg.ssh.allowedSignersFile=release-signers verify-tag test-signing
git tag -d test-signing
```

## Release checklist

Releases are batched: one is cut when there is something worth installing, not
for every merged pull request, because each needs the owner's signature.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, add the
   `## [X.Y.Z]` entry to CHANGELOG.md (move the Unreleased notes under it), write
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500
   characters at most), and update `CurrentVersion`, `CurrentVersionCode` and
   the `Builds` entry in `docs/fdroid/io.github.owenpkent.openmouse.yml`.
   `ReleaseMetadataTest` (part of `./gradlew test`) fails when any of these
   disagree, so CI catches a half-done bump.
2. Merge, and wait for the `Tests` run on that commit on `main` to pass.
3. From a clean checkout of that commit, tag and sign, with the notes as the
   tag message. Keep the notes in a file so a failed attempt costs only a retry:

   ```sh
   git tag -s v0.2.0 -F notes.txt
   scripts/verify-release-tag.sh v0.2.0
   git push origin v0.2.0
   ```

4. Build and publish from the tag (needs `keystore.properties` for the signing
   key, `gh` logged in, and Git for Windows for `sh`):

   ```powershell
   git checkout v0.2.0
   pwsh -File scripts\publish-release.ps1          # add -Draft to read it over first
   ```

   The script refuses a dirty tree, a HEAD that is not the tag, a tag that does
   not verify, a missing keystore, or an APK that `apksigner` does not verify
   or that is signed with any certificate other than the one pinned in
   `release-cert.sha256` ([SIGNING.md](SIGNING.md); `-CheckApk <apk>` runs just
   that check, for example on a downloaded asset). It writes `dist/openmouse-v0.2.0.apk` and
   its SHA-256, and creates the GitHub Release with the tag message as notes.
   The in-app updater looks for the `openmouse-*.apk` asset of the latest
   release, and Android installs it only over an app signed with the same key,
   so the keystore must be the one that signed what users have. Never attach a
   debug APK (such as the `apks/` copy) to a release. Users' install and update
   problems are covered in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
5. Play: roll through **internal → closed → open** testing tracks before
   production. Recruit real testers (see below) on the closed track.
6. F-Droid: submit metadata to the fdroiddata repo; F-Droid builds from source.
   The ready-to-submit metadata and step-by-step notes are in
   [`docs/fdroid/`](fdroid/README.md). After the first submission, F-Droid picks
   up each new tag on its own.

## After launch

- A privacy-respecting crash signal (or just ask users to file logs) so
  device-specific mouse/overlay bugs surface. Avoid trackers.
- Keep the `CHANGELOG.md` as the source of truth for release notes.
