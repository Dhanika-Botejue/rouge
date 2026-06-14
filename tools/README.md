# Rouge circuit tooling

Turn a circuit you built **and tested in-game** into an accurate Rouge circuit
JSON (`src/main/resources/rouge/circuits/<id>.json`), instead of trusting an LLM
to emit working redstone.

> Redstone correctness only proves out when tested in Minecraft. Build it, watch
> it open/close, **then export**. The converter reproduces the geometry exactly;
> you hand-author the teaching prose.

## Setup

```bash
pip install -r tools/requirements.txt
```

## Step 1 — build it in your flat creative world

Build the circuit. Leave it in the **unpowered / open** resting state (lever off,
pistons retracted) so the exported blocks equal what a player physically places.

## Step 2 — export the region to a file

### Option A — Structure Block (no mods, recommended)

1. `/give @s structure_block`
2. Place it next to your build, right-click it → set mode to **Save**.
3. **Structure Name**: e.g. `piston_door`.
4. Set **Relative Position** + **Size** so the box wraps the whole build (toggle
   "Show Bounding Box" to see it). Max 48×48×48.
5. Click **SAVE**.
6. The file lands at:
   `<world>/generated/minecraft/structures/piston_door.nbt`
   - Gradle dev run (`./gradlew runClient`): `run/saves/<world>/generated/minecraft/structures/`
   - Normal launcher (macOS): `~/Library/Application Support/minecraft/saves/<world>/generated/minecraft/structures/`

### Option B — Litematica mod (`.litematic`)

Select the area with Litematica's area tool and **Save Schematic** →
`schematics/<name>.litematic`.

## Step 3 — convert

```bash
python3 tools/litematic_to_circuit.py path/to/piston_door.nbt \
  --id piston-door-2x2 --title "2x2 Piston Door" --category door \
  --aliases "2x2 door,piston door,hidden door,redstone door" \
  --inputs lever --steps auto \
  > src/main/resources/rouge/circuits/piston-door-2x2.json
```

The converter normalizes coords to 0-based, strips volatile block-states
(redstone_wire `power`/connections, stairs `shape`), force-resets
`powered`/`extended`/`triggered`/lamp `lit` to the as-placed value (redstone
*torches* stay `lit=true`), and emits **cumulative** steps grouped
structure → components → wiring → input.

## Step 4 — author prose + verify

Replace the `TODO` text in each step's `title`/`explanation` and fill in
`description`. Then launch Rouge, build the circuit from the hologram, and
confirm it behaves (lever opens/closes the door). That in-game check is the real
acceptance gate.
```
