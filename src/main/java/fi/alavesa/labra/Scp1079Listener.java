package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * SCP-1079 gums and packets. A PACKET holds six gums (right-click to take one; green bar); a GUM is
 * EATEN - hold right-click, like SCP-500. The CRATE (a pushable sulfur cube) lives in {@link Scp1079Crate}.
 */
public final class Scp1079Listener implements Listener {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Scp1079Body body;
    private final Scp1079Crate crate;

    public Scp1079Listener(LabraPlugin plugin, LabRegistry registry, Scp1079Body body, Scp1079Crate crate) {
        this.plugin = plugin;
        this.registry = registry;
        this.body = body;
        this.crate = crate;
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
                crate.place(p, event.getClickedBlock().getLocation().add(0, 1, 0));
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

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
