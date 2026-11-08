echo BACKUP_{{PART}}_{{SIZE}}_bytes
mmc look {{PART}}
mmc read.p {{A}} {{PART}} {{SIZE}}
@B cmp.l {{A}} {{C}} 2
crc32 {{A}} {{SIZE}} {{C20}}
fatwrite usb 0:1 {{A}} {{FILE}} {{SIZE}}
fatwrite usb 0:1 {{C20}} {{CRCFILE}} 0x4
mw.l {{B}} 0xDEADBEEF {{WORDS}}
fatload usb 0:1 {{B}} {{FILE}} {{SIZE}}
cmp.l {{A}} {{B}} {{WORDS}}
