-- Project Hierarchy Query
-- Shows: Projects → Properties → Locations → Rooms → Photos → Albums
-- Usage: Replace @project_id with your project ID

SET @project_id = 4970;

-- 1. PROJECT DETAILS
SELECT '=== PROJECT DETAILS ===' as '';
SELECT
    id,
    uid,
    alias,
    created_at,
    updated_at
FROM projects
WHERE id = @project_id;

-- 2. PROPERTIES
SELECT '=== PROPERTIES ===' as '';
SELECT
    id,
    name,
    is_residential,
    is_commercial,
    loss_date,
    year_built,
    created_at
FROM properties
WHERE project_id = @project_id
AND deleted_at IS NULL;

-- 3. LOCATIONS (by Property)
SELECT '=== LOCATIONS ===' as '';
SELECT
    l.id as location_id,
    l.name as location_name,
    prop.id as property_id,
    prop.name as property_name,
    l.created_at
FROM locations l
JOIN properties prop ON l.property_id = prop.id
WHERE prop.project_id = @project_id
AND l.deleted_at IS NULL
ORDER BY prop.id, l.id;

-- 4. ROOMS (by Location)
SELECT '=== ROOMS ===' as '';
SELECT
    r.id as room_id,
    rt.name as room_type,
    r.type_occurrence,
    l.id as location_id,
    l.name as location_name,
    COUNT(DISTINCT p.id) as photo_count,
    r.created_at
FROM rooms r
JOIN room_types rt ON r.room_type_id = rt.id
JOIN locations l ON r.location_id = l.id
JOIN properties prop ON l.property_id = prop.id
LEFT JOIN photos p ON r.id = p.photoable_id
    AND p.photoable_type = 'App\\Models\\Room'
    AND p.deleted_at IS NULL
WHERE prop.project_id = @project_id
AND r.deleted_at IS NULL
GROUP BY r.id, rt.name, r.type_occurrence, l.id, l.name, r.created_at
ORDER BY l.id, rt.name, r.type_occurrence;

-- 5. ALBUMS
SELECT '=== ALBUMS ===' as '';
SELECT
    a.id,
    a.name,
    a.albumable_type,
    a.albumable_id,
    COUNT(DISTINCT ap.photo_id) as photo_count,
    a.created_at
FROM albums a
LEFT JOIN album_photo ap ON a.id = ap.album_id
WHERE (
    (a.albumable_type = 'App\\Models\\Project' AND a.albumable_id = @project_id)
    OR a.albumable_id IN (
        SELECT id FROM properties WHERE project_id = @project_id
    )
    OR a.albumable_id IN (
        SELECT r.id
        FROM rooms r
        JOIN locations l ON r.location_id = l.id
        JOIN properties prop ON l.property_id = prop.id
        WHERE prop.project_id = @project_id
    )
)
AND a.deleted_at IS NULL
GROUP BY a.id, a.name, a.albumable_type, a.albumable_id, a.created_at
ORDER BY a.albumable_type, a.created_at;

-- 6. PHOTO SUMMARY BY ROOM
SELECT '=== PHOTO SUMMARY BY ROOM ===' as '';
SELECT
    r.id as room_id,
    rt.name as room_type,
    r.type_occurrence,
    l.name as location_name,
    COUNT(DISTINCT CASE WHEN p.deleted_at IS NULL THEN p.id END) as active_photos,
    COUNT(DISTINCT CASE WHEN p.deleted_at IS NOT NULL THEN p.id END) as deleted_photos,
    COUNT(DISTINCT pa.id) as photo_assemblies,
    MIN(p.created_at) as first_photo,
    MAX(p.created_at) as latest_photo
FROM rooms r
JOIN room_types rt ON r.room_type_id = rt.id
JOIN locations l ON r.location_id = l.id
JOIN properties prop ON l.property_id = prop.id
LEFT JOIN photos p ON r.id = p.photoable_id
    AND p.photoable_type = 'App\\Models\\Room'
LEFT JOIN photo_assemblies pa ON r.id = pa.room_id
    AND pa.deleted_at IS NULL
WHERE prop.project_id = @project_id
AND r.deleted_at IS NULL
GROUP BY r.id, rt.name, r.type_occurrence, l.name
ORDER BY l.name, rt.name, r.type_occurrence;

-- 7. OVERALL STATISTICS
SELECT '=== OVERALL STATISTICS ===' as '';
SELECT
    COUNT(DISTINCT prop.id) as total_properties,
    COUNT(DISTINCT l.id) as total_locations,
    COUNT(DISTINCT r.id) as total_rooms,
    COUNT(DISTINCT a.id) as total_albums,
    COUNT(DISTINCT CASE WHEN p.deleted_at IS NULL THEN p.id END) as total_active_photos,
    COUNT(DISTINCT CASE WHEN p.deleted_at IS NOT NULL THEN p.id END) as total_deleted_photos,
    COUNT(DISTINCT pa.id) as total_photo_assemblies
FROM properties prop
LEFT JOIN locations l ON prop.id = l.property_id AND l.deleted_at IS NULL
LEFT JOIN rooms r ON l.id = r.location_id AND r.deleted_at IS NULL
LEFT JOIN photos p ON r.id = p.photoable_id AND p.photoable_type = 'App\\Models\\Room'
LEFT JOIN photo_assemblies pa ON r.id = pa.room_id AND pa.deleted_at IS NULL
LEFT JOIN albums a ON (
    (a.albumable_type = 'App\\Models\\Project' AND a.albumable_id = prop.project_id)
    OR (a.albumable_type = 'App\\Models\\Property' AND a.albumable_id = prop.id)
    OR (a.albumable_type = 'App\\Models\\Room' AND a.albumable_id = r.id)
) AND a.deleted_at IS NULL
WHERE prop.project_id = @project_id
AND prop.deleted_at IS NULL;
