/*
 * NordRegen - minimal chunk regeneration for Nord Fjell.
 *
 * The temporary-world approach is based on the regeneration design used by
 * WorldEdit (GPL-3.0), but this implementation uses only the public Paper API
 * and contains no WorldEdit source code.
 */
package dev.nordfjell.regen;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class NordRegenPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final long CONFIRMATION_WINDOW_MILLIS = 45_000L;
    private static final int MAX_BLOCKS_PER_TICK = 4_096;
    private static final long MAX_COPY_NANOS_PER_TICK = 8_000_000L;

    private final Map<UUID, PendingSelection> pendingSelections = new HashMap<>();
    private @Nullable RegenerationJob activeJob;

    @Override
    public void onEnable() {
        var command = getCommand("regenchunk");
        if (command == null) {
            throw new IllegalStateException("Command regenchunk is missing from plugin.yml");
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
        getLogger().info("NordRegen enabled. One chunk can be regenerated at a time.");
    }

    @Override
    public void onDisable() {
        pendingSelections.clear();
        RegenerationJob job = activeJob;
        if (job != null) {
            job.abort("Server is stopping; the regeneration was interrupted.");
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (args.length == 0) {
            selectCurrentChunk(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            confirm(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (pendingSelections.remove(player.getUniqueId()) != null) {
                player.sendMessage("Chunk regeneration cancelled.");
            } else {
                player.sendMessage("You do not have a pending chunk regeneration.");
            }
            return true;
        }

        player.sendMessage("Usage: /regenchunk [confirm|cancel]");
        return true;
    }

    private void selectCurrentChunk(Player player) {
        if (activeJob != null) {
            player.sendMessage("Another chunk regeneration is already running.");
            return;
        }

        Chunk chunk = player.getChunk();
        PendingSelection selection = new PendingSelection(
                player.getWorld().getUID(),
                chunk.getX(),
                chunk.getZ(),
                System.currentTimeMillis() + CONFIRMATION_WINDOW_MILLIS
        );
        pendingSelections.put(player.getUniqueId(), selection);

        player.sendMessage("Selected chunk " + chunk.getX() + ", " + chunk.getZ()
                + " in " + player.getWorld().getName() + ".");
        player.sendMessage("WARNING: all blocks, containers and non-player entities in this chunk will be reset.");
        player.sendMessage("Leave the selected chunk, then use /regenchunk confirm within 45 seconds.");
    }

    private void confirm(Player player) {
        PendingSelection selection = pendingSelections.remove(player.getUniqueId());
        if (selection == null) {
            player.sendMessage("Select a chunk first with /regenchunk.");
            return;
        }
        if (selection.expiresAtMillis() < System.currentTimeMillis()) {
            player.sendMessage("The confirmation expired. Select the chunk again.");
            return;
        }
        if (activeJob != null) {
            player.sendMessage("Another chunk regeneration is already running.");
            return;
        }

        World targetWorld = Bukkit.getWorld(selection.worldId());
        if (targetWorld == null) {
            player.sendMessage("The selected world is no longer loaded.");
            return;
        }

        List<Player> playersInside = targetWorld.getPlayers().stream()
                .filter(candidate -> candidate.getChunk().getX() == selection.chunkX()
                        && candidate.getChunk().getZ() == selection.chunkZ())
                .toList();
        if (!playersInside.isEmpty()) {
            player.sendMessage("Regeneration refused: leave the selected chunk first.");
            return;
        }

        startRegeneration(player.getUniqueId(), targetWorld, selection.chunkX(), selection.chunkZ());
    }

    private void startRegeneration(UUID ownerId, World targetWorld, int chunkX, int chunkZ) {
        String keyName = "regen_" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        WorldCreator creator = WorldCreator.ofKey(new NamespacedKey(this, keyName))
                .copy(targetWorld)
                .forcedSpawnPosition(Position.fine(centerX, targetWorld.getSeaLevel(), centerZ), 0.0F, 0.0F);
        World temporaryWorld;
        try {
            temporaryWorld = Bukkit.createWorld(creator);
        } catch (RuntimeException exception) {
            notifyPlayer(ownerId, "Could not create the temporary generation world. Check the server log.");
            getLogger().log(Level.SEVERE, "Failed to create a temporary world for chunk regeneration", exception);
            return;
        }
        if (temporaryWorld == null) {
            notifyPlayer(ownerId, "Could not create the temporary generation world.");
            return;
        }

        temporaryWorld.setAutoSave(false);
        Path temporaryFolder = temporaryWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
        RegenerationJob job = new RegenerationJob(
                ownerId, targetWorld, temporaryWorld, temporaryFolder, chunkX, chunkZ
        );
        activeJob = job;
        notifyPlayer(ownerId, "Generating a fresh copy of chunk " + chunkX + ", " + chunkZ + "...");

        temporaryWorld.getChunkAtAsync(chunkX, chunkZ, true, true).whenComplete((sourceChunk, throwable) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (activeJob != job) {
                        return;
                    }
                    if (throwable != null || sourceChunk == null) {
                        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                                ? throwable.getCause() : throwable;
                        getLogger().log(Level.SEVERE, "Fresh chunk generation failed", cause);
                        job.abort("Fresh chunk generation failed. Nothing was changed.");
                        return;
                    }
                    job.beginCopy(sourceChunk);
                })
        );
    }

    private void finishJob(RegenerationJob job) {
        if (activeJob == job) {
            activeJob = null;
        }
    }

    private void notifyPlayer(UUID playerId, String message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("confirm", "cancel").stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private record PendingSelection(UUID worldId, int chunkX, int chunkZ, long expiresAtMillis) {
    }

    private final class RegenerationJob {
        private final UUID ownerId;
        private final World targetWorld;
        private final World sourceWorld;
        private final Path sourceFolder;
        private final int chunkX;
        private final int chunkZ;
        private final int minY;
        private final int height;
        private final int totalBlocks;
        private final Map<Material, Boolean> tileMaterials = new EnumMap<>(Material.class);
        private int blockIndex;
        private int lastReportedQuarter;
        private @Nullable Chunk targetChunk;
        private @Nullable Chunk sourceChunk;
        private @Nullable BukkitTask copyTask;
        private boolean finished;
        private long copyStartedNanos;

        private RegenerationJob(
                UUID ownerId,
                World targetWorld,
                World sourceWorld,
                Path sourceFolder,
                int chunkX,
                int chunkZ
        ) {
            this.ownerId = ownerId;
            this.targetWorld = targetWorld;
            this.sourceWorld = sourceWorld;
            this.sourceFolder = sourceFolder;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minY = targetWorld.getMinHeight();
            this.height = targetWorld.getMaxHeight() - minY;
            this.totalBlocks = 16 * 16 * height;
        }

        private void beginCopy(Chunk generatedSourceChunk) {
            if (finished) {
                return;
            }
            this.sourceChunk = generatedSourceChunk;
            this.targetChunk = targetWorld.getChunkAt(chunkX, chunkZ);
            sourceChunk.addPluginChunkTicket(NordRegenPlugin.this);
            targetChunk.addPluginChunkTicket(NordRegenPlugin.this);

            for (Entity entity : targetChunk.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }

            copyBiomes();
            copyStartedNanos = System.nanoTime();
            copyTask = Bukkit.getScheduler().runTaskTimer(NordRegenPlugin.this, this::copyBatch, 1L, 1L);
        }

        private void copyBiomes() {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            for (int y = minY; y < minY + height; y += 4) {
                for (int localX = 0; localX < 16; localX += 4) {
                    for (int localZ = 0; localZ < 16; localZ += 4) {
                        int x = baseX + localX;
                        int z = baseZ + localZ;
                        targetWorld.setBiome(x, y, z, sourceWorld.getBiome(x, y, z));
                    }
                }
            }
        }

        private void copyBatch() {
            if (finished) {
                return;
            }
            try {
                int end = Math.min(totalBlocks, blockIndex + MAX_BLOCKS_PER_TICK);
                long deadline = System.nanoTime() + MAX_COPY_NANOS_PER_TICK;
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                int processed = 0;
                while (blockIndex < end && (processed < 64 || System.nanoTime() < deadline)) {
                    int localX = blockIndex & 15;
                    int localZ = (blockIndex >>> 4) & 15;
                    int y = minY + (blockIndex >>> 8);
                    Block source = sourceWorld.getBlockAt(baseX + localX, y, baseZ + localZ);
                    Block target = targetWorld.getBlockAt(baseX + localX, y, baseZ + localZ);

                    var sourceData = source.getBlockData();
                    if (!sourceData.equals(target.getBlockData())) {
                        target.setBlockData(sourceData, false);
                    }
                    boolean tileMaterial = tileMaterials.computeIfAbsent(
                            source.getType(), ignored -> source.getState(false) instanceof TileState
                    );
                    if (tileMaterial) {
                        BlockState sourceState = source.getState(false);
                        Location targetLocation = target.getLocation();
                        sourceState.copy(targetLocation).update(true, false);
                    }
                    blockIndex++;
                    processed++;
                }

                int quarter = (blockIndex * 4) / totalBlocks;
                if (quarter > lastReportedQuarter && quarter < 4) {
                    lastReportedQuarter = quarter;
                    notifyOwner("Chunk regeneration: " + (quarter * 25) + "%");
                }
                if (blockIndex >= totalBlocks) {
                    complete();
                }
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Chunk regeneration failed while copying blocks", exception);
                abort("Chunk regeneration failed while copying blocks. Check the server log.");
            }
        }

        private void complete() {
            if (finished) {
                return;
            }
            targetWorld.refreshChunk(chunkX, chunkZ);
            notifyOwner("Chunk " + chunkX + ", " + chunkZ + " was regenerated successfully.");
            long elapsedMillis = (System.nanoTime() - copyStartedNanos) / 1_000_000L;
            getLogger().info("Regenerated chunk " + chunkX + ", " + chunkZ + " in "
                    + targetWorld.getName() + "; paced copy took " + elapsedMillis + " ms");
            cleanup();
        }

        private void abort(String message) {
            if (finished) {
                return;
            }
            notifyOwner(message);
            cleanup();
        }

        private void cleanup() {
            finished = true;
            if (copyTask != null) {
                copyTask.cancel();
                copyTask = null;
            }
            if (sourceChunk != null) {
                sourceChunk.removePluginChunkTicket(NordRegenPlugin.this);
            }
            if (targetChunk != null) {
                targetChunk.removePluginChunkTicket(NordRegenPlugin.this);
            }

            boolean unloaded = Bukkit.unloadWorld(sourceWorld, false);
            if (!unloaded) {
                getLogger().warning("Could not unload temporary regeneration world " + sourceWorld.getName());
            } else {
                deleteTemporaryWorldAsync(sourceFolder);
            }
            finishJob(this);
        }

        private void notifyOwner(String message) {
            notifyPlayer(ownerId, message);
        }
    }

    private void deleteTemporaryWorldAsync(Path folder) {
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!folder.startsWith(worldContainer) || !folder.toString().contains("nordregen")) {
            getLogger().severe("Refused to delete unexpected temporary world path: " + folder);
            return;
        }
        if (!isEnabled()) {
            deleteTemporaryWorld(folder);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this, ignored -> deleteTemporaryWorld(folder));
    }

    private void deleteTemporaryWorld(Path folder) {
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new DeleteFailure(exception);
                }
            });
        } catch (IOException | DeleteFailure exception) {
            Throwable cause = exception instanceof DeleteFailure && exception.getCause() != null
                    ? exception.getCause() : exception;
            getLogger().log(Level.WARNING, "Could not fully delete temporary world " + folder, cause);
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private DeleteFailure(IOException cause) {
            super(cause);
        }
    }
}
