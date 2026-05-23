#!/usr/bin/env python3
"""Check if a Kotlin file's transitive `dev.lyo.hortay.*` imports are all in commonMain.

Walks each input file. Extracts `import dev.lyo.hortay.X.Y.Z` lines. For each,
checks whether the referenced symbol is defined in shared/src/commonMain/ or
shared/src/androidMain/. Reports files whose every import resolves to commonMain
(so they're safe to move) versus files that still reference an androidMain symbol.
"""

import os
import re
import sys
from pathlib import Path

REPO = Path("shared/src")
COMMON = REPO / "commonMain" / "kotlin"
ANDROID = REPO / "androidMain" / "kotlin"

DECL = re.compile(r"^\s*(public\s+|internal\s+|private\s+|protected\s+)?"
                  r"(open\s+|abstract\s+|sealed\s+|data\s+|enum\s+|inline\s+|expect\s+|actual\s+)*"
                  r"(class|object|interface|fun|val|var|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)",
                  re.MULTILINE)


def index_source_set(root: Path) -> dict[str, str]:
    """Return {short_symbol_name: file_path} for declarations under root."""
    idx: dict[str, str] = {}
    if not root.exists():
        return idx
    for f in root.rglob("*.kt"):
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for m in DECL.finditer(text):
            sym = m.group(4)
            idx.setdefault(sym, str(f))
    return idx


def main():
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        print("usage: check-portable.py <file.kt> [...]")
        sys.exit(1)

    common_idx = index_source_set(COMMON)
    android_idx = index_source_set(ANDROID)

    portable = []
    blocked = {}

    for f in files:
        if not f.exists():
            continue
        text = f.read_text(encoding="utf-8")
        imports = re.findall(r"^import (dev\.lyo\.hortay\.[A-Za-z_][A-Za-z0-9_.]*)$", text, re.MULTILINE)
        blockers: list[str] = []
        for imp in imports:
            sym = imp.rsplit(".", 1)[-1]
            # Skip if symbol resolves to commonMain
            if sym in common_idx:
                continue
            # Skip if symbol is in same file's source set (current file's containing set)
            if sym in android_idx:
                blockers.append(f"{imp}  ->  {android_idx[sym]}")
        if not blockers:
            portable.append(str(f))
        else:
            blocked[str(f)] = blockers

    print("=== PORTABLE ({}) ===".format(len(portable)))
    for p in portable:
        print(p)
    print()
    print("=== BLOCKED ({}) ===".format(len(blocked)))
    for f, bs in blocked.items():
        print(f)
        for b in bs:
            print(f"   {b}")


if __name__ == "__main__":
    main()
