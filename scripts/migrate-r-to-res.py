#!/usr/bin/env python3
"""Bulk-migrate Android R.* references to CMP Res.* references.

Walks each input .kt file and rewrites:
  R.string.foo          -> Res.string.foo
  R.drawable.foo        -> Res.drawable.foo
  R.plurals.foo         -> Res.plurals.foo
  R.array.foo           -> Res.array.foo
  stringResource(R.string.foo, ...)         -> stringResource(Res.string.foo, ...)
  painterResource(R.drawable.foo)           -> painterResource(Res.drawable.foo)
  pluralStringResource(R.plurals.foo, n, n) -> pluralStringResource(Res.plurals.foo, n, n)
  stringArrayResource(R.array.foo)          -> stringArrayResource(Res.array.foo)

Updates imports:
  - removes `import dev.lyo.hortay.R`
  - removes `import androidx.compose.ui.res.{stringResource,painterResource,pluralStringResource,stringArrayResource}`
  - adds   `import org.jetbrains.compose.resources.{stringResource,painterResource,pluralStringResource,stringArrayResource}` (only those used)
  - adds   `import hortay.shared.generated.resources.Res`
  - adds   `import hortay.shared.generated.resources.<name>` per referenced symbol (sorted)

Skip rules:
  - any file containing `R.array.com_google_android_gms_fonts_certs` is skipped (Google Fonts cert path stays Android).
"""

import re
import sys
from pathlib import Path

R_REF = re.compile(r"\bR\.(string|drawable|plurals|array)\.([A-Za-z_][A-Za-z0-9_]*)")
SKIP_TOKEN = "com_google_android_gms_fonts_certs"

CMP_FUNCS = {
    "stringResource",
    "painterResource",
    "pluralStringResource",
    "stringArrayResource",
}

ANDROID_RES_IMPORT_PREFIX = "import androidx.compose.ui.res."


def migrate(path: Path) -> bool:
    src = path.read_text(encoding="utf-8")
    if SKIP_TOKEN in src:
        print(f"  skip (google-fonts certs): {path}")
        return False

    refs = R_REF.findall(src)
    if not refs:
        return False

    # collect (kind, name)
    used_names = sorted({name for _, name in refs})

    # rewrite refs
    new_src = R_REF.sub(lambda m: f"Res.{m.group(1)}.{m.group(2)}", src)

    # rewrite Android compose.ui.res imports for functions we use
    # find which CMP funcs are referenced anywhere in the file
    cmp_used = set()
    for func in CMP_FUNCS:
        if re.search(rf"\b{func}\b", new_src):
            cmp_used.add(func)

    lines = new_src.split("\n")
    out_lines = []
    res_imports_inserted = False
    import_block_started = False
    package_line_idx = None
    last_import_idx = -1

    # First pass: remove android R import and android compose.ui.res imports
    cleaned = []
    for line in lines:
        stripped = line.strip()
        if stripped == "import dev.lyo.hortay.R":
            continue
        if stripped.startswith(ANDROID_RES_IMPORT_PREFIX):
            # only drop if the imported symbol is one we'll add a CMP variant of
            sym = stripped[len(ANDROID_RES_IMPORT_PREFIX):]
            if sym in CMP_FUNCS:
                continue
        cleaned.append(line)
    lines = cleaned

    # Second pass: find insertion point (after last import) and inject new imports
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("package "):
            package_line_idx = i
        if stripped.startswith("import "):
            last_import_idx = i

    new_imports = []
    new_imports.append("import hortay.shared.generated.resources.Res")
    for name in used_names:
        new_imports.append(f"import hortay.shared.generated.resources.{name}")
    for func in sorted(cmp_used):
        new_imports.append(f"import org.jetbrains.compose.resources.{func}")

    # dedup against existing imports
    existing = {l.strip() for l in lines if l.strip().startswith("import ")}
    new_imports = [imp for imp in new_imports if imp not in existing]

    if new_imports:
        if last_import_idx >= 0:
            insert_at = last_import_idx + 1
        elif package_line_idx is not None:
            insert_at = package_line_idx + 2  # skip blank line
            if insert_at > len(lines):
                insert_at = len(lines)
        else:
            insert_at = 0
        lines = lines[:insert_at] + new_imports + lines[insert_at:]

    path.write_text("\n".join(lines), encoding="utf-8")
    return True


def main():
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        print("usage: migrate-r-to-res.py <file1.kt> [file2.kt ...]")
        sys.exit(1)
    changed = 0
    for f in files:
        if not f.exists():
            print(f"  missing: {f}")
            continue
        if migrate(f):
            changed += 1
            print(f"  migrated: {f}")
    print(f"done: {changed}/{len(files)} files migrated")


if __name__ == "__main__":
    main()
