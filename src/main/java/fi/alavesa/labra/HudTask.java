package fi.alavesa.labra;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vitals strip: a boss bar pinned to the top of every player's screen -
 * health, body temperature and the facility clock. The bar itself drains
 * with health and shifts green/yellow/red.
 *
 * Body temperature is DYNAMIC: 37.0 C at rest, climbing toward 41 as an
 * SCP-008 infection cooks its host, falling toward 30 as SCP-009 crystals
 * spread. The readout goes red when feverish and icy blue when the cold has
 * you - long before the symptoms give it away, for anyone paying attention.
 *
 * /lab hud toggles it per player (PDC-persisted).
 */
public final class HudTask implements Runnable {

    private final LabraPlugin plugin;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public HudTask(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player player) {
        var key = plugin.keyOf("hud_off");
        if (player.getPersistentDataContainer().has(key,
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(key);
        } else {
            player.getPersistentDataContainer().set(key,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            hide(player);
        }
    }

    private boolean hidden(Player player) {
        return player.getPersistentDataContainer().has(plugin.keyOf("hud_off"),
            org.bukkit.persistence.PersistentDataType.BYTE);
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (hidden(player)) continue;
            double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            double hp = Math.max(0, player.getHealth());
            double temp = bodyTemp(player);

            NamedTextColor tempColor = temp >= 37.8 ? NamedTextColor.RED
                : temp <= 35.5 ? NamedTextColor.AQUA : NamedTextColor.WHITE;
            long time = player.getWorld().getTime();
            int hours = (int) ((time / 1000 + 6) % 24);
            int minutes = (int) (time % 1000 * 60 / 1000);
            boolean day = time < 12300 || time > 23850;

            Component title = Component.text()
                .append(Component.text("❤ ", NamedTextColor.RED))
                .append(Component.text(String.format(Locale.ROOT, "%.0f/%.0f", hp, max),
                    NamedTextColor.WHITE))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Body Temp: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.ROOT, "%.1f°C", temp), tempColor))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(day ? "☀ " : "☾ ",
                    day ? NamedTextColor.YELLOW : NamedTextColor.BLUE))
                .append(Component.text(String.format(Locale.ROOT, "%02d:%02d", hours, minutes),
                    NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                .build();

            float fraction = (float) Math.max(0.0, Math.min(1.0, hp / max));
            BossBar.Color color = fraction > 0.5f ? BossBar.Color.GREEN
                : fraction > 0.25f ? BossBar.Color.YELLOW : BossBar.Color.RED;

            BossBar bar = bars.get(player.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(title, 0f, color, BossBar.Overlay.PROGRESS);
                bars.put(player.getUniqueId(), bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(0f);
                bar.color(color);
            }
        }
        // players who logged off or toggled off
        bars.keySet().removeIf(id -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null || hidden(online)) {
                if (online != null) online.hideBossBar(bars.get(id));
                return true;
            }
            return false;
        });
    }

    /**
     * 37.0 at rest. SCP-008 cooks its host toward 41 over the infection's
     * three minutes; SCP-009 pulls toward 30 as the ice takes over. Both at
     * once fight each other, which is medically fascinating and personally
     * unfortunate.
     */
    private double bodyTemp(Player player) {
        double temp = 37.0;
        temp += Math.min(4.0, score(player, "lab.z008") / 45.0);
        temp -= Math.min(7.0, score(player, "lab.inf") / 9.0);
        // a living body is never a flat line
        temp += 0.1 * Math.sin(player.getTicksLived() / 90.0);
        return temp;
    }

    private int score(Player player, String objectiveName) {
        Objective objective = Bukkit.getScoreboardManager().getMainScoreboard()
            .getObjective(objectiveName);
        if (objective == null) return 0;
        var score = objective.getScore(player.getName());
        return score.isScoreSet() ? Math.max(0, score.getScore()) : 0;
    }

    private void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public void shutdown() {
        for (UUID id : bars.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) player.hideBossBar(bars.get(id));
        }
        bars.clear();
    }
}
