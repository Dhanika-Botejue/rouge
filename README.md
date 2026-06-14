# Rouge

A visual redstone building teacher for Minecraft, built as a Fabric mod that runs on the
client.

Open a chat session, ask Rouge to build something, and it projects the build into the world
as a translucent hologram you follow one block at a time. No external schematic mod is
required. As you place each step's blocks correctly, Rouge detects it locally, says "Great
job!", and advances on its own.

1. Ask. Type `/rouge`, then say something like "build me a 2x2 piston door" or "teach me an
   RS latch".
2. Rouge proposes a build. It either retrieves a matching build from its library or composes a
   bigger one out of known parts, then asks you to confirm.
3. Build it one step at a time. Say yes and Rouge anchors a translucent ghost preview in front
   of you, glowing the blocks to place for this step and explaining why. Place them and the
   next step appears automatically. Repeat until the build is done.

Replies print to your own chat as a purple `[Rouge]` line, so nothing goes to public chat. You
can ask questions during a build at any time.

## Difficulty

Set how much of each step the hologram reveals with `/rouge level`.

* `easy` hides nothing, so the hologram shows every block of the step.
* `medium` hides 30% of each step's blocks, and you work them out and place them yourself.
* `hard` hides half of each step's blocks.

Hidden blocks are still part of the build. You must place them correctly for the step to
complete and advance. Rouge biases hiding toward wiring and logic first and keeps inputs and
outputs visible longest, so the visible scaffold stays meaningful. Whether a block is hidden
or not, Rouge flags wrong placements automatically, computed locally with no API calls.

## Commands

* `/rouge` toggles the building teacher session.
* `/rouge next` skips ahead to the next build step, the same as saying "next".
* `/rouge step` shows the current step's hologram again.
* `/rouge move` places the hologram in front of where you are standing.
* `/rouge stop` cancels the active build and clears the hologram.
* `/rouge level easy|medium|hard` sets the difficulty, hiding 0, 30, or 50% of each step.
* `/rouge load` loads the bundled sample lesson as a hologram, which works without the AI.
* `/rouge solution` places the full solution in the world as the answer key for singleplayer.
* `/rouge check` reports your progress against the solution.
* `/rouge model [id]` shows or switches the chat model.

During a session you mostly just talk. Saying "yes", "no", "next", and "move" drives the flow,
so the commands are optional shortcuts. See [`ROUGE-COMMANDS.md`](ROUGE-COMMANDS.md) for the
full reference.

## The build library

Rouge ships a library of redstone builds under `src/main/resources/rouge/circuits/`.

* Buildable primitives come with verified block data authored by hand, covering logic gates,
  latches, clocks, pulse circuits, piston push, and a redstone lamp. Rouge retrieves or
  stitches these together verbatim, so the hologram is always correct.
* Blueprint builds are described in detail but generated on demand, such as flying machines,
  2x2 and 3x3 piston doors, TNT dupers and cannons, item elevators and sorters, auto farms,
  hidden staircases, and more. Rouge composes these from primitives or generates a build that
  follows the blueprint.

To add a build, drop a new JSON file in that folder and add its id to `IDS` in
`build/CircuitLibrary.java`.

## Requirements

* Minecraft 1.20.1 with the Fabric Loader and Fabric API.
* JDK 21 to run the build. Fabric Loom 1.16 requires Java 21, even though the mod itself
  compiles to Java 17 bytecode, so the built jar runs on a normal 1.20.1 client. The Gradle
  wrapper (`./gradlew`) handles the Gradle version, so you only need to supply a JDK.
* An OpenRouter API key. Sign in at <https://openrouter.ai>, open Keys, and create one. Free
  models work without paid credits.

No extra Minecraft mods are needed. Rouge renders the hologram itself.

## Setup

### 1. Install JDK 21

On this machine Homebrew cannot install a JDK, because the `temurin` cask needs `sudo` and the
`openjdk` formula needs full Xcode. Use a prebuilt Temurin tarball instead, which needs no
admin rights.

```sh
mkdir -p "$HOME/.jdks"
curl -fL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C "$HOME/.jdks"
```

This extracts to `~/.jdks/jdk-21.0.x+y/Contents/Home`. The build JDK is pinned in
`gradle.properties` through `org.gradle.java.home`, so you do not need to export `JAVA_HOME`.
If your JDK ends up at a different path, update that one line in `gradle.properties` to match.

### 2. Add your OpenRouter API key

Put the key in a `.env` file at the project root. This file is gitignored, so never commit it.

```sh
echo 'OPENROUTER_API_KEY=sk-or-...your-key...' > .env
```

The key is loaded in two ways.

* During development with `./gradlew runClient`, `build.gradle` reads `.env` fresh on every
  build and injects the key into the dev client. Reading from `.env` rather than the shell
  environment avoids a stale Gradle daemon serving an old value. If `.env` is absent, it falls
  back to the `OPENROUTER_API_KEY` environment variable.
* For a production install, the mod reads the `OPENROUTER_API_KEY` environment variable
  directly, so set it in the launcher's environment.

## Run (development)

```sh
./gradlew runClient
```

The first launch downloads Minecraft assets and is slow, while later launches are fast. Then
in the game, do the following.

1. Enter a singleplayer world, since the AI call needs you in a world.
2. Press `T` and type `/rouge`, which prints `[Rouge] Session open.`
3. Ask Rouge to build something, for example `teach me a T flip flop` or `build me a flying
   machine`.
4. Say yes to start, then build the glowing translucent blocks. Rouge advances each step as you
   finish it. Set `/rouge level medium` or `hard` to make it hide more.
5. Type `/rouge` again to close the session.

No AI key handy? `/rouge load` loads a bundled sample lesson as a hologram, so the build and
practice loop works without the AI.

### Troubleshooting

* If you see `[Rouge] OpenRouter rejected the API key` or "No auth credentials found", the key
  is not reaching the game. Confirm `.env` contains a valid `OPENROUTER_API_KEY=...` line, then
  force a clean run with `./gradlew --stop` followed by `./gradlew runClient`.
* A `429` response means the free models are rate limited, so wait a few seconds or switch with
  `/rouge model <id>`.
* If no hologram appears, make sure you confirmed the build by saying "yes". The ghost anchors
  a couple of blocks in front of where you were standing. `/rouge step` shows it again, and
  `/rouge move` brings it to you.
* If a step will not advance on its own, note that the diff is matched by base block id and
  orientation is lenient, but every block of the step must be present, including the ones
  hidden by difficulty. `/rouge check` shows what is still missing or wrong, and `/rouge level
  easy` reveals everything.
* The startup log should show `Rouge initialized (model: ...)` with no missing token warning.

## Configuration

* The chat model lives in `src/client/java/dev/dhanika/rouge/ai/OpenRouterConfig.java`, where
  `model` defaults to `openai/gpt-oss-20b:free`. Swapping it is a single line change, and you
  can point it at paid models from <https://openrouter.ai/models>.
* The prompt is `src/main/resources/rouge/system_prompt.txt`, which holds the teacher persona
  and the build directive protocol. It loads from resources, so editing it needs no recompile.
* The sample lesson is `src/main/resources/rouge/sample_solution.json`.
* The difficulty fractions and ghost transparency live in `build/Difficulty.java`, which holds
  the hide fractions for each level, and `render/GhostRenderer.java`, which holds `GHOST_ALPHA`.

## Architecture

The AI never touches the world directly. It emits a build directive inside a
` ```rougebuild ` fence, and the mod resolves that into a `StepPlan`, which is a list of steps
that each carry cumulative block data. The mod confirms with the player and renders it.
`BuildSpec` is the shared contract, a list of `{x,y,z,block,role}` entries that the difficulty
filter, ghost renderer, and local diff all read.

* `ai/` holds a reusable OpenRouter chat client with no Minecraft imports.
* `prompt/` loads the swappable system prompt.
* `build/` holds `CircuitLibrary` and `CircuitPrimitive` as the build database, plus
  `StepPlan`, `BlockEntry`, `BuildSpec`, `Difficulty`, `BuildDiff`, `WorldPlacer`, and
  `BuildDirective`, which resolves retrieve, compose, and custom directives.
* `render/` holds `GhostRenderer`, which draws the translucent step hologram and outlines
  through Fabric `WorldRenderEvents`.
* `teach/` holds `StepSession` for active build state, the anchor, and advancing each step,
  `LessonManager` for the active lesson and difficulty, and `ProactiveTutor` for local nudges
  and advancing on its own.
* `session/` holds the `RougeSession` chat state machine that moves from chat to confirm to
  build, plus the `Affirmation` yes or no parser.
* `chat/` holds chat interception through `ALLOW_CHAT` and local chat output.
* `command/` holds the `/rouge` command and its subcommands.

`render/GhostRenderer` needs no external mod. It renders real block models forced to a low
alpha, so a ghost block is obviously distinct from one you have placed, plus a green outline on
the blocks to place this step and a faint blue outline on the rest.
