package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads/saves lab.yml zones and builds the lab items (hazmat pieces, geiger counter). */
public final class LabRegistry {

    public static final List<String> ZONE_TYPES = List.of("radiation", "toxic", "cryo", "decon");

    private final Plugin plugin;
    private final NamespacedKey hazmatKey;
    private final NamespacedKey geigerKey;
    private final NamespacedKey sampleKey;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration yaml;

    public LabRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.hazmatKey = new NamespacedKey(plugin, "hazmat");
        this.geigerKey = new NamespacedKey(plugin, "geiger");
        this.sampleKey = new NamespacedKey(plugin, "sample");
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "lab.yml");
        if (!file.exists()) plugin.saveResource("lab.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        zones.clear();
        ConfigurationSection root = yaml.getConfigurationSection("zones");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            zones.put(name.toLowerCase(), new Zone(
                name.toLowerCase(),
                s.getString("world", "world"),
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                Math.max(1, Math.min(64, s.getDouble("radius", 8))),
                s.getString("type", "radiation").toLowerCase(),
                s.getBoolean("alarm", false)
            ));
        }
    }

    public Map<String, Zone> zones() { return zones; }

    /**
     * Containment: hazard only travels in a straight, unblocked line. Walls and CLOSED
     * doors block the ray; an OPEN door or any gap lets it through, because the ray tests
     * the blocks' real collision shapes. So a sealed chamber keeps its radiation inside.
     */
    public static boolean lineOfSight(Location from, Location to) {
        if (!from.getWorld().equals(to.getWorld())) return false;
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 0.5) return true;
        return from.getWorld().rayTraceBlocks(from, dir.normalize(), length,
            FluidCollisionMode.NEVER, true) == null;
    }

    /** The point a zone radiates from: chest height above where its creator stood. */
    public static Location sourceOf(Zone zone, org.bukkit.World world) {
        return new Location(world, zone.x(), zone.y() + 1.2, zone.z());
    }

    public boolean addZone(String name, String type, double radius, Location at) throws IOException {
        String key = name.toLowerCase();
        if (zones.containsKey(key)) return false;
        String path = "zones." + key + ".";
        yaml.set(path + "world", at.getWorld().getName());
        yaml.set(path + "x", at.getX());
        yaml.set(path + "y", at.getY());
        yaml.set(path + "z", at.getZ());
        yaml.set(path + "radius", Math.max(1, Math.min(64, radius)));
        yaml.set(path + "type", type.toLowerCase());
        yaml.save(file);
        load();
        return true;
    }

    /** Turn a zone's siren on/off. Returns false if the zone doesn't exist. */
    public boolean setAlarm(String name, boolean on) throws IOException {
        String key = name.toLowerCase();
        if (!zones.containsKey(key)) return false;
        yaml.set("zones." + key + ".alarm", on);
        yaml.save(file);
        load();
        return true;
    }

    public boolean removeZone(String name) throws IOException {
        String key = name.toLowerCase();
        if (!zones.containsKey(key)) return false;
        yaml.set("zones." + key, null);
        yaml.save(file);
        load();
        return true;
    }

    /** The four hazmat pieces: yellow leather armor, custom model ids hazmat_helmet etc. */
    public List<ItemStack> buildHazmatSuit() {
        return List.of(
            hazmatPiece(Material.LEATHER_HELMET, "hazmat_helmet", "Hazmat Hood"),
            hazmatPiece(Material.LEATHER_CHESTPLATE, "hazmat_chestplate", "Hazmat Suit"),
            hazmatPiece(Material.LEATHER_LEGGINGS, "hazmat_leggings", "Hazmat Pants"),
            hazmatPiece(Material.LEATHER_BOOTS, "hazmat_boots", "Hazmat Boots")
        );
    }

    private ItemStack hazmatPiece(Material material, String model, String name) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(230, 200, 40));
        meta.itemName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
        meta.getPersistentDataContainer().set(hazmatKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack buildGeiger() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Geiger Counter", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("lab_geiger"));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(geigerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** A radioactive sample: a portable radiation source. Geiger counters react to whoever
     *  carries it, and carrying it without a full hazmat suit slowly hurts. */
    public ItemStack buildSample() {
        ItemStack item = new ItemStack(Material.GLOWSTONE_DUST);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Radioactive Sample ☢", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("lab_sample"));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(sampleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSample(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(sampleKey, PersistentDataType.BYTE);
    }

    /** Is a radioactive sample anywhere in this player's inventory? */
    public boolean hasSample(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isSample(item)) return true;
        }
        return false;
    }

    /** Full protection = all four armor slots are hazmat pieces. */
    public boolean hasFullHazmat(Player player) {
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || !piece.hasItemMeta()) return false;
            if (!piece.getItemMeta().getPersistentDataContainer().has(hazmatKey, PersistentDataType.BYTE)) {
                return false;
            }
        }
        return true;
    }

    public boolean isGeiger(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(geigerKey, PersistentDataType.BYTE);
    }
}
