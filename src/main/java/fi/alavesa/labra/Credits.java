package fi.alavesa.labra;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

/**
 * "Credits" - the server currency. Kept as a BALANCE (not cash the player has to
 * carry) on the main scoreboard, so it persists across restarts for free and every
 * plugin can read/write it. There are also physical denominations (a 1-credit coin,
 * a 10-credit bill, a 100-credit bill) that convert INTO this balance when used.
 *
 *   {@code credits}       - a player's spendable balance
 *   {@code credits_stash} - credits kept in their stash (see the Facility stash)
 */
public final class Credits {

    public static final String BALANCE = "credits";
    public static final String STASH = "credits_stash";

    private Credits() { }

    private static Objective objective(String id) {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective o = board.getObjective(id);
        if (o == null) {
            try {
                o = board.registerNewObjective(id, Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text(id));
            } catch (IllegalArgumentException e) {
                o = board.getObjective(id);
            }
        }
        return o;
    }

    /** Balance of a player (by name - the scoreboard keys on name). */
    public static int balance(OfflinePlayer player) {
        return read(BALANCE, name(player));
    }

    public static int stash(OfflinePlayer player) {
        return read(STASH, name(player));
    }

    public static void setBalance(OfflinePlayer player, int amount) {
        write(BALANCE, name(player), Math.max(0, amount));
    }

    public static void setStash(OfflinePlayer player, int amount) {
        write(STASH, name(player), Math.max(0, amount));
    }

    /** Add credits to a player's balance. */
    public static void add(OfflinePlayer player, int amount) {
        setBalance(player, balance(player) + amount);
    }

    public static void addStash(OfflinePlayer player, int amount) {
        setStash(player, stash(player) + amount);
    }

    /** Take credits if the player can afford it; true on success. */
    public static boolean take(OfflinePlayer player, int amount) {
        int b = balance(player);
        if (b < amount) return false;
        setBalance(player, b - amount);
        return true;
    }

    // ---- internals ----
    private static String name(OfflinePlayer p) {
        String n = p.getName();
        return n != null ? n : p.getUniqueId().toString();
    }

    private static int read(String id, String name) {
        Objective o = objective(id);
        if (o == null) return 0;
        var s = o.getScore(name);
        return s.isScoreSet() ? s.getScore() : 0;
    }

    private static void write(String id, String name, int value) {
        Objective o = objective(id);
        if (o != null) o.getScore(name).setScore(value);
    }
}
