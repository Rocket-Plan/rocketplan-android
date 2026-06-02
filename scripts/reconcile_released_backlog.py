#!/usr/bin/env python3
"""
One-off reconciliation for release_state drift on the bug tracker.

Why: `flip_released_bugs.py` reads each investigation doc's frontmatter `fixed_in`,
but for most backlog bugs that field is `null`/prose while the real commit SHA lives
in the BUG_TRACKER row's `Fixed` column. So fixes whose code is provably in a shipped
build (an ancestor of a release tag) were never flipped to `released` → drift.

This reconciles off the TRACKER (the source of truth) + git ancestry:
  * For each canonical row with State == fixed and Rel == unreleased,
  * extract commit SHA(s) from the `Fixed` column,
  * find the EARLIEST prod-deployed build (per PROD_DEPLOY_LOG) that CONTAINS the SHA,
  * propose Rel -> released and released_in -> "<marketing>+<build> (Play Store)".
Rows with no extractable SHA, no containing prod build, or a non-`fixed` state are
left untouched and reported under "NOT flipped" for manual review.

Default is DRY-RUN. Pass --apply to write the tracker rows. (Investigation-doc
frontmatter is left to a follow-up; the tracker is authoritative.)

Android tracker layout (12 cols, after stripping the outer pipes):
  0 id  1 priority  2 aliases  3 title  4 type  5 class  6 found  7 FIXED
  8 state  9 REL  10 reg_of  11 investigation
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRACKER = ROOT / "docs" / "BUG_TRACKER.md"
DEPLOY_LOG = ROOT / "docs" / "PROD_DEPLOY_LOG.md"

SHA_RE = re.compile(r"`?\b([0-9a-f]{7,40})\b`?")
# A PROD_DEPLOY_LOG row: | ts | marketing | build | `HEAD` | tag |
DEPLOY_ROW_RE = re.compile(
    r"^\|\s*[^|]+\|\s*([\d.]+)\s*\|\s*(\d+)\s*\|\s*`?([0-9a-f]{7,40})`?\s*\|")


def prod_deploys():
    """Authoritative shipped builds from PROD_DEPLOY_LOG.md, ascending by build.

    This — NOT git tags — is the source of truth for what reached the Play Store.
    A build can ship with no git tag, so `git tag --contains` would miss it; the
    deploy log does not.
    """
    builds = []
    if not DEPLOY_LOG.exists():
        return builds
    for line in DEPLOY_LOG.read_text().splitlines():
        m = DEPLOY_ROW_RE.match(line)
        if m:
            marketing, build, head = m.groups()
            builds.append((int(build), marketing, head))
    builds.sort(key=lambda b: b[0])
    return builds


def earliest_prod_build_for(sha: str, builds):
    """First prod build (ascending) whose HEAD commit has `sha` as an ancestor."""
    for build, marketing, head in builds:
        rc = subprocess.run(["git", "merge-base", "--is-ancestor", sha, head],
                            capture_output=True, cwd=ROOT).returncode
        if rc == 0:
            return (build, marketing, head)
    return None


def sha_from_git_history(bug_id: str):
    """Fallback: newest commit whose message references the bug id, INCLUDING the
    slash-bundled form we commonly use ("RP-BUG-110/111/112/113"), where the literal
    "RP-BUG-111" substring never appears — only "/111". The pattern anchors on the
    "<PREFIX>-" token then allows any number of "<num>/" groups before the target
    number, and requires a non-digit (or end) after it so 111 doesn't match 1119.
    """
    m = re.match(r"^(.*-)(\d+)$", bug_id)  # e.g. ("RP-BUG-", "111")
    if not m:
        return None
    prefix, num = m.groups()
    # ERE: RP-BUG-(<digits>/)*111(<non-digit>|$)
    pattern = re.escape(prefix) + r"([0-9]+/)*0*" + num + r"([^0-9]|$)"
    try:
        out = subprocess.run(
            ["git", "log", "--all", "-E", f"--grep={pattern}", "--format=%H", "-n", "1"],
            capture_output=True, text=True, cwd=ROOT).stdout.strip()
    except Exception:
        return None
    return out.splitlines()[0] if out else None


def parse_rows(text: str):
    """Yield (lineno, raw_line, bug_id, fixed_in, state, release_state).

    Android canonical row: 12 cols. The bug id cell is backticked and matches the
    Android id regex. We take the trailing 8 cells (robust to pipes inside the title
    cell): type, class, found, FIXED, state, REL, reg_of, investigation.
    """
    bug_id_re = re.compile(r"RP-(?:BUG|FR|HD)-\d+|ROCKET-PLAN-ANDROID-[A-Z0-9]+")
    for i, line in enumerate(text.splitlines()):
        if not line.startswith("| `"):
            continue
        if not bug_id_re.search(line):
            # only canonical-bug data rows
            continue
        parts = [p.strip() for p in line.split("|")]
        # strip leading/trailing empty cells from the surrounding pipes
        if parts and parts[0] == "":
            parts = parts[1:]
        if parts and parts[-1] == "":
            parts = parts[:-1]
        if len(parts) < 12:
            continue
        bug_cell = parts[0]
        # trailing 8 robust to pipes in the title cell
        try:
            _type, _class, _found, fixed_in, state, release_state, _reg, _inv = parts[-8:]
        except ValueError:
            continue
        bug_id = bug_cell.strip("` ")
        yield i, line, bug_id, fixed_in, state.strip(), release_state.strip()


def main() -> int:
    apply = "--apply" in sys.argv
    text = TRACKER.read_text()
    lines = text.splitlines(keepends=True)
    builds = prod_deploys()

    flips = []          # (bug_id, sha, marketing, build, lineno, sha_source)
    not_flipped = []    # (bug_id, state, reason)

    for lineno, raw, bug_id, fixed_in, state, rel in parse_rows(text):
        if state != "fixed":
            continue
        if rel != "unreleased":
            continue
        # Pre-log version reference: a concrete shipped build (e.g. "1.29+32" or
        # "1.29 (32)") named in the Fixed cell that predates this deploy log. If its
        # marketing version is <= the latest prod marketing, it unquestionably shipped.
        vref = re.search(r"\b(\d+)\.(\d+)(?:\.(\d+))?(?:\s*\((\d+)\)|\+(\d+))\b", fixed_in)
        if vref and builds:
            major, minor, patch, build_paren, build_plus = vref.groups()
            patch = patch or "0"
            build_str = build_paren or build_plus
            v = (int(major), int(minor), int(patch))
            latest_parts = builds[-1][1].split(".")
            latest = tuple(int(x) for x in (latest_parts + ["0", "0", "0"])[:3])
            if v <= latest:
                marketing = f"{major}.{minor}" + (f".{patch}" if vref.group(3) else "")
                flips.append((bug_id, f"v{vref.group(0)}", marketing,
                              int(build_str), lineno, "version-ref"))
                continue

        shas = SHA_RE.findall(fixed_in)
        sha_source = "tracker"
        # Fallback: no SHA recorded in the Fixed cell -> recover it from git history.
        if not shas:
            git_sha = sha_from_git_history(bug_id)
            if git_sha:
                shas = [git_sha]
                sha_source = "git-history"
        # Earliest prod build (per PROD_DEPLOY_LOG) shipping ANY of this bug's fix SHAs.
        best = None  # (build, marketing, sha)
        for sha in shas:
            hit = earliest_prod_build_for(sha, builds)
            if hit and (best is None or hit[0] < best[0]):
                best = (hit[0], hit[1], sha)
        if best is None:
            reason = ("no SHA in Fixed column and none in git history" if not shas
                      else "fix SHA not in any prod-deployed build (not shipped to Play Store yet)")
            not_flipped.append((bug_id, state, reason))
            continue
        build, marketing, sha = best
        flips.append((bug_id, sha, marketing, build, lineno, sha_source))

    # report
    print(f"=== Reconciliation against PROD_DEPLOY_LOG.md ({'APPLY' if apply else 'DRY-RUN'}) ===\n")
    print(f"WOULD FLIP to released ({len(flips)}):")
    for bug_id, sha, marketing, build, _, src in sorted(flips):
        note = {"git-history": "  [SHA recovered from git history (bundled) -> backfilled into Fixed cell]",
                "version-ref": "  [pre-log version reference in Fixed cell]"}.get(src, "")
        ref = sha if src == "version-ref" else f"`{sha[:8]}`"
        print(f"  {bug_id:24s} fixed {ref} -> prod build {marketing}+{build}{note}")
    print(f"\nNOT flipped (fixed+unreleased but no in-build SHA) ({len(not_flipped)}):")
    for bug_id, state, reason in sorted(not_flipped):
        print(f"  {bug_id:24s} ({reason})")

    if not apply:
        print("\n(dry-run; pass --apply to write tracker rows)")
        return 0

    # apply: rewrite each flip row's Rel cell unreleased -> released,
    # and for git-recovered SHAs, backfill the SHA into the Fixed cell so it's recorded.
    out_lines = []
    flip_by_line = {ln: (sha, src) for (_, sha, _, _, ln, src) in flips}
    for idx, line in enumerate(lines):
        if idx in flip_by_line:
            sha, src = flip_by_line[idx]
            new = line.replace("| unreleased |", "| released |", 1)
            if src == "git-history":
                new = backfill_sha_into_fixed_cell(new, sha)
            out_lines.append(new)
        else:
            out_lines.append(line)
    TRACKER.write_text("".join(out_lines))
    n_backfill = sum(1 for f in flips if f[5] == "git-history")
    print(f"\nApplied: flipped {len(flips)} tracker rows to released "
          f"({n_backfill} with git-recovered SHAs backfilled into the Fixed cell). "
          f"Spot-check the tracker.")
    return 0


def backfill_sha_into_fixed_cell(line: str, sha: str):
    """Append `(commit `sha`)` to the Fixed cell of an Android tracker row.

    12 columns surrounded by pipes -> split('|') gives a leading and trailing empty
    cell, so cells[1..-2] are the 12 columns. Counting from the end of the line
    (trailing empty cell at -1): inv=-2, reg_of=-3, REL=-4, state=-5, FIXED=-6.
    """
    keepend = ""
    if line.endswith("\n"):
        keepend = "\n"
        line = line[:-1]
    cells = line.split("|")
    fixed_idx = -6
    if f"`{sha[:8]}" in cells[fixed_idx] or f"`{sha}" in cells[fixed_idx]:
        return line + keepend  # already recorded
    cells[fixed_idx] = cells[fixed_idx].rstrip() + f" (commit `{sha}`) "
    return "|".join(cells) + keepend


if __name__ == "__main__":
    sys.exit(main())
