package dev.dhanika.rouge.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Live redstone signal-path tracer for Rouge's diagnostic system.
 *
 * <p>{@link #traceSignalPath} — the primary entry point. Reads live Minecraft wire
 * POWER values (which the game already computed), walks the signal chain from active
 * sources to unpowered destinations, identifies the exact break point, and runs an
 * in-memory simulation of the proposed fix before reporting it. The output gives the
 * AI exact coordinates and a verified fix so it can emit a confident {@code rougefix}
 * fence without guessing.
 *
 * <p>{@link #isSafeFixForSolution} — validates that AI-proposed blocks don't add
 * permanent power sources or replace components not in the solution design.
 *
 * <p>{@link #trace} — compares the active lesson's solution against the world
 * (orientation, floating, missing wire). Used alongside the trace for lesson mode.
 */
public final class SignalTracer {

    private SignalTracer() {}

    // -------------------------------------------------------------------------
    // Signal path trace — primary entry point
    // -------------------------------------------------------------------------

    /**
     * Traces the live redstone signal path from active power sources through wire and
     * components to unpowered destinations. Uses Minecraft's own computed POWER values
     * rather than re-simulating them. Returns a structured, terse description that
     * includes: source → path → break point → destination, an in-English diagnosis,
     * and a simulated-fix result the AI can directly convert into a {@code rougefix}.
     */
    public static String traceSignalPath() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "";

        BlockPos origin = mc.player.blockPosition();
        ClientLevel world = mc.level;

        // Collect all redstone-related blocks in ±8 XZ / ±3 Y.
        Map<BlockPos, BlockState> area = new LinkedHashMap<>();
        for (int dx = -8; dx <= 8; dx++)
            for (int dy = -3; dy <= 3; dy++)
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState bs = world.getBlockState(p);
                    if (!bs.isAir() && isRedstoneRelated(idOf(bs))) area.put(p, bs);
                }

        if (area.isEmpty()) return "";

        // Classify blocks.
        List<BlockPos> sources = new ArrayList<>();
        List<BlockPos> dests = new ArrayList<>();
        Map<BlockPos, Integer> liveWire = new LinkedHashMap<>(); // pos → power > 0
        Set<BlockPos> deadWire = new LinkedHashSet<>();           // pos with power == 0

        for (Map.Entry<BlockPos, BlockState> e : area.entrySet()) {
            BlockPos p = e.getKey();
            BlockState bs = e.getValue();
            String id = idOf(bs);
            if (isActivePowerSource(id, bs)) sources.add(p);
            if (isUnpoweredDestination(id, bs)) dests.add(p);
            if (id.equals("minecraft:redstone_wire") && bs.hasProperty(BlockStateProperties.POWER)) {
                int pwr = bs.getValue(BlockStateProperties.POWER);
                if (pwr > 0) liveWire.put(p, pwr);
                else deadWire.add(p);
            }
        }

        Set<BlockPos> liveSet = liveWire.keySet();

        // Build the path: walk from each source along decreasing wire power.
        List<BlockPos> path = buildPath(sources, liveWire, area);

        // Find frontier: live wire with the lowest power AND fewer than 2 live cardinal
        // neighbours (i.e. end of the chain, not a junction).
        int minPwr = liveWire.values().stream().mapToInt(i -> i).min().orElse(0);
        List<BlockPos> frontier = liveWire.entrySet().stream()
                .filter(e -> e.getValue() <= minPwr + 1)
                .filter(e -> cardinalNeighbors(e.getKey()).stream().filter(liveSet::contains).count() <= 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Find wire-gap candidates: air positions cardinally adjacent to the frontier
        // that are closer to a destination than the frontier itself.
        List<BlockPos> gaps = new ArrayList<>();
        for (BlockPos f : frontier) {
            for (BlockPos n : cardinalNeighbors(f)) {
                if (area.containsKey(n)) continue; // occupied
                double df = closestDist(f, dests);
                double dn = closestDist(n, dests);
                if (dests.isEmpty() || dn < df + 0.5) { // toward destination, or no known dest
                    gaps.add(n);
                }
            }
        }

        // Also consider step-up / step-down wire gaps (1-block vertical)
        for (BlockPos f : frontier) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos side = f.relative(dir);
                BlockPos up   = side.above();
                BlockPos down = side.below();
                if (!area.containsKey(side) && !area.containsKey(up) && !gaps.contains(up)) {
                    // Step-up gap: f → up is passable
                    double df = closestDist(f, dests);
                    double du = closestDist(up, dests);
                    if (dests.isEmpty() || du < df + 0.5) gaps.add(up);
                }
                if (!area.containsKey(side) && !area.containsKey(down) && !gaps.contains(down)) {
                    double df = closestDist(f, dests);
                    double dd = closestDist(down, dests);
                    if (dests.isEmpty() || dd < df + 0.5) gaps.add(down);
                }
            }
        }

        // Simulate: for each gap position add virtual wire and check whether the
        // simulated power chain now reaches a destination.
        List<SimResult> simResults = new ArrayList<>();
        for (BlockPos gap : gaps) {
            int gapPwr = -1;
            for (BlockPos n : cardinalNeighbors(gap)) {
                if (liveWire.containsKey(n)) gapPwr = Math.max(gapPwr, liveWire.get(n) - 1);
            }
            // Also check vertical neighbors
            if (liveWire.containsKey(gap.above())) gapPwr = Math.max(gapPwr, liveWire.get(gap.above()) - 1);
            if (liveWire.containsKey(gap.below())) gapPwr = Math.max(gapPwr, liveWire.get(gap.below()) - 1);

            if (gapPwr <= 0) continue;

            // Would the destination be powered after placing wire here?
            // Check if any destination is directly adjacent to the gap position.
            boolean destAdjacent = dests.stream().anyMatch(d -> manhattanDist(gap, d) == 1);
            // Or: is the gap adjacent to another gap that is adjacent to the destination?
            // (multi-gap chains — keep it to 2 hops for now)
            final int finalGapPwr = gapPwr;
            boolean twoHop = !destAdjacent && dests.stream().anyMatch(d -> manhattanDist(gap, d) == 2 && finalGapPwr >= 2);
            simResults.add(new SimResult(gap, gapPwr, destAdjacent, twoHop));
        }

        // Build the output.
        // ALWAYS start with a full component listing so the AI has exact coordinates
        // even when no signal is flowing (lever off, circuit incomplete, etc.).
        StringBuilder sb = new StringBuilder("CIRCUIT TRACE:");
        sb.append("\n  COMPONENTS:");
        int shown = 0;
        for (Map.Entry<BlockPos, BlockState> e : area.entrySet()) {
            if (shown++ >= 20) { sb.append("\n    (+more)"); break; }
            sb.append("\n    ").append(compactLine(e.getKey(), e.getValue()));
        }

        // Signal analysis — only when there is active signal to trace.
        if (!sources.isEmpty() || !liveWire.isEmpty()) {
            if (!sources.isEmpty()) {
                sb.append("\n  SOURCES:");
                sources.forEach(p -> sb.append("\n    ").append(compactLine(p, area.get(p))));
            }

            if (!path.isEmpty()) {
                sb.append("\n  SIGNAL PATH (high→low power):");
                int pathShown = 0;
                for (BlockPos p : path) {
                    if (pathShown++ >= 8) { sb.append("\n    ..."); break; }
                    int pwr = liveWire.getOrDefault(p, 0);
                    sb.append("\n    wire@").append(coord(p)).append(" pwr=").append(pwr);
                }
            } else if (!liveWire.isEmpty()) {
                int max = liveWire.values().stream().mapToInt(i -> i).max().orElse(0);
                sb.append("\n  LIVE WIRE: ").append(liveWire.size()).append(" blocks, strength ").append(max).append("→").append(minPwr);
            }

            // Dead wire adjacent to live chain (something blocking signal).
            deadWire.stream()
                    .filter(d -> cardinalNeighbors(d).stream().anyMatch(liveSet::contains))
                    .forEach(d -> sb.append("\n  BLOCKED: wire@").append(coord(d)).append(" pwr=0 adjacent to live chain"));

            // Gaps.
            if (!gaps.isEmpty()) {
                sb.append("\n  GAP(S) — air where wire expected:");
                gaps.stream().limit(5).forEach(g -> sb.append("\n    @").append(coord(g)));
            }

            // Destinations.
            if (!dests.isEmpty()) {
                sb.append("\n  DESTINATION(S):");
                for (BlockPos d : dests) {
                    boolean adjacent = cardinalNeighbors(d).stream().anyMatch(liveSet::contains);
                    sb.append("\n    ").append(compactLine(d, area.get(d)));
                    sb.append(adjacent ? " ← signal at border, check facing" : " ← NOT REACHED");
                }
            }

            // Diagnosis.
            if (!gaps.isEmpty() && !dests.isEmpty()) {
                sb.append("\n  DIAGNOSIS: Wire gap(s) cut signal before reaching destination.");
            } else if (!gaps.isEmpty()) {
                sb.append("\n  DIAGNOSIS: Wire chain ends prematurely; ").append(gaps.size()).append(" gap(s).");
            } else if (!dests.isEmpty()) {
                sb.append("\n  DIAGNOSIS: Signal present but destination not reached — check facing/orientation.");
            }

            // Simulation.
            if (!simResults.isEmpty()) {
                sb.append("\n  SIMULATION:");
                for (SimResult r : simResults) {
                    sb.append("\n    Place wire@").append(coord(r.pos)).append(" (pwr≈").append(r.estimatedPower).append(")");
                    if (r.reachesDestDirect) sb.append(" → destination receives signal. ✓ FIX VERIFIED");
                    else if (r.reachesDestTwoHop) sb.append(" → destination within 2 blocks; fill remaining gap(s).");
                    else sb.append(" → extends chain, destination still not reached.");
                }
            }
        } else {
            // No active signal — tell the AI so it doesn't think data is missing.
            sb.append("\n  NOTE: No active power source detected (lever/button may be OFF, or no source placed).");
            if (!deadWire.isEmpty()) sb.append(" Wire is present but carries no signal.");
            if (!dests.isEmpty()) {
                sb.append("\n  DESTINATION(S) (unpowered):");
                dests.forEach(d -> sb.append("\n    ").append(compactLine(d, area.get(d))));
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Fix safety validation
    // -------------------------------------------------------------------------

    /**
     * Returns true when every block in {@code fixBlocks} is safe to place: no permanent
     * power sources (redstone blocks), and if a lesson solution is active, each fix block
     * must match the solution's expected type at that position or be a plain wire/support.
     */
    public static boolean isSafeFixForSolution(
            List<BlockEntry> fixBlocks, BuildSpec solution, BlockPos anchor) {

        for (BlockEntry b : fixBlocks) {
            String fixBase = BuildSpec.baseId(b.block());

            if (fixBase.equals("minecraft:redstone_block")) return false;

            if (solution == null || anchor == null) continue;

            BlockPos fixPos = new BlockPos(b.x(), b.y(), b.z());
            String expectedBase = null;
            for (BuildSpec.BlockEntry s : solution.blocks()) {
                if (anchor.offset(s.x(), s.y(), s.z()).equals(fixPos)) {
                    expectedBase = BuildSpec.baseId(s.block());
                    break;
                }
            }

            if (expectedBase == null) {
                if (!fixBase.equals("minecraft:redstone_wire") && !isSolidSupport(fixBase)) return false;
            } else if (!expectedBase.equals(fixBase)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rewrites each proposed fix block to the <em>logical</em> block for its position before it
     * is placed:
     * <ol>
     *   <li>If a lesson solution covers that position, the only correct fix is the solution's
     *       own block — so the fix block is replaced with the solution's exact block string.
     *       (This is what turns a model's "redstone_block where dust belongs" into the
     *       {@code redstone_wire} the answer key actually specifies.)</li>
     *   <li>Otherwise, any {@code redstone_block} is down-converted to {@code redstone_wire}:
     *       a redstone block is never a valid fix — it brute-forces permanent power instead of
     *       routing the existing signal — so plain dust is the logical substitute.</li>
     * </ol>
     * All other blocks pass through unchanged.
     */
    public static List<BlockEntry> normalizeFixToSolution(
            List<BlockEntry> fixBlocks, BuildSpec solution, BlockPos anchor) {

        List<BlockEntry> out = new ArrayList<>(fixBlocks.size());
        for (BlockEntry b : fixBlocks) {
            String corrected = b.block();
            boolean fromSolution = false;

            if (solution != null && anchor != null) {
                BlockPos fixPos = new BlockPos(b.x(), b.y(), b.z());
                for (BuildSpec.BlockEntry s : solution.blocks()) {
                    if (anchor.offset(s.x(), s.y(), s.z()).equals(fixPos)) {
                        corrected = s.block();
                        fromSolution = true;
                        break;
                    }
                }
            }

            // A redstone_block the design didn't ask for is replaced with dust.
            if (!fromSolution && BuildSpec.baseId(corrected).equals("minecraft:redstone_block")) {
                corrected = "minecraft:redstone_wire";
            }

            out.add(new BlockEntry(b.x(), b.y(), b.z(), corrected));
        }
        return out;
    }

    private static boolean isSolidSupport(String id) {
        return id.equals("minecraft:stone") || id.equals("minecraft:cobblestone")
                || id.equals("minecraft:smooth_stone") || id.contains("planks")
                || id.contains("concrete") || id.contains("terracotta")
                || id.contains("deepslate") || id.equals("minecraft:dirt")
                || id.equals("minecraft:grass_block");
    }

    // -------------------------------------------------------------------------
    // Solution trace — compares expected build against world (for lesson mode)
    // -------------------------------------------------------------------------

    public static String trace(BuildSpec solution, BlockPos anchor) {
        if (solution == null || anchor == null) return "";
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return "";

        List<String> orientationIssues = new ArrayList<>();
        List<String> signalStates = new ArrayList<>();
        List<String> floatingComponents = new ArrayList<>();
        List<String> missingWires = new ArrayList<>();
        Map<String, Integer> wirePowers = new LinkedHashMap<>();

        for (BuildSpec.BlockEntry entry : solution.blocks()) {
            BlockPos pos = anchor.offset(entry.x(), entry.y(), entry.z());
            BlockState actual = level.getBlockState(pos);
            String actualId = idOf(actual);
            String expectedId = BuildSpec.baseId(entry.block());
            String coord = entry.x() + "," + entry.y() + "," + entry.z();

            if (actual.isAir()) {
                if (expectedId.equals("minecraft:redstone_wire")) missingWires.add(coord);
                continue;
            }
            if (!actualId.equals(expectedId)) continue;

            Map<String, String> expectedProps = parseProps(entry.block());

            String expectedFacing = expectedProps.get("facing");
            if (expectedFacing != null) {
                String actualFacing = readFacing(actual);
                if (actualFacing != null && !actualFacing.equals(expectedFacing)) {
                    orientationIssues.add(label(expectedId) + " @" + coord
                            + ": facing " + actualFacing + " but should face " + expectedFacing);
                }
            }

            String expectedFace = expectedProps.get("face");
            if (expectedFace != null && actual.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                String actualFace = actual.getValue(BlockStateProperties.ATTACH_FACE).getSerializedName();
                if (!actualFace.equals(expectedFace)) {
                    orientationIssues.add(label(expectedId) + " @" + coord
                            + ": attached to " + actualFace + " but expected face=" + expectedFace);
                }
            }

            String expectedDelay = expectedProps.get("delay");
            if (expectedDelay != null && actual.hasProperty(BlockStateProperties.DELAY)) {
                int actualDelay = actual.getValue(BlockStateProperties.DELAY);
                if (!expectedDelay.equals(String.valueOf(actualDelay))) {
                    signalStates.add(label(expectedId) + " @" + coord
                            + ": delay=" + actualDelay + " (expected " + expectedDelay + ")");
                }
            }

            String expectedMode = expectedProps.get("mode");
            if (expectedMode != null && actual.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
                String actualMode = actual.getValue(BlockStateProperties.MODE_COMPARATOR).getSerializedName();
                if (!actualMode.equals(expectedMode)) {
                    signalStates.add("comparator @" + coord + ": mode=" + actualMode
                            + " (expected " + expectedMode + ")");
                }
            }

            if (actual.hasProperty(BlockStateProperties.LIT)) {
                boolean lit = actual.getValue(BlockStateProperties.LIT);
                if (expectedId.contains("redstone_torch") || expectedId.contains("redstone_wall_torch")) {
                    signalStates.add("torch @" + coord + ": " + (lit ? "ON" : "OFF — being extinguished"));
                } else if (expectedId.contains("redstone_lamp")) {
                    signalStates.add("lamp @" + coord + ": " + (lit ? "ON" : "OFF — no power"));
                }
            }
            if (actual.hasProperty(BlockStateProperties.POWERED)) {
                boolean powered = actual.getValue(BlockStateProperties.POWERED);
                if (expectedId.contains("repeater") || expectedId.contains("comparator")
                        || expectedId.contains("observer")) {
                    signalStates.add(label(expectedId) + " @" + coord + ": "
                            + (powered ? "passing signal" : "no signal through it"));
                } else if (expectedId.contains("piston")) {
                    signalStates.add(label(expectedId) + " @" + coord + ": "
                            + (powered ? "receiving signal" : "no signal"));
                }
            }
            if (actual.hasProperty(BlockStateProperties.EXTENDED)) {
                signalStates.add(label(expectedId) + " @" + coord + ": "
                        + (actual.getValue(BlockStateProperties.EXTENDED) ? "extended" : "retracted"));
            }
            if (actual.hasProperty(BlockStateProperties.LOCKED) && actual.getValue(BlockStateProperties.LOCKED)) {
                signalStates.add("repeater @" + coord + ": LOCKED — can't pass signal");
            }
            if (expectedId.equals("minecraft:redstone_wire") && actual.hasProperty(BlockStateProperties.POWER)) {
                wirePowers.put(coord, actual.getValue(BlockStateProperties.POWER));
            }
            if (needsFloorSupport(expectedId, expectedProps) && level.getBlockState(pos.below()).isAir()) {
                floatingComponents.add(label(expectedId) + " @" + coord + " — no solid block below");
            }
        }

        StringBuilder sb = new StringBuilder("SOLUTION VS WORLD:");
        if (!orientationIssues.isEmpty()) {
            sb.append("\nORIENTATION ERRORS:");
            orientationIssues.forEach(s -> sb.append("\n  - ").append(s));
        }
        if (!floatingComponents.isEmpty()) {
            sb.append("\nFLOATING (no support below):");
            floatingComponents.forEach(s -> sb.append("\n  - ").append(s));
        }
        if (!missingWires.isEmpty()) {
            sb.append("\nMISSING WIRE:");
            missingWires.forEach(c -> sb.append("\n  - @").append(c));
        }
        if (!wirePowers.isEmpty()) {
            List<String> live = new ArrayList<>(), dead = new ArrayList<>();
            wirePowers.forEach((c, p) -> (p > 0 ? live : dead).add(c + "(pwr=" + p + ")"));
            sb.append("\nWIRE POWER:");
            live.forEach(s -> sb.append("\n  + ").append(s));
            dead.forEach(s -> sb.append("\n  - ").append(s).append(" dead"));
        }
        if (!signalStates.isEmpty()) {
            sb.append("\nLIVE STATES:");
            signalStates.forEach(s -> sb.append("\n  ").append(s));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Path-building helpers
    // -------------------------------------------------------------------------

    /**
     * Walks from each source outward along live wire (following the decreasing POWER
     * gradient) to produce an ordered list of wire positions from strongest to weakest.
     * Stops when the next position has unexpected power or there is no live continuation.
     */
    private static List<BlockPos> buildPath(
            List<BlockPos> sources, Map<BlockPos, Integer> liveWire, Map<BlockPos, BlockState> area) {

        if (liveWire.isEmpty()) return Collections.emptyList();

        // Find the live wire with the highest power adjacent to any source.
        BlockPos start = null;
        int startPwr = 0;
        for (BlockPos src : sources) {
            for (BlockPos n : allNeighbors(src)) {
                Integer pwr = liveWire.get(n);
                if (pwr != null && pwr > startPwr) {
                    start = n;
                    startPwr = pwr;
                }
            }
        }
        // If no source, start from the highest-power live wire we found.
        if (start == null) {
            start = liveWire.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (start != null) startPwr = liveWire.get(start);
        }
        if (start == null) return Collections.emptyList();

        List<BlockPos> path = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos current = start;
        int currentPwr = startPwr;
        path.add(current);
        visited.add(current);

        // Follow the path greedily: next step is the neighbor at exactly power - 1.
        while (currentPwr > 1) {
            BlockPos next = null;
            int target = currentPwr - 1;
            for (BlockPos n : allNeighbors(current)) {
                Integer pwr = liveWire.get(n);
                if (pwr != null && pwr == target && !visited.contains(n)) {
                    next = n;
                    break;
                }
            }
            if (next == null) break;
            path.add(next);
            visited.add(next);
            current = next;
            currentPwr--;
        }
        return path;
    }

    // -------------------------------------------------------------------------
    // Classification helpers
    // -------------------------------------------------------------------------

    private static boolean isActivePowerSource(String id, BlockState bs) {
        if ((id.contains("lever") || id.contains("button") || id.contains("pressure_plate"))
                && bs.hasProperty(BlockStateProperties.POWERED)
                && bs.getValue(BlockStateProperties.POWERED)) return true;
        if ((id.contains("redstone_torch") || id.contains("redstone_wall_torch"))
                && bs.hasProperty(BlockStateProperties.LIT)
                && bs.getValue(BlockStateProperties.LIT)) return true;
        if (id.equals("minecraft:redstone_block")) return true;
        if (id.contains("observer") && bs.hasProperty(BlockStateProperties.POWERED)
                && bs.getValue(BlockStateProperties.POWERED)) return true;
        return false;
    }

    private static boolean isUnpoweredDestination(String id, BlockState bs) {
        if ((id.contains("piston") || id.contains("sticky_piston"))
                && bs.hasProperty(BlockStateProperties.EXTENDED)
                && !bs.getValue(BlockStateProperties.EXTENDED)) return true;
        if (id.contains("redstone_lamp") && bs.hasProperty(BlockStateProperties.LIT)
                && !bs.getValue(BlockStateProperties.LIT)) return true;
        if ((id.contains("dropper") || id.contains("dispenser"))
                && bs.hasProperty(BlockStateProperties.TRIGGERED)
                && !bs.getValue(BlockStateProperties.TRIGGERED)) return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    private static List<BlockPos> cardinalNeighbors(BlockPos p) {
        return List.of(p.north(), p.south(), p.east(), p.west());
    }

    /** Cardinal neighbors at same Y, plus one step up and down. */
    private static List<BlockPos> allNeighbors(BlockPos p) {
        List<BlockPos> r = new ArrayList<>(cardinalNeighbors(p));
        for (BlockPos c : cardinalNeighbors(p)) { r.add(c.above()); r.add(c.below()); }
        return r;
    }

    private static double closestDist(BlockPos p, List<BlockPos> targets) {
        return targets.stream().mapToDouble(t -> Math.sqrt(p.distSqr(t))).min().orElse(Double.MAX_VALUE);
    }

    private static int manhattanDist(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static String coord(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static String compactLine(BlockPos pos, BlockState state) {
        if (state == null) return "?" + "@" + coord(pos);
        String id = idOf(state);
        String name = id.startsWith("minecraft:") ? id.substring(10) : id;
        StringBuilder sb = new StringBuilder(name).append('@').append(coord(pos));
        String f = readFacing(state);
        if (f != null) sb.append(' ').append(f);
        if (state.hasProperty(BlockStateProperties.POWER))
            sb.append(" pwr=").append(state.getValue(BlockStateProperties.POWER));
        if (state.hasProperty(BlockStateProperties.LIT))
            sb.append(state.getValue(BlockStateProperties.LIT) ? " ON" : " OFF");
        if (state.hasProperty(BlockStateProperties.POWERED))
            sb.append(state.getValue(BlockStateProperties.POWERED) ? " powered" : " unpowered");
        if (state.hasProperty(BlockStateProperties.EXTENDED))
            sb.append(state.getValue(BlockStateProperties.EXTENDED) ? " extended" : " retracted");
        if (state.hasProperty(BlockStateProperties.LOCKED) && state.getValue(BlockStateProperties.LOCKED))
            sb.append(" LOCKED");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Shared helpers (also used by trace())
    // -------------------------------------------------------------------------

    private static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String label(String blockId) {
        String s = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
        return s.replace('_', ' ');
    }

    private static Map<String, String> parseProps(String blockSpec) {
        Map<String, String> result = new LinkedHashMap<>();
        int lb = blockSpec.indexOf('[');
        if (lb < 0) return result;
        String propStr = blockSpec.substring(lb + 1, blockSpec.length() - 1);
        for (String kv : propStr.split(",")) {
            String[] parts = kv.split("=", 2);
            if (parts.length == 2) result.put(parts[0].trim(), parts[1].trim());
        }
        return result;
    }

    private static String readFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING))
            return state.getValue(BlockStateProperties.FACING).getName();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getName();
        return null;
    }

    private static boolean isRedstoneRelated(String id) {
        return id.contains("redstone") || id.contains("repeater") || id.contains("comparator")
                || id.contains("piston") || id.contains("observer") || id.contains("lever")
                || id.contains("button") || id.contains("pressure_plate") || id.contains("target")
                || id.contains("trapped_chest") || id.contains("hopper") || id.contains("dispenser")
                || id.contains("dropper") || id.contains("powered_rail") || id.contains("tripwire");
    }

    private static boolean needsFloorSupport(String blockId, Map<String, String> props) {
        if (blockId.contains("redstone_wall_torch")) return false;
        String face = props.get("face");
        if ("wall".equals(face) || "ceiling".equals(face)) return false;
        return blockId.contains("redstone_wire") || blockId.contains("repeater")
                || blockId.contains("comparator") || blockId.contains("redstone_torch")
                || blockId.contains("lever") || blockId.contains("button")
                || blockId.contains("pressure_plate") || blockId.contains("observer")
                || blockId.contains("piston") || blockId.contains("sticky_piston")
                || blockId.contains("hopper") || blockId.contains("dispenser")
                || blockId.contains("dropper");
    }

    // -------------------------------------------------------------------------
    // Simulation result record
    // -------------------------------------------------------------------------

    private record SimResult(BlockPos pos, int estimatedPower,
                             boolean reachesDestDirect, boolean reachesDestTwoHop) {}
}
