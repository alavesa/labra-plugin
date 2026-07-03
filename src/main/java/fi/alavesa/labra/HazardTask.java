package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Runs once a second: players inside a hazard zone without a full hazmat suit get the
 * zone's effect + a warning; suited players see a calm "protected" note instead.
 */
public final class HazardTask implements Runnable {

    private final LabraPlugin plugin;
    private final LabRegistry registry;

    public HazardTask(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void run() {
        if (registry.zones().isEmpty()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Zone zone = registry.zones().values().stream()
                .filter(z -> z.contains(player.getLocation()))
                .findFirst().orElse(null);
            if (zone == null) continue;

            if (registry.hasFullHazmat(player)) {
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
    }
}
