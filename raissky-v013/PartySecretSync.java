package dev.raistey.raisskysecrets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Best-effort party secret synchronization for RaisSky clients.
 *
 * The relay contains no raw Minecraft UUIDs. A per-party topic is derived from the stable tab-list
 * roster and hashed before use. Messages are published with Cache:no and Firebase:no so ntfy does not
 * persist or forward the secret-state payloads. Local/hybrid detection remains authoritative; the
 * relay is only an additional eventual-consistency layer for teammates who also run RaisSky.
 */
final class PartySecretSync {
    private static final String RELAY_HTTP = "https://ntfy.sh/";
    private static final String RELAY_WS = "wss://ntfy.sh/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Object levelIdentity;
    private volatile WebSocket socket;
    private volatile String topic = "";
    private String lastRosterMaterial = "";
    private int rosterStableTicks;
    private long reconnectAfterMillis;
    private int snapshotTicks;
    private final Set<String> allowedSenders = new HashSet<>();

    void tick(Minecraft mc, SecretStateTracker tracker) {
        Object nowLevel = mc.level;
        if (nowLevel != levelIdentity) {
            reset(nowLevel);
        }
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (topic.isEmpty()) {
            stabilizeAndConnect(mc);
        } else if ((socket == null || socket.isInputClosed() || socket.isOutputClosed())
                && System.currentTimeMillis() >= reconnectAfterMillis) {
            connect();
        }

        if (!topic.isEmpty()) {
            for (Models.SyncUpdate update : tracker.drainSyncUpdates()) {
                publishSingle(mc, update);
            }

            // Periodic compact snapshot repairs the rare case where a websocket/HTTP packet is missed.
            if (++snapshotTicks >= 200) {
                snapshotTicks = 0;
                List<Models.SyncUpdate> state = tracker.snapshotSyncState();
                if (!state.isEmpty()) publishSnapshot(mc, state);
            }
        }
    }

    private void reset(Object newLevel) {
        levelIdentity = newLevel;
        topic = "";
        lastRosterMaterial = "";
        rosterStableTicks = 0;
        reconnectAfterMillis = 0L;
        snapshotTicks = 0;
        allowedSenders.clear();
        WebSocket previous = socket;
        socket = null;
        if (previous != null && !previous.isOutputClosed()) {
            try {
                previous.sendClose(WebSocket.NORMAL_CLOSURE, "Dungeon changed");
            } catch (Exception ignored) {
            }
        }
    }

    private void stabilizeAndConnect(Minecraft mc) {
        List<PlayerInfo> infos = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        List<String> roster = infos.stream()
                .filter(info -> info.getProfile().name() != null
                        && info.getProfile().name().matches("[A-Za-z0-9_]{1,16}"))
                .map(info -> info.getProfile().id().toString())
                .distinct()
                .sorted()
                .toList();
        if (roster.size() < 2) {
            rosterStableTicks = 0;
            return;
        }

        String material = String.join("|", roster);
        if (material.equals(lastRosterMaterial)) {
            rosterStableTicks++;
        } else {
            lastRosterMaterial = material;
            rosterStableTicks = 0;
        }
        // Wait two seconds so both clients freeze the same Hypixel dungeon tab-list roster.
        if (rosterStableTicks < 40) return;

        allowedSenders.clear();
        for (String uuid : roster) allowedSenders.add(senderToken(uuid));
        topic = "raissky-dungeon-" + sha256("party-v1|" + material).substring(0, 48);
        RaisSkySecrets.LOGGER.info("RaisSky Party Sync ready ({} roster entries, topic fingerprint {}…)",
                roster.size(), topic.substring(topic.length() - 8));
        connect();
    }

    private synchronized void connect() {
        if (topic.isEmpty()) return;
        WebSocket current = socket;
        if (current != null && !current.isInputClosed() && !current.isOutputClosed()) return;
        reconnectAfterMillis = System.currentTimeMillis() + 5000L;
        long since = Instant.now().getEpochSecond();
        HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(URI.create(RELAY_WS + topic + "/ws?since=" + since), new RelayListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        RaisSkySecrets.LOGGER.debug("RaisSky Party Sync websocket unavailable; local detection continues", error);
                        socket = null;
                        reconnectAfterMillis = System.currentTimeMillis() + 5000L;
                    } else {
                        socket = ws;
                        RaisSkySecrets.LOGGER.info("RaisSky Party Sync connected");
                    }
                });
    }

    private void publishSingle(Minecraft mc, Models.SyncUpdate update) {
        JsonObject body = envelope(mc, "event");
        body.add("update", encodeUpdate(update));
        post(body);
    }

    private void publishSnapshot(Minecraft mc, List<Models.SyncUpdate> updates) {
        JsonObject body = envelope(mc, "snapshot");
        JsonArray array = new JsonArray();
        for (Models.SyncUpdate update : updates) array.add(encodeUpdate(update));
        body.add("updates", array);
        post(body);
    }

    private JsonObject envelope(Minecraft mc, String type) {
        JsonObject body = new JsonObject();
        body.addProperty("v", 1);
        body.addProperty("type", type);
        body.addProperty("sender", senderToken(mc.getUser().getProfileId().toString()));
        body.addProperty("time", System.currentTimeMillis());
        return body;
    }

    private static JsonObject encodeUpdate(Models.SyncUpdate update) {
        JsonObject out = new JsonObject();
        out.addProperty("room", update.roomInstanceKey());
        out.addProperty("kind", update.kind());
        out.addProperty("index", update.secretIndex());
        out.addProperty("x", update.x());
        out.addProperty("y", update.y());
        out.addProperty("z", update.z());
        return out;
    }

    private void post(JsonObject body) {
        if (topic.isEmpty()) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELAY_HTTP + topic))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Cache", "no")
                .header("Firebase", "no")
                .header("User-Agent", "RaisSky-Secrets/0.1.7")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> {
                    RaisSkySecrets.LOGGER.debug("RaisSky Party Sync publish failed; snapshot retry will repair it", error);
                    return null;
                });
    }

    private void handleRelayMessage(String raw) {
        try {
            JsonObject relay = JsonParser.parseString(raw).getAsJsonObject();
            if (!relay.has("event") || !"message".equals(relay.get("event").getAsString()) || !relay.has("message")) return;
            JsonObject body = JsonParser.parseString(relay.get("message").getAsString()).getAsJsonObject();
            if (!body.has("v") || body.get("v").getAsInt() != 1) return;
            String sender = body.has("sender") ? body.get("sender").getAsString() : "";
            if (!allowedSenders.contains(sender)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && sender.equals(senderToken(mc.getUser().getProfileId().toString()))) return;

            List<Models.SyncUpdate> updates = new ArrayList<>();
            String type = body.has("type") ? body.get("type").getAsString() : "";
            if ("event".equals(type) && body.has("update")) {
                Models.SyncUpdate update = decodeUpdate(body.getAsJsonObject("update"));
                if (update != null) updates.add(update);
            } else if ("snapshot".equals(type) && body.has("updates")) {
                for (JsonElement element : body.getAsJsonArray("updates")) {
                    if (!element.isJsonObject()) continue;
                    Models.SyncUpdate update = decodeUpdate(element.getAsJsonObject());
                    if (update != null) updates.add(update);
                }
            }
            if (updates.isEmpty()) return;

            mc.execute(() -> {
                boolean changed = false;
                for (Models.SyncUpdate update : updates) {
                    changed |= ClientController.applyPartySync(update);
                }
                if (changed) ClientController.onPartySyncChanged();
            });
        } catch (Exception e) {
            RaisSkySecrets.LOGGER.debug("Ignored malformed RaisSky Party Sync message", e);
        }
    }

    private static Models.SyncUpdate decodeUpdate(JsonObject object) {
        try {
            String room = object.get("room").getAsString();
            String kind = object.get("kind").getAsString();
            int index = object.get("index").getAsInt();
            int x = object.get("x").getAsInt();
            int y = object.get("y").getAsInt();
            int z = object.get("z").getAsInt();
            if (room.isBlank() || !("S".equals(kind) || "U".equals(kind))) return null;
            if (room.length() > 160 || Math.abs(x) > 10000 || Math.abs(y) > 1000 || Math.abs(z) > 10000) return null;
            return new Models.SyncUpdate(room, kind, index, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String senderToken(String uuid) {
        return sha256("sender-v1|" + uuid).substring(0, 20);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class RelayListener implements WebSocket.Listener {
        private final StringBuilder parts = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            parts.append(data);
            if (last) {
                String message = parts.toString();
                parts.setLength(0);
                handleRelayMessage(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            reconnectAfterMillis = System.currentTimeMillis() + 3000L;
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            reconnectAfterMillis = System.currentTimeMillis() + 3000L;
            RaisSkySecrets.LOGGER.debug("RaisSky Party Sync websocket error; reconnecting", error);
        }
    }
}
