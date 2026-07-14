package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Night vision goggles - with a battery. A fresh pair runs 30 minutes of
 * wear; a dead pair still straps to your face (the green overlay is the
 * client's pumpkin view, charge or no charge) but amplifies nothing, which
 * is exactly as useless as it sounds. A 9V battery in hand, right-clicked,
 * swaps a fresh cell into the goggles on your head - or the first pair in
 * your pack - and is spent doing it.
 */
public final class NvgListener implements Listener, Runnable {

    private static final int FULL_CHARGE_SECONDS = 30 * 60;
    private static final int LOW_WARN_SECONDS = 60;

    private final LabraPlugin plugin;
    private final NamespacedKey chargeKey;
    private final Set<UUID> wasSeeing = new HashSet<>();
    private final java.util.Map<UUID, Integer> lastSegments = new java.util.HashMap<>();

    public NvgListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.chargeKey = new NamespacedKey(plugin, "nvg_charge");
    }

    private boolean isGoggles(ItemStack item) {
        if (item == null || item.getType() != Material.CARVED_PUMPKIN || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_nvg");
    }

    private boolean isBattery(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_battery");
    }

    private int charge(ItemStack goggles) {
        Integer stored = goggles.getItemMeta().getPersistentDataContainer()
            .get(chargeKey, PersistentDataType.INTEGER);
        return stored == null ? FULL_CHARGE_SECONDS : stored; // fresh pair = full cell
    }

    private void setCharge(ItemStack goggles, int seconds) {
        var meta = goggles.getItemMeta();
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER,
            Math.max(0, Math.min(FULL_CHARGE_SECONDS, seconds)));
        goggles.setItemMeta(meta);
    }

    /** Once a second: drain worn goggles, grant sight while there's charge. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack helmet = player.getInventory().getHelmet();
            if (isGoggles(helmet)) {
                int left = charge(helmet) - 1;
                setCharge(helmet, left);
                if (left > 0) {
                    wasSeeing.add(player.getUniqueId());
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        300, 0, true, false));
                    int segments = (int) Math.ceil(left * 10.0 / FULL_CHARGE_SECONDS);
                    Integer previous = lastSegments.put(player.getUniqueId(), segments);
                    player.sendActionBar(batteryBar(segments)); // always on display
                    if (previous != null && segments <= 2 && previous != segments) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 0.6f);
                    }
                } else {
                    if (wasSeeing.remove(player.getUniqueId())) {
                        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7f, 0.5f);
                    }
                    player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                    player.sendActionBar(batteryBar(0)); // an empty dead frame, always
                }
            } else {
                lastSegments.remove(player.getUniqueId());
                if (wasSeeing.remove(player.getUniqueId())) {
                    player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                }
            }
        }
    }

    /** A 9V in hand: right-click feeds the goggles a fresh cell. */
    @EventHandler
    public void onRecharge(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack battery = event.getItem();
        if (!isBattery(battery)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();

        ItemStack goggles = null;
        if (isGoggles(player.getInventory().getHelmet())) {
            goggles = player.getInventory().getHelmet();
        } else {
            for (ItemStack item : player.getInventory().getContents()) {
                if (isGoggles(item)) { goggles = item; break; }
            }
        }
        if (goggles == null) {
            player.sendActionBar(line("Nothing to power."));
            return;
        }
        if (charge(goggles) >= FULL_CHARGE_SECONDS - 5) {
            player.sendActionBar(line("Still charged."));
            return;
        }
        setCharge(goggles, FULL_CHARGE_SECONDS);
        battery.setAmount(battery.getAmount() - 1);
        player.sendActionBar(line("Fresh cell. The dark turns green."));
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.7f, 1.6f);
    }

    /** [||||||----] - green bars for charge, dark gray for the spent part,
     *  the whole thing red-tinted at 2 segments and under. */
    private Component batteryBar(int segments) {
        NamedTextColor full = segments <= 2 ? NamedTextColor.RED : NamedTextColor.GREEN;
        return Component.text("[", NamedTextColor.GRAY)
            .append(Component.text("|".repeat(segments), full))
            .append(Component.text("-".repeat(10 - segments), NamedTextColor.DARK_GRAY))
            .append(Component.text("]", NamedTextColor.GRAY));
    }

    private Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
