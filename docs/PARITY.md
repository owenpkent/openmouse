# Parity plan

The engineering bar for OpenMouse is set by three other projects of the owner's:
the Handy Android app (release and update machinery, regression tests on virtual
time), Polaris (CI gates, scanning, signed releases, documentation), and the
Dwell Click module in the PowerToys fork (dwell behaviour and test depth). Handy
and Polaris have no dwell clicking, so the shared bar is engineering practice;
only Dwell Click gives a behavioural bar. This page tracks both. Tick an item
when it is merged to `main`, and note the PR.

## Where OpenMouse stood on 2026-11-04

| Area | OpenMouse | Reference bar |
| --- | --- | --- |
| Unit tests | 73 JUnit tests, 3 Jazzer fuzz targets | Dwell Click: 123 engine, toolbar, input and settings tests plus 25 UI tests; Handy: end-to-end regressions on virtual time with a control test |
| CI | Build debug, test, lint; nightly fuzz uploads artifacts only | Polaris: SHA-pinned actions, aggregate check, coverage floor, release build, emulator tests, CodeQL, OSV, Dependabot, nightly runs that file issues |
| Process | PR and issue templates | Polaris: pre-push hook, review-gate status, branch protection, gates table |
| Release | Manual, keystore gitignored, no tags yet | Polaris: signed tags, verify script, checklist; Handy: one-command publish, in-app updater |
| Docs | ARCHITECTURE, RELEASING, UPSTREAMING, CLAUDE.md | Handy: TROUBLESHOOTING, SIGNING; Dwell Click: defaults table, "Not yet implemented", recorded test runs; Polaris: "It's working if", gates table |

## Track A: engineering parity

Order: A1 to A3 first (cheap, and they protect everything after), then A7, A9
and A10, then the rest.

- [ ] **A1. Harden CI.** Pin every action by commit SHA with `persist-credentials: false`, add concurrency groups, build `assembleRelease` unsigned so a release-variant break fails in CI, lint the release variant too, and add an aggregate `Tests` job for branch protection to require.
- [ ] **A2. Pre-push hook.** `.githooks/pre-push` runs the unit tests and lint, the same as the CI fast job. Enabled by `./gradlew installGitHooks` (or `git config core.hooksPath .githooks`); never bypassed with `--no-verify`.
- [ ] **A3. Review gate.** `review-gate.yml` sets a `Reviewed` commit status from the reviews on the head commit. Branch protection on `main` requires `Tests`, `Reviewed`, and the gate's own `Set the Reviewed status` check, admins included. Turn protection on only after A1 and A3 have merged, or the first PR cannot merge:

  ```sh
  gh api -X PUT repos/owenpkent/openmouse/branches/main/protection --input protection.json
  ```

  with `protection.json`:

  ```json
  {
    "required_status_checks": {
      "strict": false,
      "contexts": ["Tests", "Reviewed", "Set the Reviewed status"]
    },
    "enforce_admins": true,
    "required_pull_request_reviews": null,
    "restrictions": null
  }
  ```

- [ ] **A4. Dependency and code scanning.** Dependabot for `gradle` and `github-actions`, weekly, minor and patch grouped. CodeQL for Kotlin with a Gradle build step. Gradle dependency locking (`gradle.lockfile`) so OSV-Scanner has something to read, then the Polaris OSV diff gate, weekly full scan, and self-test.
- [ ] **A5. Nightly fuzz that reports.** Cache the evolved corpus between nights, and add a report job (read-only fuzz jobs, `issues: write` on the reporter only) that opens or comments on `Fuzz crash: <target>` with the input in base64 and the replay command.
- [ ] **A6. Coverage floor.** Kover over the pure packages (`click`, `menu`, `gesture`) with line and branch floors a couple of points under measured. Raise them as coverage rises, never lower them to get green.
- [ ] **A7. Emulator tests in CI.** Automate the manual smoke test from CONTRIBUTING with `android-emulator-runner` and KVM: the service enables, the overlay is attached and `NOT_TOUCHABLE` on API 34+, a screenshot shows the cross-hair. Add a recorder Activity so instrumented tests assert what `dispatchGesture` delivered for every `GestureAction` (the Dwell Click recorder-window idea, which sidesteps the "no mouse on the emulator" limit).
- [ ] **A8. Invariants as tests.** One `InvariantsTest` stating the CLAUDE.md safety rules by name: paused never fires on the app, every injecting action goes through `runGesture`, the touchscreen is never left locked out, pure classes never throw. A change that breaks one fails by name.
- [ ] **A9. Release machinery.** Signed `vX.Y.Z` tags against a `release-signers` file with a verify script; a `publish-release.ps1` that builds, signs, and attaches the APK to a GitHub Release; a version-consistency test over `versionName`, `versionCode`, CHANGELOG and the fastlane changelog; a release checklist in RELEASING.md.
- [ ] **A10. In-app updater.** Sideload is the only live channel today. Poll GitHub Releases, download the APK, verify its signing certificate matches the installed one, hand it to the installer. Off by default in F-Droid builds (a build flavor or a `BuildConfig` flag).
- [ ] **A11. Docs.** TROUBLESHOOTING.md and SIGNING.md; a gates table in CONTRIBUTING; "It's working if" and "Who it is not for" in the README; SECURITY.md moved to GitHub private vulnerability reporting with a short security model; a defaults table with provenance in ARCHITECTURE; a dated record of each emulator suite run.

## Track B: Dwell Click behaviour parity

Each item is one small PR, like the `dwell-click/*` branches in the PowerToys
fork. Order: B1 to B4 first, then the settings surface, then the new actions.

- [ ] **B1. Wheel turn cancels a running countdown** and locks until the pointer moves: the content under a resting pointer changed, so the countdown is no longer aimed at what the user chose.
- [ ] **B2. Scroll repeat interval.** The first notch at a rest point waits the full dwell time (that one is the decision); later notches land every repeat interval (400 ms default, settings slider). Movement or any lock goes back to the full dwell.
- [ ] **B3. Held button suppresses dwelling** for the whole hold, not only at the press; release locks until the pointer moves.
- [ ] **B4. Idle polling.** Stop the 16 ms tick while the machine is locked or paused and poll only while a countdown runs. Battery matters more on Android than on Windows.
- [ ] **B5. Countdown ring show delay.** Hide the ring for the first fifth of the dwell, at most 250 ms, so it does not trail a moving pointer. Only the feedback waits; the click does not.
- [ ] **B6. Action badge** beside the cross-hair whenever the current mode is not a plain tap.
- [ ] **B7. Blocked-gesture feedback.** Use the `dispatchGesture` cancelled callback to flash a slashed circle and lock, instead of silently retrying.
- [ ] **B8. Settings surface.** Default action and a revert-to-default toggle; optional click sound; ring color; menu size (small, medium, large); per-entry visibility; docking to the top and bottom edges and a position along the edge; Ease Mouse's floating "big menu" style. Widen the dwell range toward 200 ms to 5 s.
- [ ] **B9. Actions.** Scroll left and right; pinch-zoom in and out.
- [ ] **B10. Mouse side buttons** as pause and cycle-action shortcuts, the Android analogue of Win+Shift+W and Win+Shift+A, with on-screen feedback at the pointer.
- [ ] **B11. Test depth.** An input-table test pinning the strokes each action dispatches, with a last test that fails when an action is added without one; settings-change ordering tests at the `DwellClicker` level; the engine suite grouped rule for rule like the thirteen Dwell Click classes.

## Not planned

- Right and middle click: Android has no pointer context click; long-press is the context action and is already a mode.
- Elevated-window refusal and the secure desktop: Windows-only.
- DSC and GPO: Windows configuration surfaces with no Android analogue.
- Tremor-filtered physical clicks ("Ease click"): on the README roadmap, but set aside in the Dwell Click survey too, so not a parity item.
