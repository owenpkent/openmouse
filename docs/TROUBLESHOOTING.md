# Troubleshooting

Common problems and what to do about them: building, installing, using the
cursor, and updating. "Android 14+" below means API 34 and newer, where OpenMouse
reads the mouse through the accessibility service and its overlay never takes
touches; "Android 13 and older" means API 24-33, where the overlay itself
captures the mouse (see [ARCHITECTURE.md](ARCHITECTURE.md)).

## Building

### `JAVA_HOME is not set and no 'java' command could be found`

Gradle needs a JDK 17 or newer (the app compiles to Java 17). Android Studio
bundles one:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

```bash
export JAVA_HOME="/path/to/android-studio/jbr"   # or any JDK 17+ (Temurin, Zulu, ...)
./gradlew assembleDebug
```

An older JDK fails early with an "Android Gradle plugin requires Java 17" style
error; point `JAVA_HOME` at a newer one.

### `SDK location not found`

Gradle cannot find the Android SDK. Either create `local.properties` at the
repository root (gitignored; Android Studio writes it on first sync, but a fresh
clone or a git worktree has none):

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

or set `ANDROID_HOME` to the SDK folder. The build needs SDK platform 36 and a
recent build-tools; install them from Android Studio's SDK Manager if Gradle
asks.

### `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`

`gradle/wrapper/gradle-wrapper.jar` is missing or damaged. Restore it with
`git checkout -- gradle/wrapper/`. If that does not help, regenerate it with any
installed Gradle: `gradle wrapper --gradle-version 9.2.1`. On Linux or macOS,
`permission denied: ./gradlew` means `chmod +x gradlew`.

### `Resolved dependency ... does not match the lock state`

A dependency changed without its lock. Run
`./gradlew :app:dependencies --write-locks` and commit `app/gradle.lockfile`.

### The release build is unsigned

`assembleRelease` writes `app-release-unsigned.apk` unless `keystore.properties`
exists (see [SIGNING.md](SIGNING.md)). That is expected on every machine but the
owner's, and in CI.

## Installing

### `App not installed as package conflicts with an existing package`

The APK is signed with a different key than the installed copy: a debug build
over a GitHub release, a release over a debug build, a debug build from another
machine, or F-Droid and GitHub installs mixed. Android will not replace one with
the other. Uninstall OpenMouse first (this deletes its settings) or install the
matching build. [SIGNING.md](SIGNING.md#which-builds-can-update-which) has the
full table.

### The phone will not install a downloaded APK

Sideloading needs permission for the app that opens the APK. On Android 8+,
allow **Install unknown apps** for the browser or file manager you used
(Settings > Apps > that app > Install unknown apps). On Android 7, turn on
Settings > Security > **Unknown sources**. Play Protect may warn about an
unrecognised app; OpenMouse is not on Play yet, so the warning is expected for
GitHub builds.

### "Restricted setting" when enabling the service (Android 13+)

Android 13 and newer block accessibility access for apps installed from a
downloaded file. Turning on **OpenMouse cursor** shows "Restricted setting: For
your security, this setting is currently unavailable." To allow it:

1. Try to turn on **OpenMouse cursor** once, so Android records the attempt.
2. Open Settings > Apps > **OpenMouse**, tap the **⋮** menu (top right) and
   choose **Allow restricted settings**, then confirm with your PIN or
   fingerprint.
3. Go back to Accessibility settings and turn on **OpenMouse cursor**.

From a computer with adb, the same thing is
`adb shell appops set io.github.owenpkent.openmouse ACCESS_RESTRICTED_SETTINGS allow`.
Installs through an app store (F-Droid, Play) are normally not restricted, and a
ROM build that preinstalls OpenMouse is exempt (see `platform/`).

## Using the cursor

### The service is on but there is no cross-hair

The cross-hair is drawn at the centre of the screen as soon as the service
starts; it does not wait for the mouse. If there is none at all:

- Turn **OpenMouse cursor** off and on again in Accessibility settings. If
  Android refuses the overlay window, the service stays switched on with
  nothing drawn, and the OpenMouse screen still says it is ON (it only checks
  the accessibility setting).

### The cross-hair is there but does not follow the mouse

- OpenMouse only follows devices that Android reports as a **mouse**
  (`SOURCE_MOUSE`). Most USB and Bluetooth mice and many trackballs do; some
  touchpads, air mice and game controllers do not. `adb shell dumpsys input`
  lists each device's sources.
- Check the connection: on most phones a connected mouse shows the system
  pointer.
- **Android 13 and older, after a rest:** when the mouse has been still for more
  than 6 seconds, OpenMouse hands the screen back to the finger (see below) and
  looks for the mouse again for 0.3 seconds every 2.5 seconds. The first move
  after a long rest can take up to about 2.5 seconds to pick up.

### The cursor moves but nothing clicks

- **Dwell click** may be off in OpenMouse settings. With it off there is no
  countdown anywhere, the menu included; only a physical left click acts.
- OpenMouse may be **paused**: the menu entry reads **Resume** and is
  highlighted. Pause stops dwell clicks on apps; the menu still works.
- After every click the pointer must move away before the next countdown can
  start. After a dwell click it must travel the **re-arm distance** (20 dp by
  default); after a physical click, a menu choice, a rotation or any settings
  change it must move past the **movement tolerance** (8 dp by default). This
  is what stops a resting or shaky hand from clicking the same spot again.
- A menu entry fires once per visit: resting on it longer, or a tremor inside
  the cell, does nothing until the pointer leaves the cell and comes back.

### Taps land away from the cross-hair

They should not: the overlay is laid out over the whole display (on Android 11+
including the area beside a camera cutout), and the same screen coordinate is
drawn and tapped. If
taps land at a fixed offset, report it with the device model, Android version,
orientation, and whether split screen or a second display is in use.

- After rotating the phone, move the mouse once: the cross-hair keeps its old
  position until the next mouse event, and dwell waits for a move.
- Known issue in v0.1.0 on Android 11 to 13: the overlay can stop above the
  navigation bar, so the cursor cannot reach the bottom strip of the screen. A
  fix is in progress.

### The touchscreen does nothing (Android 13 and older)

On these versions the overlay has to take touches to see the mouse, and it
ignores finger touches rather than passing them on. OpenMouse never leaves the
finger locked out for long:

- **Mouse idle:** after more than 6 seconds without mouse input, the next check
  (every 2.5 seconds) makes the screen touch-through. Expect the finger to work
  again within about 9 seconds of letting go of the mouse. While it waits for
  the mouse to come back, a finger tap can still be swallowed during the 0.3
  second look every 2.5 seconds; tap again.
- **Stuck gesture:** each injected tap or gesture passes touches through to the
  app while it runs. If Android never reports a gesture finished, OpenMouse
  takes the screen back after 1.5 seconds anyway.

If the screen still will not respond, turn off **OpenMouse cursor** in
Accessibility settings (with the mouse, or with the finger once it works). Most
phones let you assign the **accessibility shortcut** (hold both volume keys) to
OpenMouse cursor on its accessibility settings page; setting that up in advance
gives a hardware off switch. On Android 14 and newer the overlay never takes
touches, so the touchscreen always works.

### The mouse disconnected

OpenMouse does not notice a disconnect; to it, the mouse is simply resting.

- The cross-hair stays where it was. A countdown already running finishes and
  clicks there once.
- In **Scroll** mode a resting pointer scrolls once per dwell period, so it
  keeps scrolling until the mouse moves again. Pick **Pause** or another mode
  before unplugging, or turn the service off.
- A Drag or Swipe whose start point was marked stays pending; the next click
  after reconnecting finishes it from the old start. Choosing any menu entry
  cancels it.
- On Android 13 and older the touchscreen comes back as described above.

## Updating

Sideload builds have **Settings > Updates** with "Check for updates now" and an
option to check when OpenMouse opens. It talks only to GitHub, and only when one
of those asks. F-Droid builds leave the updater out and update through F-Droid.

| Message | Meaning | What to do |
| --- | --- | --- |
| You have the latest version (X) | The newest GitHub release is not newer than the installed `versionName`. | Nothing. |
| Version X is available | A newer release with an APK was found. | Tap **Download and install**. |
| Could not check for updates: no release found | No published release, or the latest one is a draft or prerelease, is not tagged `vX.Y.Z`, or has no `openmouse-*.apk` asset. | Try later; if it persists, report it. |
| Could not check for updates: HTTP 403 (or another HTTP code) | GitHub refused the request. 403 is usually its hourly limit for unauthenticated requests from one network. | Wait an hour and retry. |
| Could not check for updates: (a network error, timeout) | No connection, or GitHub took more than 10 seconds. | Check the connection and retry. |
| Could not check for updates: the download is not hosted on GitHub | The release asset pointed (or redirected) somewhere other than this repository's GitHub downloads. Refused before downloading. | Do not install it by hand; report it. |
| Could not check for updates: the download is too large / the download was incomplete | Over 100 MB, or fewer bytes than GitHub announced. | Retry; report it if it repeats. |
| The downloaded file is not signed by the same key as this app; not installing | The new APK's package or signing certificate differs from the installed app's. Usual cause: the installed copy is a debug build (or from another source) and the release is signed with the release key. | Uninstall and install the release APK by hand (settings are lost). See [SIGNING.md](SIGNING.md#which-builds-can-update-which). |
| Allow OpenMouse to install updates, then try again | Android has not given OpenMouse permission to install apps; it opened that setting. | Turn on **Allow from this source** for OpenMouse, go back, and tap the check again. |
| Opening the installer... | The APK passed the checks and was handed to Android's installer. | Confirm the install. If Android itself then says "package conflicts", see [Installing](#app-not-installed-as-package-conflicts-with-an-existing-package). |

## Reporting a problem

Include the device model, Android version, the mouse or trackball, the OpenMouse
version, and what you expected against what happened. Suspected security
problems (for example a way to make OpenMouse tap without the user) go through
[SECURITY.md](../SECURITY.md), not a public issue.
