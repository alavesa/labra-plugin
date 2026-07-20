package fi.alavesa.labra;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vitals. The health / body-temperature / clock strip stays a boss bar at
 * the TOP of the screen. The BLINK and SPRINT meters render bottom-left, down at
 * the XP-bar row (SCP:CB style) - drawn with the lab:hud font (glyphs carry a
 * negative ascent that drops them to that row) and pushed far left by the
 * ActionBars hub. Blink is cyan, sprint green (both go red when nearly spent).
 *
 * How far left they sit is config: hud.meters-x (pixels left of screen centre).
 * The vertical row is baked into the font's ascent (tools/gen_hud.py METER_ASCENT).
 *
 * Blink state is read off the shared scoreboard (lab.blink, published by ScpMobs);
 * sprint stamina comes from Labra's own SprintManager. /lab hud toggles it all.
 */
public final class HudTask implements Runnable {

    private static final Key HUD_FONT = Key.key("lab", "hud");
    private static final String EYE = "";       // blink icon
    private static final String BOOT = "";      // sprint icon
    private static final String GAP = "";       // lab:hud +8px space
    private static final int BLINK_BAR_0 = 0xE210;    // cyan bar, +segment (0..10)
    private static final int STAM_BAR_0 = 0xE230;     // green bar, +segment (0..10)
    // Approximate advances (icon 8px+1, bar 46px+1, gap 8px) - only affects
    // trailing padding, never the left anchor, so exactness doesn't matter.
    private static final int W_ICON = 9, W_BAR = 47, W_GAP = 8;

    /** Shared scoreboard channel the blink meter is published on (ScpMobs BlinkManager). */
    private static final String BLINK_OBJECTIVE = "lab.blink";

    private final LabraPlugin plugin;
    private final SprintManager sprint;
    private final Map<UUID, BossBar> health = new ConcurrentHashMap<>();

    public HudTask(LabraPlugin plugin, SprintManager sprint) {
        this.plugin = plugin;
        this.sprint = sprint;
    }

    public void toggle(Player player) {
        var key = plugin.keyOf("hud_off");
        if (player.getPersistentDataContainer().has(key,
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(key);
        } else {
            player.getPersistentDataContainer().set(key,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            hideAll(player);
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
            updateMeters(player);
            updateHealth(player);
        }
        for (UUID id : List.copyOf(health.keySet())) {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null || hidden(online)) {
                if (online != null) hideAll(online);
                else forget(id);
            }
        }
    }

    // ------------------------------------------- blink + sprint (bottom-left)

    private void updateMeters(Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            ActionBars.clearMeters(player);
            return;
        }
        int blinkPct = blinkPercent(player);            // -1 when blink is off
        double stamFrac = sprint.fraction(player);

        StringBuilder s = new StringBuilder();
        int width = 0;
        if (blinkPct >= 0) {
            s.append(EYE).append((char) (BLINK_BAR_0 + segments(blinkPct / 100.0))).append(GAP);
            width += W_ICON + W_BAR + W_GAP;
        }
        s.append(BOOT).append((char) (STAM_BAR_0 + segments(stamFrac)));
        width += W_ICON + W_BAR;

        Component line = Component.text(s.toString()).font(HUD_FONT).color(NamedTextColor.WHITE);
        int leftShift = plugin.getConfig().getInt("hud.meters-x", 220);
        ActionBars.meters(player, line, width, leftShift);
    }

    private int segments(double fraction) {
        return Math.max(0, Math.min(10, (int) Math.round(fraction * 10)));
    }

    /** -1 when the blink meter isn't published for this player. */
    private int blinkPercent(Player player) {
        Objective obj = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(BLINK_OBJECTIVE);
        if (obj == null) return -1;
        var score = obj.getScore(player.getName());
        return score.isScoreSet() ? Math.max(0, Math.min(100, score.getScore())) : -1;
    }

    // --------------------------------------------------------------- health

    private void updateHealth(Player player) {
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

        BossBar bar = health.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, 0f, color, BossBar.Overlay.PROGRESS);
            health.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(0f);
            bar.color(color);
        }
    }

    // ---------------------------------------------------------------- shared

    private void hideAll(Player player) {
        BossBar bar = health.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
        ActionBars.clearMeters(player);
    }

    private void forget(UUID id) {
        health.remove(id);
    }

    /**
     * 37.0 at rest. SCP-008 cooks its host toward 41 over the infection's three
     * minutes; SCP-009 pulls toward 30 as the ice takes over.
     */
    private double bodyTemp(Player player) {
        double temp = 37.0;
        temp += Math.min(4.0, score(player, "lab.z008") / 45.0);
        temp -= Math.min(7.0, score(player, "lab.inf") / 9.0);
        if (player.getFireTicks() > 0) {
            temp += Math.min(8.0, player.getFireTicks() / 20.0);
        }
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

    public void shutdown() {
        for (UUID id : health.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) player.hideBossBar(health.get(id));
        }
        health.clear();
    }
}
