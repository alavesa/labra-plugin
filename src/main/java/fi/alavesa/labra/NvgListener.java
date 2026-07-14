package fi.alavesa.labra;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Night vision goggles. Carved-pumpkin based ON PURPOSE: the client renders
 * its fullscreen pumpkinblur overlay for any carved pumpkin on the head, and
 * the resource pack repaints that overlay as a green phosphor goggle view -
 * the world through a creeper's eyes. The plugin's only job is the Night
 * Vision effect while they're worn.
 */
public final class NvgListener implements Runnable {

    private final LabraPlugin plugin;
    private final Set<UUID> wasWearing = new HashSet<>();

    public NvgListener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isGoggles(ItemStack item) {
        if (item == null || item.getType() != Material.CARVED_PUMPKIN || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_nvg");
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isGoggles(player.getInventory().getHelmet())) {
                wasWearing.add(player.getUniqueId());
                // 15s window refreshed every second - never blinks dark
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                    300, 0, true, false));
            } else if (wasWearing.remove(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
    }
}
