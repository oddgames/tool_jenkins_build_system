#!/usr/bin/env python3
"""
Cap maxTextureSize in Unity .meta files to a maximum value.
Only patches entries inside the platformSettings block — the top-level
TextureImporter.maxTextureSize is left untouched.

Usage: python texture_cap.py --assets <path> --max-size <N> [--dry-run] [--output <file>]
"""
import argparse
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

# Only open .meta files whose base asset is an image format Unity recognises as a texture.
# This skips scripts, prefabs, scenes, etc. without reading them (~90%+ of .meta files).
TEXTURE_EXTENSIONS = frozenset((
    '.png', '.jpg', '.jpeg', '.tga', '.psd', '.tif', '.tiff',
    '.exr', '.bmp', '.gif', '.hdr', '.iff', '.webp',
))


def patch_texture_meta(path, max_size, dry_run=False):
    """Cap maxTextureSize values inside platformSettings. Returns True if file was modified."""
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
    except OSError:
        return False

    if 'TextureImporter:' not in content:
        return False

    lines = content.splitlines(True)
    in_platform_settings = False
    platform_indent = -1
    new_lines = []
    changed = False

    for line in lines:
        stripped = line.strip()
        if not stripped:
            new_lines.append(line)
            continue

        indent = len(line) - len(line.lstrip())

        if not in_platform_settings:
            if stripped == 'platformSettings:':
                in_platform_settings = True
                platform_indent = indent
            new_lines.append(line)
        else:
            # Exit when we hit a sibling key at the same indent (e.g. spriteSheet:)
            # List items (-) stay in the block even at the same indent level
            if indent <= platform_indent and not stripped.startswith('-'):
                in_platform_settings = False
                new_lines.append(line)
            else:
                m = re.match(r'^(\s*maxTextureSize: )(\d+)(\n?)$', line)
                if m and int(m.group(2)) > max_size:
                    line = f'{m.group(1)}{max_size}{m.group(3)}'
                    changed = True
                new_lines.append(line)

    if changed and not dry_run:
        with open(path, 'w', encoding='utf-8') as f:
            f.writelines(new_lines)

    return changed


def collect_texture_metas(assets_path):
    """Walk the assets directory and return paths to .meta files for texture assets only."""
    paths = []
    for root, dirs, files in os.walk(assets_path):
        dirs[:] = sorted(d for d in dirs if not d.startswith('.'))
        for name in files:
            if not name.endswith('.meta'):
                continue
            # Check if the base asset (filename minus .meta) has a texture extension
            base = name[:-5]  # strip '.meta'
            _, ext = os.path.splitext(base)
            if ext.lower() in TEXTURE_EXTENSIONS:
                paths.append(os.path.join(root, name))
    return paths


def main():
    parser = argparse.ArgumentParser(description='Cap maxTextureSize in Unity .meta files')
    parser.add_argument('--assets', required=True, help='Path to Unity Assets directory')
    parser.add_argument('--max-size', type=int, required=True, help='Maximum texture size (must be >= 256)')
    parser.add_argument('--dry-run', action='store_true', help='Report changes without writing files')
    parser.add_argument('--output', default='texture_cap_modified.txt', help='Output file listing modified paths')
    args = parser.parse_args()

    if args.max_size < 256:
        print(f'[ERROR] --max-size must be >= 256, got {args.max_size}', file=sys.stderr)
        return 1

    # Step 1: collect only texture .meta files (fast directory walk, no file reads)
    paths = collect_texture_metas(args.assets)
    print(f'Found {len(paths)} texture .meta files to check')

    # Step 2: process in parallel (I/O-bound — threads are effective)
    modified = []
    errors = 0

    def process(path):
        return path, patch_texture_meta(path, args.max_size, args.dry_run)

    with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        futures = {pool.submit(process, p): p for p in paths}
        for future in as_completed(futures):
            path = futures[future]
            try:
                _, was_modified = future.result()
                if was_modified:
                    modified.append(path)
                    prefix = '[DRY RUN] Would cap' if args.dry_run else '[CAP]'
                    print(f'{prefix}: {path}')
            except Exception as e:
                print(f'[ERROR] {path}: {e}', file=sys.stderr)
                errors += 1

    skipped = len(paths) - len(modified) - errors
    print(f'\nDone: {len(modified)} capped, {skipped} already within limit, {errors} errors')

    if not args.dry_run and modified:
        with open(args.output, 'w', encoding='utf-8') as f:
            f.write('\n'.join(modified))
        print(f'Modified paths written to: {args.output}')

    return 0 if errors == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
