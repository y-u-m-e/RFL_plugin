"""One-off: extract OSRS identity kits (config archive, index 2 group 3) from an OpenRS2 cache.

Writes src/main/resources/com/rfl/identkits.json: kit id -> {bodyPart, models, selectable}.
Only body part, model ids and the selectable flag are kept; recolours, retextures and chathead
models are parsed and dropped.

    python tools/extract_identkits.py [openrs2-cache-id]

With no id, uses the newest OSRS live cache listed at https://archive.openrs2.org/caches.json.
Not part of the plugin build; re-run only when Jagex adds identity kits.
"""
import bz2
import gzip
import json
import os
import struct
import sys
import urllib.request

BASE = "https://archive.openrs2.org"
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "com", "rfl", "identkits.json")


def fetch(path):
    with urllib.request.urlopen(BASE + path, timeout=60) as r:
        return r.read()


def latest_cache():
    caches = json.loads(fetch("/caches.json"))
    live = [c for c in caches if c["game"] == "oldschool" and c["environment"] == "live" and c.get("timestamp")]
    return max(live, key=lambda c: c["timestamp"])


def decompress(container):
    compression, length = container[0], struct.unpack(">i", container[1:5])[0]
    if compression == 0:
        return container[5:5 + length]
    data = container[9:9 + length]
    if compression == 1:
        return bz2.decompress(b"BZh1" + data)  # JS5 strips the bzip2 header
    if compression == 2:
        return gzip.decompress(data)
    raise ValueError("unsupported compression %d" % compression)


class Buf:
    def __init__(self, data):
        self.d, self.p = data, 0

    def u8(self):
        self.p += 1
        return self.d[self.p - 1]

    def u16(self):
        self.p += 2
        return struct.unpack(">H", self.d[self.p - 2:self.p])[0]

    def i32(self):
        self.p += 4
        return struct.unpack(">i", self.d[self.p - 4:self.p])[0]

    def big_smart(self):
        return self.i32() & 0x7FFFFFFF if self.d[self.p] & 0x80 else self.u16()


def file_ids(index, group):
    """File ids of one group, from the archive's reference table (index 255)."""
    b = Buf(index)
    protocol = b.u8()
    if protocol >= 6:
        b.i32()
    flags = b.u8()
    num = b.big_smart if protocol >= 7 else b.u16
    size = num()
    groups, acc = [], 0
    for _ in range(size):
        acc += num()
        groups.append(acc)
    if flags & 1:
        b.p += 4 * size  # name hashes
    b.p += 4 * size  # checksums
    if flags & 8:
        b.p += 4 * size  # uncompressed checksums
    if flags & 2:
        b.p += 64 * size  # whirlpool digests
    if flags & 4:
        b.p += 8 * size  # lengths
    b.p += 4 * size  # versions
    counts = [num() for _ in range(size)]
    for g, count in zip(groups, counts):
        ids, acc = [], 0
        for _ in range(count):
            acc += num()
            ids.append(acc)
        if g == group:
            return ids
    raise KeyError(group)


def split_group(data, count):
    if count == 1:
        return [data]
    chunks = data[-1]
    table = len(data) - 1 - chunks * count * 4
    b = Buf(data)
    b.p = table
    sizes = [[0] * count for _ in range(chunks)]
    for c in range(chunks):
        size = 0
        for f in range(count):
            size += b.i32()
            sizes[c][f] = size
    files, p = [bytearray() for _ in range(count)], 0
    for c in range(chunks):
        for f in range(count):
            files[f] += data[p:p + sizes[c][f]]
            p += sizes[c][f]
    return [bytes(f) for f in files]


def parse_kit(data):
    b = Buf(data)
    kit = {"bodyPart": -1, "models": [], "selectable": True}
    while True:
        op = b.u8()
        if op == 0:
            break
        if op == 1:
            kit["bodyPart"] = b.u8()
        elif op == 2:
            kit["models"] = [b.u16() for _ in range(b.u8())]
        elif op == 5:
            kit["models"] = [b.i32() for _ in range(b.u8())]  # int model ids (newer caches)
        elif op == 3:
            kit["selectable"] = False
        elif op in (40, 41):
            b.p += 4 * b.u8()  # recolour / retexture (find, replace) pairs
        elif 60 <= op < 70:
            b.u16()  # chathead model
        elif 70 <= op < 80:
            b.i32()  # chathead model, int id (newer caches)
        else:
            raise ValueError("unknown identity kit opcode %d" % op)
    if b.p != len(data):
        raise ValueError("trailing bytes in identity kit")
    return kit


def main():
    if len(sys.argv) > 1:
        cache = next(c for c in json.loads(fetch("/caches.json")) if c["id"] == int(sys.argv[1]))
    else:
        cache = latest_cache()
    cid = cache["id"]
    ids = file_ids(decompress(fetch("/caches/runescape/%d/archives/255/groups/2.dat" % cid)), 3)
    files = split_group(decompress(fetch("/caches/runescape/%d/archives/2/groups/3.dat" % cid)), len(ids))
    kits = {str(i): parse_kit(f) for i, f in zip(ids, files)}
    out = {
        "source": "OpenRS2 cache %d (oldschool live, %s, build %s), index 2 group 3"
                  % (cid, cache.get("timestamp", "?"), [b["major"] for b in cache.get("builds", [])]),
        "kits": kits,
    }
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")
    print("%d kits from cache %d -> %s" % (len(kits), cid, os.path.normpath(OUT)))


if __name__ == "__main__":
    main()
