package dev.raistey.raisskysecrets;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks completed secrets and route helpers for the current dungeon instance.
 *
 * v0.1.6 uses hybrid detection:
 *  - direct local interactions for chest/wither/lever/superboom;
 *  - entity removal for Bat and dropped Item secrets (works when a nearby teammate solves them too);
 *  - passive block-state checks for route helpers;
 *  - room secret-counter deltas + all loaded dungeon-player positions to infer a teammate's nearby chest/item/bat/wither;
 *  - full-room completion always clears every indexed waypoint.
 *
 * We deliberately do not guess a specific waypoint when a distant teammate solves a partial room and their
 * entity/player is not loaded on this client. Hypixel can reveal that the room count changed without revealing
 * which exact secret changed, so hiding an arbitrary marker would be worse than leaving an uncertain one visible.
 */
final class SecretStateTracker {
    private static final Set<String> SECRET_ITEM_NAMES = Set.of(
            "candycomb", "decoy", "defuse kit", "dungeon chest key", "healing viii",
            "inflatable jerry", "spirit leap", "training weights", "trap", "treasure talisman"
    );
    private static final Set<String> INFERABLE_SECRET_CATEGORIES = Set.of("chest", "wither", "item", "bat", "default");

    private Object levelIdentity;
    private final Map<String, RoomState> rooms = new HashMap<>();
    private int lastInventorySecretItemCount = -1;

    void updateLevel(Minecraft mc) {
        Object now = mc.level;
        if (now != levelIdentity) {
            levelIdentity = now;
            rooms.clear();
            lastInventorySecretItemCount = -1;
        }
    }

    /** Passive detection that runs every client tick for the currently matched room. */
    boolean tick(Minecraft mc, Models.RoomCandidate room, List<Models.WorldWaypoint> all) {
        if (room == null || all.isEmpty() || mc.player == null || mc.level == null) return false;
        RoomState state = state(room);
        boolean changed = false;
        Vec3 localPlayerPos = mc.player.position();
        List<Vec3> loadedPlayers = loadedPlayerPositions(mc);

        // Entrance is a route helper, not a secret. Any loaded dungeon player reaching it means it has served its purpose.
        for (Models.WorldWaypoint waypoint : all) {
            if (state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            String category = category(waypoint);
            if (category.equals("entrance") && anyPlayerWithin(loadedPlayers, waypoint.center(), 2.8)) {
                changed |= state.hiddenUtilityPositions.add(waypoint.pos());
            }
        }

        // Superboom: observe the wall itself. This also works when a nearby teammate detonates it.
        for (Models.WorldWaypoint waypoint : all) {
            String category = category(waypoint);
            if (!category.equals("superboom") || state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            if (!mc.level.hasChunkAt(waypoint.pos())) continue;

            int currentSolid = solidCount(mc, waypoint.pos());
            int baseline = state.superboomSolidBaseline.computeIfAbsent(waypoint.pos(), ignored -> currentSolid);
            boolean targetOpened = baseline > 0 && mc.level.getBlockState(waypoint.pos()).isAir();
            boolean neighbourhoodOpened = baseline >= 3 && currentSolid <= baseline - 2;
            if (anyPlayerWithin(loadedPlayers, waypoint.center(), 11.0) && (targetOpened || neighbourhoodOpened)) {
                changed |= state.hiddenUtilityPositions.add(waypoint.pos());
            }
        }

        // Lever route helpers can be solved by a teammate without us receiving their right-click event.
        // A powered lever is a strong local world-state signal, so retire that helper immediately.
        for (Models.WorldWaypoint waypoint : all) {
            if (!category(waypoint).equals("lever") || state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            if (!mc.level.hasChunkAt(waypoint.pos())) continue;
            var blockState = mc.level.getBlockState(waypoint.pos());
            if (blockState.hasProperty(BlockStateProperties.POWERED)
                    && blockState.getValue(BlockStateProperties.POWERED)) {
                changed |= state.hiddenUtilityPositions.add(waypoint.pos());
            }
        }

        // Local inventory pickup fallback. EntityLeaveLevelEvent below is preferred because it also detects nearby teammates.
        int inventorySecretItems = countSecretItems(mc);
        if (lastInventorySecretItemCount >= 0 && inventorySecretItems > lastInventorySecretItemCount) {
            Models.WorldWaypoint item = nearestUnfound(all, state, localPlayerPos, Set.of("item"), 9.0);
            if (item != null) {
                markSecret(state, item.secretIndex(), item.pos());
                state.lastDirectMarkMillis = System.currentTimeMillis();
                changed = true;
            }
        }
        lastInventorySecretItemCount = inventorySecretItems;
        return changed;
    }

    /**
     * Entity removal is one of the strongest client-side signals because it is based on the secret entity,
     * not on who caused it. Therefore nearby teammate Bat kills and Item pickups are handled as well.
     */
    boolean onEntityRemoved(Models.RoomCandidate room, List<Models.WorldWaypoint> all, Entity entity) {
        if (room == null || all.isEmpty()) return false;
        RoomState state = state(room);

        if (entity instanceof Bat bat && bat.getHealth() <= 0.0F) {
            Models.WorldWaypoint nearest = nearestUnfound(all, state, bat.position(), Set.of("bat"), 16.0);
            if (nearest == null) return false;
            markSecret(state, nearest.secretIndex(), nearest.pos());
            state.lastDirectMarkMillis = System.currentTimeMillis();
            return true;
        }

        if (entity instanceof ItemEntity itemEntity && isSecretItem(itemEntity.getItem())) {
            Models.WorldWaypoint nearest = nearestUnfound(all, state, itemEntity.position(), Set.of("item"), 16.0);
            if (nearest == null) return false;
            markSecret(state, nearest.secretIndex(), nearest.pos());
            state.lastDirectMarkMillis = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    List<Models.WorldWaypoint> visible(Models.RoomCandidate room, List<Models.WorldWaypoint> all) {
        if (room == null || all.isEmpty()) return all;
        RoomState state = state(room);
        List<Models.WorldWaypoint> visible = new ArrayList<>();
        for (Models.WorldWaypoint waypoint : all) {
            if (waypoint.secretIndex() > 0 && state.foundSecretIndices.contains(waypoint.secretIndex())) continue;
            if (state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            visible.add(waypoint);
        }
        return List.copyOf(visible);
    }

    boolean onRightClick(Models.RoomCandidate room, List<Models.WorldWaypoint> all, BlockPos clicked, String heldItemName) {
        if (room == null || all.isEmpty()) return false;
        RoomState state = state(room);

        Models.WorldWaypoint target = findInteractable(all, clicked);
        if (target == null) target = findInteractable(all, clicked.above());
        if (target != null) {
            String category = category(target);
            if (category.equals("lever") || category.equals("redstone_key") || category.equals("key")) {
                state.hiddenUtilityPositions.add(target.pos());
                return true;
            }
            if (category.equals("chest") || category.equals("wither")) {
                markSecret(state, target.secretIndex(), target.pos());
                state.lastDirectMarkMillis = System.currentTimeMillis();
                return true;
            }
        }

        String held = heldItemName == null ? "" : heldItemName.toLowerCase(Locale.ROOT);
        if (held.contains("superboom")) {
            Vec3 clickedCenter = new Vec3(clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5);
            Models.WorldWaypoint boom = nearestByCategory(all, clickedCenter, Set.of("superboom"), 5.0);
            if (boom != null) {
                state.hiddenUtilityPositions.add(boom.pos());
                return true;
            }
        }
        return false;
    }

    /**
     * Hybrid teammate inference. The room counter tells us that a secret changed; loaded player positions tell us
     * which waypoint is plausible. We only infer a specific secret for a single-step counter increase and only
     * when the spatial match is confident. Large jumps / first observations are never guessed.
     */
    void onSecretCounter(Minecraft mc, Models.RoomCandidate room, List<Models.WorldWaypoint> all, int found, int max) {
        if (room == null || all.isEmpty() || mc.player == null || mc.level == null) return;
        RoomState state = state(room);
        int previous = state.lastReportedSecretCount;
        state.lastReportedSecretCount = found;

        if (max > 0 && found >= max) {
            for (Models.WorldWaypoint waypoint : all) {
                if (waypoint.secretIndex() > 0) state.foundSecretIndices.add(waypoint.secretIndex());
                if (waypoint.utility()) state.hiddenUtilityPositions.add(waypoint.pos());
            }
            return;
        }

        // First observation only tells us that some secrets were already solved; it does not identify which ones.
        if (previous < 0 || found <= previous) return;
        int delta = found - previous;
        if (delta != 1) return;

        // A direct local/entity event usually arrives just before the counter. Avoid consuming a second secret.
        if (System.currentTimeMillis() - state.lastDirectMarkMillis < 1400L) return;

        Models.WorldWaypoint inferred = inferSingleSecretFromLoadedPlayers(mc, all, state);
        if (inferred != null) {
            markSecret(state, inferred.secretIndex(), inferred.pos());
            RaisSkySecrets.LOGGER.info("Hybrid teammate detection inferred secret #{} ({}) at {}",
                    inferred.secretIndex(), inferred.category(), inferred.pos());
        }
    }

    private static Models.WorldWaypoint inferSingleSecretFromLoadedPlayers(Minecraft mc,
                                                                            List<Models.WorldWaypoint> all,
                                                                            RoomState state) {
        List<Vec3> players = loadedPlayerPositions(mc);
        List<InferenceCandidate> candidates = new ArrayList<>();

        for (Models.WorldWaypoint waypoint : all) {
            if (waypoint.utility() || waypoint.secretIndex() <= 0 || state.foundSecretIndices.contains(waypoint.secretIndex())) continue;
            String cat = category(waypoint);
            if (!INFERABLE_SECRET_CATEGORIES.contains(cat)) continue;

            double limit = switch (cat) {
                case "item", "bat" -> 8.5;
                case "chest", "wither" -> 5.75;
                default -> 5.0;
            };
            for (Vec3 player : players) {
                double distance = Math.sqrt(waypoint.center().distanceToSqr(player));
                if (distance <= limit) candidates.add(new InferenceCandidate(waypoint, distance));
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(InferenceCandidate::distance));
        InferenceCandidate best = candidates.getFirst();

        // Very close is enough by itself. Otherwise require a meaningful margin over another distinct secret.
        if (best.distance() <= 3.4) return best.waypoint();
        InferenceCandidate secondDistinct = candidates.stream()
                .filter(c -> c.waypoint().secretIndex() != best.waypoint().secretIndex())
                .findFirst().orElse(null);
        if (secondDistinct == null || secondDistinct.distance() - best.distance() >= 2.0) return best.waypoint();
        return null;
    }

    private static Models.WorldWaypoint findInteractable(List<Models.WorldWaypoint> all, BlockPos pos) {
        for (Models.WorldWaypoint waypoint : all) {
            if (!waypoint.pos().equals(pos)) continue;
            String category = category(waypoint);
            if (category.equals("chest") || category.equals("wither") || category.equals("lever")
                    || category.equals("redstone_key") || category.equals("key")) {
                return waypoint;
            }
        }
        return null;
    }

    private static Models.WorldWaypoint nearestUnfound(List<Models.WorldWaypoint> all, RoomState state,
                                                        Vec3 origin, Set<String> categories, double radius) {
        Models.WorldWaypoint nearest = null;
        double nearestSq = radius * radius;
        for (Models.WorldWaypoint waypoint : all) {
            if (waypoint.secretIndex() <= 0 || state.foundSecretIndices.contains(waypoint.secretIndex())) continue;
            if (!categories.contains(category(waypoint))) continue;
            double d = waypoint.center().distanceToSqr(origin);
            if (d < nearestSq) {
                nearestSq = d;
                nearest = waypoint;
            }
        }
        return nearest;
    }

    private static Models.WorldWaypoint nearestByCategory(List<Models.WorldWaypoint> all, Vec3 origin,
                                                          Set<String> categories, double radius) {
        Models.WorldWaypoint nearest = null;
        double nearestSq = radius * radius;
        for (Models.WorldWaypoint waypoint : all) {
            if (!categories.contains(category(waypoint))) continue;
            double d = waypoint.center().distanceToSqr(origin);
            if (d < nearestSq) {
                nearestSq = d;
                nearest = waypoint;
            }
        }
        return nearest;
    }

    private static List<Vec3> loadedPlayerPositions(Minecraft mc) {
        if (mc.level == null) return List.of();
        List<Vec3> positions = new ArrayList<>();
        for (var player : mc.level.players()) positions.add(player.position());
        return positions;
    }

    private static boolean anyPlayerWithin(List<Vec3> players, Vec3 point, double radius) {
        double radiusSq = radius * radius;
        for (Vec3 player : players) {
            if (point.distanceToSqr(player) <= radiusSq) return true;
        }
        return false;
    }

    private static int countSecretItems(Minecraft mc) {
        if (mc.player == null) return 0;
        int total = 0;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && isSecretItem(stack)) total += stack.getCount();
        }
        return total;
    }

    private static boolean isSecretItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return SECRET_ITEM_NAMES.stream().anyMatch(name::contains);
    }

    private static int solidCount(Minecraft mc, BlockPos center) {
        if (mc.level == null) return 0;
        int solid = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!mc.level.getBlockState(center.offset(dx, dy, dz)).isAir()) solid++;
                }
            }
        }
        return solid;
    }

    private static String category(Models.WorldWaypoint waypoint) {
        return waypoint.category().toLowerCase(Locale.ROOT);
    }

    private static void markSecret(RoomState state, int secretIndex, BlockPos fallbackPos) {
        if (secretIndex > 0) state.foundSecretIndices.add(secretIndex);
        else state.hiddenUtilityPositions.add(fallbackPos);
    }

    private RoomState state(Models.RoomCandidate room) {
        return rooms.computeIfAbsent(room.instanceKey(), ignored -> new RoomState());
    }

    private record InferenceCandidate(Models.WorldWaypoint waypoint, double distance) {}

    private static final class RoomState {
        final Set<Integer> foundSecretIndices = new HashSet<>();
        final Set<BlockPos> hiddenUtilityPositions = new HashSet<>();
        final Map<BlockPos, Integer> superboomSolidBaseline = new HashMap<>();
        int lastReportedSecretCount = -1;
        long lastDirectMarkMillis;
    }
}
