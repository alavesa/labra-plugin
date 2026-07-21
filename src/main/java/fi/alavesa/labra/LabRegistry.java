package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Loads/saves lab.yml zones and builds the lab items (hazmat pieces, geiger counter). */
public final class LabRegistry {

    public static final List<String> ZONE_TYPES = List.of("radiation", "toxic", "cryo", "decon");

    private final Plugin plugin;
    private final NamespacedKey hazmatKey;
    private final NamespacedKey wearKey;
    private final NamespacedKey geigerKey;
    private final NamespacedKey sampleKey;
    private final NamespacedKey extinguisherKey;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration yaml;

    // SCP-1499's dimension anchor (where the mask takes you). Stored raw so a
    // not-yet-loaded world doesn't lose the setting; resolved on each use.
    private boolean anchor1499Set;
    private String anchor1499World;
    private double anchor1499X, anchor1499Y, anchor1499Z;
    private float anchor1499Yaw, anchor1499Pitch;

    public LabRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.hazmatKey = new NamespacedKey(plugin, "hazmat");
        this.wearKey = new NamespacedKey(plugin, "hazmat_wear");
        this.geigerKey = new NamespacedKey(plugin, "geiger");
        this.sampleKey = new NamespacedKey(plugin, "sample");
        this.extinguisherKey = new NamespacedKey(plugin, "extinguisher");
    }

    /** How many sprays a full extinguisher holds. */
    public static final int EXTINGUISHER_MAX = 40;
    private final NamespacedKey chargeKey = new NamespacedKey("labra", "ext_charge");

    /** The held fire extinguisher: right-click sprays and puts out fire. Full. */
    public ItemStack buildExtinguisher() {
        ItemStack item = new ItemStack(Material.BRICK);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Fire Extinguisher", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("lab_extinguisher"));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(extinguisherKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, EXTINGUISHER_MAX);
        item.setItemMeta(meta);
        chargeLore(item, EXTINGUISHER_MAX);
        return item;
    }

    public int extinguisherCharge(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(chargeKey, PersistentDataType.INTEGER, 0);
    }

    /** Set the item's remaining charge and refresh its lore/durability bar. */
    public void setExtinguisherCharge(ItemStack item, int charge) {
        if (item == null || !item.hasItemMeta()) return;
        int c = Math.max(0, Math.min(EXTINGUISHER_MAX, charge));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, c);
        item.setItemMeta(meta);
        chargeLore(item, c);
    }

    private void chargeLore(ItemStack item, int charge) {
        ItemMeta meta = item.getItemMeta();
        int pct = Math.round(charge / (float) EXTINGUISHER_MAX * 100);
        NamedTextColor col = pct > 50 ? NamedTextColor.GREEN : pct > 20 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        meta.lore(List.of(Component.text("Charge: " + pct + "%", col).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
    }

    /** The item shown on a wall mount (the extinguisher, cradled). */
    public ItemStack buildMountItem() {
        return modelItem("lab_extinguisher_mount");
    }

    /** The item shown on a sprinkler control button display. */
    public ItemStack buildSprinklerButtonItem() {
        return modelItem("lab_sprinkler_button");
    }

    private ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.BRICK);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isExtinguisher(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(extinguisherKey, PersistentDataType.BYTE);
    }

    // --- gas masks (all three look identical; the tier is only in the data) ------
    private final NamespacedKey gasMaskKey = new NamespacedKey("labra", "gasmask");

    /** A worn carved-pumpkin gas mask. tier: "normal" (smoke immunity), "super"
     *  (+ infinite sprint stamina), "heavy" (+ immunity to memetic hazards). */
    public ItemStack buildGasMask(String tier) {
        String t = switch (tier == null ? "" : tier.toLowerCase()) {
            case "super", "heavy" -> tier.toLowerCase();
            default -> "normal";
        };
        ItemStack item = new ItemStack(Material.LEATHER_HELMET);
        ItemMeta meta = item.getItemMeta();
        // Every tier is deliberately INDISTINGUISHABLE: same name, same model. The
        // tier lives only in the hidden PDC below, so no one can tell a heavy/super
        // mask from a plain one by looking at it.
        meta.itemName(Component.text("Gas Mask", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("lab_gasmask"));   // identical model for every tier
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(gasMaskKey, PersistentDataType.STRING, t);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isGasMask(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(gasMaskKey, PersistentDataType.STRING);
    }

    /** "normal" | "super" | "heavy", or null if it isn't a gas mask. */
    public String gasMaskTier(ItemStack item) {
        if (!isGasMask(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(gasMaskKey, PersistentDataType.STRING);
    }

    // --- night-vision goggles (leather helmet + custom model, per type) ----------
    private final NamespacedKey nvgTypeKey = new NamespacedKey("labra", "nvg_type");

    /** NVG goggles. type: "green" (draining battery), "red" (infinite battery),
     *  "blue" (infinite; sees SCP locations through walls). */
    public ItemStack buildNvg(String type) {
        String t = switch (type == null ? "" : type.toLowerCase()) {
            case "red", "blue" -> type.toLowerCase();
            default -> "green";
        };
        ItemStack item = new ItemStack(Material.LEATHER_HELMET);
        ItemMeta meta = item.getItemMeta();
        String name = switch (t) {
            case "red" -> "Red NVG"; case "blue" -> "Recon NVG"; default -> "Night Vision Goggles";
        };
        NamedTextColor col = switch (t) {
            case "red" -> NamedTextColor.RED; case "blue" -> NamedTextColor.AQUA; default -> NamedTextColor.GREEN;
        };
        meta.itemName(Component.text(name, col).decoration(TextDecoration.ITALIC, false));
        String model = switch (t) { case "red" -> "lab_nvg_red"; case "blue" -> "lab_nvg_blue"; default -> "lab_nvg"; };
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(nvgTypeKey, PersistentDataType.STRING, t);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isNvg(ItemStack item) {
        return item != null && item.getType() == Material.LEATHER_HELMET && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(nvgTypeKey, PersistentDataType.STRING);
    }

    /** "green" | "red" | "blue", or null if it isn't NVG. */
    public String nvgType(ItemStack item) {
        if (!isNvg(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(nvgTypeKey, PersistentDataType.STRING);
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "lab.yml");
        if (!file.exists()) plugin.saveResource("lab.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);
        zones.clear();
        ConfigurationSection mask = yaml.getConfigurationSection("scp1499");
        anchor1499Set = mask != null;
        if (mask != null) {
            anchor1499World = mask.getString("world", "world");
            anchor1499X = mask.getDouble("x");
            anchor1499Y = mask.getDouble("y");
            anchor1499Z = mask.getDouble("z");
            anchor1499Yaw = (float) mask.getDouble("yaw");
            anchor1499Pitch = (float) mask.getDouble("pitch");
        }
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

    /** Where SCP-1499 takes its wearer, or null if not configured (or the
     *  world isn't loaded). Set with /lab scp1499 sethere. */
    public Location scp1499Anchor() {
        if (!anchor1499Set) return null;
        World world = Bukkit.getWorld(anchor1499World);
        if (world == null) return null;
        return new Location(world, anchor1499X, anchor1499Y, anchor1499Z, anchor1499Yaw, anchor1499Pitch);
    }

    public void setScp1499Anchor(Location at) throws IOException {
        yaml.set("scp1499.world", at.getWorld().getName());
        yaml.set("scp1499.x", at.getX());
        yaml.set("scp1499.y", at.getY());
        yaml.set("scp1499.z", at.getZ());
        yaml.set("scp1499.yaw", (double) at.getYaw());
        yaml.set("scp1499.pitch", (double) at.getPitch());
        yaml.save(file);
        load();
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

    /** One SCP-008 Host claw hit chews the worn suit: a random hazmat piece
     *  takes a point of wear and tears clean off after six (the pieces are
     *  unbreakable, so host claws are the ONLY thing that wears them out).
     *  Returns 1 if a piece was destroyed, 0 if the suit just took wear,
     *  -1 if no hazmat is worn at all. */
    public int wearHazmat(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        List<Integer> worn = new ArrayList<>();
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece != null && piece.hasItemMeta()
                && piece.getItemMeta().getPersistentDataContainer().has(hazmatKey, PersistentDataType.BYTE)) {
                worn.add(i);
            }
        }
        if (worn.isEmpty()) return -1;
        int slot = worn.get(ThreadLocalRandom.current().nextInt(worn.size()));
        ItemStack piece = armor[slot];
        ItemMeta meta = piece.getItemMeta();
        int wear = meta.getPersistentDataContainer()
            .getOrDefault(wearKey, PersistentDataType.INTEGER, 0) + 1;
        if (wear >= 6) {
            armor[slot] = null;
            player.getInventory().setArmorContents(armor);
            return 1;
        }
        meta.getPersistentDataContainer().set(wearKey, PersistentDataType.INTEGER, wear);
        piece.setItemMeta(meta);
        player.getInventory().setArmorContents(armor);
        return 0;
    }

    public boolean isGeiger(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(geigerKey, PersistentDataType.BYTE);
    }
}
