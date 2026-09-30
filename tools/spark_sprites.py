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
# Three pixels across and up to twelve down: drawn tall and thin so the bands
# survive at the size the aura draws them. A wider sprite squeezed into a thin
# quad loses its detail to the squash, and a thin quad is what a streak is.
FRAMES = [
    [
        ".ab.",
        ".ab.",
        "cab.",
        "cab.",
        ".acb",
        ".acb",
        ".bc.",
        ".bc.",
        ".bcd",
        "..cd",
        "..c.",
        "..d.",
    ],
    [
        ".ab.",
        "cab.",
        ".ab.",
        ".acb",
        ".bc.",
        ".bcd",
        "..c.",
        "..d.",
    ],
    [
        ".aa.",
        ".ab.",
        ".acb",
        ".bc.",
        "..cd",
        "..d.",
    ],
    [
        ".aa.",
        ".ab.",
        ".bc.",
        "..c.",
    ],
    [
        ".aa.",
        ".ab.",
    ],
]


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
        path = os.path.join(OUT, "spark_%d.png" % index)
        image.save(path)
        print(f"  spark_{index}.png  {len(rows)} rows")

    print(f"\nwrote {len(FRAMES)} sprites to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
