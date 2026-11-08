# RootMate: ROLLBACK, restore original boot into slot {{SLOT}}  ({{WHEN}})
# Run in MBOOT console:  usb start 1  then  udstar rollback.txt
# Locks the bootloader again, writes the backup, wipes data (see recovery.md), resets.
# original (backup): {{ORIG}}  crc32 {{CRC_ORIG}}
echo STEP1_check_active_slot_is_{{SLOT}}
mmc read.p {{C}} misc 0x1000
mw.l {{X0}} 0x0000{{SLOTHEX}}5F 1
mw.l {{X4}} 0x42414342 1
cmp.l {{C800}} {{X0}} 2
echo STEP2_check_backup_image
fatload usb 0:1 {{B}} {{ORIG}} {{SIZE}}
crc32 {{B}} {{SIZE}} {{X10}}
mw.l {{X20}} 0x{{CRC_ORIG}} 1
cmp.l {{X10}} {{X20}} 1
echo STEP3_write_backup_to_boot_{{SLOT}}
setenv devicestate lock
saveenv
mmc write.p {{B}} boot_{{SLOT}} {{SIZE}} 0
mmc read.p {{A}} boot_{{SLOT}} {{SIZE}}
cmp.l {{B}} {{A}} {{WORDS}}
echo STEP4_verified
mw.l {{X0}} 0x214B4F21 1
fatwrite usb 0:1 {{X0}} ROLLBACK.OK 0x4
recovery_wipe_partition data
echo ROLLBACK_OK_slot_{{SLOT}}
usb stop
reset
