# uimouse: driving OpenMouse on a headless emulator

Everything injected with `adb shell input` is dropped by the accessibility
input filter before it reaches `onMotionEvent`, so the cursor pipeline could
not be exercised on an emulator at all. This folder gives the emulator a real
mouse instead: `uimouse.c` creates a virtual USB mouse through the kernel's
uinput interface, which Android's InputReader classifies as `SOURCE_MOUSE`
exactly like physical hardware, and `drive.py` steers it from the host using
screenshots to find the cross-hair.

Nothing here touches the host pointer. The emulator runs with `-no-window`
and every event is a fake kernel event inside the guest.

## One-time setup

Needs a **Google APIs** system image (not the Play Store one), because
`/dev/uinput` is root-only and only the Google APIs images allow `adb root`.

```bash
SDK=~/AppData/Local/Android/Sdk
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n medium_phone_api \
    -k "system-images;android-36;google_apis;x86_64" -d medium_phone
$SDK/emulator/emulator @medium_phone_api -no-window -no-snapshot-load -no-audio -port 5556 &

# Build the helper with the NDK (x86_64 image).
$SDK/ndk/<version>/toolchains/llvm/prebuilt/<host>/bin/x86_64-linux-android24-clang \
    -static -O2 -o build/uimouse/uimouse tools/uimouse/uimouse.c

adb -s emulator-5556 root
adb -s emulator-5556 push build/uimouse/uimouse /data/local/tmp/uimouse
adb -s emulator-5556 shell chmod 755 /data/local/tmp/uimouse
```

Then install the APK and enable the service as in CONTRIBUTING.md (grant the
`ACCESS_RESTRICTED_SETTINGS` appop first). Run the `settings put` lines
*after* `adb root` has finished restarting adbd, or they are lost.

On Git Bash, set `MSYS_NO_PATHCONV=1` or the `/data/local/tmp` path gets
rewritten to a Windows path.

## Using it

```bash
python -I tools/uimouse/drive.py --serial emulator-5556 locate       # where is the cross-hair?
python -I tools/uimouse/drive.py --serial emulator-5556 goto 540 1200
python -I tools/uimouse/drive.py --serial emulator-5556 demo         # records docs/media clips
```

`drive.py` needs Pillow and numpy. The helper can also be fed a script by
hand (`adb shell /data/local/tmp/uimouse script.txt`); the commands are
documented at the top of `uimouse.c`.

## Things learned the hard way

- **Pointer acceleration is on** and roughly doubles a relative move. The
  driver measures the gain from each move and corrects, so steer with
  `goto`, never with a fixed delta.
- **A screenshot takes most of a second**, which is a whole dwell. The driver
  wiggles the pointer just past the movement tolerance before each look so a
  dwell cannot fire mid-route. The wiggle stays inside a menu cell on
  purpose: leaving and re-entering a cell re-arms it.
- **The Pixel launcher never rotates.** Landscape scenarios run over the
  Settings app.
- **The menu toggle moves when the grid opens** on a short screen (the strip
  shifts up). `menu_cell_center` mirrors `MenuGeometry` and takes `expanded`.
- **screenrecord only emits frames on screen change**, so the driver nudges
  the pointer right after starting a recording or the opening state is cut.
- **Opening the dock can move it under the pointer.** On a short screen the
  strip shifts up to fit, so after the expand dwell fires the still pointer is
  over whatever landed there. A cell is safe (the service re-anchors and locks
  on it); the gap between rows is not (a jitter into the cell above arms it).
  `menu_cell_center` picks a handle entry height that ends up inside row 0,
  and `leave_dock` glides clear right after the expand.
