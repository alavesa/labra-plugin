package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Zipties and handcuffs. Detaining is EARNED: hold right-click for the full
 * two seconds (the items charge like food - that IS the hold timer) while
 * standing BEHIND the target's back; a face-to-face attempt fails. Detained
 * hands are useless: no attacking, no item use, no dropping - only walking,
 * slowly. A ziptie is spent on use and can be STRUGGLED out of (keep sneak
 * held ~20s); handcuffs are reusable, come back to whoever releases the
 * prisoner, and do not break. Release: right-click the prisoner with an
 * empty hand.
 */
public final class RestraintsListener implements Listener, Runnable {

    private static final double BEHIND_DOT = -0.35; // captor must be in the back half-space
    private static final int STRUGGLE_SECONDS = 20;

    private final LabraPlugin plugin;
    private final NamespacedKey restrainedKey; // "ziptie" | "handcuffs" on the victim
    private final Map<UUID, Integer> struggling = new HashMap<>();

    public RestraintsListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.restrainedKey = new NamespacedKey(plugin, "restrained");
    }

    private String restraintType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var strings = item.getItemMeta().getCustomModelDataComponent().getStrings();
        if (strings.contains("lab_ziptie")) return "ziptie";
        if (strings.contains("lab_handcuffs")) return "handcuffs";
        return null;
    }

    public boolean isRestrained(Player player) {
        return player.getPersistentDataContainer().has(restrainedKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------ detaining

    /** The 2-second right-click hold completes as a "consume" - intercept it. */
    @EventHandler
    public void onChargeComplete(PlayerItemConsumeEvent event) {
        String type = restraintType(event.getItem());
        if (type == null) return;
        event.setCancelled(true); // never actually eaten
        Player captor = event.getPlayer();
        Entity target = captor.getTargetEntity(3);
        if (!(target instanceof Player victim) || isRestrained(victim)) {
            captor.sendActionBar(line("No one within reach.", NamedTextColor.GRAY));
            return;
        }
        Vector toCaptor = captor.getLocation().toVector()
            .subtract(victim.getLocation().toVector()).setY(0);
        if (toCaptor.lengthSquared() < 0.01
            || toCaptor.normalize().dot(victim.getLocation().getDirection().setY(0).normalize()) > BEHIND_DOT) {
            captor.sendActionBar(line("Not from the front.", NamedTextColor.GRAY));
            return;
        }
        victim.getPersistentDataContainer().set(restrainedKey, PersistentDataType.STRING, type);
        if (type.equals("ziptie")) {
            ItemStack hand = captor.getInventory().getItemInMainHand();
            if (restraintType(hand) != null) hand.setAmount(hand.getAmount() - 1);
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1f, 0.7f);
        victim.sendActionBar(line("Your hands are bound.", NamedTextColor.RED));
        captor.sendActionBar(line("Detained.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------- captivity

    /** Once a second: keep the bound slow, and let ziptied prisoners struggle. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String type = player.getPersistentDataContainer().get(restrainedKey, PersistentDataType.STRING);
            if (type == null) continue;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 2, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, 2, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, 1, true, false));
            if (type.equals("ziptie") && player.isSneaking()) {
                int seconds = struggling.merge(player.getUniqueId(), 1, Integer::sum);
                if (seconds % 5 == 0 && seconds < STRUGGLE_SECONDS) {
                    player.sendActionBar(line("The tie digs in...", NamedTextColor.GRAY));
                }
                if (seconds >= STRUGGLE_SECONDS) {
                    free(player);
                    player.sendActionBar(line("The tie snaps.", NamedTextColor.GRAY));
                    player.getWorld().playSound(player.getLocation(),
                        Sound.ENTITY_LEASH_KNOT_BREAK, 1f, 1.4f);
                }
            } else {
                struggling.remove(player.getUniqueId());
            }
        }
    }

    private void free(Player player) {
        player.getPersistentDataContainer().remove(restrainedKey);
        struggling.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    /** Release: an EMPTY main hand on the prisoner. Handcuffs come back. */
    @EventHandler
    public void onRelease(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player victim) || !isRestrained(victim)) return;
        Player releaser = event.getPlayer();
        if (releaser.getInventory().getItemInMainHand().getType() != Material.AIR) return;
        event.setCancelled(true);
        String type = victim.getPersistentDataContainer().get(restrainedKey, PersistentDataType.STRING);
        free(victim);
        if ("handcuffs".equals(type)) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "execute as " + releaser.getUniqueId() + " at @s run function lab:give/handcuffs");
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 1f, 1.1f);
        victim.sendActionBar(line("Your hands are free.", NamedTextColor.GRAY));
        releaser.sendActionBar(line("Released.", NamedTextColor.GRAY));
    }

    // ------------------------------------------- what bound hands cannot do

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
            player.sendActionBar(line("Your hands are bound.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getItem() != null && isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(line("Your hands are bound.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestrained(event.getPlayer())) event.setCancelled(true);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
