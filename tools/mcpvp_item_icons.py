#!/usr/bin/env python3
"""Bake MCPvP's gamemode icons out of vanilla item textures.

MCPvP ships no icon set of its own, so its rows used hand-drawn artwork that
never matched the other lists. Each kit is named after the item that defines
it, so the item texture is the icon: pulled straight from the client jar, so
they are the real thing rather than a redraw, and scaled to the same 64x64 the
other lists' icons already use.

Two kits are not a plain item sprite:

* shield  -- no version of the game ships a flat shield item texture; the
  inventory icon is a rendered 3D model. The plate and handle are lifted out
  of the entity atlas instead, which is the same artwork the model samples.
* pot     -- a potion sprite is two layers: a greyscale overlay tinted by the
  potion's colour, under the glass bottle. Instant Health is 0xF82423, read
  out of MobEffects rather than guessed.

Usage:  python tools/mcpvp_item_icons.py [--jar path/to/client.jar]
"""

import argparse
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers",
    "textures", "gui", "modes", "mcpvp",
)
SIZE = 64

ITEM = "assets/minecraft/textures/item/%s.png"
SHIELD = "assets/minecraft/textures/entity/shield/shield_base_nopattern.png"

# Instant Health, as MobEffects registers it (16262179).
HEALTH = (0xF8, 0x24, 0x23)

# The file each mode's artwork is stored under, mapped to the item whose
# texture becomes it. The names on the left are the mod's own artwork keys,
# which is what TierList.artworkKey hands the font generator.
ITEMS = {
    "sword": "diamond_sword",
    "shield": "shield",
    "mace": "mace",
    "mcpvp_spear": "diamond_spear",
    "pot": "pot",
    "netherite_pot": "netherite_sword",
    "smp": "ender_pearl",
    "diamond_smp": "diamond_chestplate",
    "crystal": "end_crystal",
    "end_game": "end_crystal",
    "early_game": "golden_apple",
    "late_game": "netherite_chestplate",
    "cart": "tnt_minecart",
    "creeper": "creeper_spawn_egg",
    "bow": "bow",
}


def read(jar, name):
    try:
        with jar.open(name) as handle:
            return Image.open(handle).convert("RGBA").copy()
    except KeyError:
        return None


def shield(jar):
    """The shield's front face, lifted out of the entity atlas.

    No version of the game ships a flat shield item sprite, so this is the
    same artwork the model samples. The box UVs are vanilla's ShieldModel,
    read off the class rather than guessed: a 12x22x1 plate at uv(0,0). In a
    box unwrap the four side faces sit in the row below the top cap, and the
    front is the first full-width one, u=[1,13) v=[1,23) -- the planks with
    their iron rivets and border. The face beside it is the plain back, and
    the handle box at uv(26,0) is the grip behind the shield, not part of
    what you see head-on.
    """
    atlas = read(jar, SHIELD)
    if atlas is None:
        return None
    return atlas.crop((1, 1, 13, 23))


def potion(jar):
    """A splash potion of Health: tinted liquid under the glass."""
    overlay = read(jar, ITEM % "potion_overlay")
    glass = read(jar, ITEM % "splash_potion")
    if overlay is None or glass is None:
        return None
    tinted = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
    for x in range(overlay.width):
        for y in range(overlay.height):
            r, g, b, a = overlay.getpixel((x, y))
            if not a:
                continue
            # The overlay is greyscale; vanilla multiplies it by the colour.
            tinted.putpixel((x, y), (
                r * HEALTH[0] // 255,
                g * HEALTH[1] // 255,
                b * HEALTH[2] // 255,
                a,
            ))
    tinted.alpha_composite(glass)
    return tinted


def scale(image):
    """Trim, then scale up on whole pixels and centre on a 64x64 canvas.

    Nearest-neighbour on purpose: these are 16-pixel sprites, and anything
    smoothing them turns crisp pixel art into mush at this size.
    """
    box = image.getbbox()
    if box:
        image = image.crop(box)
    factor = max(1, min(SIZE // image.width, SIZE // image.height))
    image = image.resize((image.width * factor, image.height * factor), Image.NEAREST)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(image, ((SIZE - image.width) // 2, (SIZE - image.height) // 2))
    return canvas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=os.path.join(ROOT, "mappings", "client-26.3.jar"))
    args = parser.parse_args()

    if not os.path.exists(args.jar):
        print(f"no client jar at {args.jar}", file=sys.stderr)
        return 1

    os.makedirs(OUT, exist_ok=True)
    written = 0
    with zipfile.ZipFile(args.jar) as jar:
        for mode, item in sorted(ITEMS.items()):
            if item == "shield":
                image = shield(jar)
            elif item == "pot":
                image = potion(jar)
            else:
                image = read(jar, ITEM % item)
            if image is None:
                print(f"  {mode}: {item} not in the jar")
                continue
            scale(image).save(os.path.join(OUT, mode + ".png"))
            print(f"  {mode:14s} <- {item}")
            written += 1

    print(f"\nwrote {written} icons to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
