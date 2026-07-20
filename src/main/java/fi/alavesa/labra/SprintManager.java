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

    private final Map<UUID, Double> stamina = new HashMap<>();
    private final Map<UUID, Boolean> winded = new HashMap<>();

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
            double s = stamina.getOrDefault(id, MAX);
            boolean wind = winded.getOrDefault(id, false);

            boolean sprinting = player.isSprinting() && !wind;
            if (sprinting) {
                s -= DRAIN;
                if (s <= 0) {
                    s = 0;
                    wind = true;
                    player.setSprinting(false);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, true, false, false));
                }
            } else {
                s = Math.min(MAX, s + REGEN);
                if (wind && s >= RECOVER_AT) wind = false;
                if (wind) player.setSprinting(false);   // hold them at a walk until recovered
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
    }
}
