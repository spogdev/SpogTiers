#!/usr/bin/env python3
"""Download each tier list's gamemode icons and bake them into mod textures.

Every site ships its own icon set, so a gamemode gets the artwork of whichever
list it came from. MCTiers, SubTiers and PvPTiers serve SVG or PNG from stable
paths; PVPHQ serves PNG under /assets/modes.

SVGs are rasterised with headless Chrome: the icons rely on CSS classes,
gradients and clip paths, so a real browser engine is the only thing that
reproduces them faithfully.

Usage:  python tools/fetch_icons.py
"""

import io
import os
import shutil
import subprocess
import tempfile
import re
import sys
import urllib.request

from PIL import Image

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "assets", "spogtiers", "textures", "gui", "modes",
)
SIZE = 64

# Gamemode keys as the mod models them, mapped to each site's own icon name.
# MCTiers only covers the eight vanilla-adjacent kits, and spells its
# netherite-pot icon "nethop".
MCTIERS = {
    "sword": "sword", "axe": "axe", "mace": "mace", "vanilla": "vanilla",
    "uhc": "uhc", "pot": "pot", "neth_pot": "nethop", "smp": "smp",
}
PVPTIERS = {
    "sword": "sword", "axe": "axe", "mace": "mace", "uhc": "uhc",
    "pot": "pot", "neth_pot": "neth_pot", "smp": "smp", "crystal": "crystal",
}
# PVPHQ abbreviates several filenames: the spear kit is "spear", netherite pot
# is "nethpot" and diamond SMP is "diasmp" -- none match the ids its API
# returns, so these are mapped by hand.
PVPHQ = {
    "sword": "sword", "axe": "axe", "mace": "mace", "uhc": "uhc",
    "pot": "pot", "smp": "smp", "cart": "cart", "spear_mace": "spear",
    "neth_pot": "nethpot", "dia_smp": "diasmp", "crystal": "crystal",
}
# SubTiers uses content-hashed filenames; resolved from its JS bundle at runtime.
SUBTIERS = [
    "bed", "bow", "creeper", "debuff", "dia_crystal", "dia_smp", "elytra",
    "mace", "manhunt", "minecart", "og_vanilla", "speed", "trident",
]


def get(url):
    """Fetch a URL, returning None for anything that is not a 200."""
    request = urllib.request.Request(url, headers={"User-Agent": "SpogTiers-icons/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != 200:
                return None
            return response.read()
    except Exception:  # noqa: BLE001 - a missing icon is not fatal
        return None


def flatten(node, out):
    """Collect drawable leaves out of a reportlab group tree."""
    if isinstance(node, Group):
        for child in node.contents:
            flatten(child, out)
    else:
        out.append(node)


def colour(value):
    if value is None:
        return None
    try:
        return (
            int(value.red * 255),
            int(value.green * 255),
            int(value.blue * 255),
            int(getattr(value, "alpha", 1) * 255),
        )
    except AttributeError:
        return None


CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]


def find_browser():
    for path in CHROME_CANDIDATES:
        if os.path.exists(path):
            return path
    return None


BROWSER = find_browser()


def rasterise(svg_bytes):
    """Render an SVG via headless Chrome.

    Hand-rolling a vector renderer was not worth it: these icons use CSS
    classes, gradients and clip paths, so anything short of a real engine
    loses detail. Chrome ships on the machines this script runs on.
    """
    if BROWSER is None:
        return None

    work = tempfile.mkdtemp(prefix="spogicons-")
    svg_path = os.path.join(work, "icon.svg")
    html_path = os.path.join(work, "icon.html")
    shot_path = os.path.join(work, "shot.png")
    try:
        with open(svg_path, "wb") as handle:
            handle.write(svg_bytes)

        # Transparent page sized to the icon so the screenshot needs no cropping.
        with open(html_path, "w", encoding="utf-8") as handle:
            handle.write(
                "<html><head><style>"
                "html,body{margin:0;padding:0;background:transparent;}"
                f"img{{width:{SIZE}px;height:{SIZE}px;display:block;}}"
                "</style></head><body>"
                f'<img src="icon.svg">'
                "</body></html>"
            )

        subprocess.run(
            [
                BROWSER, "--headless", "--disable-gpu", "--no-sandbox",
                "--hide-scrollbars", "--default-background-color=00000000",
                f"--screenshot={shot_path}",
                f"--window-size={SIZE},{SIZE}",
                html_path,
            ],
            check=False,
            timeout=60,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        if not os.path.exists(shot_path):
            return None
        with Image.open(shot_path) as shot:
            return shot.convert("RGBA").copy()
    finally:
        shutil.rmtree(work, ignore_errors=True)


def normalise(image):
    """Trim transparent margins and centre on a square canvas."""
    image = image.convert("RGBA")
    box = image.getbbox()
    if box:
        image = image.crop(box)
    image.thumbnail((SIZE, SIZE), Image.LANCZOS)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(image, ((SIZE - image.width) // 2, (SIZE - image.height) // 2))
    return canvas


def save(image, list_key, mode_key):
    directory = os.path.join(OUT, list_key)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, mode_key + ".png")
    image.save(path)
    return path


def fetch(url, list_key, mode_key):
    data = get(url)
    if not data:
        return False
    # These sites answer unknown paths with 200 + their SPA shell, which would
    # otherwise be "rendered" as a broken-image placeholder.
    head = data.lstrip()[:14].lower()
    if head.startswith(b"<!doctype html") or head.startswith(b"<html"):
        return False
    try:
        if data.lstrip()[:5] in (b"<svg ", b"<?xml") or url.endswith(".svg"):
            image = rasterise(data)
        else:
            image = Image.open(io.BytesIO(data))
        if image is None:
            return False
        save(normalise(image), list_key, mode_key)
        return True
    except Exception as exc:  # noqa: BLE001 - report and continue
        print(f"    {mode_key}: {exc}")
        return False


def subtiers_map():
    """SubTiers filenames carry a content hash, so read them off the bundle."""
    page = get("https://subtiers.net")
    if not page:
        return {}
    match = re.search(rb'src="(/assets/index-[a-z0-9]+\.js)"', page)
    if not match:
        return {}
    bundle = get("https://subtiers.net" + match.group(1).decode())
    if not bundle:
        return {}
    found = {}
    for name in SUBTIERS:
        hit = re.search(rb'"(/assets/' + name.encode() + rb'-[a-f0-9]+\.svg)"', bundle)
        if hit:
            found[name] = "https://subtiers.net" + hit.group(1).decode()
    return found


def main():
    total = 0

    print("MCTiers")
    for mode, icon in MCTIERS.items():
        if fetch(f"https://mctiers.com/tier_icons/{icon}.svg", "mctiers", mode):
            total += 1

    print("PvPTiers")
    for mode, icon in PVPTIERS.items():
        if fetch(f"https://pvptiers.com/icons/tiers/{icon}.png", "pvptiers", mode):
            total += 1

    print("PVPHQ")
    for mode, icon in PVPHQ.items():
        if fetch(f"https://pvphq.com/assets/modes/{icon}.png", "pvphq", mode):
            total += 1

    print("SubTiers")
    for mode, url in subtiers_map().items():
        if fetch(url, "subtiers", mode):
            total += 1

    print(f"\nwrote {total} icons under {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
