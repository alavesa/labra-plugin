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
    /** Two stacked bars per player: the wallet balance on top, the stash total below. */
    private final Map<UUID, BossBar> wallet = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> stash = new ConcurrentHashMap<>();

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

            // top bar: the wallet (spendable balance)
            Component w = Component.text("❈ ", NamedTextColor.YELLOW)
                .append(Component.text(bal + " credits", NamedTextColor.GOLD));
            BossBar wb = wallet.get(p.getUniqueId());
            if (wb == null) {
                wb = BossBar.bossBar(w, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                wallet.put(p.getUniqueId(), wb);
                p.showBossBar(wb);
            } else wb.name(w);

            // second bar, directly below: credits stashed away
            Component ss = Component.text("⌂ ", NamedTextColor.AQUA)
                .append(Component.text(st + " in stash", NamedTextColor.AQUA));
            BossBar sb = stash.get(p.getUniqueId());
            if (sb == null) {
                sb = BossBar.bossBar(ss, 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                stash.put(p.getUniqueId(), sb);
                p.showBossBar(sb);
            } else sb.name(ss);
        }
        wallet.keySet().removeIf(id -> !online.contains(id));
        stash.keySet().removeIf(id -> !online.contains(id));
    }

    public void shutdown() {
        hideAll(wallet);
        hideAll(stash);
    }

    private void hideAll(Map<UUID, BossBar> bars) {
        for (var e : bars.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
    }
}
