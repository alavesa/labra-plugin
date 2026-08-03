package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * Snacks - chip bags, canned drinks and the like. Right-click to eat one for a short buff (defined in
 * {@link LabRegistry.Snack}).
 */
public final class SnackListener implements Listener {

    private final LabRegistry registry;

    public SnackListener(LabRegistry registry) { this.registry = registry; }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = event.getItem();
        String id = registry.snackId(hand);
        if (id == null) return;
        LabRegistry.Snack s = registry.snack(id);
        if (s == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        p.addPotionEffect(new PotionEffect(s.effect(), 20 * Math.max(1, s.seconds()), Math.max(0, s.amplifier()), true, true, true));
        hand.setAmount(hand.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.7f, 1.2f);
        ActionBars.message(p, Component.text("You have a " + s.display() + ".", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
    }
}
