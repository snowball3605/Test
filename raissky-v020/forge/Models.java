package dev.raistey.raisskysecrets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

final class Models {
    private Models() {}

    enum Direction { NW, NE, SW, SE }
    enum HudPosition { BOTTOM, TOP, LEFT, RIGHT, OFF }
    record Cell(int x, int z) {}

    record RelativeSecret(int secretIndex, String name, String category, int x, int y, int z) {
        boolean isUtility() {
            String c = category.toLowerCase(Locale.ROOT);
            return c.equals("entrance") || c.equals("stonk") || c.equals("superboom")
                    || c.equals("lever") || c.equals("redstone_key") || c.equals("key")
                    || c.equals("aotv") || c.equals("pearl");
        }
    }

    record RoomDefinition(String shape, String fileKey, String roomName, int[] skeleton,
                          int minX, int maxX, int minZ, int maxZ, List<RelativeSecret> secrets) {}

    record RoomCandidate(RoomDefinition definition, Direction direction, int cornerX, int cornerZ, List<Cell> segments) {
        boolean containsCell(Cell cell) { return segments.contains(cell); }
        BlockPos actualToRelative(BlockPos pos) {
            return CoordinateTransforms.actualToRelative(direction, cornerX, cornerZ, pos);
        }
        BlockPos relativeToActual(RelativeSecret secret) {
            return CoordinateTransforms.relativeToActual(direction, cornerX, cornerZ,
                    new BlockPos(secret.x(), secret.y(), secret.z()));
        }
        BlockPos relativeToActual(BlockPos pos) {
            return CoordinateTransforms.relativeToActual(direction, cornerX, cornerZ, pos);
        }
        String instanceKey() {
            return definition.fileKey() + "@" + cornerX + "," + cornerZ + ":" + direction;
        }
    }

    record WorldWaypoint(int secretIndex, String name, String category, BlockPos pos, boolean utility) {
        Vec3 center() { return new Vec3(pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5); }
    }

    record SyncUpdate(String roomInstanceKey, String kind, int secretIndex, int x, int y, int z) {
        static SyncUpdate secret(String roomInstanceKey, int secretIndex) {
            return new SyncUpdate(roomInstanceKey, "S", secretIndex, 0, 0, 0);
        }
        static SyncUpdate utility(String roomInstanceKey, BlockPos pos) {
            return new SyncUpdate(roomInstanceKey, "U", 0, pos.getX(), pos.getY(), pos.getZ());
        }
        BlockPos pos() { return new BlockPos(x, y, z); }
    }

    enum DatabaseState { LOADING, READY, ERROR }

    record HudSnapshot(boolean enabled, DatabaseState databaseState, String databaseMessage,
                       String roomName, Direction roomDirection, int candidateCount,
                       Vec3 playerPos, float playerYaw, List<WorldWaypoint> waypoints,
                       boolean showUtility) {
        static HudSnapshot empty(DatabaseState state, String message, boolean enabled, boolean showUtility) {
            return new HudSnapshot(enabled, state, message, "", null, 0, Vec3.ZERO, 0f, List.of(), showUtility);
        }
    }
}

final class CoordinateTransforms {
    private CoordinateTransforms() {}
    static BlockPos actualToRelative(Models.Direction direction, int cornerX, int cornerZ, BlockPos pos) {
        return switch (direction) {
            case NW -> new BlockPos(pos.getX() - cornerX, pos.getY(), pos.getZ() - cornerZ);
            case NE -> new BlockPos(pos.getZ() - cornerZ, pos.getY(), -pos.getX() + cornerX);
            case SW -> new BlockPos(-pos.getZ() + cornerZ, pos.getY(), pos.getX() - cornerX);
            case SE -> new BlockPos(-pos.getX() + cornerX, pos.getY(), -pos.getZ() + cornerZ);
        };
    }
    static BlockPos relativeToActual(Models.Direction direction, int cornerX, int cornerZ, BlockPos pos) {
        return switch (direction) {
            case NW -> new BlockPos(pos.getX() + cornerX, pos.getY(), pos.getZ() + cornerZ);
            case NE -> new BlockPos(-pos.getZ() + cornerX, pos.getY(), pos.getX() + cornerZ);
            case SW -> new BlockPos(pos.getZ() + cornerX, pos.getY(), -pos.getX() + cornerZ);
            case SE -> new BlockPos(-pos.getX() + cornerX, pos.getY(), -pos.getZ() + cornerZ);
        };
    }
}
