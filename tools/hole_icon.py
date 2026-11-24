#!/usr/bin/env python3
"""Bake Hole PvP's icon out of the site's logo.

The logo is a pixel-art badge on a blurred sculk backdrop. This keeps the
badge -- its rim and everything inside it -- and drops the backdrop, then
scales it to the 64x64 every other list's icons use.

The rim is found per row as the outermost long run of its two greys, since
the backdrop is noisy enough to hold the odd grey pixel but never a run.
The "TIERS" lettering hides the rim on the lower half, so that half mirrors
the upper.

Usage:  python tools/hole_icon.py path/to/webpfp.png
        (the logo is https://holepvp.com/webpfp.png)
"""
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = sys.argv[1]
out = os.path.join(ROOT, "src", "main", "resources", "assets", "spogtiers",
                   "textures", "gui", "modes", "subtiers", "hole.png")
im = Image.open(src).convert('RGBA')
W, H = im.size
px = im.load()
RIM = {(73, 73, 73), (33, 33, 33), (72, 72, 72), (32, 32, 32)}
RUN = 12  # rim blocks are ~29px; background noise never runs this long


def rim(x, y):
    return px[x, y][:3] in RIM


def edges(y):
    """Outermost x on each side where a long horizontal run of rim starts."""
    left = right = None
    for x in range(W - RUN):
        if all(rim(x + i, y) for i in range(RUN)):
            left = x
            break
    for x in range(W - 1, RUN, -1):
        if all(rim(x - i, y) for i in range(RUN)):
            right = x
            break
    return left, right


span = {}
for y in range(H):
    l, r = edges(y)
    if l is not None and r - l > 40:
        span[y] = (l, r)
top, bottom = min(span), max(span)
centre = sorted(l + r for l, r in span.values())[len(span) // 2] / 2
# The badge is symmetric: take the wider side on each row, mirrored, so a
# side the lettering covers still gets its true edge.


def half(y):
    l, r = span[min(span, key=lambda k: abs(k - y))]
    return max(centre - l, r - centre)


# The lower half mirrors the upper, where no lettering hides the rim.
rows = {}
for y in range(top, bottom + 1):
    h = half(min(y, top + bottom - y))
    rows[y] = (round(centre - h), round(centre + h))
for y in range(H):
    for x in range(W):
        if y not in rows or not rows[y][0] <= x <= rows[y][1]:
            px[x, y] = (0, 0, 0, 0)
im = im.crop(im.getbbox())
side = max(im.size)
square = Image.new('RGBA', (side, side), (0, 0, 0, 0))
square.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
# Box rather than nearest: the badge's blocks are not a whole number of
# pixels across, so nearest would drop rows of them unevenly.
square.resize((64, 64), Image.BOX).save(out)
print(f"wrote {out}")
