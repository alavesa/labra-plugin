package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * SCP-1079: a gum PACKET holds six gums (right-click to take one; green bar), and a placeable CRATE
 * is a small dispenser that grows one packet every 30 minutes (up to five, shown in a hopper window).
 * The gum itself is EATEN - hold right-click, like SCP-500.
 */
public final class Scp1079Listener implements Listener {

    private static final String TAG = "lab.scp1079crate";
    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Scp1079Body body;

    public Scp1079Listener(LabraPlugin plugin, LabRegistry registry, Scp1079Body body) {
        this.plugin = plugin;
        this.registry = registry;
        this.body = body;
    }

    private int packetCooldownTicks() {
        return Math.max(0, plugin.getConfig().getInt("scp1079.packet-cooldown-seconds", 2)) * 20;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        Player p = event.getPlayer();
        ItemStack hand = event.getItem();
        // Gums are EATEN now (consumable component) - hold right-click, the eat animation plays, and
        // PlayerItemConsumeEvent fires below. So we DON'T touch a gum here (no cancel, no chew).
        if (registry.isScp1079Packet(hand)) { event.setCancelled(true); takeGum(p, hand); return; }
        if (registry.isScp1079Crate(hand)) {
            event.setCancelled(true);
            if (p.isSneaking() && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null)
                placeCrate(p, event.getClickedBlock().getLocation().add(0.5, 1, 0.5));
        }
    }

    /** Finishing the eat animation on a gum is the chew: the item is consumed by vanilla; we run the
     *  toll. The gum's own use_cooldown component gates how fast you can eat the next one. */
    @EventHandler
    public void onGumEaten(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (!registry.isScp1079Gum(event.getItem())) return;
        body.onChew(event.getPlayer());   // message, damage, particles, blisters, fever
    }

    /** Take one gum out of the packet in hand (if there's room), spending one of its six. */
    private void takeGum(Player p, ItemStack packet) {
        if (p.hasCooldown(packet)) return;             // native item cooldown - the white icon sweep
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a gum.", NamedTextColor.RED));
            return;
        }
        p.setCooldown(packet, packetCooldownTicks());
        int taken = registry.scp1079Taken(packet) + 1;
        p.getInventory().addItem(registry.buildScp1079Gum());
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.4f);
        ItemStack replacement = taken >= LabRegistry.SCP1079_PACKET_GUMS ? null : registry.buildScp1079Packet(taken);
        if (registry.isScp1079Packet(p.getInventory().getItemInMainHand())) p.getInventory().setItemInMainHand(replacement);
        else p.getInventory().setItemInOffHand(replacement);
        if (replacement == null) ActionBars.message(p, line("The packet is empty.", NamedTextColor.GRAY));
    }

    // --------------------------------------------------------------- crate

    private static final int MAX_PACKETS = 5;                  // a hopper's worth of slots
    private static final long ACCRUE_MS = 30L * 60L * 1000L;   // one packet appears every 30 minutes
    private org.bukkit.NamespacedKey packetsKey() { return plugin.keyOf("scp1079_crate_packets"); }
    private org.bukkit.NamespacedKey accrueKey() { return plugin.keyOf("scp1079_crate_accrue"); }

    private void placeCrate(Player p, Location at) {
        at.setYaw(Math.round(p.getLocation().getYaw() / 90f) * 90f);
        long now = System.currentTimeMillis();
        at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildScp1079Crate());
            d.setTransformation(new Transformation(new Vector3f(0, -0.4f, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f(0, 0, 0, 1)));
            d.setPersistent(true);
            d.addScoreboardTag(TAG);
        });
        at.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setVisible(false); a.setGravity(false); a.setBasePlate(false); a.setMarker(false);
            a.setPersistent(true); a.setRemoveWhenFarAway(false);
            a.addScoreboardTag(TAG);
            a.getPersistentDataContainer().set(packetsKey(), PersistentDataType.INTEGER, 1);   // starts with one
            a.getPersistentDataContainer().set(accrueKey(), PersistentDataType.LONG, now);
        });
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** How many packets the crate holds now - topping up any that accrued (one per 30 min, up to five)
     *  since we last looked. No countdown is ever shown; packets just quietly appear over time. */
    private int accrue(ArmorStand crate) {
        var pdc = crate.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        int count = pdc.getOrDefault(packetsKey(), PersistentDataType.INTEGER, 0);
        long since = pdc.getOrDefault(accrueKey(), PersistentDataType.LONG, now);
        if (count < MAX_PACKETS) {
            long gained = (now - since) / ACCRUE_MS;
            if (gained > 0) {
                count = (int) Math.min(MAX_PACKETS, count + gained);
                since = count >= MAX_PACKETS ? now : since + gained * ACCRUE_MS;
                pdc.set(packetsKey(), PersistentDataType.INTEGER, count);
                pdc.set(accrueKey(), PersistentDataType.LONG, since);
            }
        } else {
            pdc.set(accrueKey(), PersistentDataType.LONG, now);   // full: hold the timer until one is taken
        }
        return count;
    }

    /** Right-click the placed crate: open its hopper window (always allowed). */
    @EventHandler
    public void onCrateRightClick(PlayerInteractAtEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        openCrate(event.getPlayer(), (ArmorStand) e);
    }

    private void openCrate(Player p, ArmorStand crate) {
        int count = accrue(crate);
        Inventory inv = Bukkit.createInventory(new CrateHolder(crate.getUniqueId()), InventoryType.HOPPER,
            Component.text("SCP-1079 Crate", NamedTextColor.LIGHT_PURPLE));
        for (int i = 0; i < count && i < MAX_PACKETS; i++) inv.setItem(i, registry.buildScp1079Packet());
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.1f);
    }

    /** Take a packet out of the crate. Whatever's in there is free to take; the crate simply won't
     *  have another until one accrues. Nothing can be put back IN. */
    @EventHandler
    public void onCrateClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CrateHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= MAX_PACKETS || !(event.getWhoClicked() instanceof Player p)) return;
        if (!registry.isScp1079Packet(event.getCurrentItem())) return;   // an empty slot
        Entity ent = Bukkit.getEntity(holder.crate());
        if (!(ent instanceof ArmorStand crate) || !crate.getScoreboardTags().contains(TAG)) { p.closeInventory(); return; }
        int count = accrue(crate);
        if (count <= 0) return;
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a packet.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        p.getInventory().addItem(registry.buildScp1079Packet());
        crate.getPersistentDataContainer().set(packetsKey(), PersistentDataType.INTEGER, count - 1);
        crate.getPersistentDataContainer().set(accrueKey(), PersistentDataType.LONG, System.currentTimeMillis());
        event.getInventory().setItem(slot, null);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.3f);
        ActionBars.message(p, line("You take a gum packet.", NamedTextColor.LIGHT_PURPLE));
    }

    /** Break the placed crate to reclaim the crate item. */
    @EventHandler
    public void onBreak(EntityDamageByEntityEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p)) return;
        Location loc = e.getLocation();
        for (Entity part : loc.getNearbyEntities(1.0, 1.5, 1.0)) if (part.getScoreboardTags().contains(TAG)) part.remove();
        p.getInventory().addItem(registry.buildScp1079Crate()).values().forEach(l -> p.getWorld().dropItemNaturally(loc, l));
        loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.7f, 1.0f);
    }

    /** Marks an inventory as a specific crate's dispenser window. */
    private record CrateHolder(UUID crate) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
