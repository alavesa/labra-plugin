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

    /** The bottom-left vitals (blink + sprint): a glyph line pushed far left and
     *  down to the XP-bar row. Re-sent every tick by the HUD, so it's short-lived. */
    private record Meters(Component line, int width, int leftShift, long until) { }

    /** A full-screen gas-mask overlay glyph, centered, composed with everything else
     *  so it never flickers with the meters/messages. Re-sent while the mask is worn. */
    private record Mask(Component glyph, int width, long until) { }

    /** A centered aim reticle (the Guns crosshair brackets): composed like the mask
     *  so a held gun's reticle never erases the blink/sprint meters or messages.
     *  Re-sent every tick by Guns while a gun is held. */
    private record Reticle(Component glyph, int width, long until) { }

    /** A centered interact-crosshair highlight (Facility's corner-bracket frame): composed
     *  like the reticle so hovering an interactable never erases the blink/sprint meters
     *  or a held gun's reticle. Re-sent every couple of ticks by Facility while hovering. */
    private record Crosshair(Component glyph, int width, long until) { }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Meters> METERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Mask> MASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Reticle> RETICLES = new ConcurrentHashMap<>();
    private static final Map<UUID, Crosshair> CROSSHAIRS = new ConcurrentHashMap<>();
    private static final long TRANSIENT_MS = 4000;
    private static final long METERS_MS = 500;
    private static final long MASK_MS = 500;
    private static final long RETICLE_MS = 500;
    private static final long CROSSHAIR_MS = 500;
    /** The top line goes stale fast: its owner (the speedometer) re-sends it
     *  every tick while it's relevant, so it disappears within a couple of
     *  ticks of the driver letting go instead of lingering for seconds. */
    private static final long TOP_MS = 400;

    private ActionBars() { }

    public static void start(Plugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                State state = STATES.get(player.getUniqueId());
                long now = System.currentTimeMillis();
                if (state != null && state.persistent() == null
                    && state.transientUntil() < now
                    && state.topUntil() < now) {
                    STATES.remove(player.getUniqueId());
                    state = null;
                }
                Meters meters = METERS.get(player.getUniqueId());
                if (meters != null && meters.until() < now) {
                    METERS.remove(player.getUniqueId());
                    meters = null;
                }
                Mask mask = MASKS.get(player.getUniqueId());
                if (mask != null && mask.until() < now) {
                    MASKS.remove(player.getUniqueId());
                    mask = null;
                }
                Reticle reticle = RETICLES.get(player.getUniqueId());
                if (reticle != null && reticle.until() < now) {
                    RETICLES.remove(player.getUniqueId());
                    reticle = null;
                }
                Crosshair crosshair = CROSSHAIRS.get(player.getUniqueId());
                if (crosshair != null && crosshair.until() < now) {
                    CROSSHAIRS.remove(player.getUniqueId());
                    crosshair = null;
                }
                if (state == null && meters == null && mask == null && reticle == null
                    && crosshair == null) continue;
                render(player);
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
        render(player);
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
        render(player);
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
        render(player);
    }

    public static void clearPersistent(Player player) {
        State old = STATES.get(player.getUniqueId());
        if (old == null) return;
        STATES.put(player.getUniqueId(),
            new State(null, 0, old.transientLine(), old.transientUntil(),
                old.topLine(), old.topUntil()));
    }

    /**
     * The bottom-left blink+sprint meters. {@code line} is a lab:hud glyph line
     * (its glyphs carry the negative ascent that drops it to the XP-bar row);
     * {@code width} is its pixel advance; {@code leftShift} is how many pixels
     * left of screen-center to anchor it. Re-send this every tick from the HUD.
     */
    public static void meters(Player player, Component line, int width, int leftShift) {
        METERS.put(player.getUniqueId(),
            new Meters(line, width, leftShift, System.currentTimeMillis() + METERS_MS));
        render(player);
    }

    public static void clearMeters(Player player) {
        METERS.remove(player.getUniqueId());
    }

    /** The worn gas-mask overlay: {@code glyph} is a lab:gasmask char (its ascent/
     *  height make it fill the screen); {@code width} is its rendered pixel advance.
     *  Re-send every tick while the mask is on; it centers and composes with the rest. */
    public static void mask(Player player, Component glyph, int width) {
        MASKS.put(player.getUniqueId(), new Mask(glyph, width, System.currentTimeMillis() + MASK_MS));
        render(player);
    }

    public static void clearMask(Player player) {
        MASKS.remove(player.getUniqueId());
    }

    /** The Guns aim reticle: {@code glyph} is a guns:reticle bracket string (its
     *  own font ascent lifts it to the crosshair); {@code width} is its rendered
     *  pixel advance. Re-send every tick while a gun is held; it centers and
     *  composes with the meters/mask/messages instead of erasing them. */
    public static void reticle(Player player, Component glyph, int width) {
        RETICLES.put(player.getUniqueId(), new Reticle(glyph, width, System.currentTimeMillis() + RETICLE_MS));
        render(player);
    }

    public static void clearReticle(Player player) {
        RETICLES.remove(player.getUniqueId());
    }

    /** The Facility interact-crosshair: {@code glyph} is a facility:crosshair frame glyph
     *  (its font ascent lifts it to the crosshair); {@code width} is its rendered pixel
     *  advance. Re-send every couple of ticks while hovering an interactable; it centers
     *  and composes with the meters/mask/reticle instead of erasing them. */
    public static void crosshair(Player player, Component glyph, int width) {
        CROSSHAIRS.put(player.getUniqueId(), new Crosshair(glyph, width, System.currentTimeMillis() + CROSSHAIR_MS));
        render(player);
    }

    public static void clearCrosshair(Player player) {
        CROSSHAIRS.remove(player.getUniqueId());
    }

    private static void render(Player player) {
        long now = System.currentTimeMillis();
        State state = STATES.get(player.getUniqueId());
        Meters meters = METERS.get(player.getUniqueId());
        Mask mask = MASKS.get(player.getUniqueId());
        Reticle reticle = RETICLES.get(player.getUniqueId());
        Crosshair crosshair = CROSSHAIRS.get(player.getUniqueId());
        boolean metersLive = meters != null && meters.until() >= now;
        boolean maskLive = mask != null && mask.until() >= now;
        boolean reticleLive = reticle != null && reticle.until() >= now;
        boolean crosshairLive = crosshair != null && crosshair.until() >= now;

        // --- build the centered "base" (top/transient text + persistent bar) ---
        Component base = null;
        int baseWidth = 0;   // total advance of base; the client centers on this
        if (state != null) {
            boolean hasTop = state.topLine() != null && state.topUntil() >= now;
            Component text = hasTop ? state.topLine()
                : (state.transientLine() != null && state.transientUntil() >= now
                    ? state.transientLine() : null);
            Component persistent = state.persistent();
            if (persistent == null) {
                if (text != null) { base = text; baseWidth = pixelWidth(text); }
            } else if (text == null) {
                base = persistent; baseWidth = state.persistentWidth();
            } else {
                int textWidth = pixelWidth(text);
                int barWidth = state.persistentWidth();
                base = text
                    .append(advance(-(textWidth / 2 + barWidth / 2)))
                    .append(persistent)
                    .append(advance(textWidth / 2 - barWidth / 2));
                baseWidth = textWidth;
            }
        }

        if (!metersLive && !maskLive && !reticleLive && !crosshairLive) {
            if (base != null) player.sendActionBar(base);
            return;
        }

        // A single line carries everything. Pick a total advance T that the client
        // centers on: the base's width if there is one, else the mask's, else the
        // meters', else the reticle's. Then append the meters (left-anchored) and the
        // centered overlays (mask, reticle) with net-zero advances so they sit where
        // they should without disturbing T.
        int T;
        if (base != null) T = baseWidth;
        else if (maskLive) T = mask.width();
        else if (metersLive) T = 2 * meters.leftShift();
        else if (reticleLive) T = reticle.width();
        else T = crosshair.width();
        if (T <= 0) T = maskLive ? mask.width() : reticleLive ? reticle.width()
            : crosshairLive ? crosshair.width()
            : (meters != null ? 2 * meters.leftShift() : 1);
        Component out = base != null ? base : advance(T);   // an invisible T-wide spacer sets the centering

        if (metersLive) {
            int shift = meters.leftShift();
            int m = meters.width();
            out = out.append(advance(-shift - T / 2)).append(meters.line())
                     .append(advance(shift + T / 2 - m));
        }
        if (maskLive) {
            int w = mask.width();
            out = out.append(advance(-w / 2 - T / 2)).append(mask.glyph())
                     .append(advance(T / 2 - w / 2));
        }
        if (reticleLive) {
            int w = reticle.width();
            out = out.append(advance(-w / 2 - T / 2)).append(reticle.glyph())
                     .append(advance(T / 2 - w / 2));
        }
        if (crosshairLive) {
            int w = crosshair.width();
            out = out.append(advance(-w / 2 - T / 2)).append(crosshair.glyph())
                     .append(advance(T / 2 - w / 2));
        }
        player.sendActionBar(out);
    }

    /** Public: a horizontal pixel offset in the lab:hud font (for other HUDs to
     *  position text, e.g. the top-right credits readout). */
    public static Component spacer(int pixels) { return advance(pixels); }
    /** Public: default-font pixel width of a component (for right-aligning). */
    public static int width(Component component) { return pixelWidth(component); }

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
