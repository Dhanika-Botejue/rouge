# Rouge

A **visual redstone building teacher** for Minecraft, built as a client-side Fabric mod.

Open a chat session, ask Rouge to build something, and it projects the build into the world
as a **step-by-step, translucent hologram** you follow block by block — no external schematic
mod required. As you place each step's blocks correctly, Rouge detects it locally, says
"Great job!", and **advances on its own**.

1. **Ask** — `/rouge`, then "build me a 2x2 piston door" or "teach me an RS latch".
2. **Rouge proposes a build** — it either **retrieves** a matching build from its library or
   **composes** a bigger one out of known parts, and asks you to confirm.
3. **Build it step by step** — say **yes** and Rouge anchors a translucent ghost preview in
   front of you, glowing the blocks to place *this* step and explaining *why*. Place them and
   the next step appears automatically. Repeat until it's done.

Replies print to **your own** chat as a purple `[Rouge]` line (client-side; nothing goes to
public chat). You can ask questions mid-build at any time.

## Difficulty

Set how much of each step the hologram reveals with `/rouge level`:

| Level | Hidden | Meaning |
| --- | --- | --- |
| `easy` | 0% | The hologram shows every block of the step. |
| `medium` | 30% | 30% of each step's blocks are hidden — you work them out and place them. |
| `hard` | 50% | Half of each step's blocks are hidden. |

Hidden blocks are still part of the build: you must place them correctly for the step to
complete and advance. Rouge biases hiding toward wiring/logic first and keeps inputs/outputs
visible longest, so the visible scaffold stays meaningful. Hidden or not, Rouge flags wrong
placements automatically (computed locally — no API calls).

## Commands

| Command | What it does |
| --- | --- |
| `/rouge` | Toggle the building-teacher session |
| `/rouge next` | Skip ahead to the next build step (same as saying "next") |
| `/rouge step` | Re-show the current step's hologram |
| `/rouge move` | Re-place the hologram in front of where you're standing |
| `/rouge stop` | Cancel the active build and clear the hologram |
| `/rouge level easy\|medium\|hard` | Set difficulty (hides 0 / 30 / 50% of each step) |
| `/rouge load` | Load the bundled sample lesson as a hologram (works without the AI) |
| `/rouge solution` | Place the full solution in the world (the answer key — singleplayer) |
| `/rouge check` | Report your progress vs. the solution |
| `/rouge model [id]` | Show or switch the chat model |

During a session you mostly just **talk** — "yes", "no", "next", and "move" drive the flow,
so the commands are optional shortcuts. See [`ROUGE-COMMANDS.md`](ROUGE-COMMANDS.md) for the
full reference.

## The build library

Rouge ships a library of redstone builds under `src/main/resources/rouge/circuits/`:

- **Buildable primitives** with verified, hand-authored block data — logic gates, latches,
  clocks, pulse circuits, piston push, a redstone lamp. These are retrieved or stitched
  together verbatim, so the hologram is always correct.
- **Blueprint builds** — described in detail but generated on demand — flying machines,
  2x2 / 3x3 piston doors, TNT dupers and cannons, item elevators and sorters, auto farms,
  hidden staircases, and more. Rouge composes these from primitives or generates a build that
  follows the blueprint.

To add a build, drop a new JSON file in that folder and add its id to `IDS` in
`build/CircuitLibrary.java`.

## Requirements

- **Minecraft 1.20.1** with **Fabric Loader** + **Fabric API**.
- **JDK 21** to run the build. Fabric Loom 1.16 requires Java 21, even though the mod itself
  compiles to **Java 17** bytecode (so the built jar runs on a normal 1.20.1 client). The
  Gradle wrapper (`./gradlew`) handles the Gradle version — you only need to supply a JDK.
- An **OpenRouter API key** — sign in at <https://openrouter.ai>, open **Keys**, and create
  one. Free models work without paid credits.

No extra Minecraft mods are needed — the hologram is rendered by Rouge itself.

## Setup

### 1. Install JDK 21

On this machine, Homebrew can't install a JDK (the `temurin` cask needs `sudo` and the
`openjdk` formula needs full Xcode), so use a prebuilt **Temurin** tarball — no admin rights
required:

```sh
mkdir -p "$HOME/.jdks"
curl -fL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C "$HOME/.jdks"
```

This extracts to `~/.jdks/jdk-21.0.x+y/Contents/Home`. The build JDK is pinned in
`gradle.properties` via `org.gradle.java.home`, so **no `JAVA_HOME` export is needed**. If
your JDK ends up at a different path, update that one line in `gradle.properties` to match.

### 2. Add your OpenRouter API key

Put the key in a `.env` file at the project root. This file is **gitignored — never commit
it**:

```sh
echo 'OPENROUTER_API_KEY=sk-or-...your-key...' > .env
```

How the key is loaded:

- **Development (`./gradlew runClient`):** `build.gradle` reads `.env` fresh on every build and
  injects the key into the dev client. (Reading from `.env` rather than the shell environment
  avoids a stale Gradle daemon serving an old value.) If `.env` is absent, it falls back to the
  `OPENROUTER_API_KEY` environment variable.
- **Production install:** the mod reads the `OPENROUTER_API_KEY` environment variable directly,
  so set it in the launcher's environment.

## Run (development)

```sh
./gradlew runClient
```

The first launch downloads Minecraft assets and is slow; later launches are fast. Then
in-game:

1. Enter a **singleplayer world** (the AI call needs you in a world).
2. Press `T`, type `/rouge` → `[Rouge] Session open.`
3. Ask Rouge to build something, e.g. `teach me a T flip-flop` or `build me a flying machine`.
4. Say **yes** to start, then build the glowing translucent blocks. Rouge advances each step
   as you finish it. Set `/rouge level medium` (or `hard`) to make it hide more.
5. Type `/rouge` again to close the session.

No AI key handy? `/rouge load` loads a bundled sample lesson as a hologram so the build/practice
loop works without the AI.

### Troubleshooting

- **`[Rouge] OpenRouter rejected the API key` / "No auth credentials found"** — the key isn't
  reaching the game. Confirm `.env` contains a valid `OPENROUTER_API_KEY=...` line, then force a
  clean run: `./gradlew --stop` followed by `./gradlew runClient`.
- **`429` rate limit** — free models are rate-limited; wait a few seconds, or switch with
  `/rouge model <id>`.
- **No hologram appears** — make sure you confirmed the build (said "yes"); the ghost anchors a
  couple of blocks in front of where you were standing. `/rouge step` re-shows it, `/rouge move`
  brings it to you.
- **A step won't auto-advance** — the diff is matched by base block id (orientation is lenient),
  but every block of the step (including difficulty-hidden ones) must be present. `/rouge check`
  shows what's still missing or wrong; `/rouge level easy` reveals everything.
- **Startup log** should show `Rouge initialized (model: ...)` with no missing-token warning.

## Configuration

- **Model:** in `src/client/java/dev/dhanika/rouge/ai/OpenRouterConfig.java`, `model` is the
  chat model (default `openai/gpt-oss-20b:free`). It's a one-line swap — including to paid
  models — from <https://openrouter.ai/models>.
- **Prompt:** `src/main/resources/rouge/system_prompt.txt` — the teacher persona and the
  build-directive protocol. Loaded from resources, so no recompile needed to edit.
- **Sample lesson:** `src/main/resources/rouge/sample_solution.json`.
- **Difficulty fractions / ghost transparency:** `build/Difficulty.java` (per-level hide
  fractions) and `render/GhostRenderer.java` (`GHOST_ALPHA`).

## Architecture

The AI never touches the world directly. It emits a **build directive** inside a
` ```rougebuild ` fence; the mod resolves that into a `StepPlan` (a list of steps, each with
cumulative block data), confirms with the player, and renders it. `BuildSpec` is the shared
contract (a list of `{x,y,z,block,role}`) that the difficulty filter, ghost renderer, and
local diff all read.

| Package | Responsibility |
| --- | --- |
| `ai/` | Reusable OpenRouter chat client (no Minecraft imports) |
| `prompt/` | Loads the swappable system prompt |
| `build/` | `CircuitLibrary` + `CircuitPrimitive` (the build database), `StepPlan`/`BlockEntry`, `BuildSpec`, `Difficulty`, `BuildDiff`, `WorldPlacer`, and `BuildDirective` (resolves retrieve / compose / custom directives) |
| `render/` | `GhostRenderer` — draws the translucent step hologram + outlines via Fabric `WorldRenderEvents` |
| `teach/` | `StepSession` (active build state, anchor, per-step advance), `LessonManager` (active lesson + difficulty), `ProactiveTutor` (local nudges + auto-advance) |
| `session/` | `RougeSession` chat state machine (chat → confirm → build) + `Affirmation` yes/no parser |
| `chat/` | Chat interception (`ALLOW_CHAT`) + local chat output |
| `command/` | `/rouge` command + subcommands |

`render/GhostRenderer` needs no external mod: it renders real block models forced to a low
alpha (so a ghost block is obviously distinct from one you've placed), plus a green outline on
the blocks to place this step and a faint blue outline on the rest.
