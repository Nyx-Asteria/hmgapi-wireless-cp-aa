#!/usr/bin/env python3
"""Conservative stock-offset guard against CPUL's pinned CarPlayCore ownership.
Not a substitute for matched-pin audit or HMG-API runtime reservation checks.
"""
import json
from pathlib import Path

PIN = "e3f5772829d0fd23efa78cf8c8e35a20cb4525e5"
# source/mod/recipes/carplay-climate-m1.recipe at PIN; half-open ranges.
CPUL_VERIFY = [(0x9c18, 20), (0xaf08, 20), (0x10eec, 17),
               (0x11710, 20), (0x12428, 20), (0x12542, 17),
               (0x1255c, 17), (0x12578, 17)]
CPUL_WRITE = [(0x9c20, 4), (0xaf10, 4), (0x10ef4, 1),
              (0x11718, 4), (0x12430, 4), (0x1254a, 1),
              (0x12564, 1), (0x12580, 1)]

def regions(source):
    result = []
    for raw in source.splitlines():
        fields = raw.split()
        if not fields or fields[0].startswith("#"):
            continue
        op = fields[0]
        if op in ("verify", "patch", "branch"):
            start = int(fields[1], 0)
            size = (int(fields[2], 0) if op == "verify" else
                    len(bytes.fromhex(fields[2])) if op == "patch" else 4)
            if start < 0 or size <= 0:
                raise ValueError("invalid region")
            result.append((start, size, op))
        elif op not in ("id", "file", "native", "segment", "rel32"):
            raise ValueError("unrecognized recipe directive: " + op)
    if not any(r[2] == "verify" for r in result):
        raise ValueError("missing verification region")
    return result

def conflicts(source):
    return [(start, size, op, cp, n)
            for start, size, op in regions(source)
            for cp, n in CPUL_VERIFY + CPUL_WRITE
            if max(start, cp) < min(start + size, cp + n)]

def main():
    # Disjoint writes can still collide when verification regions overlap.
    assert conflicts("verify 0x1170c 8 x -\npatch 0x1170c 00")
    assert conflicts("verify 0x11718 4 x -\nbranch 0x11718 0x0")
    assert conflicts("verify 0x9c00 4 x -\npatch 0x9c20 00")
    assert not conflicts("verify 0x11724 4 x -\npatch 0x11724 00")
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "mod.json").read_text())
    recipes = manifest["hooks"]["native"]["libCarPlayCore.so"]
    if not recipes:
        raise SystemExit("missing CarPlayCore recipe declaration")
    for name in recipes:
        hits = conflicts((root / name).read_text())
        if hits:
            raise SystemExit(f"{name}: overlaps CPUL {PIN}: {hits}")
        print(f"{name}: no stock-region collision with CPUL {PIN}")

if __name__ == "__main__":
    main()
