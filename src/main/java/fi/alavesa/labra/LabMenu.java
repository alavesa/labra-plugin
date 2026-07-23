package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * /lab menu - the op catalog. One paged GUI holding everything the server's
 * SCP family can produce: every lab give, every SCP mob and car as a SPAWN
 * EGG item, every placeable machine, every door built with the doors-plugin,
 * every gun and mag. Clicking runs the same op command the long way would
 * (performCommand as the clicking op), so permissions stay exactly what they
 * were - this is a shortcut, not a bypass. Doors/cars/guns are discovered by
 * reading the sibling plugins' config files straight from disk: no compile
 * dependencies, and the catalog always matches what actually exists.
 */
public final class LabMenu implements Listener {

    private record Entry(Material icon, String name, NamedTextColor color,
                         String command, String eggCommand) { }

    private static final int PAGE_SIZE = 45;

    private final LabraPlugin plugin;
    private final NamespacedKey actionKey;   // click -> performCommand
    private final NamespacedKey eggKey;      // spawn egg items -> use command

    private static final class Screen implements InventoryHolder {
        final int page;
        Inventory inventory;
        Screen(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public LabMenu(LabraPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "menu_cmd");
        this.eggKey = new NamespacedKey(plugin, "menu_egg");
    }

    // -------------------------------------------------------------- content

    private List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        // --- the anomalies and lab items (lab give ...)
        record G(String id, Material icon, String name) { }
        for (G g : List.of(
            new G("scp009", Material.ICE, "SCP-009 Sample"),
            new G("scp999", Material.SLIME_BALL, "SCP-999 Gel"),
            new G("scp207", Material.POTION, "SCP-207 \"Regular Cola\""),
            new G("scp148", Material.IRON_INGOT, "SCP-148 Telekill"),
            new G("scp500", Material.RED_DYE, "SCP-500"),
            new G("scp008", Material.GHAST_TEAR, "SCP-008 Syringe"),
            new G("scp268", Material.LEATHER_HELMET, "SCP-268 Cap"),
            new G("scp1499", Material.LEATHER_HELMET, "SCP-1499 Gas Mask"),
            new G("scp714", Material.GOLD_NUGGET, "SCP-714 Jaded Ring"),
            new G("scp018", Material.EGG, "SCP-018 Super Ball"),
            new G("scp427", Material.HEART_OF_THE_SEA, "SCP-427 Talisman"),
            new G("scp1033", Material.CLOCK, "SCP-1033-RU Bracelet"),
            new G("extinguisher", Material.FIRE_CHARGE, "Fire Extinguisher"),
            new G("gasmask", Material.LEATHER_HELMET, "Gas Mask"),
            new G("supergasmask", Material.LEATHER_HELMET, "Super Gas Mask (infinite stamina)"),
            new G("heavygasmask", Material.LEATHER_HELMET, "Heavy Gas Mask (memetic immunity)"),
            new G("nvg", Material.CARVED_PUMPKIN, "NVG - Green (battery)"),
            new G("nvgred", Material.CARVED_PUMPKIN, "NVG - Red (infinite battery)"),
            new G("nvgblue", Material.CARVED_PUMPKIN, "NVG - Blue (see SCPs through walls)"),
            new G("ziptie", Material.STRING, "Zipties"),
            new G("battery", Material.COPPER_INGOT, "9V Battery"),
            new G("medkit", Material.BRICK, "Medkit"),
            new G("handcuffs", Material.CHAIN, "Handcuffs"),
            new G("scp005", Material.TRIAL_KEY, "SCP-005 Skeleton Key"),
            new G("credit", Material.GOLD_NUGGET, "1 Credit (coin)"),
            new G("credit10", Material.PAPER, "10 Credits (bill)"),
            new G("credit100", Material.PAPER, "100 Credits (bill)"),
            new G("kit", Material.WRITABLE_BOOK, "Lab Starter Kit"),
            new G("rod", Material.CARROT_ON_A_STICK, "Stirring Rod"),
            new G("pipette", Material.CARROT_ON_A_STICK, "Pipette"),
            new G("manual", Material.BOOK, "Lab Manual"),
            new G("table", Material.PAPER, "Periodic Table"),
            new G("hazmat", Material.LEATHER_CHESTPLATE, "Hazmat Suit"),
            new G("geiger", Material.CLOCK, "Geiger Counter"),
            new G("sample", Material.GLOWSTONE_DUST, "Radioactive Sample"))) {
            list.add(new Entry(g.icon(), g.name(), NamedTextColor.AQUA, "lab give " + g.id(), null));
        }
        // --- SCP mobs as spawn eggs
        record M(String id, Material egg, String name) { }
        for (M m : List.of(
            new M("scp173", Material.SKELETON_SPAWN_EGG, "SCP-173"),
            new M("scp106", Material.WITHER_SKELETON_SPAWN_EGG, "SCP-106"),
            new M("scp650", Material.WOLF_SPAWN_EGG, "SCP-650"),
            new M("scp049", Material.EVOKER_SPAWN_EGG, "SCP-049"),
            new M("scp939", Material.RAVAGER_SPAWN_EGG, "SCP-939"),
            new M("scp999", Material.SLIME_SPAWN_EGG, "SCP-999"))) {
            list.add(new Entry(m.egg(), m.name() + " Spawn Egg", NamedTextColor.RED,
                null, "scpmob spawn " + m.id()));
        }
        // --- cars as spawn eggs (cars.yml root keys)
        for (String id : configKeys("Cars", "cars.yml", null)) {
            list.add(new Entry(Material.MINECART, "Car: " + id, NamedTextColor.GOLD,
                null, "car spawn " + id));
        }
        // --- the CCTV grid (terminal-plugin 0.5.0+)
        list.add(new Entry(Material.OBSERVER, "Place: CCTV camera", NamedTextColor.YELLOW,
            "terminal cctv place", null));
        list.add(new Entry(Material.RECOVERY_COMPASS, "CCTV Monitor", NamedTextColor.AQUA,
            "terminal cctv monitor", null));
        // --- fire-safety fixtures (aim at a wall, then click)
        list.add(new Entry(Material.ITEM_FRAME, "Place: Extinguisher wall mount", NamedTextColor.YELLOW,
            "lab extinguisher mount", null));
        list.add(new Entry(Material.LEVER, "Place: Sprinkler button", NamedTextColor.YELLOW,
            "lab sprinkler button", null));
        // --- facility fixtures & personal gear
        list.add(new Entry(Material.SPAWNER, "Place: Personal stash", NamedTextColor.YELLOW,
            "stash place", null));
        list.add(new Entry(Material.PAPER, "Give: Identification card", NamedTextColor.AQUA,
            "idcard give", null));
        // --- placeable machines
        for (String machine : List.of("creator", "burner", "centrifuge", "fridge",
            "rack", "scp294", "scp038")) {
            list.add(new Entry(Material.SMITHING_TABLE, "Place: " + machine, NamedTextColor.YELLOW,
                "lab place " + machine, null));
        }
        // --- every door the doors-plugin knows
        for (String id : configKeys("Doors", "doors.yml", "doors")) {
            list.add(new Entry(Material.IRON_DOOR, "Door: " + id, NamedTextColor.GRAY,
                "doors toggle " + id, null));
        }
        // --- guns and mags
        for (String id : configKeys("Guns", "guns.yml", "guns")) {
            list.add(new Entry(Material.CROSSBOW, "Gun: " + id, NamedTextColor.DARK_RED,
                "guns give " + id, null));
        }
        for (String id : configKeys("Guns", "guns.yml", "mags")) {
            list.add(new Entry(Material.IRON_HORSE_ARMOR, "Mag: " + id, NamedTextColor.DARK_RED,
                "guns give " + id, null));
        }
        return list;
    }

    /** Sibling plugin config sections read straight off disk. */
    private List<String> configKeys(String pluginName, String fileName, String section) {
        File file = new File(plugin.getDataFolder().getParentFile(),
            pluginName + File.separator + fileName);
        if (!file.exists()) return List.of();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var root = section == null ? yaml : yaml.getConfigurationSection(section);
        if (root == null) return List.of();
        return root.getKeys(false).stream()
            .filter(k -> section != null || yaml.isConfigurationSection(k)).sorted().toList();
    }

    // ------------------------------------------------------------- the GUI

    public void open(Player player, int page) {
        List<Entry> all = entries();
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen(page);
        Inventory inv = Bukkit.createInventory(screen, 54,
            Component.text("SCP CATALOG " + (page + 1) + "/" + pages, NamedTextColor.DARK_AQUA));
        screen.inventory = inv;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= all.size()) break;
            Entry entry = all.get(index);
            ItemStack icon = new ItemStack(entry.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.itemName(Component.text(entry.name(), entry.color())
                .decoration(TextDecoration.ITALIC, false));
            if (entry.eggCommand() != null) {
                meta.lore(List.of(line("Click: take the egg. Use the egg to spawn.")));
                meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, entry.eggCommand());
            } else {
                meta.lore(List.of(line("Click: " + entry.command())));
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, entry.command());
            }
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        inv.setItem(45, nav(Material.ARROW, "Previous", page + "/" + pages));
        inv.setItem(53, nav(Material.ARROW, "Next", (page + 1) + "/" + pages));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.2f);
    }

    private ItemStack nav(Material material, String name, String pageInfo) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(line("Page " + pageInfo)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Screen screen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == 45) { open(player, screen.page - 1); return; }
        if (slot == 53) { open(player, screen.page + 1); return; }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String egg = item.getItemMeta().getPersistentDataContainer()
            .get(eggKey, PersistentDataType.STRING);
        if (egg != null) {
            ItemStack copy = item.clone(); // the egg carries its own command
            player.getInventory().addItem(copy).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.1f);
            return;
        }
        String command = item.getItemMeta().getPersistentDataContainer()
            .get(actionKey, PersistentDataType.STRING);
        if (command != null) {
            player.performCommand(command);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        }
    }

    /** Using a catalog spawn egg runs its spawn command instead of vanilla
     *  egg behavior - as the op holding it, so permissions still apply. */
    @EventHandler
    public void onEggUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String egg = item.getItemMeta().getPersistentDataContainer()
            .get(eggKey, PersistentDataType.STRING);
        if (egg == null) return;
        event.setCancelled(true);
        event.getPlayer().performCommand(egg);
    }

    private Component line(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
