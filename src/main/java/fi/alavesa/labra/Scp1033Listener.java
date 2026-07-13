package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SCP-1033, the 33 Second Man. The plugin quietly remembers where every
 * player stood - and how whole and how fed they were - for the last 33
 * seconds. Right-clicking the pocket watch winds its holder back to the
 * oldest memory: position, health and hunger, exactly as they were. The
 * hands then refuse to move again for five minutes.
 */
public final class Scp1033Listener implements Listener, Runnable {

    private static final int MEMORY_SECONDS = 33;
    private static final long COOLDOWN_MS = 5 * 60_000L;

    private record Moment(Location where, double health, int food) { }

    private final LabraPlugin plugin;
    private final NamespacedKey cooldownKey;
    private final Map<UUID, ArrayDeque<Moment>> memory = new HashMap<>();

    public Scp1033Listener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "scp1033_cd");
    }

    private boolean isWatch(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp1033");
    }

    /** Once a second: one more memory for everyone, the oldest one forgotten. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead()) continue;
            ArrayDeque<Moment> moments = memory.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
            moments.addLast(new Moment(player.getLocation().clone(), player.getHealth(), player.getFoodLevel()));
            while (moments.size() > MEMORY_SECONDS) moments.removeFirst();
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWatch(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long until = player.getPersistentDataContainer().getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
        ArrayDeque<Moment> moments = memory.get(player.getUniqueId());
        if (now < until || moments == null || moments.isEmpty()) {
            player.sendActionBar(Component.text("The hands refuse to move.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        Moment then = moments.peekFirst();
        player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + COOLDOWN_MS);
        player.teleport(then.where());
        double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.max(1.0, Math.min(then.health(), max)));
        player.setFoodLevel(then.food());
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, true, false));
        player.playSound(then.where(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.5f);
        player.sendActionBar(Component.text("Thirty-three seconds.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        memory.remove(event.getPlayer().getUniqueId());
    }
}
