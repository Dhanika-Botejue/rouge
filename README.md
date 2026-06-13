# Redstone Schematic Designer

A browser-based 2D drag-and-drop diagram tool for sketching Minecraft redstone circuits. Drop components onto a grid, wire them together, label them, and export your schematic as a PNG or JSON file.

No installation required — runs entirely in your browser using a tiny local Python server.

---

## Getting Started

```bash
python3 redstone_schematic.py
```

Your default browser will open automatically. Press **Ctrl+C** in the terminal to stop the server.

**Requirements:** Python 3.6+ (standard library only — no `pip install` needed)

---

## Features

### Component Palette

17 Minecraft redstone components are available in the left-hand palette, each rendered as a colour-accurate icon:

| Component | Description |
|---|---|
| Redstone Dust | Standard wire segment (draws a + shape) |
| Redstone Dust (X) | Explicit crossing dust tile |
| Redstone Torch | Power source / signal inverter |
| Redstone Block | Always-on power source |
| Redstone Lamp | Output indicator |
| Repeater | Signal repeater / delay (directional) |
| Comparator | Signal comparison / subtraction (directional) |
| Lever | Toggle switch input |
| Button | Momentary input |
| Target Block | Projectile-activated input |
| Note Block | Sound output block |
| Piston | Directional piston (directional) |
| Sticky Piston | Piston that pulls blocks (directional) |
| Observer | Block-change detector (directional) |
| Dispenser | Item dispenser (directional) |
| Hopper | Item transfer block |

### Canvas

- **Infinite grid** — pan and zoom freely across a large workspace
- **Snap-to-grid** — all components align to a 44 px grid automatically
- **Wires** — draw redstone connections between any two components; crossings are allowed (this is a diagram, not a simulator)
- **Labels** — add custom text to any component
- **Rotation** — directional components (repeaters, pistons, observers, etc.) can be rotated in 90° steps

### Navigation

| Action | How |
|---|---|
| Pan | Click and drag on empty canvas space |
| Zoom | Scroll wheel |
| Fit view | Press **F** or click **⤢ Fit** in the toolbar |

### Keyboard Shortcuts

| Key | Action |
|---|---|
| `V` | Switch to Select mode |
| `W` | Toggle Wire mode |
| `R` | Rotate selected component |
| `Del` / `Backspace` | Delete selected component or wire |
| `F` | Fit view to content |
| `Esc` | Cancel current action / deselect |

### Editing

- **Place a component** — drag it from the palette onto the canvas
- **Move a component** — drag it to a new position; connected wires follow automatically
- **Draw a wire** — switch to Wire mode (`W`), click a source component, then click a destination component
- **Delete** — select a component or wire and press `Del`, or right-click anything to delete it immediately
- **Label a component** — double-click it and type a name in the prompt
- **Rotate** — select a component and press `R` or click **⟳ Rotate**

### Save & Export

| Button | Action |
|---|---|
| **💾 Save** | Download the schematic as a `.json` file |
| **📂 Load** | Open a previously saved `.json` file |
| **🖼 PNG** | Export the schematic as a high-resolution PNG image |
| **✦ Clear** | Remove all components and wires (asks for confirmation) |

The schematic is also auto-saved to `localStorage` after every change, so your work is restored automatically if you refresh the page.

---

## Tips

- Use **Redstone Dust** tiles to show wire routing and **wires** (Wire mode) to show logical connections between blocks — the distinction is yours to make.
- Right-click any component or wire for a quick delete without switching modes.
- The **⤢ Fit** button is handy after loading a large schematic to re-centre the view.
- Labels are great for naming inputs (`Clock`, `Switch A`) and outputs (`Door`, `Lamp Out`) to make schematics self-documenting.
- Save your `.json` file often — it stores everything including positions, labels, and the current view.
