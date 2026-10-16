#!/usr/bin/env python3
"""Pebble watch app size report.

Reads build/<platform>/pebble-app.elf (pure Python, no readelf needed) and prints:
- static size (.text + .data + .bss): the SDK refuses a build above 65,535
  bytes because PebbleProcessInfo.virtual_size is a uint16_t
  (sdk/tools/inject_metadata.py);
- heap left at launch (appRamBytes minus static);
- with --map build/pebble-app.map, the biggest object files.

Defaults come from pebble-dev.json in the current directory (platform,
sizeBudgetBytes, appRamBytes); flags override them.
Exit code 1 when static size is over budget, so a change that eats the last
of the room fails loudly before the SDK's hard wall.

Usage: python3 watch_size.py [--elf PATH] [--map PATH] [--budget N] [--platform P]
"""
import argparse, collections, json, os, re, struct, sys

HARD_LIMIT = 0xFFFF


def load_cfg():
    try:
        with open('pebble-dev.json') as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def sections(path):
    f = open(path, 'rb').read()
    shoff = struct.unpack_from('<I', f, 0x20)[0]
    entsize, num, strndx = struct.unpack_from('<HHH', f, 0x2E)
    secs = [struct.unpack_from('<IIIIIIIIII', f, shoff + i * entsize) for i in range(num)]
    names = secs[strndx]

    def name(o):
        b = f[names[4] + o:]
        return b[:b.index(b'\0')].decode()
    return {name(s[0]): (s[3], s[5]) for s in secs if s[2] & 2}  # SHF_ALLOC


def by_object(map_path, top):
    t = open(map_path).read()
    t = t[t.find('Linker script and memory map'):]
    sizes = collections.Counter()
    for m in re.finditer(r'^ \.(text|rodata|data|bss)\S*\s*\n?\s+0x[0-9a-f]+\s+0x([0-9a-f]+)\s+(\S+)', t, re.M):
        sizes[m.group(3).split('/')[-1]] += int(m.group(2), 16)
    return sizes.most_common(top)


def main():
    cfg = load_cfg()
    ap = argparse.ArgumentParser()
    ap.add_argument('--platform', default=cfg.get('platform', 'emery'))
    ap.add_argument('--elf', default=None)
    ap.add_argument('--map', default=None)
    ap.add_argument('--budget', type=int, default=cfg.get('sizeBudgetBytes', 62 * 1024))
    ap.add_argument('--app-ram', type=int, default=cfg.get('appRamBytes', 0x20000))
    a = ap.parse_args()
    elf = a.elf or os.path.join('build', a.platform, 'pebble-app.elf')
    if not os.path.exists(elf):
        print('no ELF at %s; run `pebble build` first' % elf)
        return 2
    s = sections(elf)
    end = max(addr + size for addr, size in s.values())
    parts = {k: s[k][1] for k in ('.text', '.data', '.bss') if k in s}
    print('static (virtual_size): %6d B  %s' % (end, '  '.join('%s %d' % kv for kv in parts.items())))
    print('room to SDK limit    : %6d B  (limit %d)' % (HARD_LIMIT - end, HARD_LIMIT))
    print('room to budget       : %6d B  (budget %d)' % (a.budget - end, a.budget))
    print('heap at launch       : %6d B  (%d app RAM minus static)' % (a.app_ram - end, a.app_ram))
    if a.map:
        print('biggest objects (map, approximate):')
        for o, n in by_object(a.map, 12):
            print('  %6d  %s' % (n, o))
    if end > a.budget:
        print('OVER BUDGET by %d B' % (end - a.budget))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
