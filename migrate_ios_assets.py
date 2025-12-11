#!/usr/bin/env python3
"""
iOS to Android Asset Migration Script
Migrates all image assets from iOS .xcassets to Android drawable directories
"""

import os
import shutil
import re
from pathlib import Path
from collections import defaultdict

# Directories
ROOT_DIR = Path(__file__).parent.resolve()
IOS_ASSETS_DIR = os.environ.get(
    "IOS_ASSETS_DIR",
    "/Users/kilka/GitHub/ios.rocketplantech.com/RocketPlan/Resources/Assets.xcassets",
)
ANDROID_RES_DIR = ROOT_DIR / "app" / "src" / "main" / "res"
REPORT_PATH = ROOT_DIR / "docs" / "asset_migration_report.txt"

# Stats tracking
stats = {
    'total_files': 0,
    'png_files': 0,
    'pdf_files': 0,
    'svg_files': 0,
    'categories': defaultdict(int),
    'density_mappings': defaultdict(int),
    'errors': []
}

def sanitize_android_name(name):
    """Convert iOS naming to Android drawable naming convention"""
    # Remove file extension
    name = os.path.splitext(name)[0]

    # Remove iOS density suffixes (@1x, @2x, @3x)
    name = re.sub(r'@[123]x$', '', name)

    # Convert to lowercase
    name = name.lower()

    # Replace spaces, hyphens, and special chars with underscores
    name = re.sub(r'[\s\-\+]+', '_', name)

    # Remove any non-alphanumeric characters except underscores
    name = re.sub(r'[^a-z0-9_]', '', name)

    # Remove duplicate underscores
    name = re.sub(r'_+', '_', name)

    # Remove leading/trailing underscores
    name = name.strip('_')

    # Ensure it starts with a letter (Android requirement)
    if name and name[0].isdigit():
        name = 'img_' + name

    return name

def get_density_folder(filename):
    """Determine Android density folder based on iOS filename"""
    if '@1x' in filename:
        return 'drawable-mdpi'
    elif '@2x' in filename:
        return 'drawable-xhdpi'
    elif '@3x' in filename:
        return 'drawable-xxhdpi'
    elif filename.endswith('.pdf') or filename.endswith('.svg'):
        return 'drawable'  # Vector assets
    else:
        return 'drawable'  # Default/single resolution

def copy_asset(src_path, category_name):
    """Copy a single asset file to appropriate Android directory"""
    global stats

    filename = os.path.basename(src_path)
    file_ext = os.path.splitext(filename)[1]

    # Determine target directory
    density_folder = get_density_folder(filename)
    target_dir = ANDROID_RES_DIR / density_folder

    # Sanitize filename
    android_name = sanitize_android_name(filename)
    target_filename = android_name + file_ext
    target_path = target_dir / target_filename

    try:
        # Ensure target directory exists
        target_dir.mkdir(parents=True, exist_ok=True)

        # Copy file
        shutil.copy2(src_path, target_path)

        # Update stats
        stats['total_files'] += 1
        stats['categories'][category_name] += 1
        stats['density_mappings'][density_folder] += 1

        if file_ext == '.png':
            stats['png_files'] += 1
        elif file_ext == '.pdf':
            stats['pdf_files'] += 1
        elif file_ext == '.svg':
            stats['svg_files'] += 1

        relative_target = target_path.resolve().relative_to(ROOT_DIR)
        return True, relative_target
    except Exception as e:
        error_msg = f"Error copying {src_path}: {str(e)}"
        stats['errors'].append(error_msg)
        return False, None

def process_imageset(imageset_path, category_name):
    """Process a single .imageset directory"""
    copied_files = []

    for item in os.listdir(imageset_path):
        if item == 'Contents.json':
            continue

        item_path = os.path.join(imageset_path, item)
        if os.path.isfile(item_path):
            ext = os.path.splitext(item)[1].lower()
            if ext in ['.png', '.pdf', '.svg', '.jpg', '.jpeg']:
                success, target_path = copy_asset(item_path, category_name)
                if success:
                    copied_files.append((item, target_path))

    return copied_files

def migrate_category(category_path, category_name):
    """Migrate all assets from a category"""
    print(f"\n{'='*80}")
    print(f"Migrating: {category_name}")
    print(f"{'='*80}")

    category_files = []

    # Find all .imageset directories
    for root, dirs, files in os.walk(category_path):
        for dir_name in dirs:
            if dir_name.endswith('.imageset'):
                imageset_path = os.path.join(root, dir_name)
                imageset_name = dir_name.replace('.imageset', '')

                print(f"  Processing: {imageset_name}")
                copied = process_imageset(imageset_path, category_name)
                category_files.extend(copied)

    print(f"  ✓ Copied {len(category_files)} files from {category_name}")
    return category_files

def main():
    print("="*80)
    print("iOS to Android Asset Migration")
    print("="*80)
    print(f"Source: {IOS_ASSETS_DIR}")
    print(f"Target: {ANDROID_RES_DIR}")
    print("="*80)

    all_migrated_files = {}
    ios_assets_root = Path(IOS_ASSETS_DIR).expanduser()

    # Define all categories to migrate
    categories = [
        ("Icons/Countries", "Country Flags"),
        ("Icons/Functional", "Functional Icons"),
        ("Icons/Navigation", "Navigation Icons"),
        ("Icons/Objects", "Object Icons"),
        ("Icons/PhotosTabBar", "Photos TabBar Icons"),
        ("Icons/TabBar", "TabBar Icons"),
        ("Illustrations/Damages", "Damage Illustrations"),
        ("Illustrations/DamageType", "Damage Type Illustrations"),
        ("Illustrations/EquipmentType", "Equipment Type Illustrations"),
        ("Illustrations/Feeature", "Feature Illustrations"),
        ("Illustrations/Objects", "Object Illustrations"),
        ("Illustrations/Property", "Property Illustrations"),
        ("Illustrations/Rooms", "Room Illustrations"),
        ("Illustrations/Rooms White", "White Room Illustrations"),
        ("Images", "General Images"),
        ("Images/Flir", "FLIR Images"),
        ("Logos", "Logos"),
        ("Logos/Social", "Social Media Logos"),
        ("ServiceTitan", "ServiceTitan Assets"),
    ]

    # Process each category
    for rel_path, display_name in categories:
        full_path = ios_assets_root / rel_path
        if full_path.exists():
            migrated = migrate_category(full_path, display_name)
            if migrated:
                all_migrated_files[display_name] = migrated
        else:
            print(f"\n⚠ Warning: Category not found: {full_path}")

    # Print comprehensive summary
    print("\n" + "="*80)
    print("MIGRATION SUMMARY")
    print("="*80)
    print(f"\nTotal files copied: {stats['total_files']}")
    print(f"  - PNG files: {stats['png_files']}")
    print(f"  - PDF files: {stats['pdf_files']}")
    print(f"  - SVG files: {stats['svg_files']}")

    print(f"\nFiles by Density:")
    for density, count in sorted(stats['density_mappings'].items()):
        print(f"  - {density}: {count} files")

    print(f"\nFiles by Category:")
    for category, count in sorted(stats['categories'].items()):
        print(f"  - {category}: {count} files")

    if stats['pdf_files'] > 0:
        print(f"\n⚠ NOTE: {stats['pdf_files']} PDF files were copied to drawable/")
        print("  PDF files may need to be converted to PNG or XML vector drawables")
        print("  for optimal Android compatibility.")

    if stats['svg_files'] > 0:
        print(f"\n⚠ NOTE: {stats['svg_files']} SVG files were copied to drawable/")
        print("  SVG files should be converted to XML vector drawables")
        print("  for optimal Android compatibility.")

    if stats['errors']:
        print(f"\n⚠ ERRORS ENCOUNTERED: {len(stats['errors'])}")
        for error in stats['errors'][:10]:  # Show first 10 errors
            print(f"  - {error}")
        if len(stats['errors']) > 10:
            print(f"  ... and {len(stats['errors']) - 10} more errors")

    print("\n" + "="*80)
    print("Migration complete!")
    print("="*80)

    # Save detailed report
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(REPORT_PATH, 'w') as f:
        f.write("iOS to Android Asset Migration Report\n")
        f.write("="*80 + "\n\n")
        f.write(f"Total files copied: {stats['total_files']}\n")
        f.write(f"PNG files: {stats['png_files']}\n")
        f.write(f"PDF files: {stats['pdf_files']}\n")
        f.write(f"SVG files: {stats['svg_files']}\n\n")

        f.write("Files by Category:\n")
        for category, count in sorted(stats['categories'].items()):
            f.write(f"  - {category}: {count} files\n")

        f.write("\nFiles by Density:\n")
        for density, count in sorted(stats['density_mappings'].items()):
            f.write(f"  - {density}: {count} files\n")

        if stats['errors']:
            f.write(f"\nErrors ({len(stats['errors'])}):\n")
            for error in stats['errors']:
                f.write(f"  - {error}\n")

        f.write("\nDetailed File List:\n")
        f.write("="*80 + "\n")
        for category, files in sorted(all_migrated_files.items()):
            f.write(f"\n{category}:\n")
            for src_name, target_path in sorted(files):
                f.write(f"  {src_name} → {target_path}\n")

    print(f"\nDetailed report saved to: {REPORT_PATH}")

if __name__ == "__main__":
    main()
