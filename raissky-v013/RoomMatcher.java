package dev.raistey.raisskysecrets;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RoomMatcher {
    private static final int SCAN_RADIUS_XZ = 8;
    private static final int SCAN_RADIUS_Y = 7;
    private static final int MIN_EVIDENCE = 7;
    private static final int MAX_MISMATCHES = 0;

    private RoomDatabase database;
    private Models.RoomCandidate matched;
    private List<Models.RoomCandidate> lastCandidates = List.of();

    void setDatabase(RoomDatabase database) {
        this.database = database;
        this.matched = null;
        this.lastCandidates = List.of();
    }

    Models.RoomCandidate matched() {
        return matched;
    }

    int candidateCount() {
        return lastCandidates.size();
    }

    void clearMatch() {
        matched = null;
        lastCandidates = List.of();
    }

    void update(Minecraft mc) {
        if (database == null || mc.player == null || mc.level == null) return;
        Models.Cell currentCell = physicalRoomCell(mc.player.getX(), mc.player.getZ());
        if (matched != null && matched.containsCell(currentCell)) return;
        matched = null;

        List<Models.RoomCandidate> candidates = buildCandidates(currentCell);
        if (candidates.isEmpty()) {
            lastCandidates = List.of();
            return;
        }

        List<ObservedBlock> observed = observeBlocks(mc, BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        if (observed.size() < MIN_EVIDENCE) {
            lastCandidates = candidates;
            return;
        }

        List<ScoredCandidate> survivors = new ArrayList<>();
        for (Models.RoomCandidate candidate : candidates) {
            ScoredCandidate score = score(candidate, observed);
            if (score.mismatches <= MAX_MISMATCHES && score.matches >= MIN_EVIDENCE) survivors.add(score);
        }
        survivors.sort((a, b) -> Integer.compare(b.matches, a.matches));
        lastCandidates = survivors.stream().map(s -> s.candidate).toList();

        if (!survivors.isEmpty()) {
            ScoredCandidate first = survivors.getFirst();
            boolean decisive = survivors.size() == 1 || first.matches >= survivors.get(1).matches + 4;
            if (decisive && first.matches >= MIN_EVIDENCE) {
                matched = first.candidate;
                lastCandidates = List.of(matched);
                RaisSkySecrets.LOGGER.info("Matched dungeon room '{}' ({}, {}, evidence={})",
                        matched.definition().roomName(), matched.definition().shape(), matched.direction(), first.matches);
            }
        }
    }

    List<Models.WorldWaypoint> worldWaypoints(boolean showUtility) {
        if (matched == null) return List.of();
        List<Models.WorldWaypoint> result = new ArrayList<>();
        for (Models.RelativeSecret secret : matched.definition().secrets()) {
            if (!showUtility && secret.isUtility()) continue;
            BlockPos actual = matched.relativeToActual(secret);
            result.add(new Models.WorldWaypoint(secret.secretIndex(), secret.name(), secret.category(), actual, secret.isUtility()));
        }
        return List.copyOf(result);
    }

    private ScoredCandidate score(Models.RoomCandidate candidate, List<ObservedBlock> observed) {
        int matches = 0;
        int mismatches = 0;
        Models.RoomDefinition def = candidate.definition();
        for (ObservedBlock block : observed) {
            BlockPos rel = candidate.actualToRelative(block.pos);
            int rx = rel.getX();
            int rz = rel.getZ();
            if (rx < def.minX() || rx > def.maxX() || rz < def.minZ() || rz > def.maxZ()) continue;
            int packed = pack(rx, rel.getY(), rz, block.numericId);
            if (Arrays.binarySearch(def.skeleton(), packed) >= 0) matches++;
            else if (++mismatches > MAX_MISMATCHES) break;
        }
        return new ScoredCandidate(candidate, matches, mismatches);
    }

    private List<ObservedBlock> observeBlocks(Minecraft mc, BlockPos center) {
        List<ObservedBlock> result = new ArrayList<>();
        for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ; dx++) {
            for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz++) {
                for (int dy = -SCAN_RADIUS_Y; dy <= SCAN_RADIUS_Y; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = mc.level.getBlockState(pos);
                    byte id = numericId(state);
                    if (id != 0) result.add(new ObservedBlock(pos, id));
                }
            }
        }
        return result;
    }

    private static byte numericId(BlockState state) {
        var identifier = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (identifier == null) return 0;
        return switch (identifier.toString()) {
            case "minecraft:stone" -> (byte) 1;
            case "minecraft:diorite" -> (byte) 2;
            case "minecraft:polished_diorite" -> (byte) 3;
            case "minecraft:andesite" -> (byte) 4;
            case "minecraft:polished_andesite" -> (byte) 5;
            case "minecraft:grass_block" -> (byte) 6;
            case "minecraft:dirt" -> (byte) 7;
            case "minecraft:coarse_dirt" -> (byte) 8;
            case "minecraft:cobblestone" -> (byte) 9;
            case "minecraft:bedrock" -> (byte) 10;
            case "minecraft:oak_leaves" -> (byte) 11;
            case "minecraft:gray_wool" -> (byte) 12;
            case "minecraft:mossy_cobblestone" -> (byte) 14;
            case "minecraft:clay" -> (byte) 15;
            case "minecraft:stone_bricks" -> (byte) 16;
            case "minecraft:mossy_stone_bricks" -> (byte) 17;
            case "minecraft:chiseled_stone_bricks" -> (byte) 18;
            case "minecraft:gray_terracotta" -> (byte) 19;
            case "minecraft:cyan_terracotta" -> (byte) 20;
            case "minecraft:black_terracotta" -> (byte) 21;
            default -> (byte) 0;
        };
    }

    private static int pack(int x, int y, int z, byte id) {
        return ((byte) x << 24) | ((byte) y << 16) | ((byte) z << 8) | (id & 0xFF);
    }

    private List<Models.RoomCandidate> buildCandidates(Models.Cell current) {
        List<Models.RoomCandidate> out = new ArrayList<>();
        addShape(out, "1x1", singleCellLayouts(current));
        addShape(out, "puzzle", singleCellLayouts(current));
        addShape(out, "trap", singleCellLayouts(current));
        addShape(out, "miniboss", singleCellLayouts(current));
        addShape(out, "1x2", lineLayouts(current, 2));
        addShape(out, "1x3", lineLayouts(current, 3));
        addShape(out, "1x4", lineLayouts(current, 4));
        addShape(out, "2x2", twoByTwoLayouts(current));
        addShape(out, "l-shape", lShapeLayouts(current));
        return out;
    }

    private void addShape(List<Models.RoomCandidate> out, String shape, List<Layout> layouts) {
        List<Models.RoomDefinition> definitions = database.rooms(shape);
        if (definitions.isEmpty()) return;
        for (Layout layout : layouts) {
            for (Models.Direction direction : layout.directions) {
                int minX = layout.segments.stream().mapToInt(Models.Cell::x).min().orElseThrow();
                int maxX = layout.segments.stream().mapToInt(Models.Cell::x).max().orElseThrow();
                int minZ = layout.segments.stream().mapToInt(Models.Cell::z).min().orElseThrow();
                int maxZ = layout.segments.stream().mapToInt(Models.Cell::z).max().orElseThrow();
                int cornerX = switch (direction) {
                    case NW, SW -> minX;
                    case NE, SE -> maxX + 30;
                };
                int cornerZ = switch (direction) {
                    case NW, NE -> minZ;
                    case SW, SE -> maxZ + 30;
                };
                for (Models.RoomDefinition definition : definitions) {
                    out.add(new Models.RoomCandidate(definition, direction, cornerX, cornerZ, layout.segments));
                }
            }
        }
    }

    private static List<Layout> singleCellLayouts(Models.Cell current) {
        return List.of(new Layout(List.of(current), List.of(Models.Direction.values())));
    }

    private static List<Layout> lineLayouts(Models.Cell current, int length) {
        List<Layout> result = new ArrayList<>();
        for (int playerIndex = 0; playerIndex < length; playerIndex++) {
            List<Models.Cell> horizontal = new ArrayList<>();
            int startX = current.x() - playerIndex * 32;
            for (int i = 0; i < length; i++) horizontal.add(new Models.Cell(startX + i * 32, current.z()));
            result.add(new Layout(List.copyOf(horizontal), List.of(Models.Direction.NW, Models.Direction.SE)));

            List<Models.Cell> vertical = new ArrayList<>();
            int startZ = current.z() - playerIndex * 32;
            for (int i = 0; i < length; i++) vertical.add(new Models.Cell(current.x(), startZ + i * 32));
            result.add(new Layout(List.copyOf(vertical), List.of(Models.Direction.NE, Models.Direction.SW)));
        }
        return deduplicate(result);
    }

    private static List<Layout> twoByTwoLayouts(Models.Cell current) {
        List<Layout> result = new ArrayList<>();
        for (int px = 0; px <= 1; px++) {
            for (int pz = 0; pz <= 1; pz++) {
                int sx = current.x() - px * 32;
                int sz = current.z() - pz * 32;
                List<Models.Cell> cells = List.of(
                        new Models.Cell(sx, sz), new Models.Cell(sx + 32, sz),
                        new Models.Cell(sx, sz + 32), new Models.Cell(sx + 32, sz + 32));
                result.add(new Layout(cells, List.of(Models.Direction.values())));
            }
        }
        return result;
    }

    private static List<Layout> lShapeLayouts(Models.Cell current) {
        List<Layout> result = new ArrayList<>();
        for (int px = 0; px <= 1; px++) {
            for (int pz = 0; pz <= 1; pz++) {
                int sx = current.x() - px * 32;
                int sz = current.z() - pz * 32;
                Models.Cell nw = new Models.Cell(sx, sz);
                Models.Cell ne = new Models.Cell(sx + 32, sz);
                Models.Cell sw = new Models.Cell(sx, sz + 32);
                Models.Cell se = new Models.Cell(sx + 32, sz + 32);
                for (Models.Cell missing : List.of(nw, ne, sw, se)) {
                    if (missing.equals(current)) continue;
                    List<Models.Cell> cells = new ArrayList<>(List.of(nw, ne, sw, se));
                    cells.remove(missing);
                    Models.Direction direction = missing.equals(nw) ? Models.Direction.SW
                            : missing.equals(sw) ? Models.Direction.SE
                            : missing.equals(ne) ? Models.Direction.NW
                            : Models.Direction.NE;
                    result.add(new Layout(List.copyOf(cells), List.of(direction)));
                }
            }
        }
        return deduplicate(result);
    }

    private static List<Layout> deduplicate(List<Layout> layouts) {
        Set<String> seen = new LinkedHashSet<>();
        List<Layout> result = new ArrayList<>();
        for (Layout layout : layouts) {
            String key = layout.segments.stream()
                    .sorted((a, b) -> a.x() == b.x() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()))
                    .map(c -> c.x() + ":" + c.z()).reduce((a, b) -> a + "," + b).orElse("")
                    + "/" + layout.directions;
            if (seen.add(key)) result.add(layout);
        }
        return result;
    }

    static Models.Cell physicalRoomCell(double x, double z) {
        int shiftedX = (int) (x + 8.5);
        int shiftedZ = (int) (z + 8.5);
        return new Models.Cell(
                shiftedX - Math.floorMod(shiftedX, 32) - 8,
                shiftedZ - Math.floorMod(shiftedZ, 32) - 8);
    }

    private record ObservedBlock(BlockPos pos, byte numericId) {}
    private record ScoredCandidate(Models.RoomCandidate candidate, int matches, int mismatches) {}
    private record Layout(List<Models.Cell> segments, List<Models.Direction> directions) {}
}
