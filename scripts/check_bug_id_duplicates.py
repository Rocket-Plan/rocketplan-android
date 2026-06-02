#!/usr/bin/env python3
"""Fail if any bug ID appears more than once in docs/BUG_TRACKER.md.

Scope: only the leading-`| `<ID>` ` of a row counts as a row-defining occurrence.
Mentions inside row body or other docs are ignored. Run from pre-commit or CI.
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"

ROW_ID_RE = re.compile(r"^\|\s*`(RP-(?:BUG|FR|HD)-\d+|ROCKET-PLAN-ANDROID-[A-Z0-9]+)`\s*\|")


def main() -> int:
    if not TRACKER.exists():
        print(f"check_bug_id_duplicates: {TRACKER} not found, skipping", file=sys.stderr)
        return 0

    occurrences: dict[str, list[int]] = defaultdict(list)
    for lineno, line in enumerate(TRACKER.read_text().splitlines(), start=1):
        m = ROW_ID_RE.match(line)
        if m:
            occurrences[m.group(1)].append(lineno)

    duplicates = {bug_id: lines for bug_id, lines in occurrences.items() if len(lines) > 1}
    if not duplicates:
        return 0

    print("ERROR: duplicate bug IDs in docs/BUG_TRACKER.md", file=sys.stderr)
    for bug_id, lines in sorted(duplicates.items()):
        loc = ", ".join(f"line {ln}" for ln in lines)
        print(f"  {bug_id}: {loc}", file=sys.stderr)
    print("", file=sys.stderr)
    print("Each canonical ID must appear in exactly one row. Renumber one of them", file=sys.stderr)
    print("to the next free ID in the same prefix series.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
