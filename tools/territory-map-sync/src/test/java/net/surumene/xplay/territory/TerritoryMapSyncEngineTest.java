package net.surumene.xplay.territory;

import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TerritoryMapSyncEngineTest {
    @TempDir Path root;

    private static final String FIXED = """
            world: "world"
            dimension: "minecraft:overworld"
            marker-sets: {
              "public-area": {
                label: "共同建築エリア"
                sorting: 0
                markers: {
                  "first-area": { type: "poi", position: { x: 0, y: 250, z: 0 }, label: "fixed" }
                }
              }
              "Reserve": { label: "保護区", sorting: 1, markers: {} }
            }
            """;
    private static final String DYNAMIC = """
            "public-area": {
              label: "共同建築エリア"
              sorting: 0
              markers: { "new-area": { type: "poi", position: { x: 2, y: 250, z: 3 }, label: "territory" } }
            }
            "Reserve": { label: "保護区", sorting: 1, markers: {} }
            "Administration": { label: "運営", sorting: 2, markers: {} }
            "Personal": { label: "個人領地", sorting: 3, markers: {} }
            """;

    @Test
    void mergesFixedAndGeneratedMarkersIntoOneWorldConfig() {
        String result = TerritoryMapSyncEngine.merge(FIXED, DYNAMIC);
        Config parsed = ConfigFactory.parseString(result).resolve();
        assertEquals("world", parsed.getString("world"));
        assertEquals(0, parsed.getInt("marker-sets.public-area.sorting"));
        assertEquals(4, parsed.getObject("marker-sets").size());
        assertEquals(2, parsed.getObject("marker-sets.public-area.markers").size());
        assertEquals("fixed", parsed.getString("marker-sets.public-area.markers.first-area.label"));
        assertEquals("territory", parsed.getString("marker-sets.public-area.markers.new-area.label"));
        assertFalse(result.contains("include \"territories.conf\""));
    }

    @Test
    void staleMarkersDoNotSurviveRegeneration() {
        String initial = TerritoryMapSyncEngine.merge(FIXED, DYNAMIC);
        assertTrue(initial.contains("new-area"));
        String empty = """
                "public-area": { label: "共同建築エリア", markers: {} }
                "Reserve": { label: "保護区", markers: {} }
                "Administration": { label: "運営", markers: {} }
                "Personal": { label: "個人領地", markers: {} }
                """;
        String next = TerritoryMapSyncEngine.merge(FIXED, empty);
        Config parsed = ConfigFactory.parseString(next).resolve();
        assertFalse(next.contains("new-area"));
        assertTrue(parsed.hasPath("marker-sets.public-area.markers.first-area"));
    }

    @Test
    void malformedAndConflictingMarkerConfigsAreRejected() {
        assertThrows(RuntimeException.class, () -> TerritoryMapSyncEngine.merge(FIXED, "garbage {"));
        assertThrows(IllegalArgumentException.class, () ->
                TerritoryMapSyncEngine.merge(FIXED, DYNAMIC.replace(
                        "\"new-area\"", "\"first-area\"")));
    }

    @Test
    void parsesActualFixedWorldConfig() throws IOException {
        Path original = Path.of("..", "..", "config", "bluemap", "world.fixed.conf");
        String fixed = Files.readString(original, StandardCharsets.UTF_8);
        String result = TerritoryMapSyncEngine.merge(fixed, DYNAMIC);
        Config parsed = ConfigFactory.parseString(result).resolve();
        assertTrue(parsed.hasPath("marker-sets.public-area.markers.first-area"));
        assertTrue(parsed.hasPath("marker-sets.Reserve.markers.\"ursa mountain\""));
        assertTrue(parsed.hasPath("marker-sets.public-area.markers.new-area"));
    }

    @Test
    void fetchesAndReplacesWorldConfigWithoutTouchingFixedSource() throws Exception {
        Path fixed = root.resolve(TerritoryMapSyncEngine.FIXED);
        Path output = root.resolve(TerritoryMapSyncEngine.OUTPUT);
        Files.createDirectories(fixed.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(fixed, FIXED);
        Files.writeString(output, "old-world-conf");

        HttpServer server = startServer(200, DYNAMIC);
        try {
            URI endpoint = endpoint(server);
            var first = TerritoryMapSyncEngine.sync(root, endpoint, "test-secret");
            assertTrue(first.changed());
            assertEquals(2, first.markerCount());
            assertTrue(Files.readString(output).contains("new-area"));
            assertEquals(FIXED, Files.readString(fixed));
            var second = TerritoryMapSyncEngine.sync(root, endpoint, "test-secret");
            assertFalse(second.changed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retainsPreviouslyWorkingConfigOnHttpAndParseFailure() throws Exception {
        Path fixed = root.resolve(TerritoryMapSyncEngine.FIXED);
        Path output = root.resolve(TerritoryMapSyncEngine.OUTPUT);
        Files.createDirectories(fixed.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(fixed, FIXED);
        Files.writeString(output, "previous-valid-config");

        for (var situation : new Object[][] {
            { 503, "upstream unavailable" },
            { 200, "invalid { {" }
        }) {
            HttpServer server = startServer((int) situation[0], (String) situation[1]);
            try {
                assertThrows(Exception.class, () ->
                        TerritoryMapSyncEngine.sync(root, endpoint(server), "test-secret"));
                assertEquals("previous-valid-config", Files.readString(output));
                assertEquals(FIXED, Files.readString(fixed));
            } finally {
                server.stop(0);
            }
        }
    }

    private static HttpServer startServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/territories/bluemap-config", exchange -> {
            assertEquals("Bearer test-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/internal/territories/bluemap-config");
    }
}
