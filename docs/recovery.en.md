# Recovery and diagnostics

🇷🇺 [Русская версия](recovery.md)

This page applies to the studied CHiQ/MT5889 with MStar MBOOT. The main rule: **before any write, dump BOOT_A.IMG/BOOT_B.IMG, check their size and keep them on a PC**. A wrong restore of a partition can make things worse.

## 1. Android does not boot after writing the patched image

If the MBOOT interactive console is available, restore the **original boot of the active slot**. The example below is **_a**, USB **0:1**, partition size **0x1F00000**. Double-check all three.

Copy the original **BOOT_A.IMG** back to the FAT32 drive.

~~~text
usb start 1
usb storage
fatls usb 0:1
mmc part
mmc look boot_a
fatload usb 0:1 0x25000000 BOOT_A.IMG
crc32 0x25000000 0x1F00000
mmc write.p 0x25000000 boot_a 0x1F00000 0
mmc read.p 0x25000000 boot_a 0x1F00000
crc32 0x25000000 0x1F00000
reset
~~~

**Before writing**, check that the loaded file size matches the partition size exactly, the initial backup is readable, and the CRC before/after the write is identical. If the active slot is **_b**, restore **BOOT_B.IMG to boot_b**. Do not restore another firmware or another slot's image by default.

Restoring boot **does not bring back user data** deleted by the factory reset.

## 2. Red state / encryption error / boot loop

In the experiment, after changing the Verified Boot state this error appeared:

~~~text
vold ... begin failed, code -33 INVALID_KEY_BLOB
~~~

It meant the old **/metadata** key was incompatible with the new boot state. Fix: start the stock Android Recovery and **Wipe data/factory reset**, after backing up all available user data.

In MBOOT:

~~~text
panel_pre_init
readKL recovery
bootKL
~~~

On this TV **Recovery starts but its menu cannot be controlled with the remote**. However, Android Recovery can process a `--wipe_data` request from the BCB boot message in the `misc` partition automatically.

In related MStar MBOOT sources the request is created by `recovery_wipe_partition data`. It exists on the studied MT5889; its `help` shows only a description without arguments, but the related MStar code reads `argv[1]` and requires `data` or `cache`. Do not run it without an argument. Help:

~~~text
help recovery_wipe_partition
~~~

**After backing up** you can try queuing a full reset, then reboot (execution on this build is not yet verified):

~~~text
recovery_wipe_partition data
reset
~~~

Then boot Recovery (a normal restart if it picks up the BCB, or manually via `panel_pre_init`, `readKL recovery`, `bootKL`). **There is no confirmation yet that this command works on the current build.** An alternative with working Android and root: create `/cache/recovery/command` containing the single line `--wipe_data`, then boot Recovery. This worked earlier.

After the reset on the studied system **/data, /cache and /metadata** were formatted and Android started in the **orange** state. Flashing Magisk again does **not** replace the reset. Do not erase these partitions by hand until automatic filesystem formatting is confirmed.

If Recovery does not start, first check the **original boot**, the active slot, the UART log and the MBOOT state. Do not blindly write to **misc**, **vbmeta**, **MBOOT**, **dtbo**.

## 3. Magisk gives no root but Android boots

Check:

~~~powershell
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.boot.verifiedbootstate
adb shell getprop sys.boot_completed
adb shell "/debug_ramdisk/su -c id"
~~~

Possible causes: **the wrong slot was flashed** (compare `ro.boot.slot_suffix` with the partition you wrote), another boot slot is running, Magisk did not finish additional setup, **magiskinit** mishandled **skip_initramfs**, the image comes from another firmware version. Do not use **Direct Install** to "fix" things blindly. Procedure: [full guide](guide.en.md).

## 4. RootMate reports "no skip_initramfs string" or another error

- **"/init of the image has no skip_initramfs string: not a Magisk-patched image"**: you picked an original `BOOT_*.IMG` with the "fix only" button. For an original image use the left button **Select BOOT_\*.IMG** (auto patch); for an image already patched by Magisk use the right one. If the image was patched by a Magisk version without that string, the fix is not needed or not supported. No file is created either way.
- **"Magisk is not installed (or magiskboot not found)"**: press **Download and install Magisk**.
- **"Folder is not writable" / "low space"**: the drive is read-only, full or not FAT32. Free about 100 MB on the drive and in internal storage.
- **Buttons are dimmed and open permissions when pressed**: grant "All files access" and "Install unknown apps".
- The image is not created for no visible reason: read the `boot_patch.sh` output in the log on the right. Never flash a missing or incomplete result.

## 5. The drive is not visible or the file cannot be read

~~~text
usb start 1
usb storage
usb tree
usb dev
usb part
fatls usb 0:1
~~~

**0:1** is only an example. Take the drive number from **usb storage**. Use FAT32 + MBR, check the port, power and USB integrity. **usb start 1** does not mean the disk number is 1.

## 6. Size, CRC and partition errors

- Do not run **mmc write.p** until the partition name and size are confirmed.
- **0x1F00000** is the boot size for the studied device, not a mandatory size for every MT5889.
- The image patched by RootMate keeps the original length (32505856 bytes). If **fatload** loaded a different byte count, abort.
- On a CRC mismatch after read-back, immediately restore the **original** image from backup without rebooting.
- **0x25000000** is the RAM address in the confirmed configuration; verify with **printenv bootcmd**, **bdinfo**, **get_mmap**. Do not copy arbitrary addresses from other guides.

## 7. MBOOT does not start or no UART

Restoring boot with **mmc write.p** is possible **only if the MBOOT console still works**. If MBOOT itself, the partition table, eMMC memory or power is damaged, this guide is not enough: you need separate board diagnostics and sometimes a programmer. **Do not try to compensate for a missing MBOOT with random write commands.**

UART on the studied CHiQ/MT5889: HDMI **2 = TV TX**, **14 = TV RX**, **8 = GND**, a **3.3 V TTL** adapter, **115200 8N1**, **no VCC**. Do not connect an HDMI cable and UART wires to the same port at the same time.
