package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Runs every 5 ticks: players holding a geiger counter hear it click faster the closer
 * they are to a radiation zone, with a reading in the actionbar. The counter senses
 * radiation out to 2x the zone radius.
 */
public final class GeigerTask implements Runnable {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private int tick;

    public GeigerTask(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void run() {
        tick++;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!registry.isGeiger(player.getInventory().getItemInMainHand())
                && !registry.isGeiger(player.getInventory().getItemInOffHand())) continue;

            double intensity = 0;
            for (Zone zone : registry.zones().values()) {
                if (!zone.type().equals("radiation")) continue;
                if (!zone.world().equals(player.getWorld().getName())) continue;
                double reach = zone.radius() * 2;
                double dist = zone.distance(player.getLocation());
                if (dist < reach) intensity = Math.max(intensity, 1.0 - dist / reach);
            }

            if (intensity <= 0) {
                if (tick % 8 == 0) { // calm background tick every 2s
                    player.playSound(player.getLocation(), "minecraft:block.note_block.hat", 0.15f, 1.6f);
                    player.sendActionBar(Component.text("☢ 0 µSv/h", NamedTextColor.GREEN));
                }
                continue;
            }

            // Click faster and read higher the closer you get
            int period = Math.max(1, (int) Math.round((1.0 - intensity) * 6));
            if (tick % period == 0) {
                player.playSound(player.getLocation(), "minecraft:block.note_block.hat",
                    0.5f, 1.8f + (float) intensity * 0.2f);
            }
            int reading = (int) Math.round(intensity * intensity * 999);
            NamedTextColor color = intensity > 0.7 ? NamedTextColor.RED
                : intensity > 0.4 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
            player.sendActionBar(Component.text("☢ " + reading + " µSv/h", color));
        }
    }
}
