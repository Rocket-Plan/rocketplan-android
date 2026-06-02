#!/usr/bin/env python3
"""bugs.py — fast queries against docs/BUG_TRACKER.md status.

Reads docs/releases/CURRENT_BUG_STATUS.json (the authoritative summary).
If it's stale, regenerate first:  python3 scripts/generate_release_bug_status.py

Subcommands:
  active                          List globally active bugs (open/planned/investigating/etc.)
  unreleased                      List bugs whose fix is on branch but not shipped
  for-release [VERSION]           List bugs tied to a specific version (default: current shipping)
  find <substring>                Substring-search bug titles (case-insensitive)
  touched-by <commit-or-sha>      Rows whose fixed_in references the given commit/SHA
  by-state [STATE]                Group active bugs by state value
  by-priority [P0|P1|P2|P3]       Filter / group active bugs by priority
  since <YYYY-MM-DD>              Bugs whose investigation filename date is on/after the date
  slack-digest                    Markdown digest for Slack
  csv                             CSV of every canonical bug

Add --json for raw JSON output; otherwise prints an aligned table.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
STATUS_JSON = ROOT / "docs" / "releases" / "CURRENT_BUG_STATUS.json"
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"

ID_ALT = r"RP-(?:BUG|FR|HD)-\d+|ROCKET-PLAN-ANDROID-[A-Z0-9]+"


def die(msg: str) -> None:
    print(msg, file=sys.stderr)
    sys.exit(2)


def load_status() -> dict:
    if not STATUS_JSON.exists():
        die(
            f"missing {STATUS_JSON.relative_to(ROOT)}\n"
            f"run: python3 scripts/generate_release_bug_status.py"
        )
    return json.loads(STATUS_JSON.read_text())


def truncate(text: str, width: int) -> str:
    text = text.replace("\n", " ").strip()
    return text if len(text) <= width else text[: width - 1] + "…"


def print_table(rows: list[dict], columns: list[tuple[str, str, int]]) -> None:
    if not rows:
        print("(no matches)")
        return
    print("  ".join(h.ljust(w) for _, h, w in columns))
    print("  ".join("-" * w for _, _, w in columns))
    for row in rows:
        print("  ".join(truncate(str(row.get(k, "") or ""), w).ljust(w) for k, _, w in columns))


def print_output(rows: list[dict], as_json: bool, columns: list[tuple[str, str, int]]) -> None:
    if as_json:
        json.dump(rows, sys.stdout, indent=2)
        print()
    else:
        print_table(rows, columns)


COLUMNS_DEFAULT = [
    ("id", "ID", 24),
    ("priority", "PRI", 4),
    ("state", "STATE", 14),
    ("release_state", "REL", 11),
    ("fixed_in", "FIXED_IN", 16),
    ("title", "TITLE", 64),
]


def _all_bugs(data: dict) -> list[dict]:
    seen, out = set(), []
    for b in (
        data["current_release_line"]["fixed_bugs"]
        + data["current_release_line"]["active_followups"]
        + data["global_active_bugs"]
    ):
        if b["id"] in seen:
            continue
        seen.add(b["id"])
        out.append(b)
    return out


def cmd_active(args):
    data = load_status()
    rows = sorted(data.get("global_active_bugs", []), key=lambda b: b["id"])
    print_output(rows, args.json, COLUMNS_DEFAULT)
    return 0


def cmd_unreleased(args):
    data = load_status()
    rows = [b for b in data.get("global_active_bugs", []) if (b.get("release_state", "") or "").lower().startswith("unreleased")]
    print_output(sorted(rows, key=lambda b: b["id"]), args.json, COLUMNS_DEFAULT)
    return 0


def cmd_for_release(args):
    data = load_status()
    version = args.version or (data.get("current_shipping_release") or {}).get("version")
    if not version:
        die("no version provided and current_shipping_release missing from status JSON")
    seen, rows = set(), []
    for b in _all_bugs(data):
        if b["id"] in seen:
            continue
        if version in (b.get("fixed_in") or "") or version in (b.get("found_in") or ""):
            seen.add(b["id"])
            rows.append(b)
    print(f"# Bugs tagged for {version}\n")
    print_output(sorted(rows, key=lambda b: b["id"]), args.json, COLUMNS_DEFAULT)
    return 0


def cmd_find(args):
    data = load_status()
    needle = args.substring.lower()
    rows = [b for b in _all_bugs(data) if needle in (b.get("title", "") or "").lower()]
    print_output(sorted(rows, key=lambda b: b["id"]), args.json, COLUMNS_DEFAULT)
    return 0


def cmd_touched_by(args):
    sha = args.sha.lower()
    if len(sha) < 4:
        die("commit SHA must be at least 4 characters")
    pattern = re.compile(rf"`{re.escape(sha)}[0-9a-f]*`", re.IGNORECASE)
    row_re = re.compile(rf"^\|\s*`({ID_ALT})`\s*\|(.+)\|\s*$")
    rows = []
    for line in TRACKER.read_text().splitlines():
        m = row_re.match(line)
        if not m:
            continue
        if pattern.search(m.group(2)):
            parts = [p.strip() for p in m.group(2).split("|")]
            rows.append({"id": m.group(1), "title": parts[2] if len(parts) > 2 else m.group(2)})
    print_output(sorted(rows, key=lambda b: b["id"]), args.json, [("id", "ID", 24), ("title", "TITLE", 100)])
    return 0


def cmd_by_state(args):
    data = load_status()
    by_state: dict[str, list[dict]] = {}
    for b in data.get("global_active_bugs", []):
        st = ((b.get("state") or "—").lower().split() or ["—"])[0]
        by_state.setdefault(st, []).append(b)
    if args.state:
        target = args.state.lower()
        matches = [b for st, items in by_state.items() if st.startswith(target) for b in items]
        print_output(sorted(matches, key=lambda b: b["id"]), args.json, COLUMNS_DEFAULT)
        return 0
    if args.json:
        json.dump(by_state, sys.stdout, indent=2); print(); return 0
    for st in sorted(by_state):
        print(f"\n## {st} ({len(by_state[st])})")
        print_table(sorted(by_state[st], key=lambda b: b["id"]), COLUMNS_DEFAULT)
    return 0


def cmd_by_priority(args):
    data = load_status()
    rows = data.get("global_active_bugs", [])
    if args.priority:
        target = args.priority.upper()
        rows = [b for b in rows if (b.get("priority") or "").upper() == target]
        print_output(sorted(rows, key=lambda b: b["id"]), args.json, COLUMNS_DEFAULT)
        return 0
    by_pri: dict[str, list[dict]] = {}
    for b in rows:
        by_pri.setdefault((b.get("priority") or "—").upper(), []).append(b)
    if args.json:
        json.dump(by_pri, sys.stdout, indent=2); print(); return 0
    for pri in sorted(by_pri):
        print(f"\n## {pri} ({len(by_pri[pri])})")
        print_table(sorted(by_pri[pri], key=lambda b: b["id"]), COLUMNS_DEFAULT)
    return 0


def cmd_since(args):
    import datetime as _dt
    try:
        cutoff = _dt.date.fromisoformat(args.date)
    except ValueError:
        die("date must be YYYY-MM-DD")
    invdir = ROOT / "docs" / "investigations"
    date_re = re.compile(r"_(\d{4}-\d{2}-\d{2})\.md$")
    id_re = re.compile(rf"^({ID_ALT})")
    data = load_status()
    all_bugs = {b["id"]: b for b in _all_bugs(data)}
    rows, seen = [], set()
    matching = []
    for p in invdir.glob("*.md"):
        m = date_re.search(p.name)
        if not m:
            continue
        try:
            d = _dt.date.fromisoformat(m.group(1))
        except ValueError:
            continue
        if d >= cutoff:
            matching.append((d, p))
    for d, p in sorted(matching, key=lambda x: x[0], reverse=True):
        m = id_re.match(p.name)
        if not m or m.group(1) in seen:
            continue
        seen.add(m.group(1))
        b = dict(all_bugs.get(m.group(1)) or {"id": m.group(1), "title": p.stem})
        b["date"] = d.isoformat()
        rows.append(b)
    print_output(rows, args.json, [("date", "DATE", 12), ("id", "ID", 24), ("state", "STATE", 14), ("title", "TITLE", 70)])
    return 0


def cmd_slack_digest(args):
    data = load_status()
    rel = data.get("current_shipping_release") or {}
    unreleased = [b for b in data.get("global_active_bugs", []) if (b.get("release_state", "") or "").lower().startswith("unreleased")]
    active = [b for b in data.get("global_active_bugs", []) if b.get("state") not in ("fixed", "closed")]
    print(f"*Bug tracker digest — {data.get('generated_at', '')[:10]}*\n")
    print(f"*Shipping:* {rel.get('version','?')}+{rel.get('build','?')}")
    print(f"*Counts:* {data['counts']['all_canonical_bugs']} canonical · {len(active)} active · {len(unreleased)} fixed/unreleased\n")
    if active:
        print(f"*Active ({len(active)}):*")
        for b in sorted(active, key=lambda b: b["id"]):
            print(f"  • `{b['id']}` _{b.get('priority','')}/{b.get('state','')}_ — {(b.get('title') or '')[:100]}")
        print()
    if unreleased:
        print(f"*Fixed on branch, awaiting release ({len(unreleased)}):*")
        for b in sorted(unreleased, key=lambda b: b["id"]):
            print(f"  • `{b['id']}` (fixed_in: {b.get('fixed_in') or '—'})")
    return 0


def cmd_csv(args):
    import csv as _csv
    data = load_status()
    cols = ["id", "priority", "state", "release_state", "fixed_in", "found_in", "title"]
    w = _csv.writer(sys.stdout)
    w.writerow(cols)
    for b in sorted(_all_bugs(data), key=lambda b: b["id"]):
        w.writerow([(b.get(c) or "").replace("\n", " ") for c in cols])
    return 0


def add_json_flag(p):
    p.add_argument("--json", action="store_true", help="emit raw JSON instead of a table")


def build_parser():
    parser = argparse.ArgumentParser(description="Fast queries against BUG_TRACKER.md.")
    add_json_flag(parser)
    sub = parser.add_subparsers(dest="command", required=True)

    for name, help_text, func in [
        ("active", "list globally active bugs", cmd_active),
        ("unreleased", "list fixed-on-branch / not-yet-shipped bugs", cmd_unreleased),
        ("slack-digest", "markdown digest for Slack", cmd_slack_digest),
        ("csv", "emit CSV of every canonical bug", cmd_csv),
    ]:
        p = sub.add_parser(name, help=help_text)
        add_json_flag(p)
        p.set_defaults(func=func)

    p = sub.add_parser("for-release", help="list bugs tied to a release version"); add_json_flag(p)
    p.add_argument("version", nargs="?"); p.set_defaults(func=cmd_for_release)
    p = sub.add_parser("find", help="substring-search bug titles"); add_json_flag(p)
    p.add_argument("substring"); p.set_defaults(func=cmd_find)
    p = sub.add_parser("touched-by", help="rows whose fixed_in references the given SHA"); add_json_flag(p)
    p.add_argument("sha"); p.set_defaults(func=cmd_touched_by)
    p = sub.add_parser("by-state", help="group active bugs by state"); add_json_flag(p)
    p.add_argument("state", nargs="?"); p.set_defaults(func=cmd_by_state)
    p = sub.add_parser("by-priority", help="group/filter active bugs by priority"); add_json_flag(p)
    p.add_argument("priority", nargs="?"); p.set_defaults(func=cmd_by_priority)
    p = sub.add_parser("since", help="bugs whose investigation filename date is on/after YYYY-MM-DD"); add_json_flag(p)
    p.add_argument("date"); p.set_defaults(func=cmd_since)
    return parser


def main(argv):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
