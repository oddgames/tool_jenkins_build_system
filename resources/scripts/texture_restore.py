#!/usr/bin/env python3
"""
Restore Unity .meta files patched by texture_cap.py using Plastic SCM.

Usage: python texture_restore.py --input <file>
"""
import argparse
import os
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description='Restore patched .meta files via Plastic SCM')
    parser.add_argument('--input', default='texture_cap_modified.txt', help='File listing modified paths')
    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f'[INFO] No modified files list found at {args.input} — nothing to restore')
        return 0

    with open(args.input, encoding='utf-8') as f:
        files = [line.strip() for line in f if line.strip()]

    if not files:
        print('[INFO] No files to restore')
        os.remove(args.input)
        return 0

    print(f'[INFO] Restoring {len(files)} files...')
    errors = 0
    for path in files:
        result = subprocess.run(['cm', 'undo', path], capture_output=True, text=True)
        if result.returncode != 0:
            print(f'[ERROR] Failed to undo {path}: {result.stderr.strip()}', file=sys.stderr)
            errors += 1
        else:
            print(f'[OK] Restored: {path}')

    print(f'\nRestored {len(files) - errors}/{len(files)} files')
    if errors == 0:
        os.remove(args.input)
    else:
        print(f'[WARN] {errors} file(s) failed — run `cm undo . -r` in the Unity project if needed', file=sys.stderr)

    return 0 if errors == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
