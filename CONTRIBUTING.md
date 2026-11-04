# Contributing to OpenMouse

Thanks for helping make Android more usable for people who rely on a mouse or
trackball. Contributions of all sizes are welcome, from typo fixes to new
gestures.

## Getting set up

1. Install **Android Studio** (Koala or newer) or a JDK 17+ and the Android SDK.
2. Open the project folder and let Android Studio sync. The Gradle wrapper is
   committed, so `./gradlew` also works from the command line.
3. Build and test:
   ```bash
   ./gradlew assembleDebug   # build the APK
   ./gradlew test            # run JVM unit tests (JUnit 5)
   ./gradlew lint            # static analysis
   ```
   The fuzz targets in `app/src/test/.../fuzz/` are part of `test`, where they
   only replay their seed corpus. To actually fuzz (ten minutes per target):
   ```bash
   JAZZER_FUZZ=1 ./gradlew testDebugUnitTest --tests '*FuzzTest'
   ```
   CI runs the full ten-minute fuzz nightly (`.github/workflows/nightly-fuzz.yml`).
4. Turn on the pre-push hook, which runs the unit tests and lint before every
   push (the same checks as the CI fast job):
   ```bash
   ./gradlew installGitHooks      # or: git config core.hooksPath .githooks
   ```

To try a change on hardware: install the debug APK, plug in a mouse (USB OTG or
Bluetooth), and enable **OpenMouse cursor** in Accessibility settings.

### Smoke-testing on an emulator (no device needed)

Much of the app can be verified headlessly. Boot an API 34+ AVD, install, and
enable the service over adb (Android 14+ blocks sideloaded accessibility services
until you grant the restricted-settings appop):

```bash
emulator -avd <name> -no-window -gpu swiftshader_indirect &
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell appops set io.github.owenpkent.openmouse ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
  io.github.owenpkent.openmouse/io.github.owenpkent.openmouse.service.MouseAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys window | grep -i openmouse   # overlay attached, NOT_TOUCHABLE?
adb exec-out screencap -p > shot.png           # cross-hair + menu render?
```

This verifies the overlay, touch passthrough, rendering, and crashes. What it
**cannot** do: drive a real mouse cursor. A headless emulator has no mouse device,
and the accessibility framework filters `adb input`-injected events out of
`onMotionEvent`, so the cursor pipeline is covered by the unit tests instead.

## Project shape

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) first. The short version:

- Input enters via `service.onMotionEvent` (API 34+, non-touchable overlay) or
  `CursorView`'s capture (API 24-33, touchable overlay).
- `DwellClicker` decides when a rest is a click; a physical button click is the
  same primary action.
- `MouseAccessibilityService.handlePrimaryAction()` either selects a menu entry
  or performs the current gesture.
- `GestureDispatcher` injects the real input via `dispatchGesture()`.

**Keep logic out of the Android classes.** Decision logic goes in a pure class
(see `DwellMachine`, `MenuGeometry`) so it can be unit tested on the JVM. The
Android wrapper should be a thin pass-through.

## Gates

Every check that can stop a change, what triggers it, and what to do when it
fails. The plan for the gates still to come is in [`docs/PARITY.md`](docs/PARITY.md).

| Gate | Runs | When it fails |
| --- | --- | --- |
| Pre-push hook (`.githooks/pre-push`) | Before each `git push`: `./gradlew testDebugUnitTest lintDebug`, the same as the CI fast job. `./gradlew installGitHooks` turns it on. | Read the tail it prints (full log in `.git/pre-push.log`), fix the cause, push again. Never `--no-verify`. |
| `Tests` (`ci.yml`) | Every pull request and every push to `main`. One required check that needs every job below (the vulnerability diff included) and fails if any did not succeed. The one skip it accepts is the vulnerability diff on a push to `main`, which has no base to diff against. | Open the job that is red in the run. |
| Build, unit tests, lint | The debug APK, the JVM unit tests (the fuzz targets replay their seed corpus here), `lintDebug`, and a check that `app/gradle.lockfile` describes the build that just ran. | Reproduce with `./gradlew testDebugUnitTest lintDebug`. A stale lockfile (a Dependabot bump, a hand edit of `libs.versions.toml`): run `./gradlew :app:dependencies --write-locks` and commit the result. |
| New vulnerabilities in this pull request (`osv` job in `ci.yml`, steps in `osv-diff.yml`) | Pull requests: OSV-Scanner on the merge base and on the head, failing only on an advisory the pull request introduces. The result files are kept outside the checkout, so nothing in the tree can alter the comparison. | Bump the artifact named in the log in `gradle/libs.versions.toml`, regenerate the lockfile, commit both. If there is no fix or it does not apply, say why in the pull request. |
| Vulnerability gate self-test (`osv-selftest.yml`) | Mondays, on demand, and on pull requests that change `osv-diff.yml` or itself: runs the gate's steps on a head with a planted jackson-databind 2.9.8 and symlinks over the result file names, and must see the gate fail; then on a head with nothing new, and must see it pass. Not part of `Tests`. | The gate would let a new advisory through: do not merge the change to `osv-diff.yml` until it passes. |
| Vulnerabilities (`osv.yml`) | Mondays and on demand: a full scan of `app/gradle.lockfile` that fails on any known advisory. | Not a pull request check. Bump the artifact named in the log and regenerate the lockfile. |
| CodeQL (`codeql.yml`) | Every push and pull request into `main`, and Wednesdays: GitHub code scanning over the Kotlin, from a traced debug build. | Open the alert under Security, Code scanning. Fix the code, or dismiss the alert there with a reason if it is wrong; the workflow itself fails only when the analysis could not run. |
| Dependabot (`dependabot.yml`) | Weekly pull requests for Gradle and GitHub Actions, minor and patch bumps grouped. | Review and merge like any pull request; there is no auto-merge. A Gradle bump fails the lockfile check until you regenerate `app/gradle.lockfile` on its branch. |
| Release build and lint (unsigned) | `assembleRelease` and `lintRelease`: minified and shrunk, the way a release ships, so a ProGuard or resource problem fails here and not at release time. The unsigned APK is kept a week. | Reproduce with `./gradlew assembleRelease lintRelease`. CI has no keystore, so the APK is unsigned; signing is never done in CI. |
| `Reviewed` (`review-gate.yml`) | Every pull request into `main`, again on each push and each review. A commit status set on the head commit: success when a review with a summary was submitted on it and the newest one does not request changes. A reply to an inline comment does not count. A push leaves the new commit with no status until it is reviewed. | Get the pull request reviewed on its latest commit; after a push, review again. The workflow's own job, `Set the Reviewed status`, succeeds whatever it decides and fails only when it cannot read the reviews or write the status; then re-run it. |
| Nightly fuzz (`nightly-fuzz.yml`) | 03:17 UTC daily, and on demand: each Jazzer target fuzzes for ten minutes. Not a pull request check. | Download the `fuzz-findings` artifact, replay the input with `./gradlew test` (the file goes under `app/src/test/resources/**/*FuzzTestInputs/`), fix the bug, and commit the input as a permanent regression test. Do not weaken the invariants in the target. |

Branch protection on `main` requires `Tests`, the `Reviewed` status, and the
`Set the Reviewed status` check, and applies to admins too.

## Coding conventions

- Kotlin, official code style (`kotlin.code.style=official`, enforced by ktlint
  defaults in Android Studio).
- Match the surrounding code: comment the *why*, not the *what*.
- No em dashes in code, comments, or docs. Use a period, a colon, or parentheses.
- Run `./gradlew test lint` before opening a PR (the pre-push hook runs the
  unit tests and debug lint for you).

## How to add a new gesture

Most new gestures follow the same path:

1. Add a value to `GestureAction` (in `menu/GestureMenu.kt`).
2. Add a label string in `res/values/strings.xml` and a row in `GestureMenu`'s
   `items` list. The grid resizes itself via `MenuGeometry` (two columns,
   more on a short screen).
3. Add the injection method to `GestureDispatcher` (build a `GestureDescription`
   with the right strokes). Use existing methods as a template.
4. Handle the new mode in `MouseAccessibilityService.performMode()`. Decide
   whether it is one-shot (revert to `TAP`), sticky, or repeating (add it to
   `GestureAction.isRepeating` so a dwell re-arms in place). Two-point gestures use the
   `pendingX/pendingY` start-point flow.
5. If it injects touches, dispatch through `runGesture` so the overlay passes the
   gesture through to the app below.
6. If you extracted any pure logic, add a unit test under `app/src/test/`. If
   it consumes pointer positions, timestamps or settings, extend the matching
   fuzz target under `app/src/test/.../fuzz/` (or add one) so the nightly
   fuzz run drives it with arbitrary input.

## Reporting issues

Useful bug reports include the device model, Android version, mouse type, and
what you expected versus what happened. Accessibility regressions are treated as
high priority.

## License

By contributing you agree that your contributions are licensed under the
project's [MIT License](LICENSE).
