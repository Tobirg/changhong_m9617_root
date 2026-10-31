#!/usr/bin/env python3
"""Replace skip_initramfs with skip_initramfz in a Magisk-patched boot image.

Supported format: Android boot image header v0, v1 or v2 with a gzip
compressed newc/CRC cpio ramdisk. No device access or flashing is performed.
"""

import gzip
import hashlib
import struct
import sys
from pathlib import Path

OLD = b"skip_initramfs"
NEW = b"skip_initramfz"


def align(n, size):
    return (n + size - 1) // size * size


def patch(src, dst):
    image = bytearray(src.read_bytes())
    if image[:8] != b"ANDROID!" or len(image) < 1660:
        raise ValueError("Not a supported Android boot image")

    kernel_size = struct.unpack_from("<I", image, 8)[0]
    ramdisk_size = struct.unpack_from("<I", image, 16)[0]
    second_size = struct.unpack_from("<I", image, 24)[0]
    page_size, version = struct.unpack_from("<II", image, 36)
    if version not in (0, 1, 2) or page_size < 512 or page_size & (page_size - 1):
        raise ValueError("Only Android boot header v0-v2 with power-of-two page size is supported")

    kernel_offset = page_size
    ramdisk_offset = kernel_offset + align(kernel_size, page_size)
    second_offset = ramdisk_offset + align(ramdisk_size, page_size)
    if not (ramdisk_size > 0 and second_offset + second_size <= len(image)):
        raise ValueError("Invalid boot image layout")

    ramdisk_gz = bytes(image[ramdisk_offset:ramdisk_offset + ramdisk_size])
    try:
        archive = bytearray(gzip.decompress(ramdisk_gz))
    except (OSError, EOFError) as exc:
        raise ValueError("Ramdisk is not a valid gzip stream") from exc

    hits = []
    pos = 0
    while pos + 110 <= len(archive):
        hdr = archive[pos:pos + 110]
        if hdr[:6] not in (b"070701", b"070702"):
            raise ValueError("Ramdisk is not a valid newc cpio archive")
        try:
            data_size = int(hdr[54:62], 16)
            name_size = int(hdr[94:102], 16)
        except ValueError as exc:
            raise ValueError("Invalid cpio header") from exc
        if name_size < 1:
            raise ValueError("Invalid cpio entry name length")
        name_end = pos + 110 + name_size
        if name_end > len(archive):
            raise ValueError("Truncated cpio filename")
        name = bytes(archive[pos + 110:name_end]).rstrip(b"\0")
        data_start = align(name_end, 4)
        data_end = data_start + data_size
        if data_end > len(archive):
            raise ValueError("Truncated cpio entry")
        if name == b"TRAILER!!!":
            break
        data = archive[data_start:data_end]
        if data[:4] == b"\x7fELF":
            index = data.find(OLD)
            while index != -1:
                hits.append((pos, data_start, data_end, index, bytes(hdr[:6])))
                index = data.find(OLD, index + len(OLD))
        pos = align(data_end, 4)

    if len(hits) != 1:
        raise ValueError(
            f"Expected exactly one {OLD.decode()} in the Magisk ELF, found {len(hits)}. "
            "Provide an image already patched by Magisk."
        )
    header_pos, data_start, data_end, index, cpio_magic = hits[0]
    absolute_index = data_start + index
    archive[absolute_index:absolute_index + len(OLD)] = NEW
    if cpio_magic == b"070702":
        checksum = sum(archive[data_start:data_end]) & 0xffffffff
        archive[header_pos + 102:header_pos + 110] = f"{checksum:08x}".encode("ascii")
    compressed = gzip.compress(bytes(archive), compresslevel=9, mtime=0)
    reserved = align(ramdisk_size, page_size)
    if len(compressed) > reserved:
        raise ValueError("Recompressed ramdisk exceeds its reserved boot image space")

    image[ramdisk_offset:ramdisk_offset + reserved] = compressed.ljust(reserved, b"\0")
    struct.pack_into("<I", image, 16, len(compressed))

    # Refresh the legacy Android boot ID; keep the rest of the image in place.
    sha = hashlib.sha1()
    sha.update(image[kernel_offset:kernel_offset + kernel_size])
    sha.update(struct.pack("<I", kernel_size))
    sha.update(compressed)
    sha.update(struct.pack("<I", len(compressed)))
    sha.update(image[second_offset:second_offset + second_size])
    sha.update(struct.pack("<I", second_size))

    dtb_offset = second_offset + align(second_size, page_size)
    if version >= 1:
        recovery_size = struct.unpack_from("<I", image, 1632)[0]
        recovery_offset = struct.unpack_from("<Q", image, 1636)[0]
        if recovery_size:
            if not recovery_offset or recovery_offset + recovery_size > len(image):
                raise ValueError("Invalid recovery DTBO offset")
            sha.update(image[recovery_offset:recovery_offset + recovery_size])
            sha.update(struct.pack("<I", recovery_size))
            dtb_offset = recovery_offset + align(recovery_size, page_size)
    if version >= 2:
        dtb_size = struct.unpack_from("<I", image, 1648)[0]
        if dtb_offset + dtb_size > len(image):
            raise ValueError("Invalid DTB layout")
        sha.update(image[dtb_offset:dtb_offset + dtb_size])
        sha.update(struct.pack("<I", dtb_size))
    image[576:596] = sha.digest()
    dst.write_bytes(image)
    print(f"Patched: {dst} ({len(image)} bytes)")


def main():
    if len(sys.argv) not in (2, 3):
        print("Usage: python magisk_skipfix.py MAGISK_PATCHED.img [OUTPUT.img]", file=sys.stderr)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) == 3 else src.with_name(src.stem + "_skipfix.img")
    if src.resolve() == dst.resolve():
        print("Error: input and output must be different files", file=sys.stderr)
        return 2
    try:
        patch(src, dst)
        return 0
    except (ValueError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
