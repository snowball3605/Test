package dev.raistey.raisskysecrets;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tracks completed secrets and route helpers for the current dungeon instance. */
final class SecretStateTracker {
    private static final Set<String> SECRET_ITEM_NAMES = Set.of(
            "candycomb", "decoy", "defuse kit", "dungeon chest key", "healing viii",
            "inflatable jerry", "spirit leap", "training weights", "trap", "treasure talisman"
    );

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

    /**
     * Passive detection used every client tick. It catches the three cases that do not necessarily
     * have a clean block-interaction event: entrance route points, superboom openings and item pickups.
     */
    boolean tick(Minecraft mc, Models.RoomCandidate room, List<Models.WorldWaypoint> all) {
        if (room == null || all.isEmpty() || mc.player == null || mc.level == null) return false;
        RoomState state = state(room);
        boolean changed = false;
        Vec3 playerPos = mc.player.position();

        // Entrance is only a route helper. Once the player reaches/passes it, it has served its purpose.
        for (Models.WorldWaypoint waypoint : all) {
            if (state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            String category = waypoint.category().toLowerCase(Locale.ROOT);
            if (category.equals("entrance") && waypoint.center().distanceToSqr(playerPos) <= 2.6 * 2.6) {
                changed |= state.hiddenUtilityPositions.add(waypoint.pos());
            }
        }

        // Superboom waypoints are tied to a destructible wall. Record the local solid-block count the
        // first time we see it and hide the marker when the wall actually changes near the player.
        for (Models.WorldWaypoint waypoint : all) {
            String category = waypoint.category().toLowerCase(Locale.ROOT);
            if (!category.equals("superboom") || state.hiddenUtilityPositions.contains(waypoint.pos())) continue;
            int currentSolid = solidCount(mc, waypoint.pos());
            int baseline = state.superboomSolidBaseline.computeIfAbsent(waypoint.pos(), ignored -> currentSolid);
            boolean targetOpened = baseline > 0 && mc.level.getBlockState(waypoint.pos()).isAir();
            boolean neighbourhoodOpened = baseline >= 3 && currentSolid <= baseline - 2;
            if (waypoint.center().distanceToSqr(playerPos) <= 9.0 * 9.0 && (targetOpened || neighbourhoodOpened)) {
                changed |= state.hiddenUtilityPositions.add(waypoint.pos());
            }
        }

        // Item secrets are real inventory pickups. This is more reliable than depending only on the
        // action-bar secret counter because modern Hypixel clients can receive that line as non-overlay.
        int inventorySecretItems = countSecretItems(mc);
        if (lastInventorySecretItemCount >= 0 && inventorySecretItems > lastInventorySecretItemCount) {
            Models.WorldWaypoint item = nearestUnfound(all, state, playerPos, Set.of("item"), 9.0);
            if (item != null) {
                markSecret(state, item.secretIndex(), item.pos());
                changed = true;
            }
        }
        lastInventorySecretItemCount = inventorySecretItems;
        return changed;
    }

    /**
     * Modern Forge fires EntityLeaveLevelEvent on the client when a tracked entity is removed.
     * A dungeon bat secret is considered solved only when a Bat is removed with health <= 0,
     * mirroring the reliable approach used by mature secret-waypoint mods. The waypoint lookup is
     * based on the bat's position, not on the local player's position, so a nearby teammate killing
     * the bat is handled as well.
     */
    boolean onEntityRemoved(Models.RoomCandidate room, List<Models.WorldWaypoint> all, Entity entity) {
        if (room == null || all.isEmpty() || !(entity instanceof Bat bat) || bat.getHealth() > 0.0F) return false;
        RoomState state = state(room);
        Models.WorldWaypoint nearest = nearestUnfound(all, state, bat.position(), Set.of("bat"), 16.0);
        if (nearest == null) return false;
        markSecret(state, nearest.secretIndex(), nearest.pos());
        return true;
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
            String category = target.category().toLowerCase(Locale.ROOT);
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

        // If the player is actually holding Superboom TNT and uses it next to the route point, hide the
        // helper immediately; the solid-block change detector above is still the fallback.
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

    void onSecretCounter(Models.RoomCandidate room, List<Models.WorldWaypoint> all,
                         int found, int max, Vec3 playerPos) {
        if (room == null || all.isEmpty()) return;
        RoomState state = state(room);
        int previous = state.lastReportedSecretCount;
        state.lastReportedSecretCount = found;

        if (max > 0 && found >= max) {
            // At max count, every indexed helper for the room is stale, including Entrance/Superboom.
            for (Models.WorldWaypoint waypoint : all) {
                if (waypoint.secretIndex() > 0) state.foundSecretIndices.add(waypoint.secretIndex());
            }
            return;
        }

        if (found <= 0) return;
        boolean increased = previous >= 0 && found > previous;
        boolean firstUsefulObservation = previous < 0;
        if (!increased && !firstUsefulObservation) return;
        if (System.currentTimeMillis() - state.lastDirectMarkMillis < 1200L) return;

        // Counter remains a fallback for item/bat secrets. Direct bat removal detection is preferred.
        double radius = firstUsefulObservation ? 4.5 : 6.5;
        Models.WorldWaypoint nearest = nearestUnfound(all, state, playerPos, Set.of("item", "bat"), radius);
        if (nearest != null) markSecret(state, nearest.secretIndex(), nearest.pos());
    }

    private static Models.WorldWaypoint findInteractable(List<Models.WorldWaypoint> all, BlockPos pos) {
        for (Models.WorldWaypoint waypoint : all) {
            if (!waypoint.pos().equals(pos)) continue;
            String category = waypoint.category().toLowerCase(Locale.ROOT);
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
            String category = waypoint.category().toLowerCase(Locale.ROOT);
            if (!categories.contains(category)) continue;
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
            if (!categories.contains(waypoint.category().toLowerCase(Locale.ROOT))) continue;
            double d = waypoint.center().distanceToSqr(origin);
            if (d < nearestSq) {
                nearestSq = d;
                nearest = waypoint;
            }
        }
        return nearest;
    }

    private static int countSecretItems(Minecraft mc) {
        if (mc.player == null) return 0;
        int total = 0;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            boolean secret = SECRET_ITEM_NAMES.stream().anyMatch(name::contains);
            if (secret) total += stack.getCount();
        }
        return total;
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

    private static void markSecret(RoomState state, int secretIndex, BlockPos fallbackPos) {
        if (secretIndex > 0) state.foundSecretIndices.add(secretIndex);
        else state.hiddenUtilityPositions.add(fallbackPos);
    }

    private RoomState state(Models.RoomCandidate room) {
        return rooms.computeIfAbsent(room.instanceKey(), ignored -> new RoomState());
    }

    private static final class RoomState {
        final Set<Integer> foundSecretIndices = new HashSet<>();
        final Set<BlockPos> hiddenUtilityPositions = new HashSet<>();
        final Map<BlockPos, Integer> superboomSolidBaseline = new HashMap<>();
        int lastReportedSecretCount = -1;
        long lastDirectMarkMillis;
    }
}
