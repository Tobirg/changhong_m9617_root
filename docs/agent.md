# RootMate for agents

How an AI agent (or any script) roots the TV with RootMate without the remote: RootMate takes commands over adb
and answers in JSON, the MBOOT console is driven over the UART adapter. The flow and the scripts are the same as in
the "Semi-auto" mode of the app ([guide.en.md](guide.en.md), section 7): only who presses the buttons changes.

Русская версия инструкции для людей: [guide.md](guide.md). Этот файл для агентов, поэтому на английском.

## What a human has to do (once)

1. Wire the UART adapter (3.3 V TTL, 115200 8N1, no VCC; pins in [recovery.en.md](recovery.en.md)) and tell the agent its port.
2. Turn UART on once: RootMate → "Open the engineering menu" → Design Mode → Uart → On ([guide.en.md](guide.en.md), section 6).
3. Put a FAT32 USB drive into the USB port closer to the panel and keep it there: it works reliably in Android, and the scripts use it as `usb start 1`.
4. Turn on developer options (Settings → System → About → press "Build" 7 times) and "USB debugging" (Settings →
   System → Developer options). On this TV that also makes adbd listen on tcp 5555, so `adb connect <tv-ip>:5555` works.
5. The first `adb connect` shows "Allow USB debugging?" on the TV: "Always allow".
6. Approve the steps that write the eMMC (see [Safety](#safety)) when the agent asks.

Everything else the agent can do itself.

## Tools on the PC

- `adb` (platform-tools).
- [scripts/agent/rootmate.py](../scripts/agent/rootmate.py): one RootMate command, prints the JSON answer. Exit code 0 = ok, 1 = error answer, 2 = no answer.
- [scripts/agent/mboot.py](../scripts/agent/mboot.py): the MBOOT console over UART (`pip install pyserial`).
  Port: `--port COM8` / `/dev/ttyUSB0`, or `ROOTMATE_UART`. The whole session is appended to `scripts/agent/mboot.log`.

## Setup

```
adb connect <tv-ip>:5555
adb install -r RootMate-1.0.apk
adb shell appops set --uid com.tobirg.rootmate MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.tobirg.rootmate REQUEST_INSTALL_PACKAGES allow
adb install -r Magisk-vX.Y.apk           # or let RootMate download it (button "Download and install Magisk")
python scripts/agent/rootmate.py open    # RootMate on screen
```

RootMate only has to be open (either of its two modes) while the agent works. From the first command on it shows no
dialogs (the same data is in the JSON answers), keeps the screen on and writes every command into its log, so a
person watching the TV sees what the agent does. A key press on the remote gives the app back to the person.
Commands sent while it is closed answer with an error that says how to open it. `open` is safe to repeat.
`next` and `choose` switch the screen to the "Semi-auto" view; `patch` and `fix` work in either.

## Protocol

Raw form (what `rootmate.py` sends):

```
adb shell am broadcast -n com.tobirg.rootmate/.AgentReceiver -a com.tobirg.rootmate.AGENT --es cmd status
Broadcast completed: result=0, data="{...json...}"
```

`result=0` and `"ok": true` = success; `result=1` and `"ok": false, "error": "..."` = refused or failed. The receiver
requires `android.permission.DUMP`: the adb shell has it, ordinary apps do not, so only adb (or root) can send commands.

| command | extras | what it does |
|---|---|---|
| `status` | | recognises the stage from the drive, answers the full status (below) |
| `next` | | the main button of the flow: writes probe.txt / backup.txt, or starts the patch of the active slot (background), or (for stages that wait for MBOOT) only answers with the commands |
| `choose` | `--es id <drive id>` | picks the drive the flow works on (ids are in `drives`) |
| `patch` | `--es path <image>` | manual mode: full Magisk patch of an original boot image + the skip_initramfs fix, saved next to the input as `<name>_magisk_patched.img`; no MBOOT scripts |
| `fix` | `--es path <image>` | manual mode: only the skip_initramfs fix of an image Magisk already patched, saved as `<name>_skipfix.img` |
| `log` | `--ei lines N` | the last N lines of the on-screen log (boot_patch.sh and magiskboot output) |

`patch`, `fix` and the patch started by `next` run in the background: poll `status` until `"busy": false`
(`rootmate.py wait` does that), then read `warnings` and `log`.

Status fields:

| field | meaning |
|---|---|
| `stage` | `NO_PERMISSION`, `NO_DRIVE`, `DRIVE_MISSING`, `CHOOSE_DRIVE`, `BAD_DRIVE`, `FRESH`, `PROBE_READY`, `PROBE_DONE`, `BACKUP_READY`, `BACKUP_BAD`, `BACKUP_DONE`, `PATCHED`, `FLASH_DONE` |
| `stage_text` | the same line the app shows (Russian or English by the TV language) |
| `mboot` | the MBOOT commands for this stage, e.g. `["usb start 1", "udstar check.txt", "udstar flash.txt"]`; empty when nothing waits for MBOOT |
| `busy`, `progress` | a background patch runs; its current step |
| `drive`, `drives`, `chosen_drive` | the drive of the flow (id, path, fs, partition, bytes, fat, first_partition) and every mounted one |
| `backup_image` | the verified backup of the active slot (input of the patch) |
| `slot` | active slot `a`/`b` |
| `verified_boot_state` | `orange` = unlocked: flash.txt does not wipe data; anything else = flash.txt WIPES USER DATA |
| `magisk` | installed, version, magiskboot found |
| `permissions` | storage, install, adb (developer_options, usb_debugging, port_5555) |
| `warnings` | warnings and errors since the last command (e.g. "the input is already patched by Magisk") |

## The flow

Loop on `status` and act on `stage`:

| stage | action |
|---|---|
| `NO_PERMISSION` | the `appops` commands from Setup |
| `NO_DRIVE` | ask the human to plug in a FAT32 drive |
| `CHOOSE_DRIVE`, `DRIVE_MISSING` | `choose --id <id>` (a formatted drive gets a new id) |
| `BAD_DRIVE` | not FAT32 or not the first partition: see [Cleaning the drive](#cleaning-the-drive) |
| `FRESH` | `next` (writes probe.txt) |
| `PROBE_READY` | [run in MBOOT](#running-a-script-in-mboot) `probe.txt` |
| `PROBE_DONE`, `BACKUP_BAD` | `next` (writes backup.txt) |
| `BACKUP_READY` | run in MBOOT `backup.txt` |
| `BACKUP_DONE` | `next` (starts the patch), `wait`; then check `warnings` |
| `PATCHED` | run in MBOOT `check.txt`; only on `CHECK_OK`: ask the human, then run `flash.txt` |
| `FLASH_DONE` | verify root: `adb shell su -c id` (the first time Magisk asks on the TV screen to allow the shell) |

### Running a script in MBOOT

```
python scripts/agent/mboot.py --port COM8 catch --timeout 180 &   # start first: it holds Enter
adb reboot                                                          # or rootmate's own reboot, or a power cycle
wait for "catch" to exit 0 (the MBOOT prompt "<< MT5889 >>#")
python scripts/agent/mboot.py --port COM8 udstar probe.txt         # usb start 1 + udstar, checks the marker
```

- probe.txt, backup.txt, flash.txt and rollback.txt end with `reset`: the TV boots Android by itself. Wait for it:
  `adb connect <tv-ip>:5555`, then `adb shell getprop sys.boot_completed` = `1`, then `rootmate.py open` and `status`.
- check.txt stays in MBOOT. Run flash.txt right after it in the same session (`mboot.py udstar flash.txt`), or
  `mboot.py reset` to go back to Android.
- `udstar` prints `Saving Environment to MMC` when it starts any script: that is MBOOT saving its own variables,
  not a write of a partition.
- `mboot.py udstar` exits 0 only when the script printed its success marker. Exit 1 shows where it stopped: a failed
  `cmp` (a check did not hold: nothing after it ran), `Invalid FAT entry` (see below), no USB device (wrong port).

## Safety

- **flash.txt and rollback.txt write the boot partition.** Before either: show the human the `status` (slot,
  verified_boot_state, warnings) and the `check.txt` result, and wait for an explicit yes. Never run flash.txt
  without `CHECK_OK_slot_<x>_nothing_written` from check.txt on the same drive.
- If `verified_boot_state` is not `orange`, flash.txt ends with a data wipe. Tell the human before asking.
- Every script checks before it writes (slot in misc, CRC32 of the original and of the patched image, read-back after
  the write) and stops at the first failed check. Do not edit the scripts to get past a failed check.
- If `warnings` say the backup of the active slot is already patched by Magisk, the patch is still correct, but
  rollback.txt would restore that patched image, not the stock one. Keep the stock image (from an earlier backup or
  the firmware) and tell the human.
- Do not change other TV settings, do not uninstall apps, do not touch partitions other than the scripts do.

## Cleaning the drive

Never delete files MBOOT wrote (`PTABLE.BIN`, `ENV.TXT`, `*.IMG`, `*.CRC`, `*.OK`): MBOOT ends FAT chains with
`0xFFFFFFFF`, Linux keeps the top 4 bits when it reuses those clusters, and MBOOT then fails with `Invalid FAT entry`
on files Android writes later (check.txt stops at step 3). Files Android wrote (`*.txt`, `*_magisk_patched*.img`)
may be deleted.

To start over, or after `Invalid FAT entry`, or for `BAD_DRIVE`: with the human's consent (it erases the drive)
copy off what is needed (`adb pull`), then

```
adb shell sm list-volumes all            # public:8,1 mounted <id>
adb shell sm format public:8,1           # FAT32, new id
adb reboot                               # if the volume shows "unmountable" after the format
```

push the human's files back, and `choose --id <new id>`.

## Troubleshooting

| symptom | cause |
|---|---|
| `RootMate is not open` | `rootmate.py open` |
| `usb start 1` finds no storage device | the drive is in another USB port, or not FAT32 |
| `catch` times out | the TV did not reboot, or the UART wiring / port is wrong (115200 8N1) |
| `adb connect` refused after a reboot | USB debugging is off again, or the TV has a new IP |
| `busy` error | a patch is running: `wait` |
