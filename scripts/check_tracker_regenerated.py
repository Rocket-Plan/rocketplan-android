#!/usr/bin/env python3
"""Fail (or warn) if investigation frontmatter changed but the BUG_TRACKER.md
row for that bug differs from the frontmatter.

Runs from pre-commit. Gated on at least one `docs/investigations/*.md` being
staged. Compares each staged investigation's frontmatter-derived fields against
the current tracker row.

Default is --warn-only: emits drift to stderr but exits 0. Pass --strict (wired
in the pre-commit hook once the tracker is reliably in sync) to fail the commit.

Android tracker Canonical Bugs schema (12 columns):
  | ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation |
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"
INV_DIR = ROOT / "docs" / "investigations"

# Frontmatter field -> tracker column index (after splitting a row on `|` and
# stripping the outer empties). Title is handled separately (it may contain `|`).
COL = {
    "type": 4,
    "classification": 5,
    "found_in": 6,
    "fixed_in": 7,
    "state": 8,
    "release_state": 9,
    "regression_of": 10,
}
TRAILING_FIELDS = ["type", "classification", "found_in", "fixed_in", "state", "release_state", "regression_of"]


def staged_investigations() -> list[Path]:
    res = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=AM"],
        check=False,
        capture_output=True,
        text=True,
    )
    paths = []
    for line in res.stdout.splitlines():
        if line.startswith("docs/investigations/") and line.endswith(".md") and not line.endswith("/TEMPLATE.md"):
            p = ROOT / line
            if p.exists():
                paths.append(p)
    return paths


def parse_frontmatter(text: str) -> dict | None:
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return None
    out: dict = {}
    for line in m.group(1).splitlines():
        km = re.match(r"^([a-zA-Z_]+):\s*(.*)$", line)
        if km:
            val = km.group(2).strip()
            if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                val = val[1:-1]
            out[km.group(1)] = val
    return out


def tracker_row_for(bug_id: str) -> dict | None:
    needle = f"| `{bug_id}` |"
    for line in TRACKER.read_text().splitlines():
        if line.startswith(needle):
            parts = [p.strip() for p in line.strip().strip("|").split("|")]
            if len(parts) < 12:
                return None
            # title may contain `|`; the trailing 8 cells are stable
            trailing = parts[-8:]  # type, class, found, fixed, state, rel, regof, investigation
            return {
                "type": trailing[0],
                "classification": trailing[1],
                "found_in": trailing[2],
                "fixed_in": trailing[3],
                "state": trailing[4],
                "release_state": trailing[5],
                "regression_of": trailing[6],
            }
    return None


def norm(v: str | None) -> str:
    if v is None:
        return ""
    s = v.strip()
    if s in {"null", "None", "—", "-", "n/a", "[]"}:
        return ""
    if s.startswith("`") and s.endswith("`") and s.count("`") == 2:
        s = s[1:-1]
    return s


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--strict", action="store_true", help="exit 1 on drift (default: warn)")
    args = parser.parse_args()

    staged = staged_investigations()
    if not staged:
        return 0

    drifts: list[tuple[str, str, str, str]] = []
    for p in staged:
        fm = parse_frontmatter(p.read_text())
        if not fm:
            continue
        if fm.get("type") == "meta":
            continue
        if "alias_of" in fm:
            continue
        if "bug_ids" in fm and "bug_id" not in fm:
            continue  # cluster doc
        bug_id = fm.get("bug_id")
        if not bug_id or "XXXX" in bug_id:
            continue
        tracker = tracker_row_for(bug_id)
        if tracker is None:
            drifts.append((bug_id, "<row>", "(present)", "(missing)"))
            continue
        for field in TRAILING_FIELDS:
            fm_val = norm(fm.get(field, ""))
            tr_val = norm(tracker.get(field, ""))
            if fm_val != tr_val:
                drifts.append((bug_id, field, fm_val or "(empty)", tr_val or "(empty)"))

    if not drifts:
        return 0

    label = "ERROR" if args.strict else "WARN"
    print(f"{label}: investigation frontmatter drifted from BUG_TRACKER.md row.", file=sys.stderr)
    for bug_id, field, fm_val, tr_val in drifts:
        print(f"  {bug_id}.{field}: frontmatter={fm_val!r}  tracker={tr_val!r}", file=sys.stderr)
    print("", file=sys.stderr)
    if args.strict:
        print("Update docs/BUG_TRACKER.md (or revert the frontmatter edit) before committing.", file=sys.stderr)
        return 1
    print("(non-blocking; pass --strict in the hook to fail on drift)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
