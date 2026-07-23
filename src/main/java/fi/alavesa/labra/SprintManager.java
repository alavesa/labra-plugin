package fi.alavesa.labra;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint stamina. Everyone has a 0-100 stamina pool that drains while sprinting
 * and refills while not. Run it dry and you're winded: sprint is locked out (a
 * brief Slowness sells the exhaustion) until stamina climbs back over a
 * recovery threshold. The Labra HUD draws the bar at the top of the screen.
 *
 * Ticked every 2 ticks for smoothness; the toggle listener stops a winded
 * player from re-sprinting the instant they hit empty.
 */
public final class SprintManager implements Runnable, Listener {

    private static final double MAX = 100.0;
    private static final double SPRINT_SECONDS = 8.0;   // full bar = 8s of sprint
    private static final double REGEN_SECONDS = 6.0;    // empty -> full in 6s of rest
    private static final double RECOVER_AT = 40.0;      // must reach this to sprint again
    private static final int TICK_PERIOD = 2;           // scheduled every 2 ticks

    /** How long the "you ran the bar dry" penalty lasts: Slowness II + no running. */
    private static final int WINDED_TICKS = 40;   // 2 seconds

    private final LabRegistry registry;
    private final Map<UUID, Double> stamina = new HashMap<>();
    private final Map<UUID, Boolean> winded = new HashMap<>();
    /** Ticks of forced wind remaining after emptying the bar (the 3s lock-out). */
    private final Map<UUID, Integer> windedTicks = new HashMap<>();

    public SprintManager(LabRegistry registry) {
        this.registry = registry;
    }

    private static final double DRAIN = MAX / (SPRINT_SECONDS * 20.0 / TICK_PERIOD);
    private static final double REGEN = MAX / (REGEN_SECONDS * 20.0 / TICK_PERIOD);

    /** 0..1 stamina fraction for the HUD. */
    public double fraction(Player player) {
        return Math.max(0.0, Math.min(1.0, stamina.getOrDefault(player.getUniqueId(), MAX) / MAX));
    }

    public boolean isWinded(Player player) {
        return winded.getOrDefault(player.getUniqueId(), false);
    }

    @Override
    public void run() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
                stamina.put(player.getUniqueId(), MAX);
                winded.put(player.getUniqueId(), false);
                continue;
            }
            UUID id = player.getUniqueId();
            // The Super Gas Mask grants infinite stamina: bar stays full, never winded.
            if ("super".equals(registry.gasMaskTier(player.getInventory().getHelmet()))) {
                stamina.put(id, MAX);
                winded.put(id, false);
                if (player.getFoodLevel() < 7) player.setFoodLevel(7);
                continue;
            }
            double s = stamina.getOrDefault(id, MAX);
            boolean wind = winded.getOrDefault(id, false);

            // Sprint is governed by THIS stamina bar. Vanilla refuses to sprint once food
            // drops to 6 or below - so while NOT winded we keep food above that gate (stamina
            // decides), and while WINDED we deliberately DROP it to 6 to lock sprinting off
            // the vanilla way (setSprinting(false) alone doesn't hold - the client re-sends it).
            if (!wind && player.getFoodLevel() < 7) player.setFoodLevel(7);

            boolean sprinting = player.isSprinting() && !wind;
            if (sprinting) {
                s -= DRAIN;
                if (s <= 0) {
                    s = 0;
                    wind = true;
                    player.setSprinting(false);
                    // Slowness II for 2 seconds sells the exhaustion.
                    player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS, WINDED_TICKS, 1, true, false, false));
                }
            } else {
                s = Math.min(MAX, s + REGEN);
            }
            // Winded: locked out of running until the bar climbs back over the threshold.
            // Hold it with the vanilla food gate (<=6) so it actually sticks, plus a belt.
            if (wind) {
                player.setSprinting(false);
                if (player.getFoodLevel() > 6) player.setFoodLevel(6);
                if (s >= RECOVER_AT) {
                    wind = false;
                    if (player.getFoodLevel() < 7) player.setFoodLevel(7);
                }
            }
            stamina.put(id, s);
            winded.put(id, wind);
        }
    }

    /** A winded player can't start sprinting again until they've recovered. */
    @EventHandler
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && isWinded(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    public void forget(UUID id) {
        stamina.remove(id);
        winded.remove(id);
        windedTicks.remove(id);
    }
}
