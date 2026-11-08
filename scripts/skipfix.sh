#!/system/bin/sh
# Replaces the old magisk_skipfix.py: patches skip_initramfs -> skip_initramfz
# inside magiskinit (the "init" entry of the ramdisk cpio) using the
# magiskboot binary already bundled inside the installed Magisk app.
# Runs entirely on the device via "adb shell sh skipfix.sh" - no boot image
# ever leaves the device.
#
# Usage (from an adb shell on the TV):
#   adb push scripts/skipfix.sh /data/local/tmp/skipfix.sh
#   adb shell sh /data/local/tmp/skipfix.sh magisk_patched-....img [output.img]
#
# magisk_patched-....img is expected in /sdcard/Download (where Magisk saves
# it after "Install -> Patch boot image"). The patched result is written
# back to /sdcard/Download and auto-copied to any USB flash drive mounted
# under /storage.

set -e

IMG="$1"
OUT="${2:-magisk_skipfix.img}"

cd /sdcard/Download || { echo "No /sdcard/Download" >&2; exit 1; }

if [ -z "$IMG" ]; then
    IMG=$(ls magisk_patched-*.img 2>/dev/null | head -n1)
fi
if [ -z "$IMG" ] || [ ! -f "$IMG" ]; then
    echo "Patched Magisk image not found in /sdcard/Download" >&2
    echo "Usage: sh skipfix.sh <magisk_patched-....img> [output.img]" >&2
    exit 1
fi

PKG_PATH=$(pm path com.topjohnwu.magisk 2>/dev/null | sed 's/^package://')
if [ -z "$PKG_PATH" ]; then
    echo "Magisk app is not installed" >&2
    exit 1
fi
APP_DIR=$(dirname "$PKG_PATH")

MB=""
for ABI in arm64 arm x86_64 x86; do
    CANDIDATE="$APP_DIR/lib/$ABI/libmagiskboot.so"
    if [ -f "$CANDIDATE" ]; then
        MB="$CANDIDATE"
        break
    fi
done
if [ -z "$MB" ]; then
    echo "magiskboot not found under $APP_DIR/lib/*" >&2
    exit 1
fi

echo "magiskboot: $MB"
echo "patching:   $IMG -> $OUT"

"$MB" unpack "$IMG"
"$MB" cpio ramdisk.cpio "extract init init_bin"

if ! grep -q skip_initramfs init_bin; then
    echo "WARNING: skip_initramfs string not found in init, nothing to patch" >&2
else
    sed -i 's/skip_initramfs/skip_initramfz/' init_bin
fi

"$MB" cpio ramdisk.cpio "add 0750 init init_bin"
"$MB" repack "$IMG" "$OUT"
"$MB" cleanup
rm -f init_bin

echo "done: /sdcard/Download/$OUT"

for D in /storage/*; do
    NAME=$(basename "$D")
    case "$NAME" in
        emulated|self) continue ;;
    esac
    if cp "$OUT" "$D/" 2>/dev/null; then
        echo "copied to $D/$OUT"
    fi
done
