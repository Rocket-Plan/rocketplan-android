---
bug_id: RP-FR-009
aliases: []
title: Floorplan sketching — canvas editor (freehand → straighten → resize-by-length), append-only revisions, room binding
type: feature
classification: new_feature
source: internal
evidence: n/a
found_in: "spec 2026-06-14"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
last_updated: 2026-06-14
---

> **Feature spec** (not a defect). Backend is built, deployed to QA, and gated behind the `floorplan_enabled` feature flag. This doc tracks the **Android client** work. Sibling: iOS `RP-FR-016`. Backend reference: `docs/floorplan-feature.md` in `Rocket-Plan/mongoose.rocketplantech.com`. Originally drafted as GitHub issue #5 (now closed in favor of this tracker entry).

## Floorplan sketching (Android)

Add a floor-plan sketching feature: users draw a floor plan on a canvas, save it, re-open/edit it, track revision history, and link drawn room shapes to real rooms. The **backend is built and live** (Mongoose API) — this issue is the Android client work to consume it.

> Backend reference: `docs/floorplan-feature.md` in `Rocket-Plan/mongoose.rocketplantech.com` (authoritative as-built spec). **PDF reporting is intentionally out of scope** for now.

### Concept
- A **floorplan belongs to a `Location`** (a floor/unit). One active plan per floor.
- The **editable vector geometry (JSON) is the source of truth**; the client renders it.
- Each save creates an **immutable revision**; history is append-only and restorable.
- Drawn room shapes can **FK to real `Room` records** so a shape resolves to that room's photos / equipment / damage.

### API contract (all under the authenticated + company-approved group)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/locations/{location}/floorplans` | list a floor's plans |
| POST | `/api/locations/{location}/floorplans` | create plan + revision #1 (idempotent) |
| GET | `/api/floorplans/{floorplan}?include=currentRevision,floorplanRooms.room,revisions` | load a plan |
| PATCH | `/api/floorplans/{floorplan}` | **save = new revision**; optimistic-locked |
| DELETE | `/api/floorplans/{floorplan}` | soft delete; optimistic-locked |
| GET | `/api/floorplans/{floorplan}/revisions` | revision history (newest first) |
| GET | `/api/floorplans/{floorplan}/revisions/{revision}` | one revision (full geometry) |
| POST | `/api/floorplans/{floorplan}/revisions/{revision}/restore` | restore (appends a clone as new head) |
| POST | `/api/floorplans/{floorplan}/revisions/{revision}/render` | register the flattened S3 image |

**Create/save body:** `geometry` (object, must contain `schemaVersion:int`), `scale_ratio` (number), `unit_system` (`imperial`|`metric`), `name`, `summary`, optional `rooms[]`. `PATCH`/`DELETE` accept `updated_at` (optimistic lock). `POST` accepts `Idempotency-Key`.
**`rooms[]` item:** `{ room_id?, polygon: [[x,y],…], label?, sort_order? }` — `room_id` must belong to the floorplan's location.
**Render body:** `render_s3_key`, `render_bucket`, `render_file_name`, `render_content_type`.

### Contract notes (important)
- **Coordinate space:** origin top-left, `+x` right / `+y` down, units = pixels at `scale_ratio`. Must match web/iOS so plans render identically.
- **Geometry JSON** must include `schemaVersion` (start at `1`); otherwise it's an opaque blob the client owns (walls, dimension lines, labels, free shapes). Coordinate this schema with web + iOS.
- **Dimensions are server-authoritative** — the server computes `area`/`perimeter` from polygons + `scale_ratio` and returns them; show those values (client may compute live while drawing, but don't treat client values as stored truth).
- **Units are a label only** — there is no server-side ft↔m conversion. The client must set `scale_ratio` consistently with the chosen `unit_system`.
- **Every PATCH creates a revision.** Restore appends a new revision cloned from the chosen one (geometry + room shapes revert together).
- **Editing by length requires a scale.** `scale_ratio` (px↔real units) must be set before length-based editing/resize. The client converts entered lengths → pixel coordinates before persisting; the server stays authoritative on `area`/`perimeter`.
- **No backend change needed** for freehand→straighten or length editing — they're client-side; results persist as the same `geometry` JSON + room polygons.

### Core drawing UX (required)
The editing flow must support: **draw freehand with a finger → straighten strokes into real straight lines → adjust/resize by editing line lengths.**

### Tasks
- [ ] **Freehand drawing** (Compose `Canvas` / custom `View`): capture finger strokes
- [ ] **Straighten**: convert freehand strokes into straight line segments (polyline simplification e.g. Douglas–Peucker + angle/length snapping; auto-close into room polygons)
- [ ] **Dimension-driven editing**: select an edge/wall and edit its **length** numerically (and/or drag a handle with a live length readout) → geometry updates in real units via `scale_ratio`
- [ ] **Resize rooms by editing wall lengths**; keep shared walls between adjacent rooms consistent when a shared edge moves
- [ ] **Geometry-schema decision (coordinate w/ web + iOS):** model walls as **first-class shared segments** (editing a wall updates adjacent rooms) vs independent room polygons — affects resize/shared-wall behavior. Capture in `schemaVersion = 1`.
- [ ] Canvas editor basics: labels, dimension lines, pan/zoom, undo/redo, delete
- [ ] Define the shared `geometry` JSON model (`schemaVersion = 1`) — agree with web + iOS
- [ ] Scale tool (`scale_ratio`) + imperial/metric toggle; render server-returned area/perimeter
- [ ] Create plan (`POST`) with `Idempotency-Key`; offline-create + retry-safe
- [ ] Load/edit existing plan (`GET` with includes)
- [ ] Save (`PATCH`) with `updated_at` optimistic lock; handle **409** (reload / merge / prompt)
- [ ] Room binding: assign a `Room` to a shape; tap a room on the plan → its photos/equipment/damage
- [ ] Revision history UI: list, view a revision, restore
- [ ] Render & upload: flatten canvas → PNG → S3 (same presigned flow as photos) → `POST …/render`
- [ ] Delete (soft) with optimistic lock
- [ ] Unit + instrumentation tests

### Dependencies / coordination
- Geometry schema + coordinate-space contract shared with **iOS** (`Rocket-Plan/ios.rocketplantech.com`) and the web app.
- Reuse the existing photo S3 presigned-upload path for the render image.

---

## Implementation detail (for the implementing agent)

### Task 0 — repo discovery (do this first)
This repo's architecture isn't assumed here. Before coding, locate and mirror existing patterns:
- The **API client / networking layer** (Retrofit/OkHttp?) and how the auth token is injected.
- The **existing photo presigned-S3 upload** path (reuse it verbatim for the render image).
- The **navigation/screen pattern** (Compose nav / fragments) and a comparable create/edit feature to model the editor screen on.
- The local **model/serialization** approach (Moshi/Gson + data classes) for request/response bodies.

### Geometry schema — `schemaVersion: 1`, shared-wall topology (Option B)
Rooms are a graph of shared vertices/walls (NOT independent coordinate loops), so resizing a shared wall moves both adjacent rooms. Canonical, client-owned, stored opaquely by the server per revision:

```jsonc
{
  "schemaVersion": 1,
  "vertices": [ { "id": "v1", "x": 0, "y": 0 }, { "id": "v2", "x": 240, "y": 0 } ],   // pixel coords
  "walls":    [ { "id": "w1", "a": "v1", "b": "v2", "thickness": 4 } ],
  "rooms":    [ { "id": "r1", "room_id": 123, "label": "Kitchen", "walls": ["w1","w2","w3","w4"] } ],
  "labels":      [],
  "annotations": []
}
```
Before each save the client **resolves each room's wall loop into a `polygon`** and sends it in the request `rooms[]` (the server stores that projection + computes area/perimeter). `floorplan_rooms.polygon` is derived, not an edit surface. The server does **no** topology validation — Option B integrity is the editor's job.

### Algorithms
- **Straighten (freehand → lines):** capture raw touch points → reduce with Douglas–Peucker (epsilon ≈ touch tolerance) → snap each segment's angle to nearest 0/15/45/90° (configurable) → snap/merge endpoints onto nearby existing vertices (within tolerance) so adjacent rooms **share** vertices/walls → auto-close the loop when the last point ≈ the first to form a room polygon.
- **Length edit / resize:** wall length (real units) = `distance(a,b) × scale_ratio`. To set a wall to length `L`: move the dragged endpoint along the wall's unit vector so pixel distance = `L / scale_ratio`. Because vertices are shared, the move propagates to every wall/room on that vertex. Show a live length readout while dragging. Requires `scale_ratio` to be set first.

### Sample payloads
**Create** — `POST /api/locations/{location}/floorplans`, header `Idempotency-Key: <uuid>`:
```json
{
  "name": "Main Floor", "unit_system": "imperial", "scale_ratio": 0.0417,
  "geometry": { "schemaVersion": 1, "vertices": [], "walls": [], "rooms": [] },
  "rooms": [ { "room_id": 123, "label": "Kitchen", "polygon": [[0,0],[240,0],[240,180],[0,180]], "sort_order": 0 } ],
  "summary": "Initial sketch"
}
```
**Save** — `PATCH /api/floorplans/{id}` (omit `rooms` to keep existing shapes; send `rooms: []` to clear):
```json
{ "updated_at": "2026-06-14T18:20:00Z", "geometry": { "schemaVersion": 1 }, "scale_ratio": 0.0417,
  "rooms": [ { "room_id": 123, "label": "Kitchen", "polygon": [[0,0],[300,0],[300,180],[0,180]] } ], "summary": "Resized kitchen" }
```
→ **409** if `updated_at` is stale (reload/merge). **Restore** — `POST /api/floorplans/{id}/revisions/{rev}/restore` (empty body). **Render** — `POST /api/floorplans/{id}/revisions/{rev}/render`:
```json
{ "render_s3_key": "tmp/<uuid>", "render_bucket": "storage-...rocketplantech.com", "render_file_name": "plan.png", "render_content_type": "image/png" }
```

### Acceptance criteria
- With a scale set, drawing a rough rectangle + Straighten yields a closed room of 4 straight, right-angled segments.
- Two rooms sharing a wall: changing that wall's length resizes **both** and they stay flush (no gap/overlap).
- Editing a wall to "12 ft" then saving persists a polygon matching `12 / scale_ratio` px; the server-returned `area` updates.
- A stale save returns **409** and the user is offered reload/merge.
- Create offline → reconnect → exactly **one** floorplan exists (idempotency key reused on retry).
- Restoring an older revision repaints **both** geometry and room shapes to that revision.

### Feature flag (required — ship dark)
Gate the **entire feature behind the backend feature flag `floorplan_enabled`, default OFF.** When off, no floorplan entry point is visible anywhere in the app and no floorplan network calls are made; flipping it on reveals the feature with no further release.

- **Mechanism:** use the existing **database-backed feature-flag system** (the one that replaced LaunchDarkly). The flag already exists server-side (`floorplan_enabled`, `client_side = true`, default OFF) and the backend floorplan routes are gated by `feature:floorplan_enabled` middleware (they return **403** until enabled — handle gracefully).
- **Client read:** fetch flags from `GET /api/user/feature-flags` (already the app's flag source; `client_side` flags are included) and gate the entry point + any deep links on `floorplan_enabled`.
- **Rollout:** enabled per company/user via `feature_flag_scopes` on the backend — no app release needed. Same key on iOS + Android (`#994`).

### Non-goals (do not build)
PDF report integration · server-side unit conversion (units are a label; keep `scale_ratio` consistent) · server topology validation · more than one active plan per floor.

### Definition of done
Feature gated behind `floorplan_enabled` (default OFF, remotely toggleable); networking covers all 9 endpoints; editor supports draw → straighten → length-resize with shared walls; scale + imperial/metric; revision list + restore; render upload; 409 + idempotency handled; unit/instrumentation tests; geometry conforms to `schemaVersion 1` agreed with iOS (`#994`) + web.
