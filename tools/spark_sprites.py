#!/usr/bin/env python3
"""Bake the trial chamber spark sprites into greyscale mod textures.

The aura tints its sparks by the player's Door SMP grade, and a tinted draw
multiplies the vertex colour by the texture. Vanilla's own sprite is teal, so
multiplying a yellow C-tier colour through it gave dark green -- the grade was
only readable on the last, near-white frame.

Stripping the sprite to grey fixes that: luminance carries the shape and the
shading along the streak, the vertex colour carries the hue, and every grade
comes out as itself. The brightest pixel of the set is normalised to white so
no grade is dimmed by the texture it is drawn through.

Usage:  python tools/spark_sprites.py [--jar path/to/client.jar]
"""

import argparse
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "spogtiers",
    "textures", "particle",
)
SOURCE = ("assets/minecraft/textures/particle/"
          "trial_spawner_detection_ominous_%d.png")
FRAMES = 5

# Rec. 709, so the streak's own shading survives the conversion in the
# proportions the eye reads it.
LUMA = (0.2126, 0.7152, 0.0722)


def luminance(pixel):
    return LUMA[0] * pixel[0] + LUMA[1] * pixel[1] + LUMA[2] * pixel[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=os.path.join(ROOT, "mappings", "client-26.3.jar"))
    args = parser.parse_args()

    if not os.path.exists(args.jar):
        print(f"no client jar at {args.jar}", file=sys.stderr)
        return 1

    os.makedirs(OUT, exist_ok=True)
    with zipfile.ZipFile(args.jar) as jar:
        frames = []
        for i in range(FRAMES):
            with jar.open(SOURCE % i) as handle:
                frames.append(Image.open(handle).convert("RGBA").copy())

        # One scale across the whole set, not per frame: normalising each on
        # its own would flatten the fade, which is the sprite brightening as
        # the spark dies.
        peak = max(
            (luminance(image.getpixel((x, y)))
             for image in frames
             for x in range(image.width)
             for y in range(image.height)
             if image.getpixel((x, y))[3]),
            default=255.0,
        )

        for i, image in enumerate(frames):
            grey = Image.new("RGBA", image.size, (0, 0, 0, 0))
            for x in range(image.width):
                for y in range(image.height):
                    pixel = image.getpixel((x, y))
                    if not pixel[3]:
                        continue
                    value = min(255, round(luminance(pixel) * 255.0 / peak))
                    grey.putpixel((x, y), (value, value, value, pixel[3]))
            # Cropped to the lit column. Vanilla's sprite is a 1x6 streak in
            # an 8x8 square, which is right for a particle whose quad is sized
            # in world units but wrong for a blit: the GUI draws the whole
            # texture, so seven eighths of transparent padding would stretch
            # the streak sideways to fill it.
            box = grey.getbbox()
            if box:
                grey = grey.crop(box)
            path = os.path.join(OUT, "spark_%d.png" % i)
            grey.save(path)
            print(f"  spark_{i}.png  {grey.width}x{grey.height}")

    print(f"\nwrote {FRAMES} sprites to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
