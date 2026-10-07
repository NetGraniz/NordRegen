package dev.nordfjell.regen;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;

/** Destructive administrative cleanup, never world generation. */
public final class NordRegenPlugin extends JavaPlugin implements Listener {
    private record Selection(UUID world, int x, int z, long expires) {}
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final AtomicReference<Job> active = new AtomicReference<>();
    private CleanupSettings settings;

    @Override public void onEnable() {
        saveDefaultConfig();
        try { settings = CleanupSettings.read(getConfig()); }
        catch (IllegalArgumentException e) {
            getLogger().severe("Invalid cleanup config: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this); return;
        }
        Objects.requireNonNull(getCommand("regenchunk")).setExecutor(this::command);
        getCommand("regenchunk").setTabCompleter((sender,command,alias,args) -> args.length == 1
            ? List.of("confirm","cancel").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of());
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("Paced chunk cleanup enabled; no terrain generation.");
    }
    @Override public void onDisable() {
        selections.clear();
        Job job = active.getAndSet(null);
        if (job != null) {
            job.cancelled.set(true);
            if (job.task != null) job.task.cancel();
            getLogger().warning("Cleanup interrupted; removed objects are not restored.");
        }
        // Server shutdown removes plugin tickets. Do not access foreign chunks here.
    }
    @EventHandler public void quit(PlayerQuitEvent event) { selections.remove(event.getPlayer().getUniqueId()); }
    private boolean command(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nordregen.use")) { sender.sendMessage("You do not have permission."); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can select a chunk."); return true; }
        if (!Bukkit.isOwnedByCurrentRegion(player)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            player.sendMessage(selections.remove(player.getUniqueId()) != null ? "Pending cleanup cancelled."
                : "No pending selection. Running cleanup cannot be undone."); return true;
        }
        if (args.length == 0) {
            if (active.get() != null) { player.sendMessage("Another cleanup is running."); return true; }
            long now = System.nanoTime();
            selections.entrySet().removeIf(e -> now-e.getValue().expires() >= 0);
            if (selections.size() >= 256 && !selections.containsKey(player.getUniqueId())) {
                player.sendMessage("Too many selections; try later."); return true;
            }
            Location at = player.getLocation();
            int x = at.getBlockX() >> 4, z = at.getBlockZ() >> 4;
            selections.put(player.getUniqueId(),new Selection(player.getWorld().getUID(),x,z,now+settings.confirmSeconds()*1_000_000_000L));
            player.sendMessage("Selected chunk " + x + ", " + z + " in " + player.getWorld().getName() + ".");
            player.sendMessage("WARNING: matching mechanisms/entities AND their contents will be permanently deleted without drops or automatic undo. You may fall if a block beneath you is removed.");
            player.sendMessage("Blocks: " + settings.blocks().stream().map(Enum::name).sorted().toList());
            player.sendMessage("Entities: " + settings.entities().stream().map(Enum::name).sorted().toList());
            player.sendMessage("Stay in this chunk and use /"+label+" confirm within "+settings.confirmSeconds()+" seconds."); return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            player.sendMessage("Usage: /"+label+" [confirm|cancel]"); return true;
        }
        Selection selection = selections.remove(player.getUniqueId());
        if (selection == null || System.nanoTime()-selection.expires() >= 0) {
            player.sendMessage("Selection missing or expired. Select again."); return true;
        }
        Location at = player.getLocation();
        if (!selection.world().equals(player.getWorld().getUID()) || (at.getBlockX()>>4)!=selection.x() || (at.getBlockZ()>>4)!=selection.z()) {
            player.sendMessage("Stand in the originally selected chunk and select again."); return true;
        }
        Job job = new Job(player.getUniqueId(),player.getWorld(),selection.x(),selection.z());
        if (!active.compareAndSet(null,job)) { player.sendMessage("Another cleanup is running."); return true; }
        player.sendMessage("Chunk cleanup started. Terrain will NOT be regenerated.");
        getLogger().info("Cleanup requested by "+player.getUniqueId()+" in "+selection.world()+" chunk "+selection.x()+","+selection.z());
        try { job.task = Bukkit.getRegionScheduler().runAtFixedRate(this,job.world,job.x,job.z,ignored -> job.tick(),1,1); }
        catch (RuntimeException e) { active.compareAndSet(job,null); throw e; }
        return true;
    }
    private void notifyOwner(UUID id,String message) {
        if (!isEnabled()) return;
        Player player = Bukkit.getPlayer(id);
        if (player!=null) player.getScheduler().execute(this,() -> { if(player.isOnline()) player.sendMessage(message); },null,1);
    }
    private final class Job {
        final UUID owner; final World world; final int x,z,minY,total;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger outstanding = new AtomicInteger(), removedEntities = new AtomicInteger();
        ScheduledTask task; Chunk chunk; Entity[] entities;
        int entityIndex,blockIndex,removedBlocks; long lastRun; boolean ticket;
        Job(UUID owner,World world,int x,int z) {
            this.owner=owner; this.world=world; this.x=x; this.z=z;
            minY=world.getMinHeight(); total=256*(world.getMaxHeight()-minY);
        }
        void tick() {
            if(cancelled.get()) return;
            try {
                if(!Bukkit.isOwnedByCurrentRegion(world,x,z)) throw new IllegalStateException("Wrong region");
                if(!world.isChunkLoaded(x,z)) { finish("Cleanup stopped: chunk unloaded; changes are not undone."); return; }
                if(chunk==null) {
                    chunk=world.getChunkAt(x,z); ticket=chunk.addPluginChunkTicket(NordRegenPlugin.this);
                    entities=chunk.getEntities(); // API enumeration is not time-sliced; documented limitation
                }
                long now=System.nanoTime(), delay=lastRun==0?0:now-lastRun; lastRun=now;
                if(delay>150_000_000L) return; // overloaded owning region: skip, never catch up
                long deadline=now+settings.budgetNanos(); int checked=0;
                while(entityIndex<entities.length && checked<settings.entityChecks() && outstanding.get()<32 && System.nanoTime()<deadline) {
                    Entity entity=entities[entityIndex++]; checked++;
                    if(entity instanceof Player || !settings.entities().contains(entity.getType())) continue;
                    outstanding.incrementAndGet(); AtomicBoolean released=new AtomicBoolean();
                    Runnable release=() -> { if(released.compareAndSet(false,true)) outstanding.decrementAndGet(); };
                    try {
                        if(!entity.getScheduler().execute(NordRegenPlugin.this,() -> {
                            try {
                                if(cancelled.get() || !entity.isValid() || entity instanceof Player) return;
                                Location location=entity.getLocation();
                                if(location.getWorld().getUID().equals(world.getUID()) && (location.getBlockX()>>4)==x && (location.getBlockZ()>>4)==z
                                    && settings.entities().contains(entity.getType())) { entity.remove(); removedEntities.incrementAndGet(); }
                            } finally { release.run(); }
                        },release,1)) release.run();
                    } catch(RuntimeException e) { release.run(); throw e; }
                }
                checked=0;
                while(blockIndex<total && checked<settings.blockChecks() && System.nanoTime()<deadline) {
                    int index=blockIndex++;
                    var block=chunk.getBlock(index&15,minY+(index>>>8),(index>>>4)&15);
                    if(settings.blocks().contains(block.getType())) {
                        // Replacing a container can drop its contents even with physics=false.
                        // Snapshot inventory is local to this block, not a neighboring double chest.
                        if(block.getState(false) instanceof org.bukkit.block.Container container) {
                            container.getSnapshotInventory().clear();
                            if(!container.update(true,false)) throw new IllegalStateException("Could not clear container");
                        }
                        block.setType(Material.AIR,false); removedBlocks++;
                    }
                    checked++;
                }
                if(blockIndex==total && entityIndex==entities.length && outstanding.get()==0)
                    finish("Chunk cleanup complete: removed "+removedBlocks+" blocks and "+removedEntities.get()+" entities.");
            } catch(RuntimeException e) {
                getLogger().log(Level.SEVERE,"Cleanup failed in chunk "+x+","+z,e);
                finish("Cleanup failed. Removed objects are not restored; check logs.");
            }
        }
        void finish(String message) {
            cancelled.set(true); if(task!=null) task.cancel();
            try { if(ticket) chunk.removePluginChunkTicket(NordRegenPlugin.this); }
            finally {
                active.compareAndSet(this,null);
                getLogger().info(world.getUID()+" chunk "+x+","+z+": "+message); notifyOwner(owner,message);
            }
        }
    }
}
