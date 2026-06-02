# Bug-tracking tooling

Ported from the iOS repo so Android uses the same bug-management mechanism. The
**source of truth** is `docs/BUG_TRACKER.md` (the `### Canonical Bugs` table) plus
the per-bug `docs/investigations/<ID>.md` frontmatter. Everything below reads or
validates those; nothing replaces hand judgement on priority/triage.

## One-time setup

```sh
sh scripts/install-hooks.sh        # installs the pre-commit hook into .git/hooks
```

## Pre-commit guards (`scripts/hooks/pre-commit`)

| Guard | Script | Fires when | Blocks? |
|-------|--------|-----------|---------|
| Room version bump needs a registered migration | `check_room_migration.py` | `OfflineDatabase.kt` staged | yes |
| No duplicate bug IDs | `check_bug_id_duplicates.py` | `BUG_TRACKER.md` staged | yes |
| Frontmatter ↔ tracker drift | `check_tracker_regenerated.py` | an investigation doc staged | warn (add `--strict` to block) |

## Queries & reporting

```sh
python3 scripts/generate_release_bug_status.py   # -> docs/releases/CURRENT_BUG_STATUS.json
python3 scripts/bugs.py active                   # globally active bugs
python3 scripts/bugs.py unreleased               # fixed on branch, not shipped
python3 scripts/bugs.py by-priority P0           # filter by priority
python3 scripts/bugs.py find <substring>         # search titles
python3 scripts/bugs.py touched-by <sha>         # rows whose fixed_in mentions a commit
python3 scripts/bugs.py for-release [version]    # bugs tied to a version
python3 scripts/bugs.py by-state | since <date> | slack-digest | csv
```

Regenerate the status JSON after editing the tracker (or wire it into CI).

## Drift detector / regenerator

```sh
python3 scripts/generate_bug_tracker.py          # dry-run: writes proposed table to docs/releases/_canonical_bugs_proposed.md
python3 scripts/generate_bug_tracker.py --apply  # splice the frontmatter-derived table into BUG_TRACKER.md
```

`Priority` is preserved from the existing tracker (it is a triage judgement, not stored in frontmatter).

## Release bookkeeping (activate once releases are tagged)

```sh
scripts/record_prod_deploy.sh                    # append a row to docs/PROD_DEPLOY_LOG.md after a prod deploy
python3 scripts/flip_released_bugs.py [--apply]  # flip unreleased -> released for fixes in the latest deploy
python3 scripts/reconcile_released_backlog.py [--apply]  # reconcile release_state off tracker + deploy log
```

These are inert until `docs/PROD_DEPLOY_LOG.md` exists (i.e. after the first recorded prod deploy).

## Schema note (differs from iOS)

Android Canonical Bugs columns: `ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation` (12 cols, **has Priority, no Repro** — the reverse of iOS). IDs: `RP-BUG-###`, `RP-FR-###`, `RP-HD-###`, `ROCKET-PLAN-ANDROID-*`.

Not ported: `fetch_sentry_data.py` (iOS-specific Sentry project + hardcoded creds + local MySQL).
