#!/usr/bin/env python3
"""The TV's MBOOT console over UART (115200 8N1), for agents. Needs pyserial (pip install pyserial).

    python mboot.py --port COM8 catch                 # hold Enter until the MBOOT prompt; reboot the TV meanwhile
    python mboot.py --port COM8 run "usb start 1"     # one command, prints the output up to the prompt
    python mboot.py --port COM8 udstar probe.txt      # usb start 1 + udstar <script>, checks the script's result
    python mboot.py --port COM8 reset                 # leave MBOOT, boot Android

`catch` prints the tail of the boot log and exits 0 at the prompt (1 on timeout). Start it first, then reboot the TV
(adb reboot, or the app's reboot button), it keeps sending Enter so the autoboot stops.

`udstar` exit codes: 0 = the script printed its success marker (PROBE_OK, BACKUP_OK, CHECK_OK_..., FLASH_OK_...,
ROLLBACK_OK_...), 1 = it stopped earlier (the output says where: a failed cmp, "Invalid FAT entry"...), 2 = timeout.
probe/backup/flash/rollback end with a reset: the TV boots Android by itself afterwards; check.txt stays in MBOOT.
Everything read is also appended to mboot.log next to this file (or --log).
"""
import argparse
import os
import re
import sys
import time

try:
    import serial
except ImportError:
    sys.exit('pyserial is missing: pip install pyserial')

PROMPT = '<< MT5889 >>#'
MARKERS = re.compile(r'\b(PROBE_OK|BACKUP_OK|CHECK_OK_\w+|FLASH_OK_\w+|ROLLBACK_OK_\w+)\b')


class Console:
    def __init__(self, port, log):
        self.s = serial.Serial(port, 115200, bytesize=8, parity='N', stopbits=1, timeout=0.1)
        self.log = open(log, 'a', encoding='utf-8')

    def read(self):
        b = self.s.read(4096)
        t = b.decode('latin-1') if b else ''
        if t:
            self.log.write(t)
            self.log.flush()
        return t

    def catch(self, timeout):
        buf, end = '', time.time() + timeout
        self.s.reset_input_buffer()
        while time.time() < end:
            self.s.write(b'\r')
            time.sleep(0.04)
            buf += self.read()
            if PROMPT in buf:
                time.sleep(0.3)
                buf += self.read()
                return True, buf[-3000:]
        return False, buf[-3000:]

    def run(self, cmd, until, timeout):
        """Sends cmd, reads until the regex `until` matches the output after the echo, or the timeout."""
        self.s.reset_input_buffer()
        self.s.write((cmd + '\r').encode('ascii'))
        buf, end = '', time.time() + timeout
        pat = re.compile(until)
        while time.time() < end:
            buf += self.read()
            i = buf.find(cmd)
            if i >= 0 and pat.search(buf[i + len(cmd):]):
                time.sleep(0.2)
                return True, buf + self.read()
        return False, buf


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--port', default=os.environ.get('ROOTMATE_UART', 'COM8' if os.name == 'nt' else '/dev/ttyUSB0'))
    p.add_argument('--timeout', type=int)
    p.add_argument('--log', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mboot.log'))
    p.add_argument('action', choices=['catch', 'run', 'udstar', 'reset'])
    p.add_argument('arg', nargs='?')
    a = p.parse_args()
    c = Console(a.port, a.log)

    if a.action == 'catch':
        ok, out = c.catch(a.timeout or 180)
        print(out)
        return 0 if ok else 1
    if a.action == 'reset':
        c.s.write(b'reset\r')
        time.sleep(1)
        print(c.read())
        return 0
    if a.action == 'run':
        ok, out = c.run(a.arg, re.escape(PROMPT), a.timeout or 30)
        print(out)
        return 0 if ok else 2

    # udstar: usb start 1, then the script; done at its reset, at the prompt again, or at a FAT error
    ok, out = c.run('usb start 1', re.escape(PROMPT), 30)
    print(out)
    if 'Storage Device(s) found' not in out or '0 Storage Device' in out:
        print('[no USB drive on port 1: is the drive in the right USB port?]')
        return 1
    ok, out = c.run('udstar ' + a.arg, r'do_reset|' + re.escape(PROMPT) + r'\s*$|Invalid FAT entry', a.timeout or 600)
    if 'Invalid FAT entry' in out:
        ok2, more = c.run('', re.escape(PROMPT), 30)
        out += more
    print(out)
    if not ok:
        print('[timeout]')
        return 2
    m = MARKERS.findall(out)
    print('[result: %s]' % (m[-1] if m else 'no success marker: the script stopped'))
    return 0 if m else 1


if __name__ == '__main__':
    sys.exit(main())
