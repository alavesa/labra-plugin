package fi.alavesa.labra;

import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * Credit denominations are physical CASH you carry - a 1-credit coin, a 10-credit bill
 * and a 100-credit bill. There is no deposit any more: the HUD counts the cash in your
 * pockets directly ({@link Credits#wallet}), so right-clicking a bill does nothing
 * special. This class is now just the shared value table for those items.
 */
public final class CreditListener implements Listener {

    /** The credit value of one of this item, or 0 if it isn't credit cash. */
    public static int creditValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        var strings = item.getItemMeta().getCustomModelDataComponent().getStrings();
        if (strings.contains("lab_credit100")) return 100;
        if (strings.contains("lab_credit10")) return 10;
        if (strings.contains("lab_credit")) return 1;
        return 0;
    }
}
