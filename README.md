# Rouge

A **visual redstone building teacher** for Minecraft, built as a client-side Fabric mod.

Open a chat session, ask Rouge to build something, and it projects the build into
the world as a **step-by-step hologram** you follow block by block — no external
schematic mod required.

1. **Ask** — `/rouge`, then "build me a 2x2 piston door" or "teach me an RS latch".
2. **Rouge proposes a build** — it either **retrieves** a matching build from its
   library or **composes** a bigger one out of known parts, and asks you to confirm.
3. **Build it step by step** — say **yes** and Rouge anchors a ghost preview in front
   of you, glowing the blocks to place *this* step and explaining *why*. Place them,
   say **next**, and the next step appears. Repeat until it's done.

Replies print to **your own** chat as a purple `[Rouge]` line (client-side; nothing
goes to public chat). You can ask questions mid-build at any time.

### Commands

| Command | What it does |
| --- | --- |
| `/rouge` | Toggle the building-teacher session |
| `/rouge next` | Advance to the next build step (same as saying "next") |
| `/rouge step` | Re-show the current step's hologram |
| `/rouge stop` | Cancel the active build and clear the hologram |

During a session you mostly just **talk** — "yes", "no", and "next" drive the flow,
so the commands are optional shortcuts.

### The build library

Rouge ships a library of redstone builds under
`src/main/resources/rouge/circuits/`:

- **Buildable primitives** with verified, hand-authored block data — logic gates,
  latches, clocks, pulse circuits, piston push, a redstone lamp. These are retrieved
  or stitched together verbatim, so the hologram is always correct.
- **Blueprint builds** — described in detail but generated on demand — flying
  machines, 2x2 / 3x3 piston doors, TNT dupers and cannons, item elevators and
  sorters, auto farms, hidden staircases, and more. Rouge composes these from
  primitives or generates a build that follows the blueprint.

To add a build, drop a new JSON file in that folder and add its id to `IDS` in
`build/CircuitLibrary.java`.

## Requirements

- **Minecraft 1.20.1** with **Fabric Loader** + **Fabric API**
- **JDK 21** to run the build. Fabric Loom 1.16 requires Java 21, even though the
  mod itself compiles to **Java 17** bytecode (so the built jar runs on a normal
  1.20.1 client). The Gradle wrapper (`./gradlew`) handles the Gradle version
  itself — you only need to supply a JDK.
- An **OpenRouter API key** — sign in at <https://openrouter.ai>, open **Keys**,
  and create one. Free models work without paid credits.

## Setup

### 1. Install JDK 21

On this machine, Homebrew can't install a JDK (the `temurin` cask needs `sudo`
and the `openjdk` formula needs full Xcode), so use a prebuilt **Temurin** tarball
— no admin rights required:

```sh
mkdir -p "$HOME/.jdks"
curl -fL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C "$HOME/.jdks"
```

This extracts to `~/.jdks/jdk-21.0.x+y/Contents/Home`. The build JDK is pinned in
`gradle.properties` via `org.gradle.java.home`, so **no `JAVA_HOME` export is
needed**. If your JDK ends up at a different path (e.g. a newer patch version),
update that one line in `gradle.properties` to match.

### 2. Add your OpenRouter API key

Put the key in a `.env` file at the project root. This file is **gitignored —
never commit it**:

```sh
echo 'OPENROUTER_API_KEY=sk-or-...your-key...' > .env
```

How the key is loaded:

- **Development (`./gradlew runClient`):** `build.gradle` reads `.env` fresh on
  every build and injects the key into the dev client. (Reading from `.env` —
  rather than the shell environment — avoids a stale Gradle daemon serving an old
  value.) If `.env` is absent, it falls back to the `OPENROUTER_API_KEY`
  environment variable.
- **Production install:** the mod reads the `OPENROUTER_API_KEY` environment
  variable directly, so set it in the launcher's environment.

## Run (development)

```sh
./gradlew runClient
```

The first launch downloads Minecraft assets and is slow; later launches are fast.
Then in-game:

1. Enter a **singleplayer world** (the AI call needs you in a world).
2. Press `T`, type `/rouge` → `[Rouge] Session open.`
3. Ask Rouge to build something, e.g. `teach me a T flip-flop` or
   `build me a flying machine`.
4. Say **yes** to start, follow the glowing hologram, say **next** between steps.
5. Type `/rouge` again to close the session.

### Troubleshooting

- **`[Rouge] OpenRouter rejected the API key` / "No auth credentials found"** —
  the key isn't reaching the game. Confirm `.env` contains a valid
  `OPENROUTER_API_KEY=...` line, then force a clean run: `./gradlew --stop`
  followed by `./gradlew runClient`.
- **`429` rate limit** — free models are rate-limited; wait a few seconds, or
  switch to another model (see Configuration).
- **No hologram appears** — make sure you confirmed the build (said "yes"); the
  ghost anchors a couple of blocks in front of where you were standing. `/rouge step`
  re-shows it.
- **Startup log** should show `Rouge initialized (model: ...)` with no
  missing-token warning.

## Configuration

- **Model:** in `src/client/java/dev/dhanika/rouge/ai/OpenRouterConfig.java`,
  `model` is the chat model (default `openai/gpt-oss-20b:free`). It's a one-line
  swap — including to paid models — from <https://openrouter.ai/models>.
- **Prompt:** `src/main/resources/rouge/system_prompt.txt` — the teacher persona
  and the build-directive protocol. Loaded from resources, so no recompile needed
  to edit.
- **Builds:** `src/main/resources/rouge/circuits/*.json`.

## Architecture

The AI never touches the world directly. It emits a **build directive** inside a
` ```rougebuild ` fence; the mod resolves that into a `StepPlan` (a list of steps,
each with cumulative block data), confirms with the player, and renders it.

| Package | Responsibility |
| --- | --- |
| `ai/` | Reusable OpenRouter chat client (no Minecraft imports) |
| `prompt/` | Loads the swappable system prompt |
| `build/` | `CircuitLibrary` + `CircuitPrimitive` (the build database), `StepPlan`/`BlockEntry`, and `BuildDirective` (resolves retrieve / compose / custom directives) |
| `render/` | `GhostRenderer` — draws the step hologram via Fabric `WorldRenderEvents` |
| `teach/` | `StepSession` — active build state, anchor, per-step advance |
| `session/` | `RougeSession` chat state machine (chat → confirm → build) + `Affirmation` yes/no parser |
| `chat/` | Chat interception (`ALLOW_CHAT`) + local chat output |
| `command/` | `/rouge` command + subcommands |

`render/GhostRenderer` replaces the old Litematica dependency: it renders real block
models plus a glowing outline directly in the world, so the visual works with no
extra mods installed.
