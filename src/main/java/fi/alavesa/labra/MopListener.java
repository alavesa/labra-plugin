package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The mop (a re-skinned BRUSH): right-click a patch of floor to scrub up the harmless SCP-1079
 * mess left where someone bled out from the gum. Cleans everything within a short radius.
 */
public final class MopListener implements Listener {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Scp1079Body body;

    public MopListener(LabraPlugin plugin, LabRegistry registry, Scp1079Body body) {
        this.plugin = plugin;
        this.registry = registry;
        this.body = body;
    }

    private double radius() { return Math.max(1.0, plugin.getConfig().getDouble("scp1079.mop-radius", 4.0)); }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = event.getItem();
        if (!registry.isMop(hand)) return;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        event.setCancelled(true);
        Player p = event.getPlayer();

        // Mop where you're aiming: the clicked block, else the block you're looking at, else your feet.
        Location where;
        if (event.getClickedBlock() != null) {
            where = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            Block target = p.getTargetBlockExact(6);
            where = target != null ? target.getLocation().add(0.5, 1.0, 0.5) : p.getLocation();
        }

        // One BRUSH STROKE per click - it takes several to scrub a puddle away, not one instant wipe.
        int status = body.brushStroke(where, radius());
        p.swingMainHand();
        switch (status) {
            case 2 -> {   // that stroke finished it off
                p.getWorld().playSound(where, org.bukkit.Sound.ITEM_BRUSH_BRUSHING_GRAVEL_COMPLETE, 0.9f, 1.1f);
                p.getWorld().playSound(where, "scp:mop", 0.9f, 1.0f);
                p.getWorld().spawnParticle(Particle.ITEM_SLIME, where, 10, 0.4, 0.05, 0.4, 0.0);
                ActionBars.message(p, line("You scrub the last of it away.", NamedTextColor.WHITE));
            }
            case 1 -> {   // brushing it down, still some to go
                p.getWorld().playSound(where, org.bukkit.Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.9f, 1.0f);
                p.getWorld().playSound(where, "scp:mop", 0.7f, 1.0f);
                p.getWorld().spawnParticle(Particle.ITEM_SLIME, where, 6, 0.4, 0.05, 0.4, 0.0);
                p.getWorld().spawnParticle(Particle.DUST, where.clone().add(0, 0.2, 0), 6, 0.4, 0.15, 0.4, 0.0,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 105, 180), 1.4f));
            }
            default -> {  // nothing under the mop
                p.getWorld().playSound(where, "scp:mop", 0.5f, 1.2f);
                ActionBars.message(p, line("Nothing to mop up here.", NamedTextColor.GRAY));
            }
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
