package net.surumene.xplay.territory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class TerritoryMapSyncEngine {
    static final Path FIXED = Path.of("plugins", "BlueMap", "maps", "world.fixed.conf");
    static final Path OUTPUT = Path.of("plugins", "BlueMap", "maps", "world.conf");
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private TerritoryMapSyncEngine() {}

    public record Result(boolean changed, int markerCount) {}

    public static Result sync(Path root) throws Exception {
        Map<String, String> envFile = readDotEnv(root.resolve(".env"));
        String url = preferred(System.getenv("TERRITORY_CONFIG_URL"), envFile.get("TERRITORY_CONFIG_URL"));
        String secret = preferred(System.getenv("TERRITORY_CONFIG_SECRET"), envFile.get("TERRITORY_CONFIG_SECRET"));
        if (url == null || secret == null) {
            throw new IOException("TERRITORY_CONFIG_URL and TERRITORY_CONFIG_SECRET must both be configured");
        }
        return sync(root, URI.create(url), secret);
    }

    static Result sync(Path root, URI uri, String secret) throws Exception {
        validateUri(uri);
        if (secret == null || secret.isBlank()) throw new IOException("Territory sync secret is missing");
        Path fixedFile = root.resolve(FIXED);
        Path outputFile = root.resolve(OUTPUT);
        String fixedText = Files.readString(fixedFile, StandardCharsets.UTF_8);
        // Parse before requesting: if local fixed config is invalid, do not touch world.conf.
        ConfigFactory.parseString(fixedText).resolve().getObject("marker-sets");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + secret)
                .header("Accept", "text/plain")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Territory config fetch returned HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("Territory config response size is invalid");
        }
        String dynamicText = new String(bytes, StandardCharsets.UTF_8);
        String merged = merge(fixedText, dynamicText);
        return writeAtomically(outputFile, merged);
    }

    static String merge(String fixedText, String dynamicText) {
        Objects.requireNonNull(fixedText);
        Objects.requireNonNull(dynamicText);
        Config staticConfig = ConfigFactory.parseString(fixedText).resolve();
        ConfigObject staticSets = staticConfig.getObject("marker-sets");

        // The WebApp returns exactly these four marker-set objects, without a wrapper.
        String dynamicSection = "marker-sets: {\n" + dynamicText + "\n}\n";
        Config dynamicConfig = ConfigFactory.parseString(dynamicSection).resolve();
        ConfigObject generatedSets = dynamicConfig.getObject("marker-sets");
        for (String category : new String[] {"public-area", "Reserve", "Administration", "Personal"}) {
            if (!generatedSets.containsKey(category)) {
                throw new IllegalArgumentException("Missing territory marker category: " + category);
            }
        }
        if (generatedSets.size() != 4) {
            throw new IllegalArgumentException("Unexpected territory marker category");
        }

        // HOCON recursively merges duplicate object keys. Existing manual markers
        // remain in their category and generated markers join the same category.
        String merged = fixedText.stripTrailing()
                + "\n\n# GENERATED: territory markers from WebApp. Edit world.fixed.conf instead.\n"
                + dynamicSection;
        Config combined = ConfigFactory.parseString(merged).resolve();

        // Fail instead of silently replacing an existing manually managed marker
        // with a generated marker that happens to use the same identifier.
        ConfigObject resultSets = combined.getObject("marker-sets");
        for (Map.Entry<String, ConfigValue> category : staticSets.entrySet()) {
            if (!(category.getValue() instanceof ConfigObject staticCategory)) continue;
            ConfigValue staticMarkersValue = staticCategory.get("markers");
            if (!(staticMarkersValue instanceof ConfigObject staticMarkers)) continue;
            ConfigValue combinedCategoryValue = resultSets.get(category.getKey());
            if (!(combinedCategoryValue instanceof ConfigObject combinedCategory)) {
                throw new IllegalArgumentException("Fixed category disappeared: " + category.getKey());
            }
            ConfigValue combinedMarkersValue = combinedCategory.get("markers");
            if (!(combinedMarkersValue instanceof ConfigObject combinedMarkers)) {
                throw new IllegalArgumentException("Fixed marker category disappeared: " + category.getKey());
            }
            for (Map.Entry<String, ConfigValue> fixedMarker : staticMarkers.entrySet()) {
                if (!fixedMarker.getValue().equals(combinedMarkers.get(fixedMarker.getKey()))) {
                    throw new IllegalArgumentException("Generated marker conflicts with fixed marker: " + fixedMarker.getKey());
                }
            }
        }
        return merged;
    }

    private static Result writeAtomically(Path output, String merged) throws IOException {
        int markerCount = countMarkers(merged);
        if (Files.isRegularFile(output) && Files.readString(output, StandardCharsets.UTF_8).equals(merged)) {
            return new Result(false, markerCount);
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".world-conf-", ".tmp");
        try {
            Files.writeString(temporary, merged, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Do not fall back to a partially written map config.
                throw new IOException("Atomic replacement is not supported for BlueMap config", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Result(true, markerCount);
    }

    private static int countMarkers(String text) {
        ConfigObject sets = ConfigFactory.parseString(text).resolve().getObject("marker-sets");
        int result = 0;
        for (ConfigValue setValue : sets.values()) {
            if (setValue instanceof ConfigObject set && set.get("markers") instanceof ConfigObject markers) {
                result += markers.size();
            }
        }
        return result;
    }

    private static void validateUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IOException("Invalid territory config URL");
        }
    }

    private static Map<String, String> readDotEnv(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(path)) return values;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private static String preferred(String env, String file) {
        if (env != null && !env.isBlank()) return env.strip();
        if (file != null && !file.isBlank()) return file.strip();
        return null;
    }
}
