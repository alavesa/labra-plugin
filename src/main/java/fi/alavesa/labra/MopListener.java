package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The mop (a re-skinned BRUSH). There's no interact handler and no extra animation - you simply HOLD
 * right-click, which plays the brush's own sweeping animation, and while it's held this tick-task
 * scrubs away any SCP-1079 spill under the mop, a little at a time. Exactly like brushing a normal
 * surface, except here it actually cleans the goo.
 */
public final class MopListener implements Runnable {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Scp1079Body body;
    private int tick;

    public MopListener(LabraPlugin plugin, LabRegistry registry, Scp1079Body body) {
        this.plugin = plugin;
        this.registry = registry;
        this.body = body;
    }

    private double radius() { return Math.max(1.0, plugin.getConfig().getDouble("scp1079.mop-radius", 4.0)); }

    @Override
    public void run() {
        tick++;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.isHandRaised() || !registry.isMop(p.getItemInUse())) continue;   // only while the brush is held
            Location where = aimedFloor(p);
            int status = body.brushClean(where, radius());
            if (status == 0) continue;
            if (status == 2) {
                p.getWorld().playSound(where, Sound.ITEM_BRUSH_BRUSHING_GRAVEL_COMPLETE, 0.8f, 1.1f);
                p.getWorld().spawnParticle(Particle.ITEM_SLIME, where, 8, 0.4, 0.05, 0.4, 0.0);
            } else {
                if (tick % 4 == 0) p.getWorld().playSound(where, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.7f, 1.0f);
                p.getWorld().spawnParticle(Particle.DUST, where.clone().add(0, 0.15, 0), 4, 0.35, 0.1, 0.35, 0.0,
                    new Particle.DustOptions(Color.fromRGB(255, 105, 180), 1.4f));
            }
        }
    }

    /** The floor the player is mopping: the block they're aiming at within reach, else their feet. */
    private Location aimedFloor(Player p) {
        Block target = p.getTargetBlockExact(6);
        return target != null ? target.getLocation().add(0.5, 1.0, 0.5) : p.getLocation();
    }

    Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
