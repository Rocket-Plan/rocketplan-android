# Symptom Index

> **If you're experiencing X, start here.** Concrete user-visible symptoms → likely tickets.
> Distilled from the `## Symptom` section of `RP-BUG-*` investigations. Each row is a real, observed
> shape — not a generic category.
> Status notation: `state / release_state` (e.g. `fixed/unreleased`, `planned/unreleased`, `open`).
> `unreleased` means the fix is in the branch but not in users' hands yet.

## How to use

1. Skim the section heading that matches the surface (screen / feature) the user is on.
2. Match the concrete symptom row. If multiple plausibly fit, open the most recent (highest RP-BUG-###) first.
3. No match? `git grep -i "<keyword>" docs/BUG_TRACKER.md` — tracker rows are dense and grep-friendly.
4. Found a new recurring shape worth indexing? Add a row.

Authoritative state lives in `docs/BUG_TRACKER.md`. This is a finding aid.

> **Seed file (2026-06-07).** Started with the photo-sync surface; grow it as investigations are written
> — back-fill the `## Symptom` line from each `RP-BUG-*` doc into the matching section below.

---

## A. Photos / room cards

| Symptom | Ticket | State |
|---------|--------|-------|
| Room card spinner spins forever; room never loads its photos (and other rooms' photos in the same project stop syncing) | [RP-BUG-043](investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) | fixed/unreleased |
| Room shows a photo count (e.g. "1 Photo") but no photos appear, and nothing retries | [RP-BUG-044](investigations/RP-BUG-044_sync_all_room_photos_swallows_per_room_failures.md) · [RP-BUG-043](investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) | fixed/unreleased |
| Photo upload lands in the wrong room (room not yet synced → local id used) | [RP-BUG-030](investigations/RP-BUG-030_image_processor_room_id_zero.md) | fixed/unreleased |
| No per-item cloud / "not downloaded" indicator (can't tell what's on-device vs server-only) | [RP-BUG-041](investigations/RP-BUG-041_no_per_item_download_sync_cloud_indicator.md) | fixed/unreleased |

---

## B. Offline sync / data integrity

| Symptom | Ticket | State |
|---------|--------|-------|
| Locally-created item (note/equipment/log/support message) duplicates after a refresh/pull | [RP-BUG-036](investigations/RP-BUG-036_support_duplicate_on_refresh.md) · [RP-BUG-037](investigations/RP-BUG-037_offline_create_duplicate_on_metadata_refresh.md) | fixed/unreleased |
| Locally-staged work-scope items vanish after refresh | [RP-BUG-035](investigations/RP-BUG-035_workscope_pending_create_merge_gap.md) | fixed/unreleased |
| Offline delete of a server-modified entity never lands (stale `updated_at` → 409, retried or swallowed) | [RP-BUG-040](investigations/RP-BUG-040_delete_409_stale_timestamp_retry_loop.md) | fixed/unreleased |
| Offline pickers (damage/claim/scope/project types) empty until a project's metadata has synced | [RP-BUG-042](investigations/RP-BUG-042_reference_data_not_seeded_before_phase2.md) | fixed/unreleased |
| Deleted property leaves orphaned stale child locations/rooms | [RP-BUG-029](investigations/RP-BUG-029_deleted_property_omitted_child_location_orphan.md) | fixed/unreleased |

---

_Add sections (e.g. **C. Auth / login**, **D. Sync banner / progress UI**) and rows as new
investigations are filed. Each row should be a concrete symptom a user would actually report, not a
category._
