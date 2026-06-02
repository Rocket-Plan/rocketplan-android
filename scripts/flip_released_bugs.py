#!/usr/bin/env python3
"""Flip `release_state: unreleased → released` on every investigation doc
whose fix commit is contained in the most-recent production deploy.

Runs from the `fastlane deploy_production` lane after
`scripts/record_prod_deploy.sh`. Replaces the manual bookkeeping spelled out
in CLAUDE.md / docs § "Post-production-release bug-doc bookkeeping".

Behavior:
- Reads the last row of `docs/PROD_DEPLOY_LOG.md` to learn the build that
  just shipped: marketing version + build number + git tag.
- Finds every doc in `docs/investigations/` with frontmatter
  `state: fixed` and `release_state: unreleased`.
- For each candidate:
    * Resolves the `fixed_in` field to one or more commit SHAs (parses
      backticked tokens; falls back to nothing if no SHA is present).
    * If any commit SHA is contained in the new build's tag (via
      `git tag --contains <sha>`), or if `fixed_in` directly names the
      new build (e.g. `1.29 (32)`, `1.29+32`, or `1.29`), the doc qualifies.
    * Updates frontmatter:
        - `release_state: unreleased` → `released`
        - `released_in: null` → `"<marketing>+<build> (Play Store, <YYYY-MM-DD>)"`
    * Updates the matching row in `docs/BUG_TRACKER.md` ("Rel" column).

Idempotent — already-released docs are skipped.

Dry-run by default; pass --apply to write. The fastlane lane should
invoke with --apply.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INV_DIR = ROOT / "docs" / "investigations"
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"
DEPLOY_LOG = ROOT / "docs" / "PROD_DEPLOY_LOG.md"

# Last row format:
# | 2026-05-15 00:06:00 PDT | 1.29 | 32 | `a9d59381` | v1.29-32 |
DEPLOY_ROW_RE = re.compile(
    r"^\|\s*(\d{4}-\d{2}-\d{2})[^|]*\|\s*([\d.]+)\s*\|\s*(\d+)\s*\|\s*`?([0-9a-f]{6,40})`?\s*\|\s*(\S[^|]*?)\s*\|"
)

# Match git SHAs whether or not they are backticked. Many investigation docs write
# `fixed_in` as a bare SHA or `bug_bundle_2 (37f382b8)`; requiring backticks
# silently skipped shipped bugs. The downstream `git tag --contains <token>` check
# filters out any token that isn't a real commit, so broadening here cannot
# produce false positives.
SHA_TOKEN_RE = re.compile(r"`?\b([0-9a-f]{7,40})\b`?")
BUILD_RE = re.compile(r"\b(\d+\.\d+)(?:\s*\(\d+\)|\+\d+)?\b")


def parse_last_deploy() -> dict:
    rows = []
    for line in DEPLOY_LOG.read_text().splitlines():
        m = DEPLOY_ROW_RE.match(line)
        if m:
            rows.append({
                "date": m.group(1),
                "marketing": m.group(2),
                "build": m.group(3),
                "head_sha": m.group(4),
                "tag": m.group(5).strip(),
            })
    if not rows:
        sys.exit(f"could not parse any deploy rows from {DEPLOY_LOG}")
    return rows[-1]


def parse_frontmatter(text: str) -> tuple[dict, str, str]:
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return {}, "", text
    fm = m.group(1)
    out: dict = {}
    for line in fm.splitlines():
        km = re.match(r"^([a-zA-Z_]+):\s*(.*)$", line)
        if km:
            val = km.group(2).strip()
            if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                val = val[1:-1]
            out[km.group(1)] = val
    return out, fm, text[m.end():]


def tag_contains(sha: str, tag: str) -> bool:
    if not sha or not tag or tag == "—":
        return False
    res = subprocess.run(
        ["git", "tag", "--contains", sha],
        capture_output=True,
        text=True,
        check=False,
        cwd=ROOT,
    )
    return tag in res.stdout.splitlines()


def qualifies(fm: dict, deploy: dict) -> tuple[bool, list[str]]:
    """Return (qualifies, reasons)."""
    if fm.get("state", "").lower() not in {"fixed"}:
        return False, []
    if fm.get("release_state", "").lower() not in {"unreleased"}:
        return False, []
    fixed_in = fm.get("fixed_in", "") or ""
    reasons: list[str] = []

    # Direct version match: `1.29 (32)`, `1.29+32`, or `1.29`
    full_version_plus = f"{deploy['marketing']}+{deploy['build']}"
    full_version_paren = f"{deploy['marketing']} ({deploy['build']})"
    if (full_version_plus in fixed_in
            or full_version_paren in fixed_in
            or deploy["marketing"] in fixed_in):
        reasons.append(f"fixed_in mentions {deploy['marketing']}")

    # SHA-in-tag match
    if deploy["tag"] and deploy["tag"] != "—":
        for sha in SHA_TOKEN_RE.findall(fixed_in):
            if tag_contains(sha, deploy["tag"]):
                reasons.append(f"commit `{sha}` is in tag {deploy['tag']}")
                break

    return bool(reasons), reasons


def update_frontmatter(text: str, deploy: dict) -> str:
    released_in = f'"{deploy["marketing"]}+{deploy["build"]} (Play Store, {deploy["date"]})"'
    # release_state: unreleased -> released
    text = re.sub(
        r"^(release_state:\s*)unreleased\s*$",
        r"\1released",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    # released_in: null -> released_in: "...."
    if re.search(r"^released_in:\s*null\s*$", text, re.MULTILINE):
        text = re.sub(
            r"^(released_in:\s*)null\s*$",
            rf"\1{released_in}",
            text,
            count=1,
            flags=re.MULTILINE,
        )
    elif not re.search(r"^released_in:", text, re.MULTILINE):
        # Inject after release_state if missing entirely
        text = re.sub(
            r"^(release_state:.*)$",
            rf"\1\nreleased_in: {released_in}",
            text,
            count=1,
            flags=re.MULTILINE,
        )
    return text


def update_tracker_release_state(bug_id: str, deploy: dict, dry_run: bool) -> bool:
    """Flip the matching BUG_TRACKER.md row's release_state column (Rel, col 9)."""
    text = TRACKER.read_text()
    lines = text.splitlines(keepends=True)
    changed = False
    needle = f"| `{bug_id}` |"
    for i, line in enumerate(lines):
        if not line.startswith(needle):
            continue
        # Split, locate the unreleased token, swap. The Rel cell is column 9
        # (0-indexed, after stripping the leading empty cell from the outer pipe).
        parts = line.split("|")
        # Heuristic: search every cell for exact "unreleased" and flip the first match
        for j, cell in enumerate(parts):
            if cell.strip() == "unreleased":
                parts[j] = cell.replace("unreleased", "released")
                lines[i] = "|".join(parts)
                changed = True
                break
        if changed:
            break
    if changed and not dry_run:
        TRACKER.write_text("".join(lines))
    return changed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apply", action="store_true", help="write changes (default: dry-run)")
    ap.add_argument("--quiet", action="store_true", help="suppress per-doc progress (still prints summary)")
    args = ap.parse_args()

    if not DEPLOY_LOG.exists():
        print(f"(no {DEPLOY_LOG.relative_to(ROOT)} yet — nothing to flip)")
        return 0

    deploy = parse_last_deploy()
    if not args.quiet:
        print(f"Last deploy: {deploy['marketing']}+{deploy['build']} on {deploy['date']} (tag={deploy['tag']})")

    candidates: list[tuple[Path, list[str]]] = []
    if INV_DIR.exists():
        for p in sorted(INV_DIR.glob("*.md")):
            text = p.read_text()
            fm, _, _ = parse_frontmatter(text)
            if not fm:
                continue
            ok, reasons = qualifies(fm, deploy)
            if ok:
                candidates.append((p, reasons))

    if not candidates:
        print("(no docs qualify — nothing to flip)")
        return 0

    print(f"\nQualifying docs: {len(candidates)}")
    for p, reasons in candidates:
        if not args.quiet:
            print(f"  flip: {p.name}")
            for r in reasons:
                print(f"     ({r})")

    if not args.apply:
        print("\n(dry-run; pass --apply to write)")
        return 0

    flipped_docs = 0
    flipped_rows = 0
    for p, _ in candidates:
        text = p.read_text()
        fm, _, _ = parse_frontmatter(text)
        new_text = update_frontmatter(text, deploy)
        if new_text != text:
            p.write_text(new_text)
            flipped_docs += 1
        bug_id = fm.get("bug_id")
        if bug_id and update_tracker_release_state(bug_id, deploy, dry_run=False):
            flipped_rows += 1

    print(f"\nFlipped {flipped_docs} investigation docs, {flipped_rows} tracker rows.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
