package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SCP-1033-RU, the "Universal Protector": a ceramic bracelet whose twelve unseen
 * probes strengthen its carrier. It no longer bleeds them dry - instead its gifts
 * come on GRADUALLY the longer it's worn: strength, ten extra hearts and a suite
 * of other boons that ramp up to full over {@link #ATTUNE_SECONDS} seconds, and
 * ease back off if the bracelet is removed. Nothing here ever harms the carrier.
 *
 * Toggled on/off by right-click like the other trinkets ({@link Trinkets}); active
 * from any inventory slot.
 */
public final class Scp1033Listener implements Listener, Runnable {

    /** Seconds of continuous wear to reach the full set of buffs. */
    private static final int ATTUNE_SECONDS = 40;

    private final LabraPlugin plugin;
    /** How attuned each carrier is (0..ATTUNE_SECONDS): climbs while worn, falls when not. */
    private final Map<UUID, Integer> attune = new HashMap<>();
    /** Carriers we've already told they hit full attunement (once per session). */
    private final java.util.Set<UUID> announced = new java.util.HashSet<>();

    public Scp1033Listener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Once a second: ramp the carrier's attunement up (or down) and apply the
     *  buffs at their current level. Nothing here ever harms the carrier. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean worn = Trinkets.hasActive(player, "scp1033");
            int level = attune.getOrDefault(id, 0);
            if (worn) {
                level = Math.min(ATTUNE_SECONDS, level + 1);
            } else {
                if (level == 0) { announced.remove(id); continue; }
                level = Math.max(0, level - 2);   // fades twice as fast as it builds
            }
            attune.put(id, level);
            if (level == 0) { announced.remove(id); continue; }

            double f = level / (double) ATTUNE_SECONDS;   // 0..1 attunement fraction
            // ~1s effects, re-applied each tick so they never lapse; ambient (no swirl).
            // HEALTH_BOOST 4 = +20 HP = ten extra hearts, reached at full attunement.
            apply(player, PotionEffectType.HEALTH_BOOST, (int) Math.round(f * 4));
            apply(player, PotionEffectType.STRENGTH, f >= 0.5 ? 1 : 0);
            apply(player, PotionEffectType.REGENERATION, f >= 0.25 ? 1 : 0);
            apply(player, PotionEffectType.RESISTANCE, f >= 0.75 ? 1 : 0);
            apply(player, PotionEffectType.SPEED, f >= 0.5 ? 0 : -1);
            apply(player, PotionEffectType.SATURATION, f >= 0.9 ? 0 : -1);

            if (level >= ATTUNE_SECONDS && announced.add(id)) {
                ActionBars.message(player, Component.text("The bracelet is fully attuned to you.",
                    NamedTextColor.AQUA, TextDecoration.ITALIC));
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
            }
            if (level % 4 == 0) {
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.1, 0), 3,
                    0.25, 0.4, 0.25, new Particle.DustOptions(org.bukkit.Color.fromRGB(90, 180, 220), 0.7f));
            }
        }
    }

    /** Refresh one effect at the given amplifier; amplifier < 0 means "not yet". */
    private void apply(Player player, PotionEffectType type, int amplifier) {
        if (amplifier < 0) return;
        player.addPotionEffect(new PotionEffect(type, 45, amplifier, true, false, false));
    }

    public void forget(UUID player) {
        attune.remove(player);
        announced.remove(player);
    }
}
