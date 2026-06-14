#!/usr/bin/env python3
"""
schematic -> Rouge circuit JSON converter.

Build a circuit in-game, EXPORT the region to a file, then run this to produce a
Rouge circuit JSON (src/main/resources/rouge/circuits/<id>.json).

Accepted input files
--------------------
  *.nbt        vanilla Structure Block export   (NO MODS NEEDED — recommended)
  *.litematic  Litematica mod schematic
  *.schem      Sponge / WorldEdit schematic

Why export instead of trusting an LLM: redstone correctness (quasi-connectivity,
dust->piston powering, signal paths) only proves out when tested in Minecraft. So
build it, watch it open/close, THEN export — the geometry/block-states come out
exact; you only hand-author the teaching prose afterwards.

IMPORTANT: export in the UNPOWERED / "open" resting state (lever off, pistons
retracted) so the recorded blocks equal what a player physically places.

Usage
-----
    python3 litematic_to_circuit.py door.nbt \\
        --id piston-door-2x2 --title "2x2 Piston Door" --category door \\
        --aliases "2x2 door,piston door,hidden door" --inputs lever \\
        > src/main/resources/rouge/circuits/piston-door-2x2.json

Output matches CircuitPrimitive.fromJson: each step's `blocks` is CUMULATIVE
(all prior blocks + the new ones), since StepSession diffs consecutive steps to
decide what glows.
"""

import argparse
import json
import os
import sys

from litemapy import Region, Schematic


# --- block-state normalization -------------------------------------------------
# Drop these property NAMES entirely: auto-computed from neighbours, so they
# differ from a freshly-placed block and must not appear in the hologram.
DROP_NAMES = {"power", "north", "south", "east", "west", "up", "down",
              "shape", "waterlogged"}

# Force to the resting "as you place it" value when present — you can never place
# a powered/extended/triggered component; power arrives later from redstone.
FORCE_GLOBAL = {"powered": "false", "extended": "false", "triggered": "false"}


def normalize_props(block_id: str, props: dict) -> dict:
    out = {}
    for k, v in props.items():
        if k in DROP_NAMES:
            continue
        if k in FORCE_GLOBAL:
            v = FORCE_GLOBAL[k]
        if k == "lit" and "lamp" in block_id:  # lamps rest unlit; torches stay lit
            v = "false"
        out[k] = v
    return out


def block_string(state) -> str:
    block_id = state.id
    props = normalize_props(block_id, dict(state.properties()))
    if not props:
        return block_id
    inner = ",".join(f"{k}={props[k]}" for k in sorted(props))
    return f"{block_id}[{inner}]"


# Terrain blocks skipped by default — flat-world ground that a structure-block
# capture usually sweeps up. Override with --keep-ground; extend with --ignore.
DEFAULT_IGNORE = {
    "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
    "minecraft:podzol", "minecraft:dirt_path", "minecraft:bedrock",
    "minecraft:sand", "minecraft:gravel", "minecraft:snow", "minecraft:snow_block",
    "minecraft:water", "minecraft:lava", "minecraft:short_grass", "minecraft:grass",
    "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
    "minecraft:dead_bush", "minecraft:dandelion", "minecraft:poppy",
}


# --- input loading -------------------------------------------------------------
def load_regions(path: str):
    """Return a list of litemapy Region objects from a .nbt / .litematic / .schem."""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".litematic":
        return list(Schematic.load(path).regions.values())
    import nbtlib
    nbt = nbtlib.load(path)
    if ext in (".schem", ".schematic"):
        region, _ = Region.from_sponge_nbt(nbt)
    else:  # .nbt vanilla structure (default)
        region, _ = Region.from_structure_nbt(nbt)
    return [region]


def collect_blocks(regions, ignore=frozenset()):
    """Non-air, non-ignored blocks as (x, y, z, block_string), 0-based."""
    skip = {"minecraft:air"} | set(ignore)
    raw = []
    for region in regions:
        ox, oy, oz = region.x, region.y, region.z
        for lx in region.xrange():
            for ly in region.yrange():
                for lz in region.zrange():
                    state = region[lx, ly, lz]
                    if state.id in skip:
                        continue
                    raw.append((ox + lx, oy + ly, oz + lz, block_string(state)))
    if not raw:
        return [], (0, 0, 0)
    minx = min(b[0] for b in raw)
    miny = min(b[1] for b in raw)
    minz = min(b[2] for b in raw)
    norm = [(x - minx, y - miny, z - minz, b) for (x, y, z, b) in raw]
    size = (max(b[0] for b in norm) + 1,
            max(b[1] for b in norm) + 1,
            max(b[2] for b in norm) + 1)
    return norm, size


# --- step grouping (mirrors StepSession.inferRole ordering) ---------------------
def role_bucket(block_id: str) -> int:
    s = block_id.lower()
    if "redstone_wire" in s or "repeater" in s or "comparator" in s:
        return 2  # wiring / delay
    if any(t in s for t in ("lever", "button", "pressure_plate",
                            "daylight_detector", "tripwire")):
        return 3  # input (last)
    if any(t in s for t in ("piston", "observer", "lamp", "dispenser", "dropper",
                            "door", "note_block", "hopper", "bell",
                            "redstone_torch", "redstone_block", "target",
                            "slime", "honey")):
        return 1  # components / outputs
    return 0      # structural support


BUCKET_TITLES = {
    0: "Lay the structure",
    1: "Place the components",
    2: "Wire it up with redstone",
    3: "Add the input and test",
}
BUCKET_EXPLANATIONS = {
    0: "Place the solid blocks that frame and support the build. TODO: describe the shape.",
    1: "Add the moving/active parts — match each facing exactly. TODO: describe orientation.",
    2: "Run the redstone so the signal reaches every component. TODO: describe the path.",
    3: "Place the input and trigger it. TODO: describe the expected behaviour.",
}


def build_steps(blocks, mode):
    entries = [{"x": x, "y": y, "z": z, "block": b} for (x, y, z, b) in blocks]
    if mode == "single":
        return [{"title": "Build it",
                 "explanation": "TODO: author the teaching text.",
                 "blocks": sorted(entries, key=lambda e: (e["y"], e["z"], e["x"]))}]
    used = sorted({role_bucket(e["block"]) for e in entries})
    steps, cumulative = [], []
    for bucket in used:
        new = [e for e in entries if role_bucket(e["block"]) == bucket]
        new.sort(key=lambda e: (e["y"], e["z"], e["x"]))
        cumulative = cumulative + new
        steps.append({"title": BUCKET_TITLES[bucket],
                      "explanation": BUCKET_EXPLANATIONS[bucket],
                      "blocks": list(cumulative)})
    return steps


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("schematic", help="input .nbt / .litematic / .schem file")
    ap.add_argument("--id", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--category", default="misc")
    ap.add_argument("--description", default="TODO: describe what this build does.")
    ap.add_argument("--aliases", default="")
    ap.add_argument("--inputs", default="")
    ap.add_argument("--outputs", default="")
    ap.add_argument("--steps", choices=["auto", "single"], default="auto")
    ap.add_argument("--ignore", default="",
                    help="extra block ids to skip, comma-separated")
    ap.add_argument("--keep-ground", action="store_true",
                    help="don't skip the default terrain blocks (dirt/grass/...)")
    ap.add_argument("--source-url", default="")
    ap.add_argument("--author", default="")
    args = ap.parse_args()

    ignore = set() if args.keep_ground else set(DEFAULT_IGNORE)
    for tok in args.ignore.split(","):
        tok = tok.strip()
        if tok:
            ignore.add(tok if ":" in tok else f"minecraft:{tok}")

    blocks, size = collect_blocks(load_regions(args.schematic), ignore)
    if not blocks:
        sys.exit("No non-air blocks found in the schematic.")

    csv = lambda s: [x.strip() for x in s.split(",") if x.strip()]
    out = {
        "id": args.id, "title": args.title, "category": args.category,
        "description": args.description, "aliases": csv(args.aliases),
        "inputs": csv(args.inputs), "outputs": csv(args.outputs),
        "footprint": f"{size[0]}x{size[1]}x{size[2]}",
        "steps": build_steps(blocks, args.steps),
    }
    if args.source_url:
        out["source_url"] = args.source_url
    if args.author:
        out["author"] = args.author

    json.dump(out, sys.stdout, indent=2)
    sys.stdout.write("\n")
    print(f"[ok] {len(blocks)} blocks, footprint {out['footprint']}, "
          f"{len(out['steps'])} step(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
