# Quick Asset Reference Guide

## Quick Stats
- **Total Assets Migrated:** 601 files
- **PNG Files:** 497 (ready to use)
- **PDF Files:** 99 (need conversion)
- **SVG Files:** 5 (need conversion)
- **Zero Errors:** All files successfully copied

## File Locations

### All Drawable Directories
```bash
app/src/main/res/drawable/
app/src/main/res/drawable-mdpi/
app/src/main/res/drawable-xhdpi/
app/src/main/res/drawable-xxhdpi/
```

## Quick Search Commands

### Find all PNG files
```bash
find app/src/main/res -name "*.png" -type f
```

### Find all PDF files (need conversion)
```bash
find app/src/main/res -name "*.pdf" -type f
```

### Find all SVG files (need conversion)
```bash
find app/src/main/res -name "*.svg" -type f
```

### Search for specific icon
```bash
find app/src/main/res -name "*camera*"
find app/src/main/res -name "*logo*"
find app/src/main/res -name "*bedroom*"
```

## Asset Categories Quick Reference

### Icons (254 files)
- **Countries:** country_us, country_ca, country_gb, country_au, country_nz
- **Functional:** icon_camera, icon_add_note, icon_flash, icon_search, etc.
- **Navigation:** icon_chevron_*, icon_arrow_*, icon_xmark
- **TabBar:** icon_tab_projects, icon_tab_map, icon_tab_people, etc.
- **Objects:** icon_person, icon_phone, icon_envelope, icon_calendar, etc.

### Illustrations (292 files)
- **Damages:** appliances, ceiling, floors, walls, roofing, etc.
- **Damage Types:** water, fire, smoke, wind, mold, asbestos, etc.
- **Equipment:** air_mover, dehumidifier, air_scrubber, drying_mat, etc.
- **Rooms:** living_room, bedroom, kitchen, bathroom, garage, etc.
- **Property:** blueprint, contact, property, single_unit, multi_unit, etc.

### Images (18 files)
- **General:** image_in_progress, update_rocket
- **FLIR:** flir_disconnected, image_error

### Logos (24 files)
- logo_horizontal, logo_vertical, logo_circle, logo_black, logo_white
- logo_facebook, logo_google
- st_logo (ServiceTitan)

## Usage in Kotlin

```kotlin
// Access any drawable
R.drawable.icon_camera
R.drawable.logo_horizontal
R.drawable.living_room

// In XML
android:src="@drawable/icon_camera"

// Programmatically
imageView.setImageResource(R.drawable.bedroom)
```

## Important Files
- **Detailed Report:** `docs/asset_migration_report.txt`
- **Migration Script:** `migrate_ios_assets.py`
- **Summary:** `ASSET_MIGRATION_SUMMARY.md`

## Next Actions Required

1. **Convert PDF files** (99 files) to XML vector drawables or PNGs
2. **Convert SVG files** (5 files) to XML vector drawables
3. **Test all assets** in your Android app
4. **Verify rendering** at different densities

## Verification Commands

```bash
# Count files in each directory
ls app/src/main/res/drawable/ | wc -l
ls app/src/main/res/drawable-mdpi/ | wc -l
ls app/src/main/res/drawable-xhdpi/ | wc -l
ls app/src/main/res/drawable-xxhdpi/ | wc -l

# Total count
find app/src/main/res/drawable* -type f \( -name "*.png" -o -name "*.pdf" -o -name "*.svg" \) | wc -l
```
