#!/usr/bin/env python3
"""Rebuild assets/spogtiers/font/icons.json from the icon textures on disk.

Every gamemode icon is published twice: as a texture the profile screen blits,
and as a font glyph, because a nametag or a chat line is a Component and there
is no way to blit into one. The glyphs live on the private-use area, assigned
in sorted order -- directory, then filename -- which is the same order
ModeIcons walks to work out a codepoint by index arithmetic.

That ordering is why this is a script rather than a hand-edited file: adding
one icon to a list shifts every codepoint after it, and the two sides have to
agree or rows draw the wrong icon.

The Discord mark is appended after the modes and pinned here, as is anything
else that is not a gamemode icon.

The default font also carries each icon's width, keyed by these codepoints,
so run tools/generate_glyph_advances.py afterwards.

Usage:  python tools/generate_icon_font.py [--check]
"""

import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODES = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers",
    "textures", "gui", "modes",
)
FONT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers", "font", "icons.json",
)

FIRST = 0xE000

# Not gamemode icons, so not walked with the lists.
TRAILING = [
    ("spogtiers:gui/discord/logo.png", 9, 8),
]

# Directories under modes/ that hold something other than a list's icons.
SKIP_DIRS = set()


def providers():
    out = []
    for directory in sorted(os.listdir(MODES)):
        if directory in SKIP_DIRS:
            continue
        path = os.path.join(MODES, directory)
        if not os.path.isdir(path):
            continue
        for name in sorted(os.listdir(path)):
            if name.endswith(".png"):
                out.append(f"spogtiers:gui/modes/{directory}/{name}")
    return out


def build():
    entries = []
    codepoint = FIRST
    for file in providers():
        entries.append({
            "type": "bitmap",
            "file": file,
            "ascent": 7,
            "height": 8,
            "chars": [chr(codepoint)],
        })
        codepoint += 1
    for file, height, ascent in TRAILING:
        entries.append({
            "type": "bitmap",
            "file": file,
            "ascent": ascent,
            "height": height,
            "chars": [chr(codepoint)],
        })
        codepoint += 1
    return {"providers": entries}, codepoint


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report what would change without writing")
    args = parser.parse_args()

    font, after = build()
    text = json.dumps(font, indent=2, ensure_ascii=False) + "\n"

    if args.check:
        with open(FONT, encoding="utf-8") as handle:
            current = handle.read()
        print("unchanged" if current == text else "WOULD CHANGE")
        return 0

    with open(FONT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    modes = len(font["providers"]) - len(TRAILING)
    print(f"{modes} mode glyphs, {FIRST:04X} through {FIRST + modes - 1:04X}")
    for offset, (file, _, _) in enumerate(TRAILING):
        print(f"  {FIRST + modes + offset:04X}  {file.rsplit('/', 1)[-1]}")
    print(f"next free codepoint: {after:04X}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
