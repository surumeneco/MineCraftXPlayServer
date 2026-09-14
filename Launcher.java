import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class Launcher {

    private static final Path PAPER_JAR = Path.of("paper.jar");
    private static final Path JVM_ARGS = Path.of("jvm.args");

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

        System.out.println("[Launcher] Starting Paper...");

        Process process = new ProcessBuilder(
            "java",
            "@jvm.args",
            "-jar",
            "paper.jar",
            "--nogui"
        )
            .directory(root.toFile())
            .inheritIO()
            .start();

        int exitCode = process.waitFor();

        System.out.println(
            "[Launcher] Paper stopped with exit code: " + exitCode
        );

        System.exit(exitCode);
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