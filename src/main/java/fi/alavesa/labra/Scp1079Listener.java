package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

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

    public Scp1079Listener(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
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

    /** Chew a gum: consume one, grant a brief pick-me-up. */
    private void chew(Player p, ItemStack gum) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 0, true, false, true));
        gum.setAmount(gum.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.7f, 1.3f);
        ActionBars.message(p, line("You chew the gum.", NamedTextColor.LIGHT_PURPLE));
    }

    /** Take one gum out of the packet in hand (if there's room), spending one of its six. */
    private void takeGum(Player p, ItemStack packet) {
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a gum.", NamedTextColor.RED));
            return;
        }
        int taken = registry.scp1079Taken(packet) + 1;
        p.getInventory().addItem(registry.buildScp1079Gum());
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.4f);
        ItemStack replacement = taken >= LabRegistry.SCP1079_PACKET_GUMS ? null : registry.buildScp1079Packet(taken);
        if (registry.isScp1079Packet(p.getInventory().getItemInMainHand())) p.getInventory().setItemInMainHand(replacement);
        else p.getInventory().setItemInOffHand(replacement);
        if (replacement == null) ActionBars.message(p, line("The packet is empty.", NamedTextColor.GRAY));
    }

    // --------------------------------------------------------------- crate

    private void placeCrate(Player p, Location at) {
        at.setYaw(Math.round(p.getLocation().getYaw() / 90f) * 90f);
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
        });
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** Right-click the placed crate: take a packet, subject to the one-at-a-time + 30-min lock. */
    @EventHandler
    public void onCrateRightClick(PlayerInteractAtEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        if (hasPacket(p)) {
            ActionBars.message(p, line("You can only carry one gum packet.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        long now = System.currentTimeMillis();
        long lock = p.getPersistentDataContainer().getOrDefault(plugin.keyOf("scp1079_lock"), PersistentDataType.LONG, 0L);
        if (now < lock) {
            long mins = (lock - now) / 60000L + 1;
            ActionBars.message(p, line("The dispenser is jammed for you. Try again in " + mins + " min.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            return;
        }
        if (p.getInventory().firstEmpty() == -1) { ActionBars.message(p, line("No room for a packet.", NamedTextColor.RED)); return; }
        p.getInventory().addItem(registry.buildScp1079Packet());
        p.getPersistentDataContainer().set(plugin.keyOf("scp1079_lock"), PersistentDataType.LONG, now + LOCK_MS);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.2f);
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

    private boolean hasPacket(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (registry.isScp1079Packet(it)) return true;
        return false;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
