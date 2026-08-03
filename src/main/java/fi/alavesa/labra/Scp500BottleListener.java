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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The SCP-500 pill bottle: right-click it to take out one SCP-500-01 pill (three per bottle, shown by
 * its green durability bar). Sneak + right-click a block sets the bottle down as a model you can break
 * to pick back up - with its remaining pills intact.
 */
public final class Scp500BottleListener implements Listener {

    private static final String TAG = "lab.scp500bottle";
    private final LabraPlugin plugin;
    private final LabRegistry registry;

    public Scp500BottleListener(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    private org.bukkit.NamespacedKey usesKey() { return plugin.keyOf("scp500_uses"); }

    private int bottleCooldownTicks() {
        return Math.max(0, plugin.getConfig().getInt("scp500.bottle-cooldown-seconds", 30)) * 20;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = event.getItem();
        if (!registry.isScp500Bottle(hand)) return;
        Player p = event.getPlayer();
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!rightClick) return;
        event.setCancelled(true);
        // Sneak + right-click a block sets it down.
        if (p.isSneaking() && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            place(p, hand, event.getClickedBlock().getLocation().add(0.5, 1, 0.5));
            return;
        }
        dispensePill(p, hand);
    }

    /** Take one pill out of the bottle in hand (if there's room), spending a use. */
    private void dispensePill(Player p, ItemStack bottle) {
        if (p.hasCooldown(bottle)) return;             // native item cooldown - the white icon sweep
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a pill.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        p.setCooldown(bottle, bottleCooldownTicks());
        int used = registry.scp500BottleUses(bottle) + 1;
        plugin.giveScp500Pill(p);
        p.playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.7f, 1.4f);
        // Replace the held bottle with one at the new use count - or nothing if it's now empty.
        ItemStack replacement = used >= LabRegistry.SCP500_BOTTLE_USES ? null : registry.buildScp500Bottle(used);
        EquipmentSlot slot = registry.isScp500Bottle(p.getInventory().getItemInMainHand())
            ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        if (slot == EquipmentSlot.HAND) p.getInventory().setItemInMainHand(replacement);
        else p.getInventory().setItemInOffHand(replacement);
        if (replacement == null) ActionBars.message(p, line("The bottle is empty.", NamedTextColor.GRAY));
    }

    /** Set the bottle down as a model (ItemDisplay) with an invisible ArmorStand you break to reclaim it. */
    private void place(Player p, ItemStack bottle, Location at) {
        int used = registry.scp500BottleUses(bottle);
        at.setYaw(Math.round(p.getLocation().getYaw() / 90f) * 90f);
        at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildScp500Bottle(used));
            d.setTransformation(new Transformation(new Vector3f(0, -0.35f, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f(0, 0, 0, 1)));
            d.setPersistent(true);
            d.addScoreboardTag(TAG);
            d.getPersistentDataContainer().set(usesKey(), PersistentDataType.INTEGER, used);
        });
        at.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setSmall(true);
            a.setMarker(false);       // needs a hitbox to be broken
            a.setBasePlate(false);
            a.setPersistent(true);
            a.setRemoveWhenFarAway(false);
            a.addScoreboardTag(TAG);
            a.getPersistentDataContainer().set(usesKey(), PersistentDataType.INTEGER, used);
        });
        // consume the held bottle
        if (registry.isScp500Bottle(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_GLASS_PLACE, 0.7f, 1.3f);
    }

    /** Break the placed bottle: give it back (with its remaining pills) and remove the models. */
    @EventHandler
    public void onBreak(EntityDamageByEntityEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p)) return;
        int used = e.getPersistentDataContainer().getOrDefault(usesKey(), PersistentDataType.INTEGER, 0);
        Location loc = e.getLocation();
        // remove the display + this stand
        for (Entity part : loc.getNearbyEntities(1.0, 1.5, 1.0)) {
            if (part.getScoreboardTags().contains(TAG)) part.remove();
        }
        ItemStack back = registry.buildScp500Bottle(used);
        p.getInventory().addItem(back).values().forEach(left -> p.getWorld().dropItemNaturally(loc, left));
        loc.getWorld().playSound(loc, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.2f);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
