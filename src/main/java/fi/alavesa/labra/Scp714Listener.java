package fi.alavesa.labra;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * SCP-714, the jaded ring. Held in the off hand it closes the mind's doors:
 * poison, wither and nausea simply don't take (which covers SCP-049's touch,
 * SCP-008's fever nausea, and ordinary venom alike). The price is the jade
 * drowsiness - permanent Mining Fatigue II and Slowness I while held.
 *
 * Other code (present or future) can ask {@link #isWearing(Player)}.
 */
public final class Scp714Listener implements Listener, Runnable {

    private final LabraPlugin plugin;
    private final Set<UUID> hadRing = new HashSet<>();

    public Scp714Listener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    /** True while the jaded ring sits in this player's off hand. */
    public static boolean isWearing(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() != Material.GOLD_NUGGET || !off.hasItemMeta()) return false;
        return off.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp714");
    }

    /** Once a second: refresh the drowsiness, lift it when the ring is put away. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isWearing(player)) {
                hadRing.add(player.getUniqueId());
                player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 0, true, false));
            } else if (hadRing.remove(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }
    }

    /** The doors stay shut: no poison, wither or nausea reaches the wearer. */
    @EventHandler
    public void onEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED
            && event.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        PotionEffectType type = event.getModifiedType();
        if (!type.equals(PotionEffectType.POISON) && !type.equals(PotionEffectType.WITHER)
            && !type.equals(PotionEffectType.NAUSEA)) return;
        if (isWearing(player)) event.setCancelled(true);
    }
}
