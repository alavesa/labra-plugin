package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUIs for the lab-datapack machines (v0.14+). The datapack builds machines
 * the mob-spawner way (spawner block + item_display model + interaction
 * hitbox) and tags the interaction entity; this listener opens a real
 * inventory when one is right-clicked:
 *
 *  - lab.creator : 54-slot "Compound Creator" with a Create Compound button.
 *                  Pressing it drops the contents inside the machine and runs
 *                  lab:creator/react as the crafting player (cold recipes).
 *  - lab.burner  : same, but lab:burner/react - the hot bench. No fuel needed.
 *  - lab.fridge  : persistent 27-slot cold storage (saved per machine UUID;
 *                  contents drop if the machine is destroyed)
 *  - lab.fuge    : 9-slot centrifuge feed; on close the contents drop into
 *                  the drum, where the datapack splits compound tubes
 */
public final class MachineGuiListener implements Listener {

    private static final int BUTTON_SLOT = 49;

    private enum Kind { FRIDGE, CENTRIFUGE, CREATOR, BURNER, SCP294 }

    /** Everything SCP-294 can pour: dispatches lab:scp294/<key>. */
    private record Liquid(String key, String name, int color) { }
    private static final List<Liquid> LIQUIDS = List.of(
        new Liquid("coffee", "Coffee", 0x4B2E1E),
        new Liquid("cocoa", "Hot Chocolate", 0x7B4A12),
        new Liquid("cola", "Regular Cola", 0x3B1F14),
        new Liquid("estus", "Estus", 0xF5A623),
        new Liquid("god", "god", 0xFFFFF0),
        new Liquid("h2o", "Water", 0x3F76E4),
        new Liquid("h2", "Hydrogen Gas", 0xE0FFFF),
        new Liquid("o2", "Oxygen Gas", 0x99CCFF),
        new Liquid("n2", "Nitrogen Gas", 0xCCDDFF),
        new Liquid("h2o2", "Hydrogen Peroxide", 0xCFF3F0),
        new Liquid("co2", "Carbon Dioxide", 0xAAAAAA),
        new Liquid("co", "Carbon Monoxide", 0x666666),
        new Liquid("ch4", "Methane", 0xBFFFBF),
        new Liquid("nh3", "Ammonia", 0xD8FFD8),
        new Liquid("nacl", "Salt", 0xFFFFFF),
        new Liquid("hcl", "Hydrochloric Acid", 0xCCFF66),
        new Liquid("naoh", "Lye", 0xF0F0FF),
        new Liquid("so2", "Sulfur Dioxide", 0xFFFF99),
        new Liquid("h2so4", "Sulfuric Acid", 0xFFFFCC),
        new Liquid("ethanol", "Ethanol", 0xF5DEB3),
        new Liquid("glucose", "Glucose", 0xFFE4B5),
        new Liquid("fe2o3", "Rust", 0xB7410E),
        new Liquid("sio2", "Silica", 0xE6E6FA));

    private static final class MachineHolder implements InventoryHolder {
        final Kind kind;
        final UUID machine;
        Inventory inventory;
        MachineHolder(Kind kind, UUID machine) { this.kind = kind; this.machine = machine; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final LabraPlugin plugin;
    private final File fridgeDir;
    private final Map<UUID, Inventory> openFridges = new HashMap<>();

    public MachineGuiListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.fridgeDir = new File(plugin.getDataFolder(), "fridges");
        fridgeDir.mkdirs();
    }

    // ------------------------------------------------------------- open

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        var tags = interaction.getScoreboardTags();
        Player player = event.getPlayer();
        if (tags.contains("lab.fridge")) {
            event.setCancelled(true);
            player.openInventory(fridgeInventory(interaction.getUniqueId()));
        } else if (tags.contains("lab.fuge")) {
            event.setCancelled(true);
            player.openInventory(freshInventory(Kind.CENTRIFUGE, interaction, 9,
                Component.text("Centrifuge", NamedTextColor.DARK_AQUA)));
        } else if (tags.contains("lab.creator")) {
            event.setCancelled(true);
            player.openInventory(benchInventory(Kind.CREATOR, interaction,
                Component.text("Compound Creator", NamedTextColor.DARK_AQUA)));
        } else if (tags.contains("lab.burner")) {
            event.setCancelled(true);
            player.openInventory(benchInventory(Kind.BURNER, interaction,
                Component.text("Gas Burner", NamedTextColor.GOLD)));
        } else if (tags.contains("lab.scp294")) {
            event.setCancelled(true);
            Inventory inv = freshInventory(Kind.SCP294, interaction, 54,
                Component.text("SCP-294", NamedTextColor.DARK_GRAY));
            for (int i = 0; i < LIQUIDS.size(); i++) {
                inv.setItem(i, liquidButton(LIQUIDS.get(i)));
            }
            inv.setItem(53, coinSlotInfo());
            player.openInventory(inv);
        }
    }

    private ItemStack liquidButton(Liquid liquid) {
        ItemStack item = new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta meta =
            (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();
        meta.setColor(org.bukkit.Color.fromRGB(liquid.color()));
        meta.itemName(Component.text(liquid.name(), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("2 Quarters - click to dispense.", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack coinSlotInfo() {
        ItemStack item = new ItemStack(Material.IRON_NUGGET);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Coin slot", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Each drink costs 2 Quarters from your inventory.",
            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isQuarter(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_NUGGET || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_quarter");
    }

    private boolean takeQuarters(Player player, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int found = 0;
        for (ItemStack stack : contents) {
            if (isQuarter(stack)) found += stack.getAmount();
        }
        if (found < amount) return false;
        int left = amount;
        for (ItemStack stack : contents) {
            if (left == 0) break;
            if (!isQuarter(stack)) continue;
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            left -= take;
        }
        return true;
    }

    private Inventory freshInventory(Kind kind, Interaction machine, int size, Component title) {
        MachineHolder holder = new MachineHolder(kind, machine.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    /** A double-chest reaction bench with the Create Compound button. */
    private Inventory benchInventory(Kind kind, Interaction machine, Component title) {
        Inventory inv = freshInventory(kind, machine, 54, title);
        inv.setItem(BUTTON_SLOT, createButton());
        return inv;
    }

    private ItemStack createButton() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Create Compound", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Lay the atoms in the bench, then click here.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("The mix must match a formula exactly.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private Inventory fridgeInventory(UUID machine) {
        return openFridges.computeIfAbsent(machine, id -> {
            MachineHolder holder = new MachineHolder(Kind.FRIDGE, id);
            holder.inventory = Bukkit.createInventory(holder, 27,
                Component.text("Lab Fridge", NamedTextColor.AQUA));
            File file = fridgeFile(id);
            if (file.isFile()) {
                try {
                    holder.inventory.setContents(ItemStack.deserializeItemsFromBytes(Files.readAllBytes(file.toPath())));
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not read fridge " + id + ": " + e.getMessage());
                }
            }
            return holder.inventory;
        });
    }

    // ------------------------------------------------------------- the button

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MachineHolder holder)) return;
        if (holder.kind == Kind.SCP294) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            if (event.getSlot() >= LIQUIDS.size()) return;
            if (!(event.getWhoClicked() instanceof Player player)) return;
            Entity machine = Bukkit.getEntity(holder.machine);
            if (machine == null) { player.closeInventory(); return; }
            if (!takeQuarters(player, 2)) {
                player.sendActionBar(Component.text("SCP-294 requires two Quarters.", NamedTextColor.GRAY));
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_DISPENSER_FAIL, 0.7f, 0.9f);
                return;
            }
            Liquid liquid = LIQUIDS.get(event.getSlot());
            Location cell = machine.getLocation();
            player.playSound(machine.getLocation(), org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.8f, 1.4f);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "execute as " + player.getUniqueId() + " positioned "
                    + cell.getBlockX() + " " + cell.getBlockY() + " " + cell.getBlockZ()
                    + " run function lab:scp294/" + liquid.key());
            return;
        }
        if (holder.kind != Kind.CREATOR && holder.kind != Kind.BURNER) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getSlot() != BUTTON_SLOT) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Entity machine = Bukkit.getEntity(holder.machine);
        if (machine == null) { player.closeInventory(); return; }

        Inventory inv = event.getView().getTopInventory();
        Location cell = machine.getLocation();
        Location center = new Location(cell.getWorld(),
            cell.getBlockX() + 0.5, cell.getBlockY() + 0.5, cell.getBlockZ() + 0.5);
        boolean any = false;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (slot == BUTTON_SLOT) continue;
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            any = true;
            center.getWorld().dropItem(center, stack, item -> {
                item.setPickupDelay(200);
                item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            });
            inv.setItem(slot, null);
        }
        player.closeInventory();
        if (!any) return;
        String function = holder.kind == Kind.CREATOR ? "lab:creator/react" : "lab:burner/react";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute as " + player.getUniqueId() + " positioned "
                + cell.getBlockX() + " " + cell.getBlockY() + " " + cell.getBlockZ()
                + " run function " + function);
    }

    // ------------------------------------------------------------- close

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineHolder holder)) return;
        Inventory inv = event.getInventory();
        if (event.getViewers().size() > 1) return; // someone else still looking
        switch (holder.kind) {
            case FRIDGE -> {
                saveFridge(holder.machine, inv);
                openFridges.remove(holder.machine);
            }
            case CENTRIFUGE -> {
                Location drop = machineDropSpot(holder.machine, event.getPlayer().getLocation());
                for (ItemStack stack : inv.getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        drop.getWorld().dropItem(drop, stack, item -> item.setPickupDelay(30));
                    }
                }
                inv.clear();
            }
            case SCP294 -> { } // buttons only - nothing to hand back
            case CREATOR, BURNER -> {
                // closing without pressing the button: hand everything back
                for (int slot = 0; slot < inv.getSize(); slot++) {
                    if (slot == BUTTON_SLOT) continue;
                    ItemStack stack = inv.getItem(slot);
                    if (stack == null || stack.getType().isAir()) continue;
                    event.getPlayer().getInventory().addItem(stack).values().forEach(left ->
                        event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), left));
                    inv.setItem(slot, null);
                }
            }
        }
    }

    private Location machineDropSpot(UUID machine, Location fallback) {
        Entity entity = Bukkit.getEntity(machine);
        return entity != null ? entity.getLocation().add(0, 1.1, 0) : fallback;
    }

    // ------------------------------------------------------------- lifecycle

    @EventHandler
    public void onRemoved(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Interaction interaction)) return;
        if (!interaction.isDead()) return; // chunk unload, not destruction
        UUID id = interaction.getUniqueId();
        if (!interaction.getScoreboardTags().contains("lab.fridge")) return;
        Inventory open = openFridges.remove(id);
        ItemStack[] contents;
        if (open != null) {
            contents = open.getContents();
            open.close();
        } else {
            contents = readFridgeFile(id);
        }
        Location drop = interaction.getLocation().add(0, 0.8, 0);
        for (ItemStack stack : contents) {
            if (stack != null && !stack.getType().isAir()) {
                drop.getWorld().dropItem(drop, stack, item -> item.setPickupDelay(20));
            }
        }
        fridgeFile(id).delete();
    }

    public void shutdown() {
        for (var entry : openFridges.entrySet()) saveFridge(entry.getKey(), entry.getValue());
        openFridges.clear();
    }

    // ------------------------------------------------------------- persistence

    private File fridgeFile(UUID id) { return new File(fridgeDir, id + ".dat"); }

    private ItemStack[] readFridgeFile(UUID id) {
        File file = fridgeFile(id);
        if (!file.isFile()) return new ItemStack[0];
        try {
            return ItemStack.deserializeItemsFromBytes(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read fridge " + id + ": " + e.getMessage());
            return new ItemStack[0];
        }
    }

    private void saveFridge(UUID id, Inventory inventory) {
        boolean empty = Arrays.stream(inventory.getContents())
            .allMatch(s -> s == null || s.getType().isAir());
        File file = fridgeFile(id);
        if (empty) {
            file.delete();
            return;
        }
        try {
            Files.write(file.toPath(), ItemStack.serializeItemsAsBytes(inventory.getContents()));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save fridge " + id + ": " + e.getMessage());
        }
    }
}
