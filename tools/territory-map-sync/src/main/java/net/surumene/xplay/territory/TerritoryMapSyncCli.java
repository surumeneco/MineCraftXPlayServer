package net.surumene.xplay.territory;

import java.nio.file.Path;

public final class TerritoryMapSyncCli {
    private TerritoryMapSyncCli() {}

    public static void main(String[] args) {
        if (args.length != 1 || !"--sync".equals(args[0])) {
            System.err.println("Usage: java -jar TerritoryMapSync.jar --sync");
            System.exit(2);
            return;
        }
        try {
            TerritoryMapSyncEngine.Result result = TerritoryMapSyncEngine.sync(Path.of("").toAbsolutePath());
            System.out.println("[TerritoryMapSync] world.conf synchronized; "
                    + result.markerCount() + " markers ("
                    + (result.changed() ? "updated" : "unchanged") + ").");
        } catch (Exception e) {
            System.err.println("[TerritoryMapSync] Sync failed; keeping existing world.conf: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
