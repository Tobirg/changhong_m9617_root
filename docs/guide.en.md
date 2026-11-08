# Rooting MT5889 via UART / MBOOT / Magisk

🇷🇺 [Русская версия](guide.md)

**Important:** rooting risks irreversible data loss and a bricked TV. On the tested unit, changing the Verified Boot state required a full data reset because of incompatible encryption keys. This guide does not guarantee that it works on every firmware.

**Overview:** dump the boot images over UART → patch the image of the active slot on the TV itself with the **RootMate** app → unlock the bootloader and write the image over UART → wipe data → install Magisk. You no longer need a PC for patching or to install Magisk by hand.

## 1. What you need

- A USB–UART **TTL** adapter (not RS-232 ±12 V).
- An HDMI connector: solder/terminal type, or sacrifice an old cable. You can also open the TV and connect to the board UART, but buying a cable is cheaper than cracking the panel.
- A UART terminal: MobaXterm, PuTTY or similar (it must send keystrokes immediately, not via a separate Send button).
- A PC: only for the UART terminal and copying files to the USB drive. Patching is done on the TV.
- A FAT32 USB drive.
- Optional: `adb`, to read the active slot (step 6) and verify root at the end.


## 2. Build the UART adapter via HDMI

<img width="1702" height="1702" alt="photo_2026-10-31_20-34-52" src="https://github.com/user-attachments/assets/2ae974fb-9402-445b-80c8-4b52a521ba75" />

| TV HDMI pin | Function (TV side) | USB–UART |
| --- | --- | --- |
| **2** | **TX** of the TV | **RX** of the adapter |
| **8** | GND | GND of the adapter |
| **14** | **RX** of the TV | **TX** of the adapter |

Terminal settings: **115200, 8N1, no flow control**. Do not connect the adapter's VCC.

If the terminal shows nothing, UART may be switched off in the TV. In RootMate press **Enable UART** (Включить UART): it opens the engineering menu, then set **Design Mode → Uart → On** by hand. This works only while Android is running.


## 3. Prepare the USB drive

On a PC, format the drive as **FAT32**, a single partition. Download and copy to it:

- **RootMate-1.1.apk**: [latest release](https://github.com/Tobirg/changhong_m9617_root/releases/latest). The app downloads and installs Magisk by itself (the TV needs internet).
- Optionally the [Magisk APK](https://github.com/topjohnwu/Magisk/releases/latest): needed after the data wipe if the TV has no internet.

If the drive was used before, delete old `*_magisk_patched*.img` files so you do not mix them up.


## 4. Dump the original boot images from eMMC to USB

> **Quick way (the wizard in RootMate).** Install RootMate (step 5, Magisk is not needed for this) and put a FAT32 drive into the TV. Everything the app and the scripts make lives in the drive root; the top of the app shows "Stage: ..." recognised from the files and offers the next step. Every MBOOT script ends with a marker (`PROBE.OK`, `BACKUP.OK`, `FLASH.OK`), so an unfinished run is told apart from a finished one.
>
> 1. **Probe.** Press "Create the probe script": the app writes `probe.txt` and shows how to get into the MBOOT console. Hold Enter in the UART terminal, press "Reboot the TV" (the app reboots the TV without root through its own adb; the first time the TV asks "USB debugging": "Always allow") and type `usb start 1`, `udstar probe.txt`. The script saves the partition table `PTABLE.BIN` and the environment `ENV.TXT` (it contains the serial number, do not publish it) and reboots the TV by itself.
> 2. **Backup.** At the next start the app sees the result and offers to create `backup.txt` with the names, sizes and memory addresses of your very TV (nothing about the TV is built into the app). The same actions: `usb start 1`, `udstar backup.txt`. The script saves `boot_a`, `boot_b`, `misc` as `BOOT_A.IMG`, `BOOT_B.IMG`, `MISC.IMG` (taking `_2`, `_3`... if such files exist), checks the header, reads every file back and compares it with the eMMC, and writes a `*.CRC` next to each. The app recomputes the CRC32 and the sizes of the saved files and only then calls the stage done. Copy `BOOT_*.IMG` to a PC right away.
> 3. **Patch.** The app offers to patch the active slot image from the backup (Magisk is needed) and creates `check.txt`, `flash.txt`, `rollback.txt`.
> 4. **Flash.** In the MBOOT console: `usb start 1`, `udstar check.txt` (checks only, ends with `CHECK_OK_...`, you stay in the console), then `udstar flash.txt`: it verifies the slot, the original and the patched image by CRC32, unlocks, writes, reads back and compares, wipes data (if the boot state changes) and reboots. If something goes wrong: `udstar rollback.txt`.
>
> If `usb start 1` answers `Error, couldn't init Lowlevel part`, repeat `usb stop` and `usb start 1`.
>
> **Important about the drive.** Do not delete files from it that MBOOT wrote (the app only renames them). MBOOT marks the end of a file in the FAT with `0xFFFFFFFF`; if such a file is deleted and Android takes its clusters it keeps the top 4 bits, and MBOOT can no longer read the new file (`Invalid FAT entry`, `check.txt` stops at step 3 before writing anything). Then copy the files to a PC, format the drive (FAT32) and go through the stages again.
>
> The manual commands below stay as a fallback.


Unplug the TV from the mains.  
Connect the adapter to **HDMI port 3** (counting from the bottom) and to the PC.  
Insert the drive **into the USB port closest to the panel**.  
<img width="2487" height="2503" alt="20261101_111935" src="https://github.com/user-attachments/assets/e6165c5f-8e2b-491a-bfb3-8803b67d4c2e" />


Hold Enter in the active terminal window and power the TV on.

**Keep holding** until the prompt appears:
~~~text
<< MT5889 >>#
~~~

In the MBOOT console:
~~~text
usb start 1
~~~

Expected reply:
~~~text
(Re)start USB 1...
Check USB port[1]:
[USB] config miu select [F0] [EF] [EF] ][EF]
[USB] enable miu lower bound address subtraction
scanning bus for devices... New Device 0

[USB] interface[0] conf:1 value 8:
1 USB Device(s) found
       scanning bus for storage devices... max lun:0
1 Storage Device(s) found
~~~

Then check:
~~~text
fatls usb 0:1
~~~

Expected:
~~~text
            system volume information/
  7734485   RootMate-1.1.apk

1 file(s), 1 dir(s)
~~~

See where the system loads boot into RAM and how much it reads:
~~~text
printenv bootcmd
~~~

Expected (0x1F00000 is the boot image size read, 0x25000000 is the RAM address):
~~~text
bootcmd=mmc read.p 0x25000000 boot 0x1F00000; bootm 0x25000000
~~~

If the values match, use the commands below, **otherwise substitute your own values**.

Dump **both** slots: you will find out which one is active later, and you need the backups anyway. Run **one by one**:
~~~text
mmc read.p 0x25000000 boot_a 0x1F00000
~~~
~~~text
fatwrite usb 0:1 0x25000000 BOOT_A.IMG 0x1F00000
~~~
~~~text
mmc read.p 0x25000000 boot_b 0x1F00000
~~~
~~~text
fatwrite usb 0:1 0x25000000 BOOT_B.IMG 0x1F00000
~~~

Expected output (more # characters):
~~~text
<< MT5889 >># mmc read.p 0x25000000 boot_a 0x1F00000
<< MT5889 >># fatwrite usb 0:1 0x25000000 BOOT_A.IMG 0x1F00000
file BOOT_A.IMG not found
now create file BOOT_A.IMG
#################################################################
<< MT5889 >># mmc read.p 0x25000000 boot_b 0x1F00000
<< MT5889 >># fatwrite usb 0:1 0x25000000 BOOT_B.IMG 0x1F00000
file BOOT_B.IMG not found
now create file BOOT_B.IMG
#################################################################
~~~

Check the files and sizes:
~~~text
fatls usb 0:1
~~~
Expected:
~~~text
<< MT5889 >># fatls usb 0:1
            system volume information/
 32505856   boot_a.img
 32505856   boot_b.img
  7734485   RootMate-1.1.apk

3 file(s), 1 dir(s)
~~~

**Stop USB**:
~~~text
usb stop
~~~
Reply:
~~~text
<< MT5889 >># usb stop
stopping USB..
~~~

Reboot into Android:
~~~text
reset
~~~

**Copy BOOT_A.IMG and BOOT_B.IMG to your PC and keep them there.** This is your only way back.


## 5. Install RootMate and Magisk

After boot, open the stock file manager on the TV and install **RootMate-1.1.apk** from the drive (allow installs from unknown sources). If the stock manager cannot open APKs, use `adb install`.  
<img width="332" height="276" alt="image" src="https://github.com/user-attachments/assets/6d52afa9-80ed-493b-a0e8-b74b397f525e" />

Open RootMate from the TV's app list. On first launch it shows a permissions screen: grant **All files access** and **Install unknown apps**, then press **Done**.

<img src="img/02-permissions.png" width="720" alt="RootMate permissions screen">

Main screen: buttons on the left, operation log on the right.

<img src="img/01-main.png" width="720" alt="RootMate main screen">

Press **Download and install Magisk** (Скачать и установить Magisk). The app finds the latest release on GitHub, downloads it and starts the install; confirm in the system dialog (**Install**). If this Magisk version is already installed, the step is skipped. A line **Magisk installed: 31.1** appears under the button.

<img src="img/03-install-magisk.png" width="720" alt="Magisk install system dialog">

You do not need to open Magisk or patch through it: RootMate uses the binaries from the installed Magisk itself.


## 6. Find the active bootloader slot

You must flash the image of the slot **the TV is booting from right now**. If you patch and write the wrong slot, the TV keeps booting the stock boot and you get no root.

Easiest way: open RootMate; the line **Active boot slot: A** (or B) is shown above the patch buttons. If you pick a file named for the other slot, a large amber warning appears at the end of the log after patching.

You can check the same over adb (enable USB/network debugging in developer settings):

~~~bash
adb shell getprop ro.boot.slot_suffix
~~~

Answer **`_a`**: slot **A** is active, use `BOOT_A.IMG` and partition `boot_a`.  
Answer **`_b`**: slot **B** is active, use `BOOT_B.IMG` and partition `boot_b`.

An empty answer means the property is unavailable on this firmware. Do not continue blindly: find the slot another way first (for example the UART boot log).

Remember the result: you need it in steps 7 and 8 and for rollback. Below, `X` is your slot letter (`a` or `b`).

> This guide does not switch slots (`_a` ↔ `_b`). On the tested TV switching hung, whereas flashing the active slot works.


## 7. Patch the image in RootMate

In RootMate press **Select BOOT_\*.IMG** (the left button in "Choose one of the methods"). The built-in file picker opens.

1. Choose the USB drive in the list of storages.

   <img src="img/04-picker-drives.png" width="720" alt="Storage selection">

2. Scroll down and choose **BOOT_X.IMG** of your slot from step 6. It must be the **original** image dumped in step 4.

   <img src="img/05-picker-files.png" width="720" alt="Image selection">

The app runs Magisk's `boot_patch.sh`, applies the `skip_initramfs` fix (see below) and saves the result on the drive next to the source. Buttons are locked meanwhile; it takes about a minute. Do not leave the app.

<img src="img/06-patch-done.png" width="720" alt="Patching finished">

The log ends with a line like `Готово: /storage/XXXX-XXXX/BOOT_A_magisk_patched.img` ("Done: ..."). If a file with that name exists, the new one gets a `_2`, `_3`... suffix: note which one you will flash.

The output size must equal the original (32505856 bytes). On an error no file is saved. Common causes are in [recovery.en.md](recovery.en.md).

After patching, take the drive out and plug it into the MBOOT port, the one closest to the panel.

### What the skip_initramfs fix is

On this MBOOT the real kernel command line contains **skip_initramfs**. Because of it Magisk wrongly picks Legacy SAR instead of First Stage Init. Replacing one character in the ELF **magiskinit** (the `init` file inside the ramdisk) helps: **skip_initramfs → skip_initramfz**, so magiskinit takes the right branch. RootMate does this with `magiskboot` from Magisk itself.

### Fallbacks

- **Fix only.** If you already patched the image in the Magisk app itself (**Install → Select and Patch a File**, result `magisk_patched-....img`), press **Select magisk_patched-\*.img** in RootMate and pick that file. The result is saved next to it as `*_skipfix.img`. Same fix, without the auto patch.
- **Via adb shell**, without the app:
  ~~~bash
  adb push scripts/skipfix.sh /data/local/tmp/skipfix.sh
  adb shell sh /data/local/tmp/skipfix.sh magisk_patched-....img
  ~~~
  The script finds `magiskboot` in the installed Magisk, patches the image from `/sdcard/Download` and copies `magisk_skipfix.img` to the USB drive.


## 8. Unlock the bootloader, flash and wipe data

> **Quick way (scripts from RootMate).** After patching (step 7) RootMate writes `check.txt`, `flash.txt` and `rollback.txt` to the drive root with your slot, file names and checksums filled in. The original `BOOT_*.IMG` must be on the same drive. In the MBOOT console run `usb start 1`, then `udstar check.txt` (checks only, ends with `CHECK_OK_...`), then `udstar flash.txt`. The script verifies the slot, the original and the patched image (CRC32), unlocks the bootloader, writes the image, reads it back and compares, wipes data (if the boot state changes) and resets the TV. Any error stops the script before anything is written. An `Unknown command` line may appear at the very end because of garbage after the end of the file: it is harmless. The manual commands below stay as a fallback.


**Get the console again as in step 4.**
~~~text
<< MT5889 >>#
~~~

In MBOOT:
~~~text
setenv devicestate unlock
~~~
~~~text
saveenv
~~~

Check the stored state:
~~~text
printenv devicestate
~~~
Expected:
~~~text
devicestate=unlock
~~~

Check the file name on the drive (it depends on the suffix, see step 7):
~~~text
usb start 1
~~~
~~~text
fatls usb 0:1
~~~

Replace the original image with the patched one. **Substitute `X` from step 6 and the file name from `fatls`**; example for slot A:
~~~text
fatload usb 0:1 0x25000000 BOOT_A_magisk_patched.img
~~~
~~~text
mmc write.p 0x25000000 boot_a 0x1F00000 0
~~~

For slot B: `BOOT_B_magisk_patched.img` and `boot_b`. Make sure `fatload` read exactly 32505856 bytes (that is 0x1F00000), otherwise stop.

The next command deletes user data. After the reset you will have to set the TV up again, including enabling debugging/ADB and reinstalling the Magisk APK.  
**If the TV does not boot normally, you most likely skipped this step.**
~~~text
recovery_wipe_partition data
~~~

Reboot
~~~text
reset
~~~

After the reset RootMate is gone with the data. Install Magisk again: either the APK from the drive, or **RootMate-1.1.apk** → **Download and install Magisk**.  
**After installing, press Open immediately: there is no icon in the stock launcher. You can reopen it later from Android settings.**  
When asked for additional setup, agree.

Check (enable ADB again):
~~~bash
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.boot.verifiedbootstate
adb shell "su -c id"
~~~
The slot must match the one you flashed, `verifiedbootstate` will be `orange`, `su` returns `uid=0`.

## ROLLBACK

> **Quick way.** `udstar rollback.txt` (created by RootMate together with `flash.txt`): verifies the slot and the original image, sets `devicestate lock`, writes the original, verifies the write, wipes data and resets the TV.


Go back to MBOOT and enter. The rollback writes the **original image of the active slot** (`X`, step 6):
~~~text
setenv devicestate lock
~~~
~~~text
saveenv
~~~
~~~text
usb start 1
~~~
~~~text
fatload usb 0:1 0x25000000 BOOT_A.IMG
~~~

~~~text
mmc write.p 0x25000000 boot_a 0x1F00000 0
~~~
~~~text
recovery_wipe_partition data
~~~
~~~text
reset
~~~

For slot B use `BOOT_B.IMG` and `boot_b`.

**If things are really bad and the original image of the active slot is lost**, you can write the other slot's content into it, assuming that slot was not modified. For example slot A is active: instead of `fatload` from the drive run
~~~text
mmc read.p 0x25000000 boot_b 0x1F00000
~~~
then the same `mmc write.p ... boot_a`, `recovery_wipe_partition data`, `reset`. For active slot B do the opposite: read `boot_a`, write `boot_b`.
