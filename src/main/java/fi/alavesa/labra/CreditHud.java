package fi.alavesa.labra;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The credits readout: a player's spendable balance and their stash total, shown
 * at the top of the screen. A boss bar is used (not a title) so it's ALWAYS visible
 * and coexists with every other HUD - titles, subtitles, the gas-mask overlay, the
 * action-bar meters - instead of fighting them for the title line. Re-sent on a
 * timer; only shown once the player has any credits or stash so a broke player's
 * screen stays clean.
 */
public final class CreditHud implements Runnable {

    private final LabraPlugin plugin;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public CreditHud(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            online.add(p.getUniqueId());
            int bal = Credits.balance(p);
            int st = Credits.stash(p);
            Component text = Component.text("❈ ", NamedTextColor.YELLOW)
                .append(Component.text(bal + " credits", NamedTextColor.GOLD));
            if (st > 0) {
                text = text.append(Component.text("   ⌂ stash ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(st), NamedTextColor.AQUA));
            }
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(text, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(text);
            }
        }
        // drop bars for players who logged off
        bars.keySet().removeIf(id -> !online.contains(id));
    }

    public void shutdown() {
        for (var e : bars.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
    }
}
