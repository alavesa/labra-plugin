package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Physical credit denominations bank INTO the (non-physical) credit balance when
 * right-clicked: a 1-credit coin, a 10-credit bill and a 100-credit stack. So cash
 * exists as an item you can hand over, but your spendable money lives as a balance
 * (shown on the HUD), not clutter in your pockets.
 */
public final class CreditListener implements Listener {

    @EventHandler
    public void onDeposit(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        int unit = creditValue(held);
        if (unit <= 0) return;
        event.setCancelled(true);
        int total = unit * held.getAmount();      // bank the whole stack in one go
        Credits.add(p, total);
        p.getInventory().setItemInMainHand(null);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.4f);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
        ActionBars.message(p, Component.text("+" + total + " credits  (balance: "
            + Credits.balance(p) + ")", NamedTextColor.GREEN));
    }

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
