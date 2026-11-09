/*
 * uimouse: a virtual USB-style mouse for a rooted Android emulator, built on
 * the kernel's uinput interface. Android's InputReader classifies the device
 * as a real mouse (SOURCE_MOUSE), so its events reach an AccessibilityService
 * through onMotionEvent exactly like a physical mouse would. Events injected
 * with `adb shell input` never do (the accessibility input filter drops them),
 * which is what makes this the only way to drive OpenMouse's cursor pipeline
 * on a headless emulator.
 *
 * Build (NDK, x86_64 emulator image):
 *   x86_64-linux-android24-clang -static -O2 -o uimouse uimouse.c
 * Run (google_apis image, `adb root` first):
 *   adb push uimouse /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/uimouse
 *   adb shell /data/local/tmp/uimouse /data/local/tmp/script.txt
 *
 * Script lines (blank lines and lines starting with # are ignored):
 *   move DX DY        relative motion, one event
 *   glide DX DY N MS  N steps of (DX, DY) each, MS ms apart (a smooth move)
 *   down left|right|middle
 *   up   left|right|middle
 *   click left|right|middle
 *   wheel N           N notches, positive = wheel up (towards the user is negative)
 *   sleep MS
 *   mark TEXT         print TEXT to stdout with a timestamp (for logs)
 * Commands may also be fed on stdin when no script path is given.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static int fd = -1;

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof ev) != (ssize_t) sizeof ev) {
        perror("write");
        exit(2);
    }
}

static void syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

static void msleep(long ms) {
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
    nanosleep(&ts, NULL);
}

static int button(const char *name) {
    if (!strcmp(name, "left")) return BTN_LEFT;
    if (!strcmp(name, "right")) return BTN_RIGHT;
    if (!strcmp(name, "middle")) return BTN_MIDDLE;
    fprintf(stderr, "unknown button: %s\n", name);
    exit(3);
}

static void setup(void) {
    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput (need root)");
        exit(1);
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof dev);
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "OpenMouse Test Mouse");
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x1234;
    dev.id.product = 0x5678;
    dev.id.version = 1;
    if (write(fd, &dev, sizeof dev) != (ssize_t) sizeof dev) {
        perror("write uinput_user_dev");
        exit(1);
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        exit(1);
    }
    // Give InputReader a moment to pick the new device up before the first event.
    msleep(1500);
}

static void run_line(char *line) {
    char cmd[32], arg[32];
    long a = 0, b = 0, n = 0, ms = 0;
    char *p = line + strspn(line, " \t");
    if (*p == '\0' || *p == '#' || *p == '\n') return;
    if (sscanf(p, "%31s", cmd) != 1) return;

    if (!strcmp(cmd, "move") && sscanf(p, "%*s %ld %ld", &a, &b) == 2) {
        emit(EV_REL, REL_X, (int) a);
        emit(EV_REL, REL_Y, (int) b);
        syn();
    } else if (!strcmp(cmd, "glide") && sscanf(p, "%*s %ld %ld %ld %ld", &a, &b, &n, &ms) == 4) {
        for (long i = 0; i < n; i++) {
            emit(EV_REL, REL_X, (int) a);
            emit(EV_REL, REL_Y, (int) b);
            syn();
            msleep(ms);
        }
    } else if (!strcmp(cmd, "down") && sscanf(p, "%*s %31s", arg) == 1) {
        emit(EV_KEY, button(arg), 1);
        syn();
    } else if (!strcmp(cmd, "up") && sscanf(p, "%*s %31s", arg) == 1) {
        emit(EV_KEY, button(arg), 0);
        syn();
    } else if (!strcmp(cmd, "click") && sscanf(p, "%*s %31s", arg) == 1) {
        emit(EV_KEY, button(arg), 1);
        syn();
        msleep(60);
        emit(EV_KEY, button(arg), 0);
        syn();
    } else if (!strcmp(cmd, "wheel") && sscanf(p, "%*s %ld", &a) == 1) {
        emit(EV_REL, REL_WHEEL, (int) a);
        syn();
    } else if (!strcmp(cmd, "sleep") && sscanf(p, "%*s %ld", &ms) == 1) {
        msleep(ms);
    } else if (!strcmp(cmd, "mark")) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        printf("[%ld.%03ld] %s", (long) ts.tv_sec, ts.tv_nsec / 1000000L, p + 4);
        if (p[strlen(p) - 1] != '\n') printf("\n");
        fflush(stdout);
    } else {
        fprintf(stderr, "bad line: %s", p);
        exit(3);
    }
}

int main(int argc, char **argv) {
    FILE *in = stdin;
    if (argc > 1) {
        in = fopen(argv[1], "r");
        if (!in) {
            perror(argv[1]);
            return 1;
        }
    }
    setup();
    char line[256];
    while (fgets(line, sizeof line, in)) run_line(line);
    msleep(300); // let the last event drain before the device disappears
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
