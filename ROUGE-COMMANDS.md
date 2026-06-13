# Rouge — Command Reference

All commands are client-side and typed into Minecraft chat (`T` then type). Output prints
to **your own** chat as a purple `[Rouge]` line — nothing goes to public chat.

Rouge is a **hologram chat teacher**: ask it to build something and it projects a
step-by-step, **translucent** ghost build in front of you. You build solid blocks to match
it; Rouge detects when each step is correct, says "Great job!", and **moves on automatically**.
Difficulty controls how much of each step the hologram shows.

---

## Core / session

| Command | What it does |
| --- | --- |
| `/rouge` | Toggle the chat session on/off. Open it, ask redstone questions, ask it to build something. |

## Hologram building

These drive an active step-by-step build. You can also just **talk** — saying "yes", "no",
"next", and "move" in chat does the same thing, so these commands are optional shortcuts.
Each step auto-advances when you place its blocks correctly; the commands are for skipping
ahead or repositioning.

| Command | What it does |
| --- | --- |
| `/rouge next` | Skip ahead to the next build step (same as saying "next"). |
| `/rouge step` | Re-show the current step's hologram. |
| `/rouge move` | Re-place the hologram in front of where you're standing now. |
| `/rouge stop` | Cancel the active build and clear the hologram. |

## Difficulty

| Command | What it does |
| --- | --- |
| `/rouge level` | Show usage for the level command. |
| `/rouge level easy` | Show **all** of each step's blocks in the hologram. |
| `/rouge level medium` | Hide **30%** of each step's blocks — you figure those out and place them. |
| `/rouge level hard` | Hide **50%** of each step's blocks. |

Hidden blocks are still part of the build: you must place them correctly for the step to
complete and advance. Rouge biases hiding toward wiring/logic first and keeps inputs/outputs
visible the longest.

## Model selection

| Command | What it does |
| --- | --- |
| `/rouge model` | Show the current chat model. |
| `/rouge model <id>` | Switch the chat model (e.g. `/rouge model openai/gpt-oss-20b:free`). |

## Lessons

| Command | What it does |
| --- | --- |
| `/rouge load` | Load the bundled sample lesson as a hologram (works without the AI). |
| `/rouge solution` | Place the full solution in the world (the answer key — singleplayer only). |
| `/rouge check` | Report your progress vs. the solution (local diff, no API call). |

While you build, Rouge quietly points out wrong placements automatically (computed locally,
so it never spends API calls or rate-limits you).

---

## Conversational shortcuts (no slash needed)

While a `/rouge` session is open, plain chat drives the flow:

| You say | Effect |
| --- | --- |
| (a request, e.g. "teach me a T flip-flop") | Rouge proposes a build and starts the hologram. |
| `yes` / `next` | Confirm / skip to the next step. |
| `no` / `stop` | Decline / cancel the current build. |
| `move` / `here` | Re-place the hologram where you're standing. |
| (any other question) | Routed to the AI — you can ask questions mid-build. |
