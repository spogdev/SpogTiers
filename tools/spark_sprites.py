#!/usr/bin/env python3
"""Draw the spark sprites the aura tints.

Shaped after vanilla's trial spawner streak -- a column that shortens and
brightens as it burns out, over five frames -- but textured the way the game
textures a flame rather than copied pixel for pixel. Vanilla's own streak is
literally one pixel wide with three colour steps down it, which reads fine in
its own teal at particle size; tinted by a grade colour and drawn larger it is
just a line.

So these are drawn the way particle/flame.png is: a few discrete brightness
bands, no gradients, pixels stepping in and out so the edge is ragged rather
than straight, and every pixel fully opaque. That last point matters -- vanilla
particle art has no partial alpha, and half-transparent edges are what made an
earlier attempt look like a blurred line instead of pixel art.

Greyscale, because the draw multiplies these by the grade colour: the bands
carry the shape, the vertex colour carries the hue. The bands sit high in the
range since a dark step multiplied through a tint comes out muddy.

Usage:  python tools/spark_sprites.py
"""

import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers",
    "textures", "particle",
)

SIZE = 16

# Four brightness bands, as flame.png uses four colours. Kept high: the
# darkest is still bright enough to read as the grade's colour once tinted.
BANDS = {
    "a": 255,   # the hot core
    "b": 226,
    "c": 196,
    "d": 166,   # the coolest edge
}

# The five frames, drawn on a 16x16 sheet. The streak shortens as the spark
# dies, which is what vanilla's frames do; the ragged flanks are the flame
# idiom, not a straight-sided bar.
#
# One pixel across and up to twelve down, as vanilla's own streak is: all of
# the texture runs along it, none across.
#
# Two earlier attempts added width -- first soft flanks, then pixels stepping
# out into the columns either side -- and both were wrong. A streak is drawn
# about a pixel wide on screen at these sizes, so anything across it either
# blurs into the middle or reads as specks beside the line. The bands down the
# length are what there is room to see.
FRAMES = [
    ["a", "a", "a", "a", "b", "b", "b", "c", "c", "c", "d", "d"],
    ["a", "a", "a", "b", "b", "c", "c", "d"],
    ["a", "a", "b", "b", "c", "d"],
    ["a", "a", "b", "c"],
    ["a", "a"],
]


# How white the very top of a streak burns, and how far down that reaches.
#
# This cannot come from the sprite above. That one is multiplied by the grade
# colour, and a multiply only ever darkens: for a tinted pixel to come out
# near-white the sprite would have to hold values well past 255. So the white
# is a second sprite, drawn over the first, solid at the head and falling away
# down the streak.
HIGHLIGHT_TOP = 235
HIGHLIGHT_REACH = 0.55


def highlight(rows):
    """The white head, as an alpha ramp down the streak."""
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    reach = max(1.0, len(rows) * HIGHLIGHT_REACH)
    for y, row in enumerate(rows):
        # Full at the top and gone by the end of its reach, squared so the
        # falloff is quick rather than a long even wash over the whole streak.
        fade = max(0.0, 1.0 - y / reach)
        alpha = int(round(HIGHLIGHT_TOP * fade * fade))
        if alpha <= 0:
            continue
        for x, cell in enumerate(row):
            if cell == ".":
                continue
            image.putpixel((x, y + 1), (255, 255, 255, alpha))
    return image


def main():
    os.makedirs(OUT, exist_ok=True)
    for index, rows in enumerate(FRAMES):
        image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        for y, row in enumerate(rows):
            for x, cell in enumerate(row):
                if cell == ".":
                    continue
                shade = BANDS[cell]
                image.putpixel((x, y + 1), (shade, shade, shade, 255))
        image.save(os.path.join(OUT, "spark_%d.png" % index))
        highlight(rows).save(os.path.join(OUT, "spark_hot_%d.png" % index))
        print(f"  spark_{index}.png + spark_hot_{index}.png  {len(rows)} rows")

    print(f"\nwrote {len(FRAMES) * 2} sprites to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
