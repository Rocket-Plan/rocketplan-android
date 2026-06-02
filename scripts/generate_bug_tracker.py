#!/usr/bin/env python3
"""Generate a *proposed* `### Canonical Bugs` table from investigation frontmatter.

Drift detector + optional regenerator. Per-bug investigation frontmatter is the
source of truth for every column EXCEPT Priority (which is a triage judgement not
stored in frontmatter) — Priority is preserved from the existing tracker row.

Dry-run by default: writes the proposed table to
docs/releases/_canonical_bugs_proposed.md (gitignored) so you can eyeball drift.
Pass --apply to splice it into docs/BUG_TRACKER.md in place of the existing table.

Android Canonical Bugs schema (12 columns):
  | ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation |
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVDIR = ROOT / "docs" / "investigations"
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"

CANONICAL_HEADER = "### Canonical Bugs"
ID_ALT = r"RP-(?:BUG|FR|HD)-\d+|ROCKET-PLAN-ANDROID-[A-Z0-9]+"

COL_ORDER = [
    "id", "priority", "aliases", "title", "type", "classification",
    "found_in", "fixed_in", "state", "release_state", "regression_of", "investigation",
]


def parse_frontmatter(text: str) -> dict | None:
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return None
    out: dict = {}
    for line in m.group(1).splitlines():
        km = re.match(r"^([a-zA-Z_]+):\s*(.*)$", line)
        if not km:
            continue
        key, val = km.group(1), km.group(2).strip()
        if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
            val = val[1:-1]
        out[key] = val
    return out


def render_cell(val: str) -> str:
    if not val or val in {"null", "None", "—", "-", "[]"}:
        return "—"
    return val.replace("|", "\\|")


def existing_priorities() -> dict[str, str]:
    """Map bug_id -> current Priority cell from the tracker (preserved on regen)."""
    out: dict[str, str] = {}
    row_re = re.compile(rf"^\|\s*`({ID_ALT})`\s*\|\s*([^|]*?)\s*\|")
    for line in TRACKER.read_text().splitlines():
        m = row_re.match(line)
        if m:
            out[m.group(1)] = m.group(2).strip() or "—"
    return out


def sort_key(bug_id: str) -> tuple:
    order = {"ROCKET-PLAN-ANDROID-": 0, "RP-BUG-": 1, "RP-FR-": 2, "RP-HD-": 3}
    for prefix, rank in order.items():
        if bug_id.startswith(prefix):
            tail = bug_id.split("-")[-1]
            return (rank, int(tail) if tail.isdigit() else 99999, bug_id)
    return (9, 99999, bug_id)


def collect_rows() -> list[dict]:
    priorities = existing_priorities()
    rows = []
    valid_id = re.compile(rf"^({ID_ALT})$")
    for p in sorted(INVDIR.glob("*.md")):
        if p.stem == "TEMPLATE":
            continue
        fm = parse_frontmatter(p.read_text())
        if not fm:
            continue
        if fm.get("type") == "meta" or "alias_of" in fm:
            continue
        if "bug_ids" in fm and "bug_id" not in fm:
            continue
        bug_id = fm.get("bug_id")
        if not bug_id or "XXXX" in bug_id or not valid_id.match(bug_id):
            continue  # skip template / placeholder / malformed IDs
        rows.append({
            "id": f"`{bug_id}`",
            "priority": priorities.get(bug_id, "—"),
            "aliases": render_cell(fm.get("aliases", "")),
            "title": render_cell(fm.get("title", "")),
            "type": render_cell(fm.get("type", "")),
            "classification": render_cell(fm.get("classification", "")),
            "found_in": render_cell(fm.get("found_in", "")),
            "fixed_in": render_cell(fm.get("fixed_in", "")),
            "state": render_cell(fm.get("state", "")),
            "release_state": render_cell(fm.get("release_state", "")),
            "regression_of": render_cell(fm.get("regression_of", "")),
            "investigation": f"[{bug_id}](investigations/{p.name})",
            "_raw": bug_id,
        })
    rows.sort(key=lambda r: sort_key(r["_raw"]))
    return rows


def render_table(rows: list[dict]) -> str:
    lines = [
        "| ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation |",
        "|----|----------|---------|-------|------|--------|-------|-------|-------|-----|---------|---------------|",
    ]
    for r in rows:
        lines.append("| " + " | ".join(r[c] for c in COL_ORDER) + " |")
    return "\n".join(lines) + "\n"


def splice_table(tracker_text: str, new_table: str) -> str:
    lines = tracker_text.splitlines(keepends=True)
    start_idx = end_idx = None
    for i, line in enumerate(lines):
        if line.strip() == CANONICAL_HEADER:
            start_idx = i + 1
            continue
        if start_idx is not None and line.startswith("### ") and line.strip() != CANONICAL_HEADER:
            end_idx = i
            break
    if start_idx is None:
        raise SystemExit(f"Could not find `{CANONICAL_HEADER}` in tracker")
    if end_idx is None:
        end_idx = len(lines)
    table_start = table_end = None
    in_table = False
    for j in range(start_idx, end_idx):
        if lines[j].startswith("|"):
            if table_start is None:
                table_start = j
            table_end = j + 1
            in_table = True
        elif in_table and lines[j].strip():
            break
    if table_start is None:
        raise SystemExit("Could not find the Canonical Bugs table rows")
    pre = "".join(lines[:table_start])
    post = "".join(lines[table_end:])
    return pre + new_table + post


def main() -> int:
    apply = "--apply" in sys.argv
    rows = collect_rows()
    print(f"Collected {len(rows)} canonical rows from investigation frontmatter")
    new_table = render_table(rows)
    new_tracker = splice_table(TRACKER.read_text(), new_table)
    if apply:
        TRACKER.write_text(new_tracker)
        print(f"Wrote {TRACKER.relative_to(ROOT)}")
    else:
        out_dir = ROOT / "docs" / "releases"
        out_dir.mkdir(parents=True, exist_ok=True)
        diff_path = out_dir / "_canonical_bugs_proposed.md"
        diff_path.write_text(new_table)
        print(f"(dry-run) wrote proposed table to {diff_path.relative_to(ROOT)}")
        print("Diff it against the tracker; pass --apply to splice it in.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
