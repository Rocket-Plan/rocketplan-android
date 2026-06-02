#!/usr/bin/env python3
"""Pre-commit guard: reject a Room @Database version bump that has no matching
registered Migration. The Android counterpart to the iOS Core Data RP-CD-033
hook — and exactly the failure mode of RP-BUG-016 (version 27→28 with no
MIGRATION_27_28, which crashes non-dev upgrades now that destructive fallback
is off in staging/prod).

Runs only when the Room database file is staged. For every version step between
the previously-committed version and the staged version, requires that a
`MIGRATION_<n-1>_<n>` object both EXISTS and is passed to `addMigrations(...)`
in the staged file. Exits non-zero (blocking) if any are missing.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DB_FILE_REL = "app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt"


def staged_names() -> list[str]:
    res = subprocess.run(
        ["git", "diff", "--cached", "--name-only"],
        check=False, capture_output=True, text=True,
    )
    return res.stdout.splitlines()


def git_show(ref_path: str) -> str | None:
    res = subprocess.run(["git", "show", ref_path], check=False, capture_output=True, text=True)
    return res.stdout if res.returncode == 0 else None


def db_version(text: str) -> int | None:
    m = re.search(r"@Database\s*\(", text)
    if not m:
        return None
    vm = re.search(r"version\s*=\s*(\d+)", text[m.end():])
    return int(vm.group(1)) if vm else None


def add_migrations_block(text: str) -> str:
    m = re.search(r"\.addMigrations\s*\(", text)
    if not m:
        return ""
    # capture balanced-ish up to the closing paren on the same call
    depth, i = 1, m.end()
    while i < len(text) and depth:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
        i += 1
    return text[m.end():i]


def main() -> int:
    if DB_FILE_REL not in staged_names():
        return 0

    staged = git_show(f":{DB_FILE_REL}")
    if staged is None:
        return 0
    head = git_show(f"HEAD:{DB_FILE_REL}")

    new_v = db_version(staged)
    old_v = db_version(head) if head else None
    if new_v is None:
        return 0
    if old_v is None or new_v <= old_v:
        return 0  # new file, no bump, or rollback — nothing to enforce here

    migrations_block = add_migrations_block(staged)
    missing = []
    for n in range(old_v + 1, new_v + 1):
        name = f"MIGRATION_{n - 1}_{n}"
        defined = re.search(rf"\b(val|private val)\s+{name}\b", staged) or re.search(rf"\bobject\s*:\s*Migration\({n - 1},\s*{n}\)", staged)
        registered = name in migrations_block
        if not (defined and registered):
            missing.append((name, bool(defined), registered))

    if not missing:
        return 0

    print(f"ERROR: Room @Database version bumped {old_v} -> {new_v} without a complete migration (RP-BUG-016 class).", file=sys.stderr)
    print("", file=sys.stderr)
    for name, defined, registered in missing:
        why = []
        if not defined:
            why.append("not defined")
        if not registered:
            why.append("not in addMigrations(...)")
        print(f"  {name}: {', '.join(why)}", file=sys.stderr)
    print("", file=sys.stderr)
    print("Add a Migration object for each step (a no-op body is fine for a pure version", file=sys.stderr)
    print("bump) and register it in .addMigrations(...). Non-dev builds no longer fall", file=sys.stderr)
    print("back to destructive migration, so a missing step crashes on upgrade.", file=sys.stderr)
    print("Use --no-verify only if you are certain and have documented why.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
