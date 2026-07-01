# Building OpenMouse into a ROM (AOSP / LineageOS)

This is the platform build path: preinstalling OpenMouse as a **system app** in an
Android source tree. Being preinstalled is what makes it "native" and, usefully,
**exempts the accessibility service from the Android 13+ restricted-settings
block** (the user can just enable it, with no adb `appop` workaround).

Day-to-day development and the F-Droid build still use Gradle. The platform build
reuses the same sources via the root [`Android.bp`](../Android.bp) and this
folder's manifest.

> Status: this kit is a starting point and has **not** been built in a real
> AOSP/LineageOS checkout yet. Expect to adjust the prebuilt module names and,
> in some trees, add license metadata.

## Prerequisites

- A synced AOSP or LineageOS source tree and a working build environment
  (`source build/envsetup.sh`, `lunch <target>`). This is a large commitment
  (hundreds of GB, long builds).

## 1. Place the app in the tree

Put this repository at `packages/apps/OpenMouse/` in the tree (clone, submodule,
or symlink), so the module file lands at
`packages/apps/OpenMouse/Android.bp`.

## 2. Build just the app (optional sanity check)

```bash
m OpenMouse
```

The Kotlin sources build directly; there is no viewBinding (the activities use
`findViewById`) precisely so Soong can build them.

## 3. Include it in the ROM

Add the module to a product's package list, e.g. in the device or a LineageOS
common makefile:

```makefile
PRODUCT_PACKAGES += OpenMouse
```

Rebuild the ROM; OpenMouse ships preinstalled under `/product`.

## 4. (Optional) make enabling easier

Preinstalled accessibility services still require the user to turn them on, which
is correct. If a specific product wants OpenMouse enabled out of the box (for an
assistive-tech device), set the default in a settings/config overlay rather than
here, so it stays a product decision.

## Notes

- **Licensing.** OpenMouse is MIT, which is Apache-2.0 compatible, so it is safe
  to include in AOSP/LineageOS. (The GPL EVA Facial Mouse is not.) Strict trees
  may want an explicit `license {}` module.
- **Prebuilt names.** `androidx.core_core-ktx`, `androidx.appcompat_appcompat`,
  and `com.google.android.material_material` exist in current trees but names
  drift; adjust if the build cannot resolve them.
- **Two manifests.** `platform/AndroidManifest.xml` mirrors
  `app/src/main/AndroidManifest.xml` plus an explicit `package`. Keep them in sync
  when components change.

See [`../docs/UPSTREAMING.md`](../docs/UPSTREAMING.md) for the wider strategy
(why LineageOS is the realistic route and mainline AOSP is not).
