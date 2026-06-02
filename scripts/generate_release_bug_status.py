#!/usr/bin/env python3
"""Generate docs/releases/CURRENT_BUG_STATUS.json — an authoritative summary of
BUG_TRACKER.md for fast queries (see bugs.py).

Android Canonical Bugs schema (12 columns):
  | ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation |
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

CANONICAL_PREFIXES = ("RP-BUG-", "RP-FR-", "RP-HD-", "ROCKET-PLAN-ANDROID-")


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
            parts = [p.strip() for p in line.strip().strip("|").split("|")]
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
    for line in text.splitlines():
        if line.strip() == "### Canonical Bugs":
            in_registry = True
            continue
        if not in_registry:
            continue
        if not line.startswith("| `"):
            continue

        parts = [p.strip() for p in line.strip().strip("|").split("|")]
        if len(parts) < 12:
            continue

        candidate_id = parts[0].replace("`", "").strip()
        if not candidate_id.startswith(CANONICAL_PREFIXES):
            continue

        # Stable trailing 8 cells; title may contain `|`.
        bug_id, priority, aliases = parts[0], parts[1], parts[2]
        trailing = parts[-8:]
        title = " | ".join(parts[3:-8])
        bug_type, classification, found_in, fixed_in, state, release_state, regression_of, investigation = trailing

        rows.append(
            {
                "id": bug_id.replace("`", "").strip(),
                "priority": priority,
                "aliases": aliases,
                "title": title,
                "type": bug_type,
                "classification": classification,
                "found_in": found_in,
                "fixed_in": fixed_in,
                "state": state,
                "release_state": release_state,
                "regression_of": regression_of,
                "investigation": investigation,
            }
        )
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
