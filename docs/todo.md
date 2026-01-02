# UUID Implementation TODO

## Completed (Android)

- [x] Add UUID v7 generator utility (`UuidUtils.kt`)
- [x] Update API request models with UUID fields
  - `CreateRoomRequest` - uuid, levelUuid, locationUuid
  - `CreateLocationRequest` - uuid, propertyUuid, parentLocationUuid
  - `CreateCompanyProjectRequest` - uuid, addressUuid
  - `PropertyMutationRequest` - uuid, projectUuid
- [x] Migrate entity creation from UUID v4 to UUID v7
- [x] Update sync handlers to send UUIDs in API requests
- [x] Remove name-matching fallback logic in location sync

## Completed (Server/Laravel)

- [x] Add `uuid` column to all entity tables (nullable initially)
  - projects, properties, locations, levels, rooms, notes, equipment_room, atmospheric_logs, work_scopes, albums, damage_materials
- [x] Backfill existing records with UUIDs (`php artisan uuid:backfill`)
- [x] Make `uuid` column NOT NULL after backfill (migration ready)
- [x] Update models to auto-generate UUID on creation (`HasUuid` trait with UUID v7)
- [x] Update API resources to return `uuid` in responses
- [x] Accept UUID references in requests (`location_uuid`, `level_uuid`, etc.) via `ResolvesUuidReferences` trait
- [x] Update controllers to resolve entities by UUID via `ResolvesUuidToId` trait

## Pending (iOS)

- [ ] Add UUID v7 generator
- [ ] Update API request models with UUID fields
- [ ] Migrate entity creation to UUID v7
- [ ] Update sync handlers to send UUIDs
- [ ] Remove fallback ID resolution logic

## Future Optimization

- [ ] Add UUID-based API routing (`/api/locations/uuid/{uuid}/rooms`)
- [ ] Remove `refreshEssentialsOnce` logic once server accepts UUID in path
- [ ] Remove remaining server ID resolution code

## Testing

- [ ] Test offline room creation with UUID sync
- [ ] Test offline location creation with UUID sync
- [ ] Test offline property creation with UUID sync
- [ ] Test idempotency (duplicate UUID handling)
- [ ] Test backward compatibility (old clients still work)

## Decision Required: Contact & Claim Offline Support

Neither Contact nor Claim currently has offline support on Android.

### Claims (Android Status)
- `ClaimDto` exists but has **NO UUID field** - only `id: Long`
- Uses `idempotencyKey` for API deduplication (not offline creation)
- No `OfflineClaimEntity` exists
- No sync queue handler in `SyncQueueProcessor`
- API calls only - no offline fallback

### Contacts (Android Status)
- **Do not exist at all** in Android codebase
- No model, entity, or UI

### Options
- [ ] **Option A (Full UUID):** Add UUID support on backend for Contact/Claim → implement offline entities on Android/iOS
- [ ] **Option B (idempotency_key only):** Keep using `idempotencyKey` approach for Claims (simpler, no backend UUID changes needed)

### Recommendation
Claims are relatively rare operations (usually created by backend, edited by users) - `idempotencyKey` approach is likely sufficient. Contacts need to be implemented from scratch if Android needs them.

## Notes

- Server must be deployed before Android changes take effect
- Backward compatible: old clients continue using IDs, new clients use UUIDs
- UUID v7 is time-ordered for better database indexing
