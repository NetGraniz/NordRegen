package dev.nordfjell.regen;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CleanupSettingsTest {
    // The actual block registry is server-owned; integration verifies the default registry.
    private CleanupSettings read(YamlConfiguration yaml) { return CleanupSettings.read(yaml,m -> m!=Material.DIAMOND); }
    private YamlConfiguration config() throws Exception {
        var yaml=new YamlConfiguration();
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/config.yml"),StandardCharsets.UTF_8)) { yaml.load(reader); }
        return yaml;
    }
    @Test void defaultsTargetMechanismsNotTerrainOrPlayers() throws Exception {
        var settings=read(config());
        assertTrue(settings.blocks().contains(Material.PISTON));
        assertTrue(settings.blocks().contains(Material.IRON_TRAPDOOR));
        assertTrue(settings.blocks().contains(Material.OAK_BUTTON));
        assertFalse(settings.blocks().contains(Material.STONE));
        assertFalse(settings.blocks().contains(Material.CHEST));
        assertFalse(settings.entities().contains(EntityType.PLAYER));
        assertFalse(settings.entities().contains(EntityType.PIG));
        assertEquals(256,settings.blockChecks()); assertEquals(750000,settings.budgetNanos());
        assertThrows(UnsupportedOperationException.class,()->settings.blocks().clear());
    }
    @Test void playerDeletionAndBadTypesFailClosed() throws Exception {
        for(Object invalid:List.of(List.of("PLAYER"),List.of("UNKNOWN"),List.of("not_real"),"MINECART")) {
            var yaml=config(); yaml.set("entities",invalid);
            assertThrows(IllegalArgumentException.class,()->read(yaml));
        }
        var yaml=config(); yaml.set("blocks",List.of("DIAMOND"));
        assertThrows(IllegalArgumentException.class,()->read(yaml));
    }
    @Test void budgetsBoundedAndBooleanStrict() throws Exception {
        for(Object invalid:List.of(0,4097,"256",256.0)) {
            var yaml=config(); yaml.set("block-checks-per-tick",invalid);
            assertThrows(IllegalArgumentException.class,()->read(yaml));
        }
        var yaml=config(); yaml.set("include-trapdoors","true");
        assertThrows(IllegalArgumentException.class,()->read(yaml));
    }
}
