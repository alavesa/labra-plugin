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
import java.util.Map;
import java.util.UUID;

/**
 * GUIs for the lab-datapack machines (v0.13+). The datapack builds machines the
 * mob-spawner way (spawner block + item_display model + interaction hitbox) and
 * tags the interaction entity; this listener opens a real inventory when one is
 * right-clicked:
 *
 *  - lab.fridge : persistent 27-slot cold storage (saved per machine UUID;
 *                 contents drop if the machine is destroyed)
 *  - lab.fuge   : 9-slot centrifuge feed; on close the contents are dropped
 *                 into the drum, where the datapack splits compound tubes
 *  - lab.burner : fuel gauge; coal keeps it burning, and the lab.lit tag on
 *                 the interaction is what the datapack's heat checks read
 */
public final class MachineGuiListener implements Listener {

    private static final int COAL_SECONDS = 60;
    private static final int COAL_BLOCK_SECONDS = 540;
    private static final int STATUS_SLOT = 4;

    private enum Kind { FRIDGE, CENTRIFUGE, BURNER }

    private static final class MachineHolder implements InventoryHolder {
        final Kind kind;
        final UUID machine;
        Inventory inventory;
        MachineHolder(Kind kind, UUID machine) { this.kind = kind; this.machine = machine; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final LabraPlugin plugin;
    private final File fridgeDir;
    private final File burnerFile;
    private final Map<UUID, Inventory> openFridges = new HashMap<>();
    private final Map<UUID, Integer> burnerFuel = new HashMap<>();
    private final Map<UUID, Inventory> openBurners = new HashMap<>();

    public MachineGuiListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.fridgeDir = new File(plugin.getDataFolder(), "fridges");
        this.burnerFile = new File(plugin.getDataFolder(), "burners.yml");
        fridgeDir.mkdirs();
        loadBurners();
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
            MachineHolder holder = new MachineHolder(Kind.CENTRIFUGE, interaction.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 9,
                Component.text("Centrifuge", NamedTextColor.DARK_AQUA));
            player.openInventory(holder.inventory);
        } else if (tags.contains("lab.burner")) {
            event.setCancelled(true);
            MachineHolder holder = new MachineHolder(Kind.BURNER, interaction.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 9,
                Component.text("Gas Burner", NamedTextColor.GOLD));
            holder.inventory.setItem(STATUS_SLOT, statusItem(interaction.getUniqueId()));
            openBurners.put(interaction.getUniqueId(), holder.inventory);
            player.openInventory(holder.inventory);
        }
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

    // ------------------------------------------------------------- close

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MachineHolder holder)) return;
        Inventory inv = event.getInventory();
        if (!event.getViewers().isEmpty() && event.getViewers().size() > 1) return; // someone still looking
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
            case BURNER -> openBurners.remove(holder.machine);
        }
    }

    private Location machineDropSpot(UUID machine, Location fallback) {
        Entity entity = Bukkit.getEntity(machine);
        return entity != null ? entity.getLocation().add(0, 1.1, 0) : fallback;
    }

    // ------------------------------------------------------------- burner fuel

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MachineHolder holder)) return;
        if (holder.kind == Kind.FRIDGE) return; // free-form storage
        if (holder.kind == Kind.CENTRIFUGE) return; // free-form feed
        // burner: only fuel goes in, and it is consumed on the spot
        event.setCancelled(true);
        ItemStack offered = event.getClickedInventory() == event.getView().getTopInventory()
            ? event.getCursor()
            : (event.isShiftClick() ? event.getCurrentItem() : null);
        if (offered == null || offered.getType().isAir()) return;
        int perItem = switch (offered.getType()) {
            case COAL, CHARCOAL -> COAL_SECONDS;
            case COAL_BLOCK -> COAL_BLOCK_SECONDS;
            default -> 0;
        };
        if (perItem == 0) return;
        int added = perItem * offered.getAmount();
        burnerFuel.merge(holder.machine, added, Integer::sum);
        offered.setAmount(0);
        setLit(holder.machine, true);
        refreshStatus(holder.machine);
        Entity entity = Bukkit.getEntity(holder.machine);
        if (entity != null) {
            entity.getWorld().playSound(entity.getLocation(),
                org.bukkit.Sound.ITEM_FLINTANDSTEEL_USE, 0.8f, 1f);
        }
    }

    /** Called once a second from the plugin's scheduler. */
    public void tickBurners() {
        for (var iterator = burnerFuel.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null) continue; // chunk not loaded - the flame waits
            int left = entry.getValue() - 1;
            if (left <= 0) {
                iterator.remove();
                setLit(entry.getKey(), false);
            } else {
                entry.setValue(left);
            }
            refreshStatus(entry.getKey());
        }
    }

    private void setLit(UUID machine, boolean lit) {
        Entity entity = Bukkit.getEntity(machine);
        if (entity == null) return;
        if (lit) entity.addScoreboardTag("lab.lit");
        else entity.removeScoreboardTag("lab.lit");
    }

    private void refreshStatus(UUID machine) {
        Inventory inv = openBurners.get(machine);
        if (inv != null) inv.setItem(STATUS_SLOT, statusItem(machine));
    }

    private ItemStack statusItem(UUID machine) {
        int fuel = burnerFuel.getOrDefault(machine, 0);
        ItemStack item = new ItemStack(fuel > 0 ? Material.BLAZE_POWDER : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(fuel > 0 ? "Burning - " + fuel + "s of fuel left" : "Cold",
                fuel > 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(Component.text("Click coal or a coal block here to fuel the flame.",
            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------- lifecycle

    @EventHandler
    public void onRemoved(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Interaction interaction)) return;
        if (!interaction.isDead()) return; // chunk unload, not destruction
        UUID id = interaction.getUniqueId();
        if (interaction.getScoreboardTags().contains("lab.fridge")) {
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
        } else if (interaction.getScoreboardTags().contains("lab.burner")) {
            burnerFuel.remove(id);
            openBurners.remove(id);
        }
    }

    public void shutdown() {
        for (var entry : openFridges.entrySet()) saveFridge(entry.getKey(), entry.getValue());
        openFridges.clear();
        saveBurners();
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

    private void loadBurners() {
        if (!burnerFile.isFile()) return;
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(burnerFile);
        for (String key : yaml.getKeys(false)) {
            try {
                burnerFuel.put(UUID.fromString(key), yaml.getInt(key));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveBurners() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        burnerFuel.forEach((id, fuel) -> yaml.set(id.toString(), fuel));
        try {
            yaml.save(burnerFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save burners.yml: " + e.getMessage());
        }
    }
}
