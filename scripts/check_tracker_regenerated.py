#!/usr/bin/env python3
"""Fail (or warn) if investigation frontmatter changed but the proposed
canonical-bugs table differs from what's currently in BUG_TRACKER.md.

Runs from pre-commit. Gated on at least one `docs/investigations/*.md` being
staged. Compares each staged investigation's frontmatter-derived row against
the current tracker row.

Default is --warn-only: emits drift to stderr but exits 0. Pass --strict
(wired in the pre-commit hook once the tracker is reliably in sync) to fail
the commit.

RESILIENCE: the tracker row is parsed **header-driven** — column→field mapping
is derived from the table's own header row, not hard-coded offsets. This is the
same logic across every repo that vendors this framework (welltracker / iOS /
android / shopify), each of which has a *different* column set/order. It
survives columns being added, removed, or reordered, and honours `\\|`-escaped
pipes in prose cells. The previous hard-coded `parts[-8:]` slice mis-mapped
every field when the 12-column Android schema had a 6-column Title cell that
contained `|` — this cannot recur.
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


def split_cells(line: str) -> list[str]:
    """Split a markdown table row into cells on UNESCAPED pipes only.

    Prose cells frequently contain an escaped pipe (`\\|`); a naive
    `split("|")` shatters those rows and mis-aligns every column. Split on
    `|` not preceded by a backslash, drop the empty leading/trailing boundary
    cells, then unescape `\\|` -> `|` per cell."""
    raw = re.split(r"(?<!\\)\|", line.strip())
    if raw and raw[0].strip() == "":
        raw = raw[1:]
    if raw and raw[-1].strip() == "":
        raw = raw[:-1]
    return [c.strip().replace("\\|", "|") for c in raw]


def _normalize_header(name: str) -> str:
    """Canonicalize a header label: lower-case, strip punctuation, collapse
    whitespace. So `Reg. Of`, `Reg Of`, `Reg.Of` all normalize to `reg of`."""
    n = re.sub(r"[^\w\s]", " ", name.lower())
    return re.sub(r"\s+", " ", n).strip()


HEADER_TO_FIELD = {
    "type": "type",
    "class": "classification",
    "class.": "classification",
    "classification": "classification",
    "found": "found_in",
    "found in": "found_in",
    "fixed": "fixed_in",
    "fixed in": "fixed_in",
    "state": "state",
    "rel": "release_state",
    "release": "release_state",
    "release state": "release_state",
    "reg of": "regression_of",
    "regression of": "regression_of",
    "regof": "regression_of",
}

COMPARE_FIELDS = [
    "type",
    "classification",
    "fixed_in",
    "state",
    "release_state",
    "regression_of",
]


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
    fm = m.group(1)
    out: dict = {}
    for line in fm.splitlines():
        km = re.match(r"^([a-zA-Z_]+):\s*(.*)$", line)
        if km:
            val = km.group(2).strip()
            if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                val = val[1:-1]
            out[km.group(1)] = val
    return out


def _tracker_columns() -> list[str] | None:
    """Return, for each column of the canonical-bugs table, the frontmatter
    field it maps to (or '' to ignore) — derived from the header row."""
    lines = TRACKER.read_text().splitlines()
    has_canon = any(l.strip().lower().startswith("### canonical") for l in lines)
    in_canon = not has_canon
    for line in lines:
        if line.strip().lower().startswith("### canonical"):
            in_canon = True
            continue
        if not in_canon or not line.lstrip().startswith("|"):
            continue
        cells = split_cells(line)
        if cells and _normalize_header(cells[0]) == "id":
            return [HEADER_TO_FIELD.get(_normalize_header(c), "") for c in cells]
    return None


def tracker_row_for(bug_id: str) -> dict | None:
    """Pull the current tracker row for the given bug_id, mapping each cell to
    its frontmatter field via the header-derived column list."""
    columns = _tracker_columns()
    if not columns:
        return None
    needle = f"| `{bug_id}` |"
    for line in TRACKER.read_text().splitlines():
        if line.startswith(needle):
            parts = split_cells(line)
            if len(parts) < len(columns):
                return None
            if len(parts) > len(columns):
                keep = len(columns) - 1
                parts = parts[:keep] + [" | ".join(parts[keep:])]
            return {field: cell for field, cell in zip(columns, parts) if field}
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
            continue
        bug_id = fm.get("bug_id")
        if not bug_id or "XXXX" in bug_id:
            continue
        tracker = tracker_row_for(bug_id)
        if tracker is None:
            drifts.append((bug_id, "<row>", "(present)", "(missing)"))
            continue
        for field in COMPARE_FIELDS:
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
