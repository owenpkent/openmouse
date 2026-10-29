"""
Drive OpenMouse on a headless, rooted emulator with a virtual mouse.

Pairs with uimouse.c (a uinput mouse running on the device). This script runs
on the host: it streams mouse commands to the helper over `adb shell`, takes
screenshots to find the yellow cross-hair, and steers it closed-loop to screen
targets, so pointer acceleration never matters. It never touches the host
pointer: every event is a fake kernel event inside the emulator.

Usage:
  python -I tools/uimouse/drive.py --serial emulator-5556 demo            # record both demo clips
  python -I tools/uimouse/drive.py --serial emulator-5556 --clips 2 demo  # landscape clip only
  python -I tools/uimouse/drive.py --serial emulator-5556 goto 540 1200
  python -I tools/uimouse/drive.py --serial emulator-5556 locate

Requires: the uimouse helper pushed to /data/local/tmp/uimouse (see uimouse.c),
`adb root` done, the OpenMouse service enabled, Pillow + numpy on the host.
"""
import argparse
import io
import os
import subprocess
import threading
import time

import numpy as np
from PIL import Image

ADB = os.environ.get("ADB", os.path.expanduser("~/AppData/Local/Android/Sdk/platform-tools/adb.exe"))
HELPER = "/data/local/tmp/uimouse"

# The cross-hair fill (res/values/colors.xml cursor_fill, #FFEB3B).
CROSS_RGB = (255, 235, 59)


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.mouse = None
        self.recorder = None
        self.screen_size = (1080, 2400)
        self.log = None
        # While "in transit" a background thread wiggles the pointer every few
        # hundred milliseconds so no dwell can fire between a glide and the next
        # screenshot; a deliberate rest turns it off.
        self.io_lock = threading.RLock()
        self._transit = threading.Event()
        self._wiggler = None
        # Screen pixels moved per relative unit sent. Android applies a pointer
        # acceleration curve, so this is measured from each move and reused.
        self.gain = 2.0

    def adb(self, *args, binary=False):
        out = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, check=True).stdout
        return out if binary else out.decode(errors="replace")

    def shell(self, cmd):
        return self.adb("shell", cmd)

    # ---- mouse -------------------------------------------------------------

    def open_mouse(self):
        self.mouse = subprocess.Popen(
            [ADB, "-s", self.serial, "shell", HELPER],
            stdin=subprocess.PIPE, stdout=self.log or subprocess.DEVNULL, stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        time.sleep(2.0)  # the helper sleeps 1.5 s after creating the device
        if self._wiggler is None:
            self._wiggler = threading.Thread(target=self._wiggle_loop, daemon=True)
            self._wiggler.start()

    def close_mouse(self):
        self._transit.clear()
        if self.mouse and self.mouse.stdin:
            self.mouse.stdin.close()
            self.mouse.wait(timeout=10)
            self.mouse = None

    def send(self, line):
        assert self.mouse and self.mouse.stdin
        with self.io_lock:
            self.mouse.stdin.write(line + "\n")
            self.mouse.stdin.flush()

    def _wiggle_loop(self):
        while True:
            if self._transit.is_set() and self.mouse:
                try:
                    self.wiggle()
                except (ValueError, OSError):
                    pass  # the mouse is being reopened
            time.sleep(0.35)

    def transit(self, on):
        if on:
            self._transit.set()
        else:
            self._transit.clear()
            time.sleep(0.15)  # let a wiggle in progress finish before resting

    def move(self, dx, dy):
        self.send(f"move {int(dx)} {int(dy)}")

    def click(self, button="left"):
        self.send(f"click {button}")

    def wheel(self, notches):
        self.send(f"wheel {int(notches)}")

    # ---- screen ------------------------------------------------------------

    def screenshot(self):
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        assert isinstance(png, bytes)
        img = Image.open(io.BytesIO(png)).convert("RGB")
        self.screen_size = img.size
        return img

    def locate_cross(self, img=None):
        """
        Centre of the cross-hair, or None. The arms are the longest yellow runs
        on screen by far, so the row and column with the most yellow pixels are
        the horizontal and vertical arm; a centroid would be pulled off by any
        yellow in an app icon (Photos, Gmail).
        """
        img = img or self.screenshot()
        a = np.asarray(img).astype(int)
        m = (abs(a[:, :, 0] - CROSS_RGB[0]) < 40) & (abs(a[:, :, 1] - CROSS_RGB[1]) < 40) & (abs(a[:, :, 2] - CROSS_RGB[2]) < 60)
        rows = m.sum(axis=1)
        cols = m.sum(axis=0)
        if rows.max() < 60 or cols.max() < 60:
            return None
        # Average the few rows/columns that tie at the arm's thickness.
        ry = np.nonzero(rows >= rows.max() * 0.8)[0]
        cx = np.nonzero(cols >= cols.max() * 0.8)[0]
        return int(cx.mean()), int(ry.mean())

    def near_edge(self, pos, margin=120):
        w, h = self.screen_size
        return pos[0] < margin or pos[1] < margin or pos[0] > w - margin or pos[1] > h - margin

    # ---- steering ----------------------------------------------------------

    def glide(self, dx, dy, step=4, pause=0.01):
        """Move by (dx, dy) relative units in small, slow steps so it reads as a hand movement."""
        n = max(1, int(max(abs(dx), abs(dy)) / step))
        ax = ay = 0.0
        sx = dx / n
        sy = dy / n
        with self.io_lock:  # a glide is continuous motion; no wiggle needed inside it
            for _ in range(n):
                ax += sx
                ay += sy
                mx, my = int(round(ax)), int(round(ay))
                if mx or my:
                    self.move(mx, my)
                    ax -= mx
                    ay -= my
                time.sleep(pause)

    def wiggle(self, px=40):
        """
        Out and back just past the movement tolerance (14 dp = 37 px here) so a
        countdown over the app restarts: a screenshot round trip takes most of
        a second, and without this the dwell would fire wherever the pointer
        happened to be while we looked. Small enough to stay inside a menu
        cell, since leaving and re-entering a cell re-arms it on purpose.
        """
        units = max(1, int(round(px / self.gain)))
        with self.io_lock:
            self.move(units, 0)
            time.sleep(0.03)
            self.move(-units, 0)
        time.sleep(0.05)

    def goto(self, x, y, tolerance=8, tries=6):
        """
        Steer the cross-hair to (x, y) in screen pixels, correcting from
        screenshots. The pointer is in transit (wiggled) until the final
        position is confirmed, so the only rest that can complete a dwell is
        the deliberate one the caller does afterwards.
        """
        self.transit(True)
        cur = None
        for i in range(tries):
            cur = self.locate_cross()
            if cur is None:
                # Usually pinned in a corner, off screen: nudge toward the centre and look again.
                self.glide(200, 200)
                time.sleep(0.2)
                continue
            dx, dy = x - cur[0], y - cur[1]
            self.note(f"goto ({x},{y}) try {i}: at {cur}, delta ({dx},{dy}), gain {self.gain:.2f}")
            if abs(dx) <= tolerance and abs(dy) <= tolerance:
                break
            self.glide(dx / self.gain, dy / self.gain)
            time.sleep(0.2)
            after = self.locate_cross()
            if after is not None:
                moved = ((after[0] - cur[0]) ** 2 + (after[1] - cur[1]) ** 2) ** 0.5
                asked = (dx ** 2 + dy ** 2) ** 0.5 / self.gain
                if moved < 5 and asked > 30:
                    # Nothing moved at all: the helper or the event stream is
                    # stuck, not the gain. Reopen the virtual mouse.
                    self.note("no movement after a glide; reopening the mouse")
                    self.close_mouse()
                    self.open_mouse()
                    self.transit(True)
                elif not self.near_edge(after) and asked > 30 and moved > 10:
                    # A pointer pinned against a screen edge moves less than
                    # asked for reasons that have nothing to do with acceleration.
                    self.gain = min(4.0, max(0.5, self.gain * 0.5 + 0.5 * moved / asked))
        self.transit(False)
        return cur

    def note(self, text):
        line = f"[{time.strftime('%H:%M:%S')}] {text}"
        print(line, flush=True)
        if self.log:
            self.log.write(line + "\n")
            self.log.flush()

    def rest(self, seconds):
        time.sleep(seconds)

    SERVICE = "io.github.owenpkent.openmouse/io.github.owenpkent.openmouse.service.MouseAccessibilityService"

    def reset_service(self):
        """
        Bounce the accessibility service so the menu comes back collapsed, in
        Tap mode and not paused: its state survives between runs otherwise.
        """
        self.shell("settings put secure enabled_accessibility_services ''")
        time.sleep(1.5)
        self.shell(f"settings put secure enabled_accessibility_services {self.SERVICE}")
        self.shell("settings put secure accessibility_enabled 1")
        time.sleep(3.0)
        self.settle()

    def settle(self, timeout=15.0):
        """
        After a (re)start the service draws the cross at screen centre and only
        follows the pointer once events flow again, which takes a few seconds.
        Keep wiggling until the cross has moved (a wiggle restarts any
        countdown, so nothing fires while we wait), then park in open space.
        """
        self.transit(True)
        start = self.locate_cross()
        t0 = time.time()
        while time.time() - t0 < timeout:
            self.glide(30, 30)
            self.glide(-30, -30)
            now = self.locate_cross()
            if start is None or (now is not None and (abs(now[0] - start[0]) > 20 or abs(now[1] - start[1]) > 20)):
                break
        self.note(f"settled after {time.time() - t0:.1f} s")
        w, h = self.screen_size
        self.goto(int(w * 0.3), int(h * 0.4))

    def goto_cell(self, cx, cy, panel_left):
        """
        Enter a menu cell from open space beside the strip, so no correction
        pause lands on a neighbouring cell (a rest on any cell selects it).
        """
        self.goto(int(panel_left - 160), cy)
        return self.goto(cx, cy)

    def rotate(self, landscape):
        self.shell("settings put system accelerometer_rotation 0")
        self.shell(f"settings put system user_rotation {1 if landscape else 0}")
        time.sleep(2.0)

    # ---- recording ---------------------------------------------------------

    def start_recording(self, remote_path, time_limit=120):
        self.recorder = subprocess.Popen(
            [ADB, "-s", self.serial, "shell", f"screenrecord --bit-rate 6000000 --time-limit {time_limit} {remote_path}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        time.sleep(2.5)
        # The encoder only emits frames when the screen changes, so a still
        # start would be cut: nudge the pointer so the opening state is captured.
        self.wiggle()
        time.sleep(0.8)

    def stop_recording(self, remote_path, local_path):
        self.shell("pkill -INT screenrecord")
        if self.recorder:
            try:
                self.recorder.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.recorder.kill()
        time.sleep(1.0)
        self.adb("pull", remote_path, local_path)


# ---- the demo scenario -----------------------------------------------------

DENSITY = 420 / 160
PAUSE_INDEX = 12  # the last menu entry
DWELL = 1.0  # the default dwell time, seconds


def panel_left(width, columns, density=DENSITY):
    item_w, gap, margin = 84 * density, 6 * density, 8 * density
    return width - margin - (columns * item_w + (columns - 1) * gap)


def menu_cell_center(width, height, index, columns, expanded=True, density=DENSITY, expanded_items=13):
    """
    Mirror MenuGeometry for the production menu (right dock) to find a cell.
    The toggle sits at its collapsed centre until the grid opens; on a short
    screen the whole strip then shifts up, so ask for the collapsed position
    when steering to the toggle of a collapsed menu.
    """
    item_w, item_h, gap, margin = 84 * density, 52 * density, 6 * density, 8 * density
    panel = columns * item_w + (columns - 1) * gap
    left = width - margin - panel
    rows = -(-expanded_items // columns) if expanded else 0
    strip = item_h + gap + rows * item_h + (rows - 1) * gap if rows else item_h
    top = max(min((height - item_h) / 2, height - strip), 0)
    if index == "toggle":
        return int(left + panel / 2), int(top + item_h / 2)
    grid_top = top + item_h + gap
    r, c = divmod(index, columns)
    return int(left + c * (item_w + gap) + item_w / 2), int(grid_top + r * (item_h + gap) + item_h / 2)


def step(dev, name):
    dev.note(f"== {name}")


def clip_portrait(dev, out_dir, icon):
    """Expand the menu, pause, rest over the app, resume, collapse, dwell-click an icon."""
    W, H = 1080, 2400
    dev.shell("input keyevent HOME")
    dev.rotate(False)
    dev.reset_service()
    dev.start_recording("/sdcard/clip1.mp4")
    time.sleep(0.5)
    pl = panel_left(W, 2)
    step(dev, "toggle: expand")
    dev.goto_cell(*menu_cell_center(W, H, "toggle", 2, expanded=False), pl)
    dev.rest(DWELL + 0.6)
    step(dev, "pause")
    dev.goto_cell(*menu_cell_center(W, H, PAUSE_INDEX, 2), pl)
    dev.rest(DWELL + 0.6)  # the entry now reads Resume
    step(dev, "rest over the app while paused")
    dev.goto(300, 900)
    dev.rest(2.5)  # nothing fires
    step(dev, "resume")
    dev.goto_cell(*menu_cell_center(W, H, PAUSE_INDEX, 2), pl)
    dev.rest(DWELL + 0.6)
    step(dev, "toggle: collapse")
    dev.goto_cell(*menu_cell_center(W, H, "toggle", 2), pl)
    dev.rest(DWELL + 0.6)
    step(dev, "dwell-click the icon")
    dev.goto(*icon)
    dev.rest(DWELL + 1.2)  # launches the app under the cross-hair
    time.sleep(1.5)
    dev.stop_recording("/sdcard/clip1.mp4", os.path.join(out_dir, "clip1.mp4"))
    dev.shell("am force-stop com.google.android.apps.photos")
    dev.shell("input keyevent HOME")


def clip_landscape(dev, out_dir):
    """Over Settings (the launcher never rotates): the menu widens to three columns and Pause stays on screen."""
    W, H = 2400, 1080
    dev.shell("input keyevent HOME")
    time.sleep(0.5)
    dev.reset_service()
    dev.shell("am start -a android.settings.SETTINGS --activity-clear-task")  # a fresh top page, not a restored search
    time.sleep(2.5)
    dev.rotate(True)  # only takes effect over an app that rotates, so after Settings is up
    dev.start_recording("/sdcard/clip2.mp4")
    time.sleep(0.5)
    pl = panel_left(W, 3)
    step(dev, "landscape: expand")
    dev.goto_cell(*menu_cell_center(W, H, "toggle", 3, expanded=False), pl)
    dev.rest(DWELL + 0.6)
    step(dev, "landscape: pause")
    dev.goto_cell(*menu_cell_center(W, H, PAUSE_INDEX, 3), pl)
    dev.rest(DWELL + 0.6)  # Pause is reachable in landscape
    dev.rest(1.5)
    dev.stop_recording("/sdcard/clip2.mp4", os.path.join(out_dir, "clip2.mp4"))
    dev.rotate(False)
    dev.shell("am force-stop com.android.settings")
    dev.shell("input keyevent HOME")


def demo(dev, out_dir, icon, clips):
    os.makedirs(out_dir, exist_ok=True)
    dev.log = open(os.path.join(out_dir, "drive.log"), "a", encoding="utf-8")
    dev.open_mouse()
    try:
        if "1" in clips:
            clip_portrait(dev, out_dir, icon)
        if "2" in clips:
            clip_landscape(dev, out_dir)
    finally:
        # pkill exits 1 when nothing was recording, which is the normal case here.
        subprocess.run([ADB, "-s", dev.serial, "shell", "pkill -INT screenrecord"], capture_output=True)
        dev.close_mouse()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--out", default="build/uimouse")
    ap.add_argument("--clips", default="12", help="which demo clips to record: 1, 2 or 12")
    ap.add_argument("--icon", nargs=2, type=int, default=(660, 1500), metavar=("X", "Y"),
                    help="home-screen icon the portrait clip dwell-clicks at the end")
    ap.add_argument("command", choices=["demo", "goto", "locate"])
    ap.add_argument("args", nargs="*")
    a = ap.parse_args()
    dev = Device(a.serial)
    if a.command == "locate":
        print(dev.locate_cross())
    elif a.command == "goto":
        dev.open_mouse()
        try:
            print(dev.goto(int(a.args[0]), int(a.args[1])))
        finally:
            dev.close_mouse()
    else:
        demo(dev, a.out, tuple(a.icon), a.clips)


if __name__ == "__main__":
    main()
