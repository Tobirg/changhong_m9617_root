# RootMate: backup of the boot partitions and misc to the USB drive ({{WHEN}})
# Run in the MBOOT console:  usb start 1   then   udstar backup.txt   (the TV reboots when it is done)
# It WRITES NOTHING TO THE eMMC: partitions are only read and saved as files on the drive.
# Every file is read back from the drive and compared with the eMMC, any mismatch stops the script.
# Boot partitions must start with the Android boot image magic, otherwise the script stops.
# Sizes, names and the RAM buffers come from PTABLE.BIN and ENV.TXT of this TV ({{SIZE_SOURCE}}).
# MBOOT writes only into the drive root, with names up to 12 characters.
# Next to every image the script saves its CRC32 (.CRC, 4 bytes, little endian) and the marker file at the very end.
# Files: {{FILES}}
# Marker: {{MARKER}}
printenv bootcmd
mw.l {{C}} 0x52444E41 1
mw.l {{C4}} 0x2144494F 1
{{PARTS}}
mw.l {{C}} 0x214B4F21 1
fatwrite usb 0:1 {{C}} {{MARKER}} 0x4
echo BACKUP_OK
echo REBOOTING
usb stop
reset
