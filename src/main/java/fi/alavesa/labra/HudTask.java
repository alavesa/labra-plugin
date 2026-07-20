package fi.alavesa.labra;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vitals stack: boss bars pinned to the top of every player's screen. Top to
 * bottom - the BLINK meter, the SPRINT stamina meter, then the health / body
 * temperature / clock strip.
 *
 * The blink and sprint bars are drawn like the Guns ammo bar: a notched boss bar
 * plus a row of filled/empty segment glyphs and a percentage. Blink state is read
 * off the shared scoreboard (lab.blink, published by ScpMobs); sprint stamina
 * comes from Labra's own SprintManager.
 *
 * Body temperature is DYNAMIC: 37.0 C at rest, climbing toward 41 as an SCP-008
 * infection cooks its host, falling toward 30 as SCP-009 crystals spread.
 *
 * /lab hud toggles the whole stack per player (PDC-persisted).
 */
public final class HudTask implements Runnable {

    private static final int SEGMENTS = 10;               // segment glyphs in a meter row
    private static final String FILLED = "█";
    private static final String EMPTY = "░";
    /** Shared scoreboard channel the blink meter is published on (see ScpMobs BlinkManager). */
    private static final String BLINK_OBJECTIVE = "lab.blink";

    private final LabraPlugin plugin;
    private final SprintManager sprint;
    private final Map<UUID, BossBar> health = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> blink = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> stamina = new ConcurrentHashMap<>();

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
            // Order matters: show blink first, then sprint, then health, so they
            // stack top-to-bottom in that order on a fresh screen.
            updateBlink(player);
            updateSprint(player);
            updateHealth(player);
        }
        // players who logged off or toggled off
        for (UUID id : List.copyOf(health.keySet())) {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null || hidden(online)) {
                if (online != null) hideAll(online);
                else forget(id);
            }
        }
    }

    // ---------------------------------------------------------------- blink

    private void updateBlink(Player player) {
        int pct = blinkPercent(player);
        if (pct < 0) {                       // blink system off / not applicable - drop the bar
            BossBar bar = blink.remove(player.getUniqueId());
            if (bar != null) player.hideBossBar(bar);
            return;
        }
        float f = pct / 100f;
        TextColor rowColor = f < 0.2f ? NamedTextColor.RED : NamedTextColor.AQUA;
        BossBar.Color color = f < 0.2f ? BossBar.Color.RED : BossBar.Color.BLUE;
        Component title = meter("Blink", NamedTextColor.AQUA, f, rowColor, pct);
        show(blink, player, title, f, color);
    }

    /** -1 when the blink meter isn't published for this player. */
    private int blinkPercent(Player player) {
        Objective obj = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(BLINK_OBJECTIVE);
        if (obj == null) return -1;
        var score = obj.getScore(player.getName());
        return score.isScoreSet() ? Math.max(0, Math.min(100, score.getScore())) : -1;
    }

    // --------------------------------------------------------------- sprint

    private void updateSprint(Player player) {
        double f = sprint.fraction(player);
        boolean winded = sprint.isWinded(player);
        int pct = (int) Math.round(f * 100);
        TextColor rowColor = winded ? NamedTextColor.RED
            : f > 0.5 ? NamedTextColor.GREEN : f > 0.25 ? NamedTextColor.YELLOW : NamedTextColor.GOLD;
        BossBar.Color color = winded ? BossBar.Color.RED
            : f > 0.5 ? BossBar.Color.GREEN : f > 0.25 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        Component label = winded ? Component.text("Sprint (winded)", NamedTextColor.RED)
            : Component.text("Sprint", NamedTextColor.GREEN);
        Component title = meterTitled(label, (float) f, rowColor, pct);
        show(stamina, player, title, (float) f, color);
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

    /** A labelled segment meter, styled like the Guns ammo bar. */
    private Component meter(String label, TextColor labelColor, float fraction, TextColor rowColor, int pct) {
        return meterTitled(Component.text(label, labelColor), fraction, rowColor, pct);
    }

    private Component meterTitled(Component label, float fraction, TextColor rowColor, int pct) {
        int filled = Math.max(0, Math.min(SEGMENTS, Math.round(fraction * SEGMENTS)));
        Component row = Component.text(FILLED.repeat(filled), rowColor)
            .append(Component.text(EMPTY.repeat(SEGMENTS - filled), NamedTextColor.DARK_GRAY));
        return Component.text()
            .append(label)
            .append(Component.text("  "))
            .append(row)
            .append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
            .build();
    }

    private void show(Map<UUID, BossBar> store, Player player, Component title, float fraction, BossBar.Color color) {
        float f = Math.max(0f, Math.min(1f, fraction));
        BossBar bar = store.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, f, color, BossBar.Overlay.NOTCHED_10);
            store.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(f);
            bar.color(color);
        }
    }

    private void hideAll(Player player) {
        UUID id = player.getUniqueId();
        for (Map<UUID, BossBar> store : List.of(blink, stamina, health)) {
            BossBar bar = store.remove(id);
            if (bar != null) player.hideBossBar(bar);
        }
    }

    private void forget(UUID id) {
        blink.remove(id);
        stamina.remove(id);
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
        for (Map<UUID, BossBar> store : List.of(blink, stamina, health)) {
            for (UUID id : store.keySet()) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) player.hideBossBar(store.get(id));
            }
            store.clear();
        }
    }
}
