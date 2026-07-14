package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Runs once a second: zone effects, decontamination, zone sirens and the radioactive
 * sample's exposure. A full hazmat suit protects from everything except the siren noise.
 *
 * Containment (v0.3): hazard reaches a player only with a clear line from the zone source -
 * walls and closed doors seal it in; open doors and gaps leak. Radiation is deliberately
 * INVISIBLE: no warning text, no protected-message - only the geiger counter, the metallic
 * taste when dangerously close, and the damage itself reveal it.
 */
public final class HazardTask implements Runnable {

    private static final Component METAL_TASTE = Component
        .text("I taste metal in my mouth...", NamedTextColor.GRAY)
        .decoration(TextDecoration.ITALIC, true);

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
            boolean suited = registry.hasFullHazmat(player);

            // Carrying a radioactive sample without a suit hurts - no ☢ text, just the taste
            if (!suited && registry.hasSample(player)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 45, 0));
                ActionBars.message(player, METAL_TASTE);
            }

            Zone inside = null;
            boolean metalTaste = false;
            for (Zone zone : registry.zones().values()) {
                if (!zone.world().equals(player.getWorld().getName())) continue;
                if (zone.type().equals("decon")) {
                    if (zone.contains(player.getLocation())) { inside = zone; break; }
                    continue;
                }
                double dist = zone.distance(player.getLocation());
                if (dist > zone.radius() * 1.3) continue;
                boolean exposed = LabRegistry.lineOfSight(
                    LabRegistry.sourceOf(zone, player.getWorld()), player.getEyeLocation());
                if (!exposed) continue; // sealed chamber - nothing escapes
                if (dist <= zone.radius()) { inside = zone; break; }
                // Dangerously close to leaking radiation, but not in it yet
                if (zone.type().equals("radiation") && !suited) metalTaste = true;
            }

            if (inside == null) {
                if (metalTaste) ActionBars.message(player, METAL_TASTE);
                continue;
            }

            if (inside.type().equals("decon")) {
                decontaminate(player);
                continue;
            }

            if (suited) {
                // Radiation stays invisible even when protected; gas/cold you can feel
                if (!inside.type().equals("radiation")) {
                    ActionBars.message(player, Component.text("Hazmat suit protecting you ("
                        + inside.type() + " zone)", NamedTextColor.GREEN));
                }
                continue;
            }

            switch (inside.type()) {
                case "toxic" -> {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 45, 0));
                    ActionBars.message(player, Component.text("☠ TOXIC ZONE - get a hazmat suit!",
                        NamedTextColor.DARK_GREEN));
                }
                case "cryo" -> {
                    player.setFreezeTicks(Math.min(player.getMaxFreezeTicks() + 40,
                        player.getFreezeTicks() + 60));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 1));
                    ActionBars.message(player, Component.text("❄ CRYO ZONE - get a hazmat suit!",
                        NamedTextColor.AQUA));
                }
                default -> { // radiation: damage + the taste, nothing else gives it away
                    player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 45, 0));
                    ActionBars.message(player, METAL_TASTE);
                }
            }
        }

        // Sirens: any alarm-enabled zone with an unprotected, actually-exposed player inside
        // blares to everyone within 48 blocks (every 4 seconds - the horn is long).
        if (tick % 4 != 0) return;
        for (Zone zone : registry.zones().values()) {
            if (!zone.alarm() || zone.type().equals("decon")) continue;
            World world = plugin.getServer().getWorld(zone.world());
            if (world == null) continue;
            boolean breach = plugin.getServer().getOnlinePlayers().stream()
                .anyMatch(p -> zone.contains(p.getLocation())
                    && !registry.hasFullHazmat(p)
                    && LabRegistry.lineOfSight(LabRegistry.sourceOf(zone, world), p.getEyeLocation()));
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
        ActionBars.message(player, Component.text("Decontaminating...", NamedTextColor.AQUA));
    }
}
