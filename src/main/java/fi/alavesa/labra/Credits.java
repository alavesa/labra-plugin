package fi.alavesa.labra;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

/**
 * "Credits" - the server currency, held as PHYSICAL CASH the player carries. There is
 * no bank balance any more: your spendable money is exactly the credit coins/bills in
 * your inventory (a 1-credit coin, a 10-credit bill, a 100-credit bill), and the HUD
 * simply counts them. Spending removes cash and hands back change; earning hands you
 * fresh bills.
 *
 *   {@code wallet(player)}  - credits carried in the inventory (the ❈ HUD number)
 *   {@code credits_stash}   - credits kept in the stash, tallied by Facility (the ⌂ number)
 */
public final class Credits {

    public static final String STASH = "credits_stash";

    /** Set once at enable so the currency util can mint change/cash items. */
    private static LabRegistry registry;
    public static void attach(LabRegistry reg) { registry = reg; }

    private Credits() { }

    // ------------------------------------------------------------- wallet (cash)

    /** The credit value of one of this item, or 0 if it isn't credit cash. */
    public static int unitValue(ItemStack item) {
        return CreditListener.creditValue(item);
    }

    /** Total credits the player is physically carrying. */
    public static int wallet(Player player) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            int v = unitValue(it);
            if (v > 0) total += v * it.getAmount();
        }
        return total;
    }

    /** Back-compat: "balance" now means the wallet for online players, else 0. */
    public static int balance(OfflinePlayer player) {
        return player instanceof Player p ? wallet(p) : 0;
    }

    /** Remove up to {@code amount} of physical cash, returning change. false if the
     *  player can't afford it (nothing is removed in that case). */
    public static boolean spend(Player player, int amount) {
        if (amount <= 0) return true;
        if (wallet(player) < amount) return false;
        int removed = removeCash(player, amount);
        if (removed > amount) give(player, removed - amount);   // change
        return true;
    }

    /** Hand the player {@code amount} credits as coins/bills (100s, then 10s, then 1s). */
    public static void give(Player player, int amount) {
        int rem = Math.max(0, amount);
        while (rem >= 100 && registry != null) { addOrDrop(player, registry.buildCredit100()); rem -= 100; }
        while (rem >= 10 && registry != null)  { addOrDrop(player, registry.buildCredit10());  rem -= 10; }
        while (rem >= 1) { addOrDrop(player, registry == null ? null : registry.buildCredit()); rem -= 1; }
    }

    /** Remove every credit item from the inventory (used by /credits set). */
    public static void clearCash(Player player) {
        ItemStack[] c = player.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (unitValue(c[i]) > 0) player.getInventory().setItem(i, null);
    }

    /** Remove cash totalling AT LEAST {@code amount}; returns how much was actually
     *  removed (>= amount when it had to break a bigger bill). Greedy-fills without
     *  overshooting first, then breaks one larger bill if a remainder is left. */
    private static int removeCash(Player player, int amount) {
        Inventory inv = player.getInventory();
        int removed = 0;
        // pass 1: take exact denominations without exceeding the amount (100 -> 10 -> 1)
        for (int denom : new int[]{100, 10, 1}) {
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length && removed < amount; i++) {
                if (unitValue(c[i]) != denom) continue;
                while (c[i].getAmount() > 0 && removed + denom <= amount) {
                    c[i].setAmount(c[i].getAmount() - 1);
                    removed += denom;
                }
                inv.setItem(i, c[i].getAmount() > 0 ? c[i] : null);
            }
        }
        // pass 2: still short (remainder smaller than any bill we hold) -> break one bill
        if (removed < amount) {
            for (int denom : new int[]{10, 100, 1}) {
                ItemStack[] c = inv.getContents();
                for (int i = 0; i < c.length && removed < amount; i++) {
                    if (unitValue(c[i]) != denom) continue;
                    c[i].setAmount(c[i].getAmount() - 1);
                    inv.setItem(i, c[i].getAmount() > 0 ? c[i] : null);
                    removed += denom;
                }
                if (removed >= amount) break;
            }
        }
        return removed;
    }

    private static void addOrDrop(Player player, ItemStack item) {
        if (item == null) return;
        player.getInventory().addItem(item).values()
            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // ------------------------------------------------------------- stash (scoreboard)

    public static int stash(OfflinePlayer player) { return read(STASH, name(player)); }
    public static void setStash(OfflinePlayer player, int amount) { write(STASH, name(player), Math.max(0, amount)); }
    public static void addStash(OfflinePlayer player, int amount) { setStash(player, stash(player) + amount); }

    // ---- internals ----
    private static String name(OfflinePlayer p) {
        String n = p.getName();
        return n != null ? n : p.getUniqueId().toString();
    }

    private static Objective objective(String id) {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective o = board.getObjective(id);
        if (o == null) {
            try {
                o = board.registerNewObjective(id, Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text(id));
            } catch (IllegalArgumentException e) { o = board.getObjective(id); }
        }
        return o;
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
