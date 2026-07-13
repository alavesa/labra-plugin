package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * SCP-038, the Everything Tree. The datapack plants it (/lab place scp038,
 * op only: barrier column + item_display model + a lab.scp038 interaction);
 * this listener does the growing. Touch the tree with something in hand and
 * a copy of it - one, and only one - ripens off a branch. The tree needs ten
 * seconds between fruit per person, and it flatly refuses to copy anything
 * anomalous: SCP objects, keycards, ID cards. It knows better.
 */
public final class Scp038Listener implements Listener {

    private static final long COOLDOWN_MS = 10_000L;

    /** custom_data flags the tree will not reproduce. */
    private static final List<String> FORBIDDEN_FLAGS = List.of(
        "keycard", "scp268", "scp1499", "scp714", "scp018", "scp427", "scp1033",
        "scp500", "scp008_syringe", "scp009", "scp207");

    private final NamespacedKey cooldownKey;

    public Scp038Listener(LabraPlugin plugin) {
        this.cooldownKey = new NamespacedKey(plugin, "scp038_cd");
    }

    @EventHandler
    public void onTouch(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        if (!interaction.getScoreboardTags().contains("lab.scp038")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;

        long now = System.currentTimeMillis();
        long until = player.getPersistentDataContainer().getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
        if (now < until) {
            player.sendActionBar(Component.text("The branch is bare.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        if (refuses(hand)) {
            player.sendActionBar(Component.text("It refuses.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + COOLDOWN_MS);
        ItemStack fruit = hand.clone();
        fruit.setAmount(1);
        player.getInventory().addItem(fruit).values().forEach(left ->
            player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.getWorld().playSound(interaction.getLocation().add(0, 1.2, 0),
            Sound.BLOCK_CAVE_VINES_PICK_BERRIES, 0.9f, 1.2f);
        player.sendActionBar(Component.text("A fruit that is not a fruit.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Anomalies don't grow on trees. Not even on this one. */
    private boolean refuses(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        for (String id : meta.getCustomModelDataComponent().getStrings()) {
            String lower = id.toLowerCase(Locale.ROOT);
            if (lower.startsWith("keycard") || lower.contains("scp") || lower.contains("lab_idcard")) {
                return true;
            }
        }
        String components = meta.getAsString().toLowerCase(Locale.ROOT);
        for (String flag : FORBIDDEN_FLAGS) {
            if (components.contains(flag)) return true;
        }
        return false;
    }
}
