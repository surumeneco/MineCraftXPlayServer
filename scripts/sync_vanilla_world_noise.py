#!/usr/bin/env python3
"""Install the unmodified Minecraft 26.2 overworld noise settings under an independent ID."""

import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

SOURCE_URL = (
    "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/26.2/"
    "data/minecraft/worldgen/noise_settings/overworld.json"
)
# Git blob SHA from the 26.2 vanilla asset (protects against asset drift).
EXPECTED_BLOB_SHA = "c0f23154f5517fe40c6af0af1cd4299798c2f6f8"
OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "datapacks/vanilla_world/data/xplay/worldgen/noise_settings/vanilla_overworld.json"
)


def main() -> None:
    with urlopen(SOURCE_URL, timeout=60) as response:
        contents = response.read()
    blob = b"blob " + str(len(contents)).encode("ascii") + b"\x00" + contents
    actual_sha = hashlib.sha1(blob).hexdigest()
    if actual_sha != EXPECTED_BLOB_SHA:
        raise RuntimeError(f"Unexpected vanilla noise settings SHA: {actual_sha}")

    settings = json.loads(contents)
    required = {"noise", "noise_router", "surface_rule", "spawn_target"}
    if not required.issubset(settings):
        raise RuntimeError("Vanilla overworld noise settings are incomplete")
    if settings["noise_router"]["continents"] != "minecraft:overworld/continents":
        raise RuntimeError("Unexpected vanilla noise settings reference")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(contents)
    print(f"Installed Minecraft 26.2 vanilla noise settings: {OUTPUT}")


if __name__ == "__main__":
    main()
