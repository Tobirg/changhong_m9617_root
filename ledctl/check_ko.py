# Compare a .ko against a reference vendor .ko: this_module size, init/exit reloc offsets, undefined symbols.
import sys, struct
def load(f):
    d = open(f,'rb').read()
    shoff, = struct.unpack_from('<Q', d, 0x28); es, n, si = struct.unpack_from('<HHH', d, 0x3a)
    sh = [struct.unpack_from('<IIQQQQIIQQ', d, shoff+i*es) for i in range(n)]
    so = sh[si][4]; nm = lambda o, base: d[base+o:d.index(b'\0', base+o)].decode()
    secs = {nm(s[0], so): (i, s) for i, s in enumerate(sh)}
    symi, syms = secs['.symtab']; stro = sh[syms[6]][4]
    sy = [struct.unpack_from('<IBBHQQ', d, syms[4]+j*24) for j in range(syms[5]//24)]
    und = sorted(nm(s[0], stro) for s in sy if s[3] == 0 and s[0])
    tm = secs['.gnu.linkonce.this_module']
    rel = []
    for name, (i, s) in secs.items():
        if s[1] == 4 and s[7] == tm[0]:
            for j in range(s[5]//24):
                off, info, add = struct.unpack_from('<QQq', d, s[4]+j*24)
                rel.append((hex(off), nm(sy[info>>32][0], stro)))
    return tm[1][5], sorted(rel), und
for f in sys.argv[1:]:
    size, rel, und = load(f)
    print(f, 'this_module size', hex(size), rel); print('   undefined:', und if len(und) < 12 else len(und))
