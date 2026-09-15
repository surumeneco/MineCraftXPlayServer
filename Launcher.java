import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Launcher {

    private static final Path PAPER_JAR = Path.of("paper.jar");
    private static final Path JVM_ARGS = Path.of("jvm.args");
    private static final Path ENV_FILE = Path.of(".env");

    private static final String DISCORD_BOT_TOKEN = "DISCORD_BOT_TOKEN";
    private static final String DISCORDSRV_TOKEN = "DISCORDSRV_TOKEN";

    private static final Path DATAPACK_SOURCE = Path.of("datapacks");
    private static final Path WORLD_DATAPACKS = Path.of("world", "datapacks");

    private static final List<Path> PRE_START_DELETIONS = List.of(
        Path.of("plugins", "clickmobs", "lang", "en_US.json"),
        Path.of("plugins", "ClickMobs", "lang", "en_US.json")
    );

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();

        requireFile(root.resolve(PAPER_JAR));
        requireFile(root.resolve(JVM_ARGS));

        syncDatapacks(
            root.resolve(DATAPACK_SOURCE),
            root.resolve(WORLD_DATAPACKS)
        );

        for (Path relativePath : PRE_START_DELETIONS) {
            Path target = root.resolve(relativePath);

            if (Files.deleteIfExists(target)) {
                System.out.println("[Launcher] Deleted: " + relativePath);
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
            "java",
            "@jvm.args",
            "-jar",
            "paper.jar",
            "--nogui"
        )
            .directory(root.toFile())
            .inheritIO();

        configureDiscordSrvToken(root.resolve(ENV_FILE), processBuilder);

        System.out.println("[Launcher] Starting Paper...");

        Process process = processBuilder.start();

        int exitCode = process.waitFor();

        System.out.println(
            "[Launcher] Paper stopped with exit code: " + exitCode
        );

        System.exit(exitCode);
    }

    private static void configureDiscordSrvToken(
        Path envFile,
        ProcessBuilder processBuilder
    ) throws IOException {
        Map<String, String> environment = processBuilder.environment();

        if (hasText(environment.get(DISCORDSRV_TOKEN))) {
            System.out.println(
                "[Launcher] DiscordSRV token configured from DISCORDSRV_TOKEN."
            );
            return;
        }

        String token = trimToNull(environment.get(DISCORD_BOT_TOKEN));
        String source = "DISCORD_BOT_TOKEN environment variable";

        if (token == null) {
            token = trimToNull(readDotEnv(envFile).get(DISCORD_BOT_TOKEN));
            source = ENV_FILE + " " + DISCORD_BOT_TOKEN;
        }

        if (token == null) {
            System.out.println(
                "[Launcher] DiscordSRV token is not configured; "
                    + "continuing without DISCORDSRV_TOKEN."
            );
            return;
        }

        environment.put(DISCORDSRV_TOKEN, token);
        System.out.println(
            "[Launcher] DiscordSRV token configured from " + source + "."
        );
    }

    private static Map<String, String> readDotEnv(Path path)
        throws IOException {

        Map<String, String> values = new HashMap<>();

        if (!Files.isRegularFile(path)) {
            return values;
        }

        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }

            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();

            if (
                value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length() - 1);
            }

            values.put(key, value);
        }

        return values;
    }

    private static boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void syncDatapacks(Path source, Path target)
        throws IOException {

        Files.createDirectories(source);
        Files.createDirectories(target);

        try (Stream<Path> stream = Files.walk(target)) {
            stream
                .filter(path -> !path.equals(target))
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }

        try (Stream<Path> stream = Files.walk(source)) {
            for (Path sourcePath : stream.toList()) {
                if (sourcePath.equals(source)) {
                    continue;
                }

                Path relative = source.relativize(sourcePath);
                Path targetPath = target.resolve(relative);

                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(
                        sourcePath,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
            }
        }

        System.out.println(
            "[Launcher] Datapacks synchronized: "
                + source
                + " -> "
                + target
        );
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(
                "Required file not found: " + path
            );
        }
    }
}
