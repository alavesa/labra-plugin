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

    // SCP-500 pill bottle: dispenses pills, three per bottle (shown as its durability bar).
    public static final int SCP500_BOTTLE_USES = 3;
    private final NamespacedKey scp500BottleKey = new NamespacedKey("labra", "scp500_bottle");
    private final NamespacedKey scp500UsesKey = new NamespacedKey("labra", "scp500_uses");

    /** Give an item Minecraft's native use-cooldown (the white sweep down the icon), on its OWN
     *  cooldown group so only this item type is gated - not every item sharing its base material. */
    private void useCooldown(ItemMeta meta, String group, float seconds) {
        org.bukkit.inventory.meta.components.UseCooldownComponent uc = meta.getUseCooldown();
        uc.setCooldownSeconds(Math.max(0f, seconds));
        uc.setCooldownGroup(new NamespacedKey("labra", group));
        meta.setUseCooldown(uc);
    }

    /** A SCP-500 pill bottle with all three uses left. */
    public ItemStack buildScp500Bottle() { return buildScp500Bottle(0); }

    /** A SCP-500 pill bottle with {@code used} pills already taken (drives the green health bar). */
    public ItemStack buildScp500Bottle(int used) {
        ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("SCP-500 Pill Bottle", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Right-click to take a pill.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Sneak + right-click a block to set it down.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text((SCP500_BOTTLE_USES - used) + " / " + SCP500_BOTTLE_USES + " pills left.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("scp500_bottle"));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(scp500BottleKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(scp500UsesKey, PersistentDataType.INTEGER, Math.max(0, used));
        useCooldown(meta, "scp500_bottle", plugin.getConfig().getInt("scp500.bottle-cooldown-seconds", 30));
        if (meta instanceof org.bukkit.inventory.meta.Damageable dm) {   // green health bar = pills left
            dm.setMaxDamage(SCP500_BOTTLE_USES);
            dm.setDamage(Math.max(0, Math.min(SCP500_BOTTLE_USES, used)));
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean isScp500Bottle(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
            .has(scp500BottleKey, PersistentDataType.BYTE);
    }

    public int scp500BottleUses(ItemStack item) {
        if (!isScp500Bottle(item)) return 0;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(scp500UsesKey, PersistentDataType.INTEGER, 0);
    }

    // ---------------------------------------------------------------- SCP-1079

    public static final int SCP1079_PACKET_GUMS = 6;
    private final NamespacedKey packet1079Key = new NamespacedKey("labra", "scp1079_packet");
    private final NamespacedKey gumsTakenKey = new NamespacedKey("labra", "scp1079_taken");
    private final NamespacedKey gum1079Key   = new NamespacedKey("labra", "scp1079_gum");
    private final NamespacedKey crate1079Key = new NamespacedKey("labra", "scp1079_crate");

    public ItemStack buildScp1079Packet() { return buildScp1079Packet(0); }

    /** A SCP-1079 gum packet with {@code taken} of its 6 gums already removed (green bar). */
    public ItemStack buildScp1079Packet(int taken) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("SCP-1079 Gum Packet", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Right-click to take a gum.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text((SCP1079_PACKET_GUMS - taken) + " / " + SCP1079_PACKET_GUMS + " gums left.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("You can only carry one packet.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, "scp1079_packet");
        useCooldown(meta, "scp1079_packet", plugin.getConfig().getInt("scp1079.packet-cooldown-seconds", 2));
        meta.getPersistentDataContainer().set(packet1079Key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(gumsTakenKey, PersistentDataType.INTEGER, Math.max(0, taken));
        if (meta instanceof org.bukkit.inventory.meta.Damageable dm) {
            dm.setMaxDamage(SCP1079_PACKET_GUMS);
            dm.setDamage(Math.max(0, Math.min(SCP1079_PACKET_GUMS, taken)));
        }
        item.setItemMeta(meta);
        return item;
    }

    public boolean isScp1079Packet(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(packet1079Key, PersistentDataType.BYTE);
    }
    public int scp1079Taken(ItemStack i) {
        return isScp1079Packet(i) ? i.getItemMeta().getPersistentDataContainer().getOrDefault(gumsTakenKey, PersistentDataType.INTEGER, 0) : 0;
    }

    public ItemStack buildScp1079Gum() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("SCP-1079 Chewing Gum", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click to chew.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, "scp1079_gum");
        useCooldown(meta, "scp1079_gum", 2f);   // 2s to chew again - the white icon sweep
        meta.getPersistentDataContainer().set(gum1079Key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }
    public boolean isScp1079Gum(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(gum1079Key, PersistentDataType.BYTE);
    }

    public ItemStack buildScp1079Crate() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("SCP-1079 Crate", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Sneak + right-click a block to set down.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Right-click the crate to take a packet.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, "scp1079_crate");
        meta.getPersistentDataContainer().set(crate1079Key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }
    public boolean isScp1079Crate(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(crate1079Key, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ snacks

    /** A consumable snack (chip bag / canned drink / ...) that grants a short buff when eaten. Defined
     *  by id -> {base material, model, effect}; the effect is applied by {@link SnackListener}. */
    public record Snack(String id, Material base, String model, String display,
                        org.bukkit.potion.PotionEffectType effect, int seconds, int amplifier) { }

    private final java.util.Map<String, Snack> snacks = new java.util.LinkedHashMap<>();
    { // built-in snacks (custom models to be textured in the pack)
        addSnack(new Snack("chip_bag",    Material.PAPER, "snack_chip_bag",    "Bag of Chips",   org.bukkit.potion.PotionEffectType.REGENERATION, 5, 0));
        addSnack(new Snack("canned_drink",Material.PAPER, "snack_canned_drink","Canned Drink",   org.bukkit.potion.PotionEffectType.SPEED,        10, 0));
        addSnack(new Snack("energy_bar",  Material.PAPER, "snack_energy_bar",  "Energy Bar",     org.bukkit.potion.PotionEffectType.HASTE,        15, 0));
        addSnack(new Snack("water_bottle",Material.PAPER, "snack_water_bottle","Bottled Water",  org.bukkit.potion.PotionEffectType.SATURATION,   3, 0));
    }
    private void addSnack(Snack s) { snacks.put(s.id(), s); }
    public java.util.Collection<Snack> snacks() { return snacks.values(); }
    public Snack snack(String id) { return id == null ? null : snacks.get(id.toLowerCase()); }

    private final NamespacedKey snackKey = new NamespacedKey("labra", "snack");
    public ItemStack buildSnack(Snack s) {
        ItemStack item = new ItemStack(s.base());
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(s.display(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Right-click to eat.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(prettyEffect(s.effect()) + " for " + s.seconds() + "s.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, s.model());
        meta.getPersistentDataContainer().set(snackKey, PersistentDataType.STRING, s.id());
        item.setItemMeta(meta);
        return item;
    }
    public String snackId(ItemStack i) {
        if (i == null || !i.hasItemMeta()) return null;
        return i.getItemMeta().getPersistentDataContainer().get(snackKey, PersistentDataType.STRING);
    }

    private String prettyEffect(org.bukkit.potion.PotionEffectType t) {
        return t.getKey().getKey().replace('_', ' ');
    }

    private void setModel(ItemMeta meta, String model) {
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
    }

    /** The held fire extinguisher: right-click sprays and puts out fire. Full. */
    /** Physical credit cash - built in-plugin (no datapack dependency). Right-clicking
     *  banks it into the credit balance (see CreditListener). */
    public ItemStack buildCredit() {
        return credit(Material.GOLD_NUGGET, "1 Credit", NamedTextColor.GOLD, "lab_credit",
            "A credit coin. Carry it to spend it.");
    }
    public ItemStack buildCredit10() {
        return credit(Material.PAPER, "10 Credits", NamedTextColor.GREEN, "lab_credit10",
            "A 10-credit bill. Carry it to spend it.");
    }
    public ItemStack buildCredit100() {
        return credit(Material.PAPER, "100 Credits", NamedTextColor.DARK_GREEN, "lab_credit100",
            "A 100-credit stack. Carry it to spend it.");
    }
    private ItemStack credit(Material mat, String name, NamedTextColor color, String model, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(lore, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

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
        cmd.setStrings(List.of("lab_gasmask"));   // identical HELD model for every tier
        meta.setCustomModelDataComponent(cmd);
        // WORN model: a custom gas-mask equipment asset (lab:gasmask) instead of the plain
        // dyed leather helmet, so it looks like a mask on the head.
        try {
            var eq = meta.getEquippable();
            eq.setSlot(org.bukkit.inventory.EquipmentSlot.HEAD);
            eq.setModel(org.bukkit.NamespacedKey.fromString("lab:gasmask"));
            meta.setEquippable(eq);
        } catch (Throwable ignored) { /* older API: skip, held model still applies */ }
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
