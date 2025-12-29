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

## Pending (Server/Laravel)

- [ ] Add `uuid` column to all entity tables (nullable initially)
- [ ] Backfill existing records with UUIDs
- [ ] Make `uuid` column NOT NULL after backfill
- [ ] Update models to auto-generate UUID on creation
- [ ] Update API resources to return `uuid` in responses
- [ ] Accept UUID references in requests (`location_uuid`, `level_uuid`, etc.)
- [ ] Update controllers to resolve entities by UUID

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

## Notes

- Server must be deployed before Android changes take effect
- Backward compatible: old clients continue using IDs, new clients use UUIDs
- UUID v7 is time-ordered for better database indexing
