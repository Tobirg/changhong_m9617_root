#!/usr/bin/env python3
"""Sends one command to RootMate over adb and prints its JSON answer.

    python rootmate.py [-s SERIAL] status
    python rootmate.py next
    python rootmate.py choose --id 02EF-1D00
    python rootmate.py patch --path /storage/02EF-1D00/original_boot/BOOT_A.IMG
    python rootmate.py fix --path /storage/.../magisk_patched-....img
    python rootmate.py log --lines 60
    python rootmate.py open            # start RootMate (it must be open to take commands)
    python rootmate.py wait            # poll status until busy is false, print the last status

Exit code: 0 when the answer has "ok": true, 1 on an error answer, 2 when adb or the answer itself failed.
Needs only adb in PATH (or ADB=/path/to/adb). See docs/agent.md.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

PKG = 'com.tobirg.rootmate'
ADB = os.environ.get('ADB', 'adb')


def adb(serial, *args, timeout=60):
    cmd = [ADB] + (['-s', serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)


def tv_path(p):
    """Git Bash / MSYS turns '/storage/...' into 'C:/Program Files/Git/storage/...' before Python sees it: undo that."""
    if p is None:
        return None
    m = re.match(r'^[A-Za-z]:[\\/].*?[\\/]Git(?:[\\/]usr)?([\\/](?:storage|sdcard|mnt|data)[\\/].*)$', p)
    return m.group(1).replace('\\', '/') if m else p


def send(serial, cmd, extras):
    args = ['shell', 'am', 'broadcast', '-n', f'{PKG}/.AgentReceiver', '-a', f'{PKG}.AGENT', '--es', 'cmd', cmd]
    for k, v in extras.items():
        if v is None:
            continue
        if isinstance(v, int):
            args += ['--ei', k, str(v)]
        else:
            # am runs in a shell on the TV: quote the value for it
            args += ['--es', k, "'" + str(v).replace("'", "'\\''") + "'"]
    r = adb(serial, *args)
    out = r.stdout + r.stderr
    m = re.search(r'result=(-?\d+), data="(.*)"\s*$', out, re.S)
    if not m:
        raise RuntimeError('no answer from RootMate:\n' + out.strip())
    return json.loads(m.group(2))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('-s', '--serial', default=os.environ.get('ANDROID_SERIAL'))
    p.add_argument('command', choices=['status', 'next', 'choose', 'patch', 'fix', 'log', 'open', 'wait'])
    p.add_argument('--id')
    p.add_argument('--path')
    p.add_argument('--lines', type=int)
    p.add_argument('--timeout', type=int, default=300, help='for wait, seconds')
    a = p.parse_args()
    try:
        if a.command == 'open':
            r = adb(a.serial, 'shell', 'am', 'start', '-n', f'{PKG}/.MainActivity')
            print((r.stdout + r.stderr).strip())
            time.sleep(3)
            ans = send(a.serial, 'status', {})
        elif a.command == 'wait':
            end = time.time() + a.timeout
            while True:
                ans = send(a.serial, 'status', {})
                if not ans.get('busy') or time.time() > end:
                    break
                print('busy:', ans.get('progress', ''), file=sys.stderr)
                time.sleep(5)
        else:
            ans = send(a.serial, a.command, {'id': a.id, 'path': tv_path(a.path), 'lines': a.lines})
    except Exception as e:
        print(json.dumps({'ok': False, 'error': str(e)}, ensure_ascii=False))
        return 2
    print(json.dumps(ans, ensure_ascii=False, indent=2))
    return 0 if ans.get('ok') else 1


if __name__ == '__main__':
    sys.exit(main())
