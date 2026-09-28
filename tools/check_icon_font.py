#!/usr/bin/env python3
"""Check that ModeIcons' codepoint arithmetic agrees with icons.json.

The font assigns codepoints by sorted position, and ModeIcons recomputes them
by walking its own copy of the same lists. Nothing enforces that the two agree,
and when they drift a row silently draws another list's icon -- so this asserts
it, and CI or a pre-commit run catches a mismatch instead of a player finding
one.

Usage:  python tools/check_icon_font.py
"""

import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "src", "main", "resources", "assets", "spogtiers",
                    "font", "icons.json")
JAVA = os.path.join(ROOT, "src", "main", "java", "dev", "spog", "tiers",
                    "client", "ModeIcons.java")

FIRST = 0xE000


def font_map():
    with io.open(FONT, encoding="utf-8") as handle:
        providers = json.load(handle)["providers"]
    return {e["file"]: ord("".join(e["chars"])) for e in providers}


def java_tables():
    with io.open(JAVA, encoding="utf-8") as handle:
        source = handle.read()

    order = re.search(r"ORDER = List\.of\((.*?)\);", source, re.S).group(1)
    order = [x.strip().split(".")[-1].lower()
             for x in order.split(",") if x.strip()]

    start = source.index("MODES = Map.of(")
    block = source[start:source.index(";", start)]
    modes = {}
    # Each entry is "TierList.NAME, List.of(...)"; split on the key so a
    # greedy match cannot run one list's names into the next.
    for match in re.finditer(r"TierList\.(\w+),\s*List\.of\((.*?)\)(?=,\s*\n\s*TierList\.|\s*$)",
                             block, re.S):
        modes[match.group(1).lower()] = re.findall(r'"([a-z_]+)"', match.group(2))

    constants = {
        name: int(value, 16)
        for name, value in re.findall(r"(\w+_CODEPOINT) = 0x([0-9A-Fa-f]+);", source)
    }
    return order, modes, constants


def main():
    glyphs = font_map()
    order, modes, constants = java_tables()

    problems = 0
    checked = 0
    offset = 0
    for key in order:
        names = modes.get(key)
        if names is None:
            print(f"ModeIcons has no MODES entry for {key}")
            problems += 1
            continue
        for index, name in enumerate(names):
            path = f"spogtiers:gui/modes/{key}/{name}.png"
            want = FIRST + offset + index
            got = glyphs.get(path)
            checked += 1
            if got != want:
                seen = "missing" if got is None else f"{got:04X}"
                print(f"  {path}: ModeIcons says {want:04X}, font says {seen}")
                problems += 1
        offset += len(names)

    # The three pinned glyphs sit immediately after the generated ones.
    trailing = [
        ("DOOR_CODEPOINT", "spogtiers:gui/modes/doorsmp/door.png"),
        ("DOOR_RAISED_CODEPOINT", "spogtiers:gui/modes/doorsmp/door_nametag.png"),
        ("DISCORD_CODEPOINT", "spogtiers:gui/discord/logo.png"),
    ]
    for position, (constant, path) in enumerate(trailing):
        want = FIRST + offset + position
        checked += 1
        if constants.get(constant) != want:
            print(f"  {constant}: is {constants.get(constant, 0):04X}, should be {want:04X}")
            problems += 1
        if glyphs.get(path) != want:
            seen = glyphs.get(path)
            seen = "missing" if seen is None else f"{seen:04X}"
            print(f"  {path}: font says {seen}, should be {want:04X}")
            problems += 1

    # Nothing in the font should be unaccounted for.
    expected = {f"spogtiers:gui/modes/{k}/{n}.png"
                for k in order for n in modes.get(k, [])}
    expected.update(path for _, path in trailing)
    for path in sorted(set(glyphs) - expected):
        print(f"  {path}: in the font but no Java entry claims it")
        problems += 1

    print(f"checked {checked} glyphs, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
