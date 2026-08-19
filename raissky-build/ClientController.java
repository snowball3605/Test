package dev.raistey.raisskysecrets;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class ClientController {
    private static final Identifier HUD_LAYER = Identifier.fromNamespaceAndPath(RaisSkySecrets.MOD_ID, "secret_hud");
    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.raissky_secrets.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_P, KeyMapping.Category.MISC);
    private static final KeyMapping UTILITY = new KeyMapping(
            "key.raissky_secrets.utility", InputConstants.Type.KEYSYM, InputConstants.KEY_O, KeyMapping.Category.MISC);
    private static final KeyMapping REFRESH = new KeyMapping(
            "key.raissky_secrets.refresh", InputConstants.Type.KEYSYM, InputConstants.KEY_K, KeyMapping.Category.MISC);

    private static final RoomMatcher MATCHER = new RoomMatcher();
    private static volatile Models.DatabaseState databaseState = Models.DatabaseState.LOADING;
    private static volatile String databaseMessage = "Preparing room database…";
    private static volatile RoomDatabase database;
    private static volatile Models.HudSnapshot snapshot = Models.HudSnapshot.empty(
            Models.DatabaseState.LOADING, databaseMessage, true, true);

    private static boolean enabled = true;
    private static boolean showUtility = true;
    private static int ticks;

    private ClientController() {}

    static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(ClientController::registerKeys);
        AddGuiOverlayLayersEvent.BUS.addListener(ClientController::registerHud);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        beginDatabaseLoad(false);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(UTILITY);
        event.register(REFRESH);
    }

    private static void registerHud(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().add(HUD_LAYER, (graphics, deltaTracker) -> SecretHud.extract(graphics));
    }

    static Models.HudSnapshot snapshot() {
        return snapshot;
    }

    private static void beginDatabaseLoad(boolean forceRefresh) {
        databaseState = Models.DatabaseState.LOADING;
        databaseMessage = forceRefresh ? "Refreshing room database…" : "Preparing room database…";
        snapshot = Models.HudSnapshot.empty(databaseState, databaseMessage, enabled, showUtility);
        CompletableFuture<RoomDatabase> future = RoomDatabase.prepare(forceRefresh);
        future.whenComplete((loaded, error) -> {
            Minecraft.getInstance().execute(() -> {
                if (error != null) {
                    databaseState = Models.DatabaseState.ERROR;
                    databaseMessage = rootMessage(error);
                    RaisSkySecrets.LOGGER.error("Failed to prepare room database", error);
                } else {
                    database = loaded;
                    MATCHER.setDatabase(loaded);
                    databaseState = Models.DatabaseState.READY;
                    databaseMessage = loaded.roomCount() + " rooms loaded";
                    notifyPlayer("RaisSky Secrets · " + loaded.roomCount() + " rooms ready");
                }
            });
        });
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ticks++;

        while (TOGGLE.consumeClick()) {
            enabled = !enabled;
            notifyPlayer("Secret Waypoints: " + (enabled ? "ON" : "OFF"));
        }
        while (UTILITY.consumeClick()) {
            showUtility = !showUtility;
            notifyPlayer("Route Helpers: " + (showUtility ? "ON" : "OFF"));
        }
        while (REFRESH.consumeClick()) {
            beginDatabaseLoad(true);
            notifyPlayer("Refreshing Secret room database…");
        }

        if (!enabled || mc.player == null || mc.level == null) {
            snapshot = Models.HudSnapshot.empty(databaseState, databaseMessage, enabled, showUtility);
            return;
        }
        if (databaseState != Models.DatabaseState.READY || database == null) {
            snapshot = Models.HudSnapshot.empty(databaseState, databaseMessage, enabled, showUtility);
            return;
        }

        if (ticks % 10 == 0) MATCHER.update(mc);
        Models.RoomCandidate room = MATCHER.matched();
        List<Models.WorldWaypoint> waypoints = MATCHER.worldWaypoints(showUtility);
        snapshot = new Models.HudSnapshot(
                true,
                databaseState,
                databaseMessage,
                room == null ? "" : room.definition().roomName(),
                room == null ? null : room.direction(),
                MATCHER.candidateCount(),
                mc.player.position(),
                mc.player.getYRot(),
                waypoints,
                showUtility);

        if (ticks % 5 == 0 && !waypoints.isEmpty()) spawnMarkers(mc, waypoints);
    }

    private static void spawnMarkers(Minecraft mc, List<Models.WorldWaypoint> waypoints) {
        Vec3 player = mc.player.position();
        List<Models.WorldWaypoint> nearest = waypoints.stream()
                .filter(w -> w.center().distanceToSqr(player) <= 55.0 * 55.0)
                .sorted(Comparator.comparingDouble(w -> w.center().distanceToSqr(player)))
                .limit(5)
                .toList();
        for (Models.WorldWaypoint waypoint : nearest) {
            Vec3 c = waypoint.center();
            double radius = 0.33;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI * 2.0 / 8.0;
                mc.level.addParticle(ParticleTypes.END_ROD,
                        c.x + Math.cos(a) * radius,
                        c.y + Math.sin(a) * radius,
                        c.z,
                        0.0, 0.002, 0.0);
            }
            mc.level.addParticle(ParticleTypes.END_ROD, c.x, c.y + 0.45, c.z, 0.0, 0.01, 0.0);
        }
    }

    private static void notifyPlayer(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(message));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
