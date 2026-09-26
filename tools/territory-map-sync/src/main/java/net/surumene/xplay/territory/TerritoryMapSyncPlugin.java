package net.surumene.xplay.territory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class TerritoryMapSyncPlugin extends JavaPlugin implements CommandExecutor {
    private final AtomicBoolean syncing = new AtomicBoolean();

    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("territorymap")).setExecutor(this);
        getLogger().info("Manual territory sync available: /territorymap sync");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !"sync".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /territorymap sync");
            return true;
        }
        if (!sender.hasPermission("territorymap.sync")) {
            sender.sendMessage("You do not have permission to synchronize territory markers.");
            return true;
        }
        if (!syncing.compareAndSet(false, true)) {
            sender.sendMessage("Territory marker synchronization is already running.");
            return true;
        }
        sender.sendMessage("Fetching and generating BlueMap territory configuration...");
        // Never perform HTTP or filesystem operations on the Paper main thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            TerritoryMapSyncEngine.Result result = null;
            Exception failure = null;
            try {
                result = TerritoryMapSyncEngine.sync(Path.of("").toAbsolutePath());
            } catch (Exception e) {
                failure = e;
            }
            TerritoryMapSyncEngine.Result finalResult = result;
            Exception finalFailure = failure;
            try {
                getServer().getScheduler().runTask(this, () -> {
                    try {
                        if (finalFailure != null) {
                            String message = "Territory sync failed; previous world.conf retained: "
                                    + finalFailure.getClass().getSimpleName() + ": " + finalFailure.getMessage();
                            getLogger().warning(message);
                            sender.sendMessage(message);
                            return;
                        }
                        boolean accepted = getServer().dispatchCommand(
                                getServer().getConsoleSender(), "bluemap reload light");
                        if (!accepted) {
                            String message = "world.conf synchronized, but BlueMap reload command was not accepted.";
                            getLogger().warning(message);
                            sender.sendMessage(message);
                            return;
                        }
                        String message = "Territory marker configuration synchronized ("
                                + finalResult.markerCount() + " markers, "
                                + (finalResult.changed() ? "updated" : "unchanged")
                                + "); BlueMap reload light dispatched.";
                        getLogger().info(message);
                        sender.sendMessage(message);
                    } finally {
                        syncing.set(false);
                    }
                });
            } catch (IllegalStateException e) {
                syncing.set(false); // Plugin was disabled before the completion could be handled.
            }
        });
        return true;
    }
}
