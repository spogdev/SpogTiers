#!/usr/bin/env python3
"""Rebuild the icon widths in assets/minecraft/font/include/space.json.

Essential pads a nameplate by measuring the label with Font.width after
flattening it to a legacy string, which drops our font style: our icon
glyphs are then measured in the default font, where they do not exist, and
its padding strips land inside the plate. Vanilla's own space include is the
fix -- it tells the default font each icon codepoint's true advance.

Those advances are keyed by codepoint, and the codepoints are assigned by
sorted position in icons.json, so adding one icon shifts every width after
it. Run this after tools/generate_icon_font.py whenever icons.json changes.

An advance is what vanilla's bitmap provider computes: the rightmost column
with any opacity, scaled to the glyph's height, rounded, plus one.

Entries outside the icon range (the plain space, the zero-width joiner, and
the narrow and hair spaces at U+E0FE and U+E0FF) are kept as they are.

Usage:  python tools/generate_glyph_advances.py [--check]
"""

import argparse
import json
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets")
FONT = os.path.join(ASSETS, "spogtiers", "font", "icons.json")
SPACE = os.path.join(ASSETS, "minecraft", "font", "include", "space.json")


def advance(entry):
    namespace, path = entry["file"].split(":", 1)
    with Image.open(os.path.join(ASSETS, namespace, "textures", path)) as image:
        alpha = image.convert("RGBA").split()[3]
        width, height = alpha.size
        right = 0
        for x in range(width - 1, -1, -1):
            if any(alpha.getpixel((x, y)) for y in range(height)):
                right = x + 1
                break
    return int(0.5 + right * entry["height"] / height) + 1


def build():
    with open(FONT, encoding="utf-8") as handle:
        glyphs = json.load(handle)["providers"]
    with open(SPACE, encoding="utf-8") as handle:
        space = json.load(handle)
    old = space["providers"][0]["advances"]

    icons = {entry["chars"][0]: advance(entry) for entry in glyphs}
    first = min(ord(c) for c in icons)
    # Anything in the old icon range that is no longer an icon goes; anything
    # outside it is ours for another reason and stays.
    last_old_icon = max([ord(c) for c in old if first <= ord(c) < 0xE0FE] or [first])
    last = max(max(ord(c) for c in icons), last_old_icon)

    keep_before = {c: v for c, v in old.items() if ord(c) < first}
    keep_after = {c: v for c, v in old.items() if ord(c) > last}
    merged = dict(keep_before)
    merged.update(dict(sorted(icons.items())))
    merged.update(keep_after)
    return merged


def render(advances):
    # The file's own convention: escapes for everything below U+E0FE, and the
    # two pixel-padding spaces written as raw characters.
    lines = []
    for char, value in advances.items():
        if ord(char) < 0x80 and char != "‌":
            key = json.dumps(char)
        elif ord(char) >= 0xE0FE:
            key = '"' + char + '"'
        else:
            key = f'"\\u{ord(char):04X}"'
        lines.append(f"                {key}: {value}")
    return (
        "{\n"
        '    "providers": [\n'
        "        {\n"
        '            "type": "space",\n'
        '            "advances": {\n'
        + ",\n".join(lines) + "\n"
        "            }\n"
        "        }\n"
        "    ]\n"
        "}\n"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report whether the file is current without writing")
    args = parser.parse_args()

    text = render(build())
    with open(SPACE, encoding="utf-8") as handle:
        current = handle.read()
    if args.check:
        print("unchanged" if current == text else "WOULD CHANGE")
        return 0 if current == text else 1
    with open(SPACE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print(f"wrote {SPACE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
