# RootMate: flash patched boot into slot {{SLOT}}  ({{WHEN}})
# Run in MBOOT console:  usb start 1  then  udstar flash.txt   (check.txt = same checks, writes nothing)
# Any failed command stops the script. Lines marked @W write to the eMMC, @D exist only in the dry run.
# original (backup): {{ORIG}}  crc32 {{CRC_ORIG}}
# patched:           {{NEW}}  crc32 {{CRC_NEW}}
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
echo STEP3_check_patched_image
fatload usb 0:1 {{A}} {{NEW}} {{SIZE}}
crc32 {{A}} {{SIZE}} {{X30}}
mw.l {{X40}} 0x{{CRC_NEW}} 1
cmp.l {{X30}} {{X40}} 1
@D echo CHECK_OK_slot_{{SLOT}}_nothing_written
@W echo STEP4_write_boot_{{SLOT}}
@W setenv devicestate unlock
@W saveenv
@W mmc write.p {{A}} boot_{{SLOT}} {{SIZE}} 0
@W mmc read.p {{B}} boot_{{SLOT}} {{SIZE}}
@W cmp.l {{A}} {{B}} {{WORDS}}
@W echo STEP5_written_and_verified
@W mw.l {{X0}} 0x214B4F21 1
@W fatwrite usb 0:1 {{X0}} FLASH.OK 0x4
@W {{WIPE_CMD}}
@W echo FLASH_OK_slot_{{SLOT}}
@W usb stop
@W reset
