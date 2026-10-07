package dev.nordfjell.regen;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import java.util.*;
import java.util.function.Predicate;

record CleanupSettings(Set<Material> blocks,Set<EntityType> entities,int blockChecks,int entityChecks,long budgetNanos,int confirmSeconds) {
    static CleanupSettings read(ConfigurationSection config) {
        return read(config,Material::isBlock);
    }
    static CleanupSettings read(ConfigurationSection config,Predicate<Material> isBlock) {
        Set<Material> blocks=EnumSet.noneOf(Material.class);
        for(String name:names(config,"blocks")) {
            Material type;
            try { type=Material.valueOf(name); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Unknown block: "+name); }
            if(!isBlock.test(type) || type==Material.AIR || type==Material.CAVE_AIR || type==Material.VOID_AIR) throw new IllegalArgumentException("Not a removable block: "+name);
            blocks.add(type);
        }
        for(String suffix:List.of("TRAPDOOR","BUTTON","PRESSURE_PLATE")) {
            String key="include-"+suffix.toLowerCase(Locale.ROOT).replace('_','-')+"s";
            if(!(config.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Expected boolean: "+key);
            if(value) for(Material material:Material.values()) if(material.name().endsWith("_"+suffix) && isBlock.test(material)) blocks.add(material);
        }
        Set<EntityType> entities=EnumSet.noneOf(EntityType.class);
        for(String name:names(config,"entities")) {
            EntityType type;
            try { type=EntityType.valueOf(name); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Unknown entity: "+name); }
            if(type==EntityType.PLAYER || type==EntityType.UNKNOWN) throw new IllegalArgumentException("Forbidden entity: "+name);
            entities.add(type);
        }
        return new CleanupSettings(Set.copyOf(blocks),Set.copyOf(entities),integer(config,"block-checks-per-tick",1,4096),
            integer(config,"entity-checks-per-tick",1,32),integer(config,"budget-microseconds",100,2000)*1000L,integer(config,"confirmation-seconds",5,120));
    }
    private static List<String> names(ConfigurationSection config,String key) {
        if(!(config.get(key) instanceof List<?> list)) throw new IllegalArgumentException("Expected list: "+key);
        List<String> result=new ArrayList<>();
        for(Object value:list) {
            if(!(value instanceof String name) || !name.matches("[A-Z_]+")) throw new IllegalArgumentException("Invalid entry: "+key);
            result.add(name);
        }
        return result;
    }
    private static int integer(ConfigurationSection config,String key,int min,int max) {
        if(!(config.get(key) instanceof Integer number) || number<min || number>max) throw new IllegalArgumentException("Invalid range/type: "+key);
        return number;
    }
}
