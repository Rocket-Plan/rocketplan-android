#!/usr/bin/env python3
"""Generate docs/releases/CURRENT_BUG_STATUS.json — an authoritative summary of
BUG_TRACKER.md for fast queries (see bugs.py).

Header-driven parser: reads the table's own header row to build a column index,
then maps cells by normalized header name. Works regardless of column count or order.
"""

import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"
OUTPUT_DIR = ROOT / "docs" / "releases"
OUTPUT_FILE = OUTPUT_DIR / "CURRENT_BUG_STATUS.json"

ID_PATTERN = re.compile(r"^((?:RP-BUG-|RP-FR-|RP-HD-|ROCKET-PLAN-ANDROID-)\d+)")


def _split_cells(line: str) -> list[str]:
    """Split on pipe, strip whitespace, drop leading/trailing empty cells."""
    raw = [c.strip() for c in line.strip().strip("|").split("|")]
    while raw and raw[0] == "":
        raw = raw[1:]
    while raw and raw[-1] == "":
        raw = raw[:-1]
    return raw


def _normalize(name: str) -> str:
    n = name.lower().strip()
    return {
        "class.": "classification",
        "class": "classification",
        "reg. of": "regression_of",
        "regof": "regression_of",
        "regressionof": "regression_of",
        "rel": "release_state",
        "release state": "release_state",
        "type": "type",
        "found": "found_in",
        "found in": "found_in",
        "fixed": "fixed_in",
        "fixed in": "fixed_in",
        "state": "state",
        "id": "id",
        "title": "title",
        "summary": "title",
        "priority": "priority",
        "aliases": "aliases",
        "investigation": "investigation",
        "inv": "investigation",
    }.get(n, n)


# Internal field names we emit (stable across all repos).
CANONICAL_KEYS = [
    "id", "priority", "aliases", "title", "type", "classification",
    "found_in", "fixed_in", "state", "release_state", "regression_of", "investigation",
]


def extract_shipping_rows(text: str):
    rows = []
    in_shipping = False
    for line in text.splitlines():
        if line.strip() == "## Shipping Status":
            in_shipping = True
            continue
        if in_shipping and line.startswith("## ") and line.strip() != "## Shipping Status":
            break
        if in_shipping and line.startswith("|"):
            parts = _split_cells(line)
            if len(parts) == 3 and parts[0] != "Version" and not set(parts[0]).issubset({"-"}):
                rows.append(
                    {
                        "version": parts[0].replace("**", ""),
                        "build": parts[1],
                        "status": parts[2],
                    }
                )
    return rows


def extract_bug_rows(text: str):
    rows = []
    in_registry = False
    colindex: dict[str, int] = {}
    id_col: int | None = None
    title_col: int | None = None
    trailing_start: int | None = None

    for line in text.splitlines():
        if line.strip() == "### Canonical Bugs":
            in_registry = True
            continue
        if not in_registry:
            continue

        if line.startswith("| ") and "---" not in line and colindex == {}:
            header_cells = _split_cells(line)
            colindex = {(_normalize(c)): i for i, c in enumerate(header_cells)}
            id_col = colindex.get("id")
            title_col = colindex.get("title")
            trailing_start = len(header_cells) - 8 if len(header_cells) >= 8 else None
            continue

        if not line.startswith("| `") or id_col is None or trailing_start is None:
            continue

        parts = _split_cells(line)
        if len(parts) < 3:
            continue

        bug_id_cell = parts[id_col]
        m = ID_PATTERN.match(bug_id_cell.replace("`", "").strip())
        if not m:
            continue
        bug_id = m.group(1)

        priority = colindex.get("priority")
        aliases = colindex.get("aliases")
        type_col = colindex.get("type")
        class_col = colindex.get("classification")
        found_col = colindex.get("found_in")
        fixed_col = colindex.get("fixed_in")
        state_col = colindex.get("state")
        rel_col = colindex.get("release_state")
        regof_col = colindex.get("regression_of")
        inv_col = colindex.get("investigation")

        title = " | ".join(parts[title_col:trailing_start]) if title_col is not None else ""
        trailing_vals = parts[trailing_start:]

        row = {"id": bug_id}
        if priority is not None and priority < len(parts):
            row["priority"] = parts[priority]
        if aliases is not None and aliases < len(parts):
            row["aliases"] = parts[aliases]
        row["title"] = title
        if type_col is not None and type_col < len(parts):
            row["type"] = parts[type_col]
        if class_col is not None and class_col < len(parts):
            row["classification"] = parts[class_col]
        if found_col is not None and found_col < len(parts):
            row["found_in"] = parts[found_col]
        if fixed_col is not None and fixed_col < len(parts):
            row["fixed_in"] = parts[fixed_col]
        if state_col is not None and state_col < len(parts):
            row["state"] = parts[state_col]
        if rel_col is not None and rel_col < len(parts):
            row["release_state"] = parts[rel_col]
        if regof_col is not None and regof_col < len(parts):
            row["regression_of"] = parts[regof_col]
        if inv_col is not None and inv_col < len(parts):
            row["investigation"] = parts[inv_col]

        rows.append(row)
    return rows


def normalize_state(state: str) -> str:
    s = state.lower()
    for prefix in [
        "investigating", "planned", "open", "monitoring",
        "partially_fixed", "deferred", "superseded", "new",
        "fixed", "closed",
    ]:
        if s.startswith(prefix):
            return prefix
    return s


def normalize_release_state(release_state: str) -> str:
    s = release_state.lower()
    for prefix in ["released", "unreleased", "n/a"]:
        if s.startswith(prefix):
            return prefix
    return s


ACTIVE_STATES = {"investigating", "open", "planned", "monitoring", "partially_fixed", "deferred", "new"}


def bug_summary(bug: dict):
    return {
        "id": bug["id"],
        "priority": bug["priority"],
        "state": bug["state"],
        "release_state": bug["release_state"],
        "fixed_in": bug["fixed_in"],
        "found_in": bug["found_in"],
        "title": bug["title"],
    }


def main():
    text = TRACKER.read_text()
    shipping_rows = extract_shipping_rows(text)
    bug_rows = extract_bug_rows(text)

    released_rows = [row for row in shipping_rows if row["status"].startswith("✅") or "Released" in row["status"]]
    current_release = released_rows[-1] if released_rows else None
    current_version = current_release["version"] if current_release else None

    line_fixed_bugs = []
    line_active_bugs = []
    global_active_bugs = []

    for bug in bug_rows:
        norm_state = normalize_state(bug["state"])
        norm_release = normalize_release_state(bug["release_state"])

        if norm_release == "unreleased" or norm_state in ACTIVE_STATES:
            global_active_bugs.append(bug_summary(bug))

        if current_version and current_version in bug["fixed_in"]:
            line_fixed_bugs.append(bug_summary(bug))

        if current_version and (
            current_version in bug["found_in"]
            or current_version in bug["fixed_in"]
            or current_version in bug["release_state"]
        ):
            if norm_release == "unreleased" or norm_state in ACTIVE_STATES:
                line_active_bugs.append(bug_summary(bug))

    global_state_counts = Counter(normalize_state(b["state"]) for b in bug_rows)
    global_release_counts = Counter(normalize_release_state(b["release_state"]) for b in bug_rows)
    priority_counts = Counter(b["priority"] for b in global_active_bugs)

    payload = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "source_tracker": str(TRACKER.relative_to(ROOT)),
        "current_shipping_release": current_release,
        "counts": {
            "all_canonical_bugs": len(bug_rows),
            "global_state_counts": dict(global_state_counts),
            "global_release_state_counts": dict(global_release_counts),
            "global_active_priority_counts": dict(priority_counts),
            "current_release_line_fixed_count": len(line_fixed_bugs),
            "current_release_line_active_count": len(line_active_bugs),
            "global_active_count": len(global_active_bugs),
        },
        "current_release_line": {
            "version": current_version,
            "fixed_bugs": line_fixed_bugs,
            "active_followups": line_active_bugs,
        },
        "global_active_bugs": global_active_bugs,
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"Wrote {OUTPUT_FILE.relative_to(ROOT)} ({len(bug_rows)} canonical bugs, {len(global_active_bugs)} active)")


if __name__ == "__main__":
    main()
