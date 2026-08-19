package dev.raistey.raisskysecrets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RoomDatabase {
    private static final URI RELEASE_API = URI.create("https://api.github.com/repos/SkyblockerMod/Skyblocker/releases/latest");
    private static final String CATACOMBS_MARKER = "assets/skyblocker/dungeons/catacombs/";
    private static final Path ROOT = Path.of("config", "raissky_secrets");
    private static final Path ROOM_DB = ROOT.resolve("roomdb");
    private static final Path MANIFEST = ROOT.resolve("roomdb-manifest.txt");
    private static final Duration UPDATE_AGE = Duration.ofDays(7);

    private final Map<String, List<Models.RoomDefinition>> byShape;

    private RoomDatabase(Map<String, List<Models.RoomDefinition>> byShape) {
        this.byShape = byShape;
    }

    List<Models.RoomDefinition> rooms(String shape) {
        return byShape.getOrDefault(shape, List.of());
    }

    int roomCount() {
        return byShape.values().stream().mapToInt(List::size).sum();
    }

    static boolean localDatabaseExists() {
        return Files.isDirectory(ROOM_DB);
    }

    static CompletableFuture<RoomDatabase> prepare(boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(ROOT);
                if (forceRefresh || !localDatabaseExists() || databaseLooksStale()) {
                    try {
                        downloadLatestRoomData();
                    } catch (Exception downloadFailure) {
                        if (!localDatabaseExists()) {
                            throw downloadFailure;
                        }
                        RaisSkySecrets.LOGGER.warn("Room database refresh failed; using cached copy", downloadFailure);
                    }
                }
                return loadLocal();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static boolean databaseLooksStale() {
        try {
            if (!Files.exists(MANIFEST)) return true;
            return Files.getLastModifiedTime(MANIFEST).toInstant().isBefore(Instant.now().minus(UPDATE_AGE));
        } catch (IOException ignored) {
            return true;
        }
    }

    private static void downloadLatestRoomData() throws Exception {
        RaisSkySecrets.LOGGER.info("Updating dungeon room database from Skyblocker release assets...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest releaseRequest = HttpRequest.newBuilder(RELEASE_API)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RaisSky-Secrets/0.1.6")
                .build();
        HttpResponse<String> releaseResponse = client.send(releaseRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (releaseResponse.statusCode() / 100 != 2) {
            throw new IOException("GitHub release API returned HTTP " + releaseResponse.statusCode());
        }

        JsonObject release = JsonParser.parseString(releaseResponse.body()).getAsJsonObject();
        String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "unknown";
        JsonArray assets = release.getAsJsonArray("assets");
        String downloadUrl = null;
        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar") && name.contains("26.2") && !name.contains("source")) {
                    downloadUrl = asset.get("browser_download_url").getAsString();
                    break;
                }
            }
        }
        if (downloadUrl == null) {
            throw new IOException("No Skyblocker Minecraft 26.2 release JAR was found in latest release " + tag);
        }

        HttpRequest jarRequest = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "RaisSky-Secrets/0.1.6")
                .build();
        HttpResponse<byte[]> jarResponse = client.send(jarRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (jarResponse.statusCode() / 100 != 2) {
            throw new IOException("Skyblocker JAR download returned HTTP " + jarResponse.statusCode());
        }

        Path tempRoot = ROOT.resolve("roomdb-new");
        deleteTree(tempRoot);
        Files.createDirectories(tempRoot);
        int extracted = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jarResponse.body()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int marker = name.indexOf(CATACOMBS_MARKER);
                if (marker < 0) continue;
                String relative = name.substring(marker + CATACOMBS_MARKER.length());
                if (!(relative.endsWith(".json") || relative.endsWith(".skeleton") || relative.endsWith("attribution.md"))) continue;
                Path output = tempRoot.resolve(relative).normalize();
                if (!output.startsWith(tempRoot)) throw new IOException("Blocked unsafe ZIP path: " + relative);
                Files.createDirectories(output.getParent());
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                extracted++;
            }
        }
        if (extracted < 20) {
            deleteTree(tempRoot);
            throw new IOException("Downloaded JAR did not contain a complete Catacombs room database (files=" + extracted + ")");
        }

        deleteTree(ROOM_DB);
        try {
            Files.move(tempRoot, ROOM_DB, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tempRoot, ROOM_DB, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(MANIFEST, "source=Skyblocker\ntag=" + tag + "\nupdated=" + Instant.now() + "\nfiles=" + extracted + "\n", StandardCharsets.UTF_8);
        RaisSkySecrets.LOGGER.info("Dungeon room database updated: {} files from {}", extracted, tag);
    }

    private static RoomDatabase loadLocal() throws Exception {
        if (!Files.isDirectory(ROOM_DB)) throw new IOException("Room database directory does not exist: " + ROOM_DB);
        Map<String, List<Models.RoomDefinition>> byShape = new HashMap<>();
        try (var paths = Files.walk(ROOM_DB)) {
            for (Path json : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String filename = json.getFileName().toString();
                String base = filename.substring(0, filename.length() - 5);
                Path skeletonPath = json.resolveSibling(base + ".skeleton");
                if (!Files.exists(skeletonPath)) continue;
                String shape = json.getParent().getFileName().toString().toLowerCase(Locale.ROOT);
                Models.RoomDefinition room = parseRoom(shape, base, json, skeletonPath);
                byShape.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(room);
            }
        }
        for (List<Models.RoomDefinition> rooms : byShape.values()) {
            rooms.sort(Comparator.comparing(Models.RoomDefinition::roomName));
        }
        RoomDatabase result = new RoomDatabase(Collections.unmodifiableMap(byShape));
        if (result.roomCount() < 50) throw new IOException("Only " + result.roomCount() + " rooms loaded; database looks incomplete");
        RaisSkySecrets.LOGGER.info("Loaded {} Catacombs room definitions", result.roomCount());
        return result;
    }

    private static Models.RoomDefinition parseRoom(String shape, String fileKey, Path json, Path skeletonPath) throws Exception {
        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(json), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String roomName = fileKey;
        if (root.has("info") && root.getAsJsonObject("info").has("name")) {
            roomName = root.getAsJsonObject("info").get("name").getAsString();
        }
        List<Models.RelativeSecret> secrets = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("secrets");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject s = element.getAsJsonObject();
                String secretName = getString(s, "secretName", "Secret");
                secrets.add(new Models.RelativeSecret(
                        parseSecretIndex(secretName),
                        secretName,
                        getString(s, "category", "secret"),
                        s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt()));
            }
        }

        int[] skeleton;
        try (ObjectInputStream input = new ObjectInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(skeletonPath))))) {
            skeleton = (int[]) input.readObject();
        }
        Arrays.sort(skeleton);
        int minX = 255, minZ = 255, maxX = 0, maxZ = 0;
        for (int packed : skeleton) {
            int x = (packed >>> 24) & 0xFF;
            int z = (packed >>> 8) & 0xFF;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return new Models.RoomDefinition(shape, fileKey, roomName, skeleton, minX, maxX, minZ, maxZ, List.copyOf(secrets));
    }

    private static int parseSecretIndex(String name) {
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(name.substring(0, i));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
