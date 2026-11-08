# RootMate: probe. Saves the eMMC partition table and the MBOOT environment to the USB drive.
# Run in the MBOOT console:  usb start 1   then   udstar probe.txt   (the TV reboots when it is done)
# It WRITES NOTHING TO THE eMMC. RootMate reads PTABLE.BIN and ENV.TXT and builds the other scripts from them.
# MBOOT can only write files into the drive root, with names up to 12 characters.
# PROBE.OK is written last: its presence tells RootMate that the script ran to the end.
# The scratch RAM address comes from the environment of this very TV (MI_KERNEL_POOL2_ADR).
printenv MI_KERNEL_POOL2_ADR
mmc read ${MI_KERNEL_POOL2_ADR} 0x0 0x8000
fatwrite usb 0:1 ${MI_KERNEL_POOL2_ADR} PTABLE.BIN 0x8000
env export -t ${MI_KERNEL_POOL2_ADR} 0x10000
fatwrite usb 0:1 ${MI_KERNEL_POOL2_ADR} ENV.TXT 0x10000
mw.l ${MI_KERNEL_POOL2_ADR} 0x214B4F21 1
fatwrite usb 0:1 ${MI_KERNEL_POOL2_ADR} PROBE.OK 0x4
echo PROBE_OK
echo REBOOTING
usb stop
reset
