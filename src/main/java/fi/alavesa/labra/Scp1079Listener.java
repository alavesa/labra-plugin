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
 * dispenses packets. You may only carry ONE packet at a time - taking a second locks you out of
 * crates for 30 minutes. Chewing a gum gives a short pick-me-up.
 */
public final class Scp1079Listener implements Listener {

    private static final String TAG = "lab.scp1079crate";
    private static final long LOCK_MS = 30L * 60L * 1000L;
    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Scp1079Body body;
    private static final int GUM_COOLDOWN_TICKS = 40;   // 2s between chews (matches the gum's use_cooldown)

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
        if (registry.isScp1079Gum(hand)) { event.setCancelled(true); chew(p, hand); return; }
        if (registry.isScp1079Packet(hand)) { event.setCancelled(true); takeGum(p, hand); return; }
        if (registry.isScp1079Crate(hand)) {
            event.setCancelled(true);
            if (p.isSneaking() && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null)
                placeCrate(p, event.getClickedBlock().getLocation().add(0.5, 1, 0.5));
        }
    }

    /** Chew a gum: the native 2s item cooldown gates the rate; the body handles the toll. */
    private void chew(Player p, ItemStack gum) {
        if (p.hasCooldown(gum)) return;               // still sweeping - ignore the click
        p.setCooldown(gum, GUM_COOLDOWN_TICKS);        // white sweep on the gum's own cooldown group
        gum.setAmount(gum.getAmount() - 1);
        body.onChew(p);                                 // message, damage, particles, spots, fever
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

    private static final int PACKET_SLOT = 13;
    private org.bukkit.NamespacedKey readyKey() { return plugin.keyOf("scp1079_crate_ready"); }
    private org.bukkit.NamespacedKey lockKey() { return plugin.keyOf("scp1079_lock"); }

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
            a.getPersistentDataContainer().set(readyKey(), PersistentDataType.LONG, now);   // stocked with one on placement
        });
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** Right-click the placed crate: OPEN it (always allowed). Whether a packet can be taken is
     *  decided on click, inside the GUI. */
    @EventHandler
    public void onCrateRightClick(PlayerInteractAtEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        openCrate(event.getPlayer(), (ArmorStand) e);
    }

    private void openCrate(Player p, ArmorStand crate) {
        Inventory inv = Bukkit.createInventory(new CrateHolder(crate.getUniqueId()), 27,
            Component.text("SCP-1079 Crate", NamedTextColor.LIGHT_PURPLE));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        long ready = crate.getPersistentDataContainer().getOrDefault(readyKey(), PersistentDataType.LONG, 0L);
        inv.setItem(PACKET_SLOT, System.currentTimeMillis() >= ready
            ? registry.buildScp1079Packet() : restockingIcon(ready - System.currentTimeMillis()));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.1f);
    }

    /** Click inside a crate GUI: the only takeable thing is the packet in the middle, and only if the
     *  crate has restocked AND this player hasn't taken one in the last 30 minutes. */
    @EventHandler
    public void onCrateClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CrateHolder holder)) return;
        event.setCancelled(true);   // it's a dispenser window - nothing goes in, only the packet comes out
        if (event.getRawSlot() != PACKET_SLOT || !(event.getWhoClicked() instanceof Player p)) return;
        Entity ent = Bukkit.getEntity(holder.crate);
        if (!(ent instanceof ArmorStand crate) || !crate.getScoreboardTags().contains(TAG)) { p.closeInventory(); return; }

        long now = System.currentTimeMillis();
        long ready = crate.getPersistentDataContainer().getOrDefault(readyKey(), PersistentDataType.LONG, 0L);
        if (now < ready) { deny(p, "The crate is restocking - " + mins(ready - now) + " min."); return; }
        long lock = p.getPersistentDataContainer().getOrDefault(lockKey(), PersistentDataType.LONG, 0L);
        if (now < lock) { deny(p, "You already took a packet - " + mins(lock - now) + " min."); return; }
        if (hasPacket(p)) { deny(p, "You can only carry one gum packet."); return; }
        if (p.getInventory().firstEmpty() == -1) { deny(p, "No room for a packet."); return; }

        p.getInventory().addItem(registry.buildScp1079Packet());
        crate.getPersistentDataContainer().set(readyKey(), PersistentDataType.LONG, now + LOCK_MS);   // one packet / 30 min
        p.getPersistentDataContainer().set(lockKey(), PersistentDataType.LONG, now + LOCK_MS);        // one per player / 30 min
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.3f);
        ActionBars.message(p, line("You take a gum packet.", NamedTextColor.LIGHT_PURPLE));
        event.getInventory().setItem(PACKET_SLOT, restockingIcon(LOCK_MS));
    }

    private void deny(Player p, String msg) {
        ActionBars.message(p, line(msg, NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
    }

    private long mins(long ms) { return ms / 60000L + 1; }

    private ItemStack restockingIcon(long remainMs) {
        return named(Material.CLOCK, "Restocking… " + mins(remainMs) + " min");
    }

    private ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
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

    private boolean hasPacket(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (registry.isScp1079Packet(it)) return true;
        return false;
    }

    /** Marks an inventory as a specific crate's dispenser window. */
    private record CrateHolder(UUID crate) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
