package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Runs once a second: zone effects, decontamination, zone sirens and the radioactive
 * sample's exposure. A full hazmat suit protects from everything except the siren noise.
 */
public final class HazardTask implements Runnable {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private int tick;

    public HazardTask(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void run() {
        tick++;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            // Carrying a radioactive sample without a full suit hurts, wherever you are
            boolean suited = registry.hasFullHazmat(player);
            if (!suited && registry.hasSample(player)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 45, 0));
                player.sendActionBar(Component.text("☢ Radioactive sample in your inventory!",
                    NamedTextColor.RED));
            }

            Zone zone = registry.zones().values().stream()
                .filter(z -> z.contains(player.getLocation()))
                .findFirst().orElse(null);
            if (zone == null) continue;

            if (zone.type().equals("decon")) {
                decontaminate(player);
                continue;
            }

            if (suited) {
                player.sendActionBar(Component.text("Hazmat suit protecting you ("
                    + zone.type() + " zone)", NamedTextColor.GREEN));
                continue;
            }

            switch (zone.type()) {
                case "toxic" -> {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 45, 0));
                    player.sendActionBar(Component.text("☠ TOXIC ZONE - get a hazmat suit!",
                        NamedTextColor.DARK_GREEN));
                }
                case "cryo" -> {
                    player.setFreezeTicks(Math.min(player.getMaxFreezeTicks() + 40,
                        player.getFreezeTicks() + 60));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 1));
                    player.sendActionBar(Component.text("❄ CRYO ZONE - get a hazmat suit!",
                        NamedTextColor.AQUA));
                }
                default -> { // radiation
                    player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 45, 0));
                    player.sendActionBar(Component.text("☢ RADIATION - get a hazmat suit!",
                        NamedTextColor.RED));
                }
            }
        }

        // Sirens: any alarm-enabled zone with an unprotected player inside blares to
        // everyone within 48 blocks of the zone (every 4 seconds - the horn is long).
        if (tick % 4 != 0) return;
        for (Zone zone : registry.zones().values()) {
            if (!zone.alarm() || zone.type().equals("decon")) continue;
            World world = plugin.getServer().getWorld(zone.world());
            if (world == null) continue;
            boolean breach = plugin.getServer().getOnlinePlayers().stream()
                .anyMatch(p -> zone.contains(p.getLocation()) && !registry.hasFullHazmat(p));
            if (!breach) continue;
            Location center = new Location(world, zone.x(), zone.y(), zone.z());
            for (Player near : world.getPlayers()) {
                if (near.getLocation().distance(center) <= 48) {
                    near.playSound(center, "minecraft:event.raid.horn", 1.5f, 0.8f);
                }
            }
        }
    }

    /** Decon shower: washes off bad effects, fire and freezing, with water/cloud particles. */
    private void decontaminate(Player player) {
        player.removePotionEffect(PotionEffectType.POISON);
        player.removePotionEffect(PotionEffectType.WITHER);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.GLOWING);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.setFreezeTicks(0);
        player.setFireTicks(0);
        Location top = player.getLocation().add(0, 2.2, 0);
        player.getWorld().spawnParticle(Particle.FALLING_WATER, top, 12, 0.4, 0.2, 0.4, 0);
        player.getWorld().spawnParticle(Particle.CLOUD, top, 3, 0.3, 0.1, 0.3, 0.01);
        if (tick % 2 == 0) {
            player.playSound(player.getLocation(), "minecraft:block.water.ambient", 0.6f, 1.4f);
        }
        player.sendActionBar(Component.text("Decontaminating...", NamedTextColor.AQUA));
    }
}
