# Background Loading Architecture

## Snapshot-based Room Photos
Room Detail renders photos from `offline_room_photo_snapshots`, a room-specific read model that isolates the UI from cross-room writes in `offline_photos`.

Workflow:
1. `LocalDataService.refreshRoomPhotoSnapshot(roomId)` runs inside a Room transaction:
   - Query newest→oldest photos for `roomId` from `offline_photos`.
   - Clear snapshot rows for that room and insert the ordered list, storing precomputed `imageUrl`/`thumbnailUrl` so adapters skip per-bind logic.
   - Because delete + insert happen transactionally, Paging sees only one invalidation when the snapshot is ready.
2. `RoomDetailViewModel.photoPagingData` consumes `pagedPhotoSnapshotsForRoom(roomId)` and maps each snapshot row to `RoomPhotoItem`. The flow is `cachedIn(viewModelScope)` so paging jobs survive fragment recreation.
3. `RoomDetailFragment` observes `viewModel.isSnapshotRefreshing`; while true we show a loading overlay and hide the RecyclerView so users only see the final dataset once the snapshot rebuild completes.

## Refresh Triggers
We rebuild the snapshot explicitly:
- First time `_resolvedRoom` emits for a room (if we haven’t built a snapshot yet).
- After `ensureRoomPhotosFresh()` completes (network sync → `offline_photos`).
- After local captures (`onLocalPhotoCaptured`).

Because we rebuild the snapshot only at these points, background syncs for *other* rooms no longer invalidate this room’s Pager. The only visible reload now happens when we intentionally rebuild the snapshot for the active room.

## Future Work
- Delay the initial snapshot until the post-sync refresh completes, so users don’t see the “newest → oldest” shuffle.
- Gate snapshot rebuilds on SyncQueueManager signals so we never rebuild mid-sync.
- Resume background project syncs after the snapshot stabilizes.
