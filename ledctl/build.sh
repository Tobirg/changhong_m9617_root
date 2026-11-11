#!/bin/bash
# Build ledctl.ko without a kernel tree: headers from the TV's /sys/kernel/kheaders.tar.xz,
# clang/lld from Ubuntu debs unpacked into ~/ledko/tc (no root needed). Run inside WSL.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
TC=${TC:-$HOME/ledko/tc/usr/lib/llvm-21}
KH=${KH:-$HOME/ledko/kh}
KHEADERS=${KHEADERS:-$HERE/kheaders.tar.xz}   # adb pull of /sys/kernel/kheaders.tar.xz (root: cp it to /data/local/tmp first)
if [ ! -d "$KH/include" ]; then mkdir -p "$KH" && tar -xJf "$KHEADERS" -C "$KH"; fi
CC=$TC/bin/clang; LD=$TC/bin/ld.lld
OUT=${OUT:-$HOME/ledko/out}; mkdir -p "$OUT"
FLAGS="--target=aarch64-linux-gnu -nostdinc -isystem $($CC -print-file-name=include) -I$HERE/stub
 -I$KH/arch/arm64/include -I$KH/arch/arm64/include/generated -I$KH/include
 -I$KH/arch/arm64/include/uapi -I$KH/arch/arm64/include/generated/uapi -I$KH/include/uapi -I$KH/include/generated/uapi
 -include $KH/include/linux/kconfig.h -D__KERNEL__ -DMODULE -DKBUILD_MODNAME=\"ledctl\" -DKBUILD_BASENAME=\"ledctl\"
 -mlittle-endian -mgeneral-regs-only -fno-pic -fno-common -fno-strict-aliasing -fno-delete-null-pointer-checks
 -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables -fno-jump-tables -O2 -std=gnu89
 -Wno-unknown-warning-option -Wno-gnu-variable-sized-type-not-at-end -Wno-address-of-packed-member
 -Wno-pointer-sign -Wno-unused-value -Wno-format-invalid-specifier -Wno-default-const-init-var-unsafe
 -Wno-default-const-init-field-unsafe -Wno-int-conversion -Wno-incompatible-pointer-types-discards-qualifiers"
$CC $FLAGS -c "$HERE/ledctl.c" -o "$OUT/ledctl.o"
$LD -r -m aarch64linux -T "$HERE/module.lds" --build-id=none -o "$OUT/ledctl.ko" "$OUT/ledctl.o"
cp "$OUT/ledctl.ko" "$HERE/ledctl.ko"; cp "$OUT/ledctl.ko" "$HERE/../app/src/main/assets/ledctl/ledctl.ko"
ls -la "$HERE/ledctl.ko"
