#!/usr/bin/env python3
"""Draw the spark sprites the aura tints.

Vanilla's trial spawner streak is a single column of pixels in an 8x8 square:
one hard-edged line, no shading across its width. That reads well in its own
teal, lit by its own glow, but the aura tints these by the player's grade and
draws them small, where a one-pixel line has nothing to look at.

So the shape is vanilla's -- a streak that shortens and brightens as it dies --
drawn with the detail the real thing implies rather than copied pixel for
pixel: a hot core down the middle, dimmer flanks either side so the streak has
an edge that falls off instead of stopping, and a tail that tapers rather than
ending square.

Greyscale, because the draw multiplies this by the grade colour: luminance
carries the shape, the vertex colour carries the hue. Kept well up the range
too, since a dark step multiplied through a tint turns muddy.

The canvas stays 8x8. Cropping to the lit column gave textures 1 pixel wide,
which the GUI was slow to bring in -- the menu showed a big placeholder square
for a frame before the real sprite arrived.

Usage:  python tools/spark_sprites.py
"""

import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers",
    "textures", "particle",
)

SIZE = 8
FRAMES = 5

# How long the streak is in each frame, as vanilla's own five run: six pixels
# down to one, the spark burning out.
LENGTHS = [6, 4, 3, 1, 1]

# How bright the core of each frame is. Vanilla brightens as it shortens, so
# the last flash is the hottest part of a spark's life.
CORES = [0.86, 0.92, 0.96, 1.0, 1.0]

# How dark the dimmest lit pixel is allowed to get. A tinted draw multiplies
# this through the grade colour, and anything much darker comes out muddy.
FLOOR = 0.62

# How much of the core's brightness the pixels either side carry. Low enough
# to read as a falloff rather than as a three-pixel-wide bar.
FLANK = 0.42


def main():
    os.makedirs(OUT, exist_ok=True)
    for frame in range(FRAMES):
        length = LENGTHS[frame]
        core = CORES[frame]
        image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        top = 1
        for row in range(length):
            # Along the streak: brightest at the head, falling towards the
            # tail, which is what gives it a direction.
            along = 1.0 - (row / length) * 0.45
            # The tail thins as well as dims, so it comes to a point.
            taper = 1.0 - (row / max(length, 1)) * 0.5
            for offset, across in ((0, 1.0), (-1, FLANK * taper), (1, FLANK * taper)):
                value = core * along * across
                if value <= 0.0:
                    continue
                level = FLOOR + (1.0 - FLOOR) * value
                shade = min(255, int(round(level * 255)))
                # The flanks fade out rather than being cut off, so the streak
                # has a soft edge at the size it is actually drawn.
                alpha = 255 if offset == 0 else int(round(255 * across))
                if alpha <= 0:
                    continue
                image.putpixel((SIZE // 2 + offset, top + row), (shade, shade, shade, alpha))
        path = os.path.join(OUT, "spark_%d.png" % frame)
        image.save(path)
        print(f"  spark_{frame}.png  {length} long, core {core:.2f}")

    print(f"\nwrote {FRAMES} sprites to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
