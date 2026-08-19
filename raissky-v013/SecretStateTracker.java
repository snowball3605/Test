package dev.raistey.raisskysecrets;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which secret waypoints have already been completed in the current dungeon instance.
 * The important bit is that a completed secret hides every helper waypoint sharing its secret index
 * (for example Superboom -> Chest), while utility interactions such as levers can be hidden alone.
 */
final class SecretStateTracker {
    private Object levelIdentity;
    private final Map<String, RoomState> rooms = new HashMap<>();

    void updateLevel(Minecraft mc) {
        Object now = mc.level;
        if (now != levelIdentity) {
            levelIdentity = now;
            rooms.clear();
        }
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

    boolean onRightClick(Models.RoomCandidate room, List<Models.WorldWaypoint> all, BlockPos clicked) {
        if (room == null || all.isEmpty()) return false;
        RoomState state = state(room);

        Models.WorldWaypoint target = findInteractable(all, clicked);
        if (target == null) target = findInteractable(all, clicked.above());
        if (target == null) return false;

        String category = target.category().toLowerCase(Locale.ROOT);
        if (category.equals("lever") || category.equals("redstone_key")) {
            state.hiddenUtilityPositions.add(target.pos());
            return true;
        }

        if (category.equals("chest") || category.equals("wither")) {
            markSecret(state, target.secretIndex(), target.pos());
            state.lastDirectMarkMillis = System.currentTimeMillis();
            return true;
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
            for (Models.WorldWaypoint waypoint : all) {
                if (!waypoint.utility() && waypoint.secretIndex() > 0) {
                    state.foundSecretIndices.add(waypoint.secretIndex());
                }
            }
            return;
        }

        if (previous < 0 || found <= previous) return;
        if (System.currentTimeMillis() - state.lastDirectMarkMillis < 1500L) return;

        Models.WorldWaypoint nearest = null;
        double nearestSq = 6.5 * 6.5;
        for (Models.WorldWaypoint waypoint : all) {
            if (waypoint.utility() || waypoint.secretIndex() <= 0 || state.foundSecretIndices.contains(waypoint.secretIndex())) continue;
            String category = waypoint.category().toLowerCase(Locale.ROOT);
            if (!(category.equals("item") || category.equals("bat"))) continue;
            double d = waypoint.center().distanceToSqr(playerPos);
            if (d < nearestSq) {
                nearestSq = d;
                nearest = waypoint;
            }
        }
        if (nearest != null) markSecret(state, nearest.secretIndex(), nearest.pos());
    }

    private static Models.WorldWaypoint findInteractable(List<Models.WorldWaypoint> all, BlockPos pos) {
        for (Models.WorldWaypoint waypoint : all) {
            if (!waypoint.pos().equals(pos)) continue;
            String category = waypoint.category().toLowerCase(Locale.ROOT);
            if (category.equals("chest") || category.equals("wither") || category.equals("lever") || category.equals("redstone_key")) {
                return waypoint;
            }
        }
        return null;
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
        int lastReportedSecretCount = -1;
        long lastDirectMarkMillis;
    }
}
