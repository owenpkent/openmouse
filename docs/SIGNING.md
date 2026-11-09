# App signing

Android identifies an app by its package name (`io.github.owenpkent.openmouse`)
**and** the certificate it is signed with. It installs an update only when the
new APK is signed with the same certificate as the copy already on the device.
Everything in this page follows from that rule.

If an install fails with:

> **App not installed as package conflicts with an existing package.**

(or `INSTALL_FAILED_UPDATE_INCOMPATIBLE` from `adb install`), the APK is signed
with a different key than the installed copy. It is not a bug in the app; see
[Which builds can update which](#which-builds-can-update-which).

## The release key

| | |
| --- | --- |
| Keystore | `openmouse-release.jks` (kept outside the repository) |
| Alias | `openmouse` |
| Certificate | `CN=Owen Kent, O=OpenMouse, C=US` |
| Certificate SHA-256 | `39d33daa9d4f31a0153f631d1dc2accf45197fb810cecf4923304c1ad1f4d50f` |
| First used for | v0.1.0 on GitHub Releases (`openmouse-v0.1.0.apk`) |

The digest is pinned in [`release-cert.sha256`](../release-cert.sha256) at the
repository root, the one place it is written. `scripts/publish-release.ps1`
refuses to publish an APK that does not verify or whose single signer has any
other certificate, and `ReleaseMetadataTest` checks that the file holds one
well-formed digest.

`app/build.gradle.kts` signs the `release` build type only when
`keystore.properties` exists at the repository root (copy
[`keystore.properties.example`](../keystore.properties.example) and fill it in).
Without it `assembleRelease` writes an unsigned `app-release-unsigned.apk`, which
is what CI builds. Both `keystore.properties` and `*.jks` are gitignored.

### Check which key signed an APK

```powershell
pwsh -File scripts\publish-release.ps1 -CheckApk path\to\openmouse-vX.Y.Z.apk
```

That runs only the certificate check and exits 0 on a match. By hand, with the
SDK's build-tools:

```bash
apksigner verify --print-certs openmouse-vX.Y.Z.apk   # "Signer #1 certificate SHA-256 digest: ..."
keytool -list -v -keystore openmouse-release.jks -alias openmouse   # SHA256 shown as colon-separated hex
```

To check the copy on a device, pull it first:
`adb shell pm path io.github.owenpkent.openmouse`, then `adb pull <path>` and run
`apksigner` on it.

## Why the key is not in the repository

The owner's Handy app commits its signing keystore so every machine produces
installable updates. OpenMouse must not, for two reasons:

- **The repository is public.** Anyone could read a committed key, and anything
  signed with it is, to Android, an update to OpenMouse.
- **OpenMouse is an accessibility service that injects taps.** A user who has
  enabled it has granted it the power to act on every app on the phone. An
  "update" built by a stranger with the real key would install over the user's
  copy, keep that permission, and could tap anything the user can. A leaked key
  is a remote-control risk, not just a branding problem.

So the key lives only with the owner, and releases are signed on the owner's
machine by `scripts/publish-release.ps1`. CI never signs.

## Back up the key

There is no way to recover or regenerate a lost key. Keep at least two copies of
**both** `openmouse-release.jks` and its passwords (store and key):

1. **A password manager** entry holding the two passwords, the alias, the
   certificate SHA-256 above, and the `.jks` file as an attachment.
2. **An offline copy**: the `.jks` and a printed or written note of the
   passwords on an encrypted USB drive (or similar) kept somewhere other than the
   main machine.

After making a backup, prove it works: `keytool -list -v` on the backup copy
with the stored password must print the same SHA-256. Never put the key in the
repository, an issue, a chat, an email or a cloud folder that syncs the
checkout.

## Publishing from a second machine

1. Clone the repository as usual.
2. Copy `openmouse-release.jks` and `keystore.properties` over a private channel
   (the encrypted drive, or the password manager attachment). Put the `.jks`
   outside the checkout and fix `storeFile` in `keystore.properties` to its
   absolute path on that machine.
3. Confirm git ignores them: `git status` must not list either file
   (`git check-ignore -v keystore.properties` names the rule).
4. Follow the [release checklist](RELEASING.md#release-checklist). The publish
   script's certificate check fails if the copied keystore is not the release
   key, before anything is uploaded.

## If the key is lost

There is no update path. Every install from GitHub Releases is tied to the lost
key, and Android will not let a differently signed APK replace it. To move to a
new key:

1. Generate a new keystore, put its certificate SHA-256 in
   `release-cert.sha256` (and the table above), and note the change in
   CHANGELOG.md.
2. Publish the next release with it, and tell users plainly in the release
   notes and the README that they must **uninstall OpenMouse and install the
   new APK**. The in-app updater will refuse the new APK (its signature check
   fails), so users have to do this by hand.
3. Uninstalling deletes OpenMouse's settings (dwell time, tolerances, cursor size
   and colour, menu side). After reinstalling, users enable **OpenMouse cursor**
   in Accessibility settings again, and on Android 13+ allow restricted settings
   again ([TROUBLESHOOTING.md](TROUBLESHOOTING.md#restricted-setting-when-enabling-the-service-android-13)).

If the key is **leaked** rather than lost, treat it as a security incident:
report it through [SECURITY.md](../SECURITY.md), stop publishing with it, and
plan a move to a new key the same way. (APK Signature Scheme v3 key rotation,
`apksigner rotate`, can carry existing installs to a new key, but only while
the old key is still in hand.)

## Which builds can update which

| Installed copy | Signed with | Can be updated by |
| --- | --- | --- |
| GitHub Releases APK (`openmouse-vX.Y.Z.apk`) | The release key above | Newer GitHub Releases APKs only |
| Debug build (`./gradlew installDebug`, `apks/openmouse-v*-debug.apk`) | The building machine's `~/.android/debug.keystore` | Debug builds from the **same machine** only |
| F-Droid | F-Droid's own key for this app | F-Droid only |
| Google Play (planned) | Play App Signing | Play only |

All four share one package name, so only one can be installed at a time, and
switching between them means uninstalling first (losing the settings):

- **Debug over release, or release over debug:** refused with "package
  conflicts". The debug key is generated per machine, so a debug build from a
  second machine will not install over the first machine's debug build either.
  To test a debug build on a phone that has the release, uninstall the release.
- **F-Droid and GitHub:** F-Droid builds from source and signs with its own key,
  so an F-Droid install and a GitHub install can never update each other. F-Droid
  builds also compile the in-app updater out (`-PopenmouseUpdater=false`) and
  update through the F-Droid client.
- **The in-app updater** (sideload builds) checks before installing that the
  downloaded APK has the same package name and exactly the same signing
  certificates as the running app. A mismatch shows "The downloaded file is not
  signed by the same key as this app; not installing" instead of handing it to
  the installer.

Never attach a debug APK (from `apks/` or `app/build/outputs/`) to a GitHub
Release: the updater picks the release's `openmouse-*.apk` asset, and a
debug-signed one would fail that check for every user.
