#!/usr/bin/env python3
"""Generate identity mappings for deobfuscated Minecraft (26.x and later).

Minecraft 26.x ships deobfuscated: Mojang publishes no mappings file for it and
Fabric's intermediary is an empty 0.0.0 stub, so there is nothing to remap.
Loom still requires a mapping set that declares a `named` namespace, and Fabric
API's class tweakers are authored in `named` -- without one the loader aborts at
startup with "Namespace (named) does not match current runtime namespace".

This builds that mapping set: every class in the vanilla client jar mapped to
its own name across official/intermediary/named.

Usage:
    python mappings/generate_identity_mappings.py [--version 26.1.2]
"""

import argparse
import json
import pathlib
import sys
import urllib.request
import zipfile

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
OUT = pathlib.Path(__file__).parent / "identity-mappings.jar"


def client_jar_url(version: str) -> str:
    with urllib.request.urlopen(MANIFEST) as response:
        manifest = json.load(response)
    for entry in manifest["versions"]:
        if entry["id"] == version:
            with urllib.request.urlopen(entry["url"]) as meta_response:
                meta = json.load(meta_response)
            return meta["downloads"]["client"]["url"]
    raise SystemExit(f"version {version} not found in the Mojang manifest")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="26.1.2")
    args = parser.parse_args()

    url = client_jar_url(args.version)
    print(f"downloading {args.version} client jar...")
    cache = OUT.parent / f"client-{args.version}.jar"
    if not cache.exists():
        urllib.request.urlretrieve(url, cache)

    with zipfile.ZipFile(cache) as jar:
        classes = [
            name[:-6]
            for name in jar.namelist()
            if name.endswith(".class") and not name.startswith("META-INF/")
        ]

    if not classes:
        raise SystemExit("no classes found; is this the right jar?")

    lines = ["tiny\t2\t0\tofficial\tintermediary\tnamed"]
    lines += [f"c\t{name}\t{name}\t{name}" for name in sorted(classes)]

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as out:
        out.writestr("mappings/mappings.tiny", "\n".join(lines) + "\n")

    print(f"wrote {OUT} ({len(classes)} classes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
