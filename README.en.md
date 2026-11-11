# Root for Android TV on MediaTek MT5889 (MStar MBOOT)

🇷🇺 [Русская версия](README.md)

Rooting TVs with the **MT5889** SoC and the **MStar MBOOT (U-Boot 2011.06)** bootloader using Magisk. The **RootMate** app on the TV itself backs up the boot partitions, patches the image and prepares the flash scripts, and you run them in the bootloader console over UART (the adapter connects to HDMI). Everything that depends on the model (partitions, RAM addresses, the active slot) is taken from the TV itself.

**Tested on:** Tuvio TO55UFGCV5, DEXP 55OCG. It may work on other TVs with MT5889 and MBOOT, but that is not tested: start with the backup and the dry-run `check.txt`.

<img src="docs/img/01-main.png" width="720" alt="RootMate: main screen">

## Start here

- 📖 [Guide: rooting and rollback](docs/guide.en.md)
- ⬇️ [Download RootMate](https://github.com/Tobirg/changhong_m9617_root/releases/latest) (the APK is built by [GitHub Actions](https://github.com/Tobirg/changhong_m9617_root/actions))
- 🛟 [Recovery and troubleshooting](docs/recovery.en.md)

## How it works

1. **UART.** A home-made USB–UART adapter connects to an HDMI port of the TV; UART is turned on in the engineering menu (a button in RootMate).
2. **RootMate on the TV.** The app asks for its permissions (files, installing Magisk, developer options and USB debugging) and installs the latest Magisk by itself.
3. **Semi-auto, four steps.** Probe → backup → patch → check and flash. At every step the app writes a script to the USB drive and reboots the TV by itself, and you type two commands in the MBOOT console: `usb start 1` and `udstar <script>`.
4. **Data wipe and Magisk.** If the TV was not unlocked before, the flash ends with a data wipe; after it you install Magisk again and have root.

<img src="docs/img/07-wizard-continue.png" width="720" alt="RootMate recognised the step and offers to continue">

## How the scripts guard against mistakes

- No built-in values: the partition table, sizes, RAM addresses and the active slot are read from your TV in the probe step.
- The backup is read back from the drive and compared with the eMMC; the app checks the CRC32 of every image once more.
- `check.txt` runs all the checks of the flash without writing anything. `flash.txt` repeats them (slot, CRC32 of the original and of the patched image), writes, reads back and compares; any error stops it before the write.
- `rollback.txt` brings back the original image with the same checks.

## What you need

- A **3.3 V TTL** USB–UART adapter and an HDMI connector or cable to solder.
- A PC with a terminal (MobaXterm, PuTTY): only for the MBOOT console.
- A FAT32 USB drive, a single partition.

## What RootMate does

| | |
| --- | --- |
| **Semi-auto** | walks you through the steps, recognises the stage from the files on the drive, picks the drive once and rejects unsuitable ones (exFAT/NTFS, FAT not on the first partition) |
| **Manual** | full patch of a picked `BOOT_*.IMG` with the `skip_initramfs` fix, or only the fix for an image Magisk already patched |
| **Magisk** | downloads the latest release from GitHub and installs it |
| **Reboot into MBOOT** | through the TV's own adb, no root needed |
| **Power LED** | after root: off / on / blink in standby and while on, brightness, no boot changes: [docs/led.md](docs/led.md) (in Russian) |
| **Agents** | the same process over adb with JSON answers, plus scripts for the MBOOT console: [docs/agent.md](docs/agent.md) |

The `skip_initramfs` fix: this MBOOT version puts `skip_initramfs` on the kernel command line, which makes Magisk pick the wrong boot mode. RootMate changes it to `skip_initramfz` inside magiskinit.

## Extras

- [Guide for agents (AI, scripts): RootMate over adb, MBOOT over UART](docs/agent.md)
- [skip_initramfs fix via adb shell](scripts/skipfix.sh) (fallback)
- [App development notes](docs/app.md) (in Russian)
- [Power LED: the ledctl kernel module](docs/led.md) (in Russian)
