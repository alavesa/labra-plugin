package fi.alavesa.labra;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place actionbars come from. Vanilla gives every player exactly ONE
 * actionbar line, and whoever sends last wins - which is how a gunshot
 * message used to erase the NVG battery and vice versa. This hub composes
 * everything into a single component instead:
 *
 *   - a PERSISTENT slot (the NVG battery bar - a lab:hud font glyph with
 *     negative ascent, so it RENDERS BELOW the text line, above the hotbar)
 *   - a TRANSIENT message (4s TTL) rendered at the normal position -
 *     visually ABOVE the battery line
 *   - a TOP slot (short TTL, re-sent every tick by its owner - the Cars
 *     speedometer): when present it OWNS the text line, so it always sits
 *     above the battery and never flickers with transient messages, which
 *     yield to it while it's live
 *
 * The bar is pulled back under the message's center with the hud font's
 * negative space advances, using a width table for Minecraft's default
 * font. Other plugins reach this through {@code ActionBars.message(...)}
 * (Guns does, via its Labra soft-dependency); anything unbridged still
 * works - the renderer re-sends within half a second.
 */
public final class ActionBars {

    private record State(Component persistent, int persistentWidth,
                         Component transientLine, long transientUntil,
                         Component topLine, long topUntil) { }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final long TRANSIENT_MS = 4000;
    /** The top line goes stale fast: its owner (the speedometer) re-sends it
     *  every tick while it's relevant, so it disappears within a couple of
     *  ticks of the driver letting go instead of lingering for seconds. */
    private static final long TOP_MS = 400;

    private ActionBars() { }

    public static void start(Plugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                State state = STATES.get(player.getUniqueId());
                if (state == null) continue;
                long now = System.currentTimeMillis();
                if (state.persistent() == null
                    && state.transientUntil() < now
                    && state.topUntil() < now) {
                    STATES.remove(player.getUniqueId());
                    continue;
                }
                render(player, state);
            }
        }, 40L, 10L);
    }

    /** A normal message: shows at the usual spot, above any persistent bar. */
    public static void message(Player player, Component text) {
        State old = STATES.get(player.getUniqueId());
        State state = new State(old == null ? null : old.persistent(),
            old == null ? 0 : old.persistentWidth(),
            text, System.currentTimeMillis() + TRANSIENT_MS,
            old == null ? null : old.topLine(),
            old == null ? 0 : old.topUntil());
        STATES.put(player.getUniqueId(), state);
        render(player, state);
    }

    /**
     * The top-priority text line, re-sent every tick by its owner. While it's
     * live it OWNS the text position, so it sits above the battery bar and any
     * transient message. Used by the Cars speedometer so "blocks/s" always
     * reads out on top of the NVG battery. Backward-compatible: plugins that
     * never call this see the old two-slot behaviour unchanged.
     */
    public static void top(Player player, Component text) {
        State old = STATES.get(player.getUniqueId());
        State state = new State(old == null ? null : old.persistent(),
            old == null ? 0 : old.persistentWidth(),
            old == null ? null : old.transientLine(),
            old == null ? 0 : old.transientUntil(),
            text, System.currentTimeMillis() + TOP_MS);
        STATES.put(player.getUniqueId(), state);
        render(player, state);
    }

    /** The below-line slot. glyph = a lab:hud char; width = its pixel width. */
    public static void persistent(Player player, Component glyph, int width) {
        State old = STATES.get(player.getUniqueId());
        State state = new State(glyph, width,
            old == null ? null : old.transientLine(),
            old == null ? 0 : old.transientUntil(),
            old == null ? null : old.topLine(),
            old == null ? 0 : old.topUntil());
        STATES.put(player.getUniqueId(), state);
        render(player, state);
    }

    public static void clearPersistent(Player player) {
        State old = STATES.get(player.getUniqueId());
        if (old == null) return;
        STATES.put(player.getUniqueId(),
            new State(null, 0, old.transientLine(), old.transientUntil(),
                old.topLine(), old.topUntil()));
    }

    private static void render(Player player, State state) {
        long now = System.currentTimeMillis();
        boolean hasTop = state.topLine() != null && state.topUntil() >= now;
        // The top line wins the text position: while it's live the transient
        // message steps aside, so the speedometer never fights a gun message
        // for the same baseline (that fight is what flickers).
        Component text = hasTop ? state.topLine()
            : (state.transientLine() != null && state.transientUntil() >= now
                ? state.transientLine() : null);
        if (state.persistent() == null) {
            if (text != null) player.sendActionBar(text);
            return;
        }
        if (text == null) {
            player.sendActionBar(state.persistent());
            return;
        }
        // text, rewind to its center, draw the bar - then restore the
        // cursor so the component's TOTAL advance equals the text width:
        // the client centers by total width, so both the text and the bar
        // land dead center regardless of message length
        int textWidth = pixelWidth(text);
        int barWidth = state.persistentWidth();
        player.sendActionBar(text
            .append(advance(-(textWidth / 2 + barWidth / 2)))
            .append(state.persistent())
            .append(advance(textWidth / 2 - barWidth / 2)));
    }

    /** Compose any pixel offset from the hud font's power-of-two spaces. */
    private static Component advance(int pixels) {
        StringBuilder chars = new StringBuilder();
        int remaining = Math.abs(pixels);
        int base = pixels < 0 ? 0xE300 : 0xE310;
        for (int bit = 8; bit >= 0; bit--) {
            int size = 1 << bit;
            while (remaining >= size) {
                chars.append((char) (base + bit));
                remaining -= size;
            }
        }
        return Component.text(chars.toString())
            .font(Key.key("lab", "hud")).color(NamedTextColor.WHITE);
    }

    /** Default-font pixel widths (advance incl. 1px spacing) for ASCII. */
    private static int pixelWidth(Component component) {
        String text = PlainTextComponentSerializer.plainText().serialize(component);
        int width = 0;
        for (char c : text.toCharArray()) {
            width += switch (c) {
                case 'i', '!', ',', '.', '\'', ':', ';', '|' -> 2;
                case 'l' -> 3;
                case 't', 'I', '[', ']', ' ' -> 4;
                case 'k', 'f', '(', ')', '{', '}', '<', '>', '"' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return width;
    }
}
