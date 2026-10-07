package dev.nordfjell.tests;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Container;
import java.util.*;

/** Synthetic local tests only. NEVER deploy to production. */
public class CleanupProbe extends JavaPlugin implements Listener {
    @Override public void onEnable(){getServer().getPluginManager().registerEvents(this,this);}
    @EventHandler public void join(PlayerJoinEvent event) {
        Player p=event.getPlayer(); p.setGameMode(GameMode.CREATIVE); p.setAllowFlight(true); p.setFlying(true);
        p.getScheduler().runDelayed(this,ignored -> setup(p),null,10);
    }
    private void setup(Player p) {
        try {
            World w=p.getWorld(); int bx=p.getLocation().getBlockX()&~15,bz=p.getLocation().getBlockZ()&~15,y=120;
            if(!Bukkit.isOwnedByCurrentRegion(w,(bx>>4)+1,bz>>4) || !w.isChunkLoaded((bx>>4)+1,bz>>4)) throw new IllegalStateException("Neighbor not owned/loaded");
            List<Material> targets=List.of(Material.REDSTONE_WIRE,Material.PISTON,Material.STICKY_PISTON,Material.OBSERVER,
                Material.HOPPER,Material.OAK_TRAPDOOR,Material.REPEATER,Material.COMPARATOR,Material.DISPENSER,Material.DROPPER);
            for(int i=0;i<targets.size();i++) {
                w.getBlockAt(bx+1+i,y-1,bz+2).setType(Material.STONE,false);
                w.getBlockAt(bx+1+i,y,bz+2).setType(targets.get(i),false);
            }
            ((Container)w.getBlockAt(bx+5,y,bz+2).getState()).getInventory().addItem(new ItemStack(Material.DIAMOND,3));
            w.getBlockAt(bx+1,y,bz+4).setType(Material.STONE,false);
            w.getBlockAt(bx+2,y,bz+4).setType(Material.CHEST,false);
            ((Container)w.getBlockAt(bx+2,y,bz+4).getState()).getInventory().addItem(new ItemStack(Material.EMERALD,2));
            w.getBlockAt(bx+16,y,bz+2).setType(Material.PISTON,false);
            ArmorStand stand=w.spawn(new Location(w,bx+5.5,y+2,bz+6.5),ArmorStand.class); stand.setGravity(false);
            Minecart cart=w.spawn(new Location(w,bx+6.5,y+2,bz+6.5),Minecart.class); cart.setGravity(false);
            Pig pig=w.spawn(new Location(w,bx+7.5,y+2,bz+6.5),Pig.class); pig.setAI(false); pig.setGravity(false);
            ArmorStand neighbor=w.spawn(new Location(w,bx+16.5,y+2,bz+6.5),ArmorStand.class); neighbor.setGravity(false);
            var permission=p.addAttachment(this); permission.setPermission("nordregen.use",false);
            var command=Bukkit.getPluginCommand("regenchunk");
            command.getExecutor().onCommand(p,command,"regenchunk",new String[0]);
            command.getExecutor().onCommand(p,command,"regenchunk",new String[]{"confirm"});
            permission.setPermission("nordregen.use",true);
            p.performCommand("clearlagchunk"); p.performCommand("clearlagchunk cancel"); p.performCommand("clearlagchunk confirm");
            if(w.getBlockAt(bx+2,y,bz+2).getType()!=Material.PISTON) throw new IllegalStateException("Changed without confirmation");
            p.performCommand("clearlagchunk"); p.performCommand("clearlagchunk confirm");
            if(w.getBlockAt(bx+2,y,bz+2).getType()!=Material.PISTON) throw new IllegalStateException("Cleanup not paced");
            long started=System.nanoTime();
            p.getScheduler().runAtFixedRate(this,task -> {
                try {
                    if(System.nanoTime()-started>90_000_000_000L) throw new IllegalStateException("Cleanup timeout");
                    for(int i=0;i<targets.size();i++) if(!w.getBlockAt(bx+1+i,y,bz+2).getType().isAir()) return;
                    if(stand.isValid() || cart.isValid()) return;
                    if(!pig.isValid() || !neighbor.isValid() || !p.isOnline()) throw new IllegalStateException("Untargeted entity removed");
                    if(w.getBlockAt(bx+1,y,bz+4).getType()!=Material.STONE || w.getBlockAt(bx+2,y,bz+4).getType()!=Material.CHEST
                        || w.getBlockAt(bx+16,y,bz+2).getType()!=Material.PISTON) throw new IllegalStateException("Boundary/terrain changed");
                    if(!((Container)w.getBlockAt(bx+2,y,bz+4).getState()).getInventory().contains(Material.EMERALD,2)) throw new IllegalStateException("Untargeted inventory changed");
                    for(Entity entity:w.getChunkAt(bx>>4,bz>>4).getEntities()) if(entity instanceof Item item) throw new IllegalStateException("Unexpected drops: "+item.getItemStack());
                    getLogger().info("CLEANUP_TEST_PASS permissions cancellation confirmation pacing targeted blocks/entities boundary terrain inventory player"); task.cancel();
                }catch(Exception e){getLogger().severe("CLEANUP_TEST_FAIL "+e);task.cancel();}
            },null,20,20);
        }catch(Exception e){getLogger().severe("CLEANUP_TEST_FAIL "+e);}
    }
}
