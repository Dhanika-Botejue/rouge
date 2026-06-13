#!/usr/bin/env python3
"""
schematic_to_circuit.py
=======================
Convert Minecraft schematic files into rouge circuit JSON format.

Supported input formats:
  .nbt    — Minecraft structure file (vanilla /structure save)
  .litematic — Litematica mod schematic

Output:
  A single .json file with all blocks placed in one step.
  The "steps" array has exactly one entry containing every block.
  Fill in title, description, category, footprint, etc. manually afterward.

Dependencies:
  pip install nbtlib

Usage:
  python3 schematic_to_circuit.py input.nbt output.json
  python3 schematic_to_circuit.py input.litematic output.json

  # With optional metadata:
  python3 schematic_to_circuit.py input.nbt output.json \
      --id my-circuit \
      --title "My Circuit" \
      --category logic \
      --description "Does something cool"
"""

import argparse
import gzip
import json
import re
import struct
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Minimal NBT parser (no external dependencies for the core read path).
# We implement just enough to read structure and litematic files.
# ---------------------------------------------------------------------------

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class _Reader:
    def __init__(self, data: bytes):
        self._data = data
        self._pos = 0

    def read(self, n: int) -> bytes:
        chunk = self._data[self._pos : self._pos + n]
        self._pos += n
        return chunk

    def read_tag(self, tag_type: int):
        if tag_type == TAG_END:
            return None
        elif tag_type == TAG_BYTE:
            return struct.unpack(">b", self.read(1))[0]
        elif tag_type == TAG_SHORT:
            return struct.unpack(">h", self.read(2))[0]
        elif tag_type == TAG_INT:
            return struct.unpack(">i", self.read(4))[0]
        elif tag_type == TAG_LONG:
            return struct.unpack(">q", self.read(8))[0]
        elif tag_type == TAG_FLOAT:
            return struct.unpack(">f", self.read(4))[0]
        elif tag_type == TAG_DOUBLE:
            return struct.unpack(">d", self.read(8))[0]
        elif tag_type == TAG_BYTE_ARRAY:
            length = struct.unpack(">i", self.read(4))[0]
            return list(struct.unpack(f">{length}b", self.read(length)))
        elif tag_type == TAG_STRING:
            length = struct.unpack(">H", self.read(2))[0]
            return self.read(length).decode("utf-8")
        elif tag_type == TAG_LIST:
            elem_type = struct.unpack(">b", self.read(1))[0]
            length = struct.unpack(">i", self.read(4))[0]
            return [self.read_tag(elem_type) for _ in range(length)]
        elif tag_type == TAG_COMPOUND:
            result = {}
            while True:
                child_type = struct.unpack(">b", self.read(1))[0]
                if child_type == TAG_END:
                    break
                name_len = struct.unpack(">H", self.read(2))[0]
                name = self.read(name_len).decode("utf-8")
                result[name] = self.read_tag(child_type)
            return result
        elif tag_type == TAG_INT_ARRAY:
            length = struct.unpack(">i", self.read(4))[0]
            return list(struct.unpack(f">{length}i", self.read(length * 4)))
        elif tag_type == TAG_LONG_ARRAY:
            length = struct.unpack(">i", self.read(4))[0]
            return list(struct.unpack(f">{length}q", self.read(length * 8)))
        else:
            raise ValueError(f"Unknown NBT tag type: {tag_type}")


def _parse_nbt(data: bytes) -> dict:
    """Parse gzipped or raw NBT bytes; return the root compound as a dict."""
    try:
        data = gzip.decompress(data)
    except Exception:
        pass  # already uncompressed
    reader = _Reader(data)
    root_type = struct.unpack(">b", reader.read(1))[0]
    name_len = struct.unpack(">H", reader.read(2))[0]
    reader.read(name_len)  # root name (usually "")
    return reader.read_tag(root_type)


# ---------------------------------------------------------------------------
# Block-state string helpers
# ---------------------------------------------------------------------------

def _props_to_str(name: str, props: dict) -> str:
    """Convert block name + properties dict to Minecraft block state string."""
    if not props:
        return name
    parts = ",".join(f"{k}={v}" for k, v in sorted(props.items()))
    return f"{name}[{parts}]"


# ---------------------------------------------------------------------------
# .nbt (vanilla structure) converter
# ---------------------------------------------------------------------------

def _convert_nbt_structure(root: dict) -> list[dict]:
    """
    Parse a vanilla structure NBT.
    Root keys: palette (list of {Name, Properties}), blocks (list of {pos, state}), size
    """
    palette = root.get("palette", [])
    blocks_data = root.get("blocks", [])

    palette_strs = []
    for entry in palette:
        name = entry.get("Name", "minecraft:air")
        props = entry.get("Properties", {})
        palette_strs.append(_props_to_str(name, props))

    blocks = []
    for b in blocks_data:
        state_idx = b.get("state", 0)
        block_str = palette_strs[state_idx] if state_idx < len(palette_strs) else "minecraft:air"
        if "minecraft:air" in block_str:
            continue
        pos = b.get("pos", [0, 0, 0])
        blocks.append({"x": pos[0], "y": pos[1], "z": pos[2], "block": block_str})

    return blocks


# ---------------------------------------------------------------------------
# .litematic converter
# ---------------------------------------------------------------------------

def _decode_litematic_blocks(block_states: list[int], palette_size: int, volume: int) -> list[int]:
    """
    Decode the packed long array from a Litematica region into palette indices.
    Litematica packs ceil(log2(palette_size)) bits per block, packed into 64-bit longs.
    Unlike vanilla structures, Litematica does NOT span a value across two longs.
    """
    bits = max(2, (palette_size - 1).bit_length())
    mask = (1 << bits) - 1
    values_per_long = 64 // bits

    result = []
    for long_val in block_states:
        # treat as unsigned 64-bit
        if long_val < 0:
            long_val += 1 << 64
        for i in range(values_per_long):
            result.append((long_val >> (i * bits)) & mask)
            if len(result) >= volume:
                break
        if len(result) >= volume:
            break
    return result


def _convert_litematic(root: dict) -> list[dict]:
    """
    Parse a Litematica .litematic NBT.
    Root keys: Regions (compound of region_name -> region), Metadata
    Region keys: BlockStatePalette, BlockStates, Size
    """
    regions = root.get("Regions", {})
    all_blocks: list[dict] = []

    # Global offset so multiple regions don't overlap
    global_y_offset = 0

    for region_name, region in regions.items():
        palette = region.get("BlockStatePalette", [])
        block_states = region.get("BlockStates", [])
        size = region.get("Size", {})

        sx = abs(size.get("x", 1))
        sy = abs(size.get("y", 1))
        sz = abs(size.get("z", 1))
        volume = sx * sy * sz

        palette_strs = []
        for entry in palette:
            name = entry.get("Name", "minecraft:air")
            props = entry.get("Properties", {})
            palette_strs.append(_props_to_str(name, props))

        indices = _decode_litematic_blocks(block_states, max(len(palette), 1), volume)

        for i, idx in enumerate(indices):
            if idx >= len(palette_strs):
                continue
            block_str = palette_strs[idx]
            if "minecraft:air" in block_str:
                continue
            # Litematica order: y * (sz * sx) + z * sx + x
            y = i // (sz * sx)
            z = (i % (sz * sx)) // sx
            x = i % sx
            all_blocks.append({
                "x": x,
                "y": y + global_y_offset,
                "z": z,
                "block": block_str,
            })

        global_y_offset += sy

    return all_blocks


# ---------------------------------------------------------------------------
# Normalise coordinates to start at (0,0,0)
# ---------------------------------------------------------------------------

def _normalise(blocks: list[dict]) -> list[dict]:
    if not blocks:
        return blocks
    min_x = min(b["x"] for b in blocks)
    min_y = min(b["y"] for b in blocks)
    min_z = min(b["z"] for b in blocks)
    return [
        {"x": b["x"] - min_x, "y": b["y"] - min_y, "z": b["z"] - min_z, "block": b["block"]}
        for b in blocks
    ]


def _footprint(blocks: list[dict]) -> str:
    if not blocks:
        return "0x0x0"
    max_x = max(b["x"] for b in blocks) + 1
    max_y = max(b["y"] for b in blocks) + 1
    max_z = max(b["z"] for b in blocks) + 1
    return f"{max_x}x{max_y}x{max_z}"


# ---------------------------------------------------------------------------
# Main conversion entry point
# ---------------------------------------------------------------------------

def convert(src: Path, circuit_id: str, title: str, category: str, description: str) -> dict:
    raw = src.read_bytes()
    root = _parse_nbt(raw)

    suffix = src.suffix.lower()
    if suffix == ".nbt":
        blocks = _convert_nbt_structure(root)
    elif suffix == ".litematic":
        blocks = _convert_litematic(root)
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Use .nbt or .litematic")

    blocks = _normalise(blocks)
    # Sort for deterministic output: y first, then z, then x
    blocks.sort(key=lambda b: (b["y"], b["z"], b["x"]))

    return {
        "id": circuit_id,
        "title": title,
        "category": category,
        "description": description,
        "footprint": _footprint(blocks),
        "aliases": [],
        "steps": [
            {
                "title": "Place all blocks",
                "explanation": "Imported from schematic. Split into logical steps manually for a guided build experience.",
                "blocks": blocks,
            }
        ],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main():
    parser = argparse.ArgumentParser(
        description="Convert a Minecraft schematic (.nbt or .litematic) to rouge circuit JSON.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", help="Path to .nbt or .litematic file")
    parser.add_argument("output", help="Output .json path")
    parser.add_argument("--id", dest="circuit_id", default=None,
                        help="Circuit ID slug (default: derived from filename)")
    parser.add_argument("--title", default=None,
                        help="Circuit title (default: derived from filename)")
    parser.add_argument("--category", default="uncategorized",
                        help="Category (default: uncategorized)")
    parser.add_argument("--description", default="Imported from schematic.",
                        help="Short description")

    args = parser.parse_args()
    src = Path(args.input)
    dst = Path(args.output)

    if not src.exists():
        print(f"Error: file not found: {src}", file=sys.stderr)
        sys.exit(1)

    stem = src.stem
    circuit_id = args.circuit_id or _slug(stem)
    title = args.title or stem.replace("-", " ").replace("_", " ").title()

    print(f"Reading {src} ...")
    result = convert(src, circuit_id, title, args.category, args.description)

    block_count = len(result["steps"][0]["blocks"])
    print(f"Converted {block_count} blocks  |  footprint {result['footprint']}")

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(f"Written to {dst}")
    print()
    print("Next steps:")
    print("  1. Open the output JSON and fill in a proper description.")
    print("  2. Split the single step into logical build steps for guided placement.")
    print(f"  3. Move the file to src/main/resources/rouge/circuits/{circuit_id}.json")


if __name__ == "__main__":
    main()
