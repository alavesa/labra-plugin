package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SCP-1499, the gas mask. Two seconds after it settles over your face, you
 * are somewhere else - a single configured anchor point standing in for the
 * mask's dimension (/lab scp1499 sethere). You stay as long as the mask stays
 * on; take it off (or come back online without it) and you are returned to
 * the exact spot you left. If no anchor is configured the mask is just an old
 * mask: it smells of dust, and nothing happens.
 */
public final class Scp1499Listener implements Listener, Runnable {

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final NamespacedKey insideKey;
    private final NamespacedKey returnKey;
    private final Map<UUID, Integer> wornSeconds = new HashMap<>();

    public Scp1499Listener(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.insideKey = new NamespacedKey(plugin, "scp1499_inside");
        this.returnKey = new NamespacedKey(plugin, "scp1499_return");
    }

    private boolean isMask(ItemStack item) {
        if (item == null || item.getType() != Material.LEATHER_HELMET || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp1499");
    }

    private boolean isInside(Player player) {
        return player.getPersistentDataContainer().has(insideKey, PersistentDataType.BYTE);
    }

    /** Once a second: count seconds under the mask, move people in and out. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isMask(player.getInventory().getHelmet())) {
                int seconds = wornSeconds.merge(player.getUniqueId(), 1, Integer::sum);
                if (seconds == 2 && !isInside(player)) enter(player);
            } else {
                wornSeconds.remove(player.getUniqueId());
                if (isInside(player)) exit(player);
            }
        }
    }

    private void enter(Player player) {
        Location anchor = registry.scp1499Anchor();
        if (anchor == null) {
            player.sendActionBar(Component.text("It smells of dust. Nothing happens.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        Location back = player.getLocation();
        String stored = String.format(Locale.ROOT, "%s;%f;%f;%f;%f;%f", back.getWorld().getName(),
            back.getX(), back.getY(), back.getZ(), back.getYaw(), back.getPitch());
        player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, stored);
        player.getPersistentDataContainer().set(insideKey, PersistentDataType.BYTE, (byte) 1);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, true, false));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 0.5f);
        player.teleport(anchor);
    }

    private void exit(Player player) {
        String stored = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        player.getPersistentDataContainer().remove(insideKey);
        player.getPersistentDataContainer().remove(returnKey);
        if (stored == null) return;
        String[] parts = stored.split(";");
        if (parts.length != 6) return;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;
        Location back = new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
            Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, true, false));
        player.playSound(back, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 0.6f);
        player.teleport(back);
    }

    /** Logging back in without the mask on: you don't get to stay. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isInside(player) && !isMask(player.getInventory().getHelmet())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && isInside(player)) exit(player);
            });
        }
    }

    /** Dying inside (or anywhere) ends the trip - the corpse isn't couriered home. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        player.getPersistentDataContainer().remove(insideKey);
        player.getPersistentDataContainer().remove(returnKey);
        wornSeconds.remove(player.getUniqueId());
    }
}
