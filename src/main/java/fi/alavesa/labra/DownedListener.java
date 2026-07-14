package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The downed state. Ordinary lethal damage no longer kills outright: the
 * victim collapses instead - one heart, crawling pace, useless hands, sixty
 * seconds on the clock. Three ways out: a MEDKIT (three-second hold, on them
 * or by their own hand if they still have one), a FINISHER (any hit while
 * down is the end), or the clock.
 *
 * Anomalies don't do "downed": massive single hits (SCP kill routines use
 * damage in the hundreds), the void and direct setHealth deaths pass
 * straight through - SCP-173 does not leave survivors, and the 008/049
 * reanimations keep firing off real deaths exactly as before.
 */
public final class DownedListener implements Listener, Runnable {

    private static final long BLEED_OUT_MS = 60_000L;
    private static final double ANOMALY_THRESHOLD = 100.0; // damage this big is fate

    private final LabraPlugin plugin;
    private final Map<UUID, Long> downed = new HashMap<>(); // player -> deadline

    public DownedListener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isDowned(Player player) {
        return downed.containsKey(player.getUniqueId());
    }

    private boolean isMedkit(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_medkit");
    }

    // ------------------------------------------------------------- going down

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getFinalDamage() < victim.getHealth()) return; // survivable
        if (event.getDamage() >= ANOMALY_THRESHOLD) return;      // anomalies kill
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) return;

        if (isDowned(victim)) {
            // a hit while down IS the finisher - let this one kill for real
            downed.remove(victim.getUniqueId());
            clearEffects(victim);
            return;
        }
        event.setCancelled(true);
        victim.setHealth(1.0);
        downed.put(victim.getUniqueId(), System.currentTimeMillis() + BLEED_OUT_MS);
        victim.setPose(Pose.SWIMMING, true); // face-down on the floor
        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false));
        blood(victim, 24); // the moment of collapse leaves a mark
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_BIG_FALL, 1f, 0.6f);
        victim.sendActionBar(line("You are down.", NamedTextColor.RED));
        if (event instanceof EntityDamageByEntityEvent byEntity
            && byEntity.getDamager() instanceof Player attacker) {
            attacker.sendActionBar(line("They're down. Finish it - or don't.", NamedTextColor.GRAY));
        }
    }

    /** Once a second: the crawl, the countdown, the clock running out. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Long deadline = downed.get(player.getUniqueId());
            if (deadline == null) continue;
            long left = (deadline - now) / 1000;
            if (left <= 0) {
                downed.remove(player.getUniqueId());
                clearEffects(player);
                player.setHealth(0.0); // bled out - a real death, drops and all
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 3, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, 2, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, 1, true, false));
            blood(player, 6);
            player.sendActionBar(Component.text("You are down. ", NamedTextColor.RED,
                    TextDecoration.ITALIC)
                .append(Component.text(left + "s", NamedTextColor.DARK_RED, TextDecoration.ITALIC)));
            if (left % 2 == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.5f, 0.9f);
            }
        }
    }

    /** Dark red pools and falling drips around the body. */
    private void blood(Player player, int amount) {
        var at = player.getLocation().add(0, 0.15, 0);
        player.getWorld().spawnParticle(Particle.DUST, at, amount, 0.45, 0.1, 0.45,
            new Particle.DustOptions(Color.fromRGB(122, 8, 8), 1.4f));
        player.getWorld().spawnParticle(Particle.FALLING_DUST, at.clone().add(0, 0.6, 0),
            Math.max(1, amount / 6), 0.3, 0.2, 0.3,
            org.bukkit.Material.REDSTONE_BLOCK.createBlockData());
    }

    private void clearEffects(Player player) {
        player.setPose(Pose.STANDING, false); // release the crawl
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    // ------------------------------------------------------------- the medkit

    /** The 3-second medkit hold completes as a "consume" - never eaten. */
    @EventHandler
    public void onMedkit(PlayerItemConsumeEvent event) {
        if (!isMedkit(event.getItem())) return;
        event.setCancelled(true);
        Player medic = event.getPlayer();
        Entity target = medic.getTargetEntity(3);
        Player patient = target instanceof Player p && isDowned(p) ? p
            : isDowned(medic) ? medic : null;
        if (patient == null) {
            // nobody bleeding: the kit patches the holder's scrapes instead
            if (medic.getHealth() >= 19.5) {
                medic.sendActionBar(line("Nothing to treat.", NamedTextColor.GRAY));
                return;
            }
            medic.setHealth(Math.min(20.0, medic.getHealth() + 8.0));
            spend(medic);
            medic.sendActionBar(line("Patched up.", NamedTextColor.GRAY));
            return;
        }
        downed.remove(patient.getUniqueId());
        clearEffects(patient);
        patient.setHealth(12.0);
        patient.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0, true, false));
        spend(medic);
        patient.getWorld().playSound(patient.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        patient.sendActionBar(line("Back on your feet.", NamedTextColor.GRAY));
        if (patient != medic) medic.sendActionBar(line("They'll live.", NamedTextColor.GRAY));
    }

    private void spend(Player medic) {
        ItemStack hand = medic.getInventory().getItemInMainHand();
        if (isMedkit(hand)) hand.setAmount(hand.getAmount() - 1);
        else {
            ItemStack off = medic.getInventory().getItemInOffHand();
            if (isMedkit(off)) off.setAmount(off.getAmount() - 1);
        }
    }

    // ---------------------------------------------- useless hands, no escape

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isDowned(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        // a downed player may still start their OWN medkit - dying with
        // supplies in hand and no help coming shouldn't be a death sentence
        if (!isDowned(event.getPlayer())) return;
        if (isMedkit(event.getItem())) return;
        if (event.getItem() != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isDowned(event.getPlayer())) event.setCancelled(true);
    }

    /** Logging out while down is choosing the clock. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (downed.remove(event.getPlayer().getUniqueId()) != null) {
            clearEffects(event.getPlayer());
            event.getPlayer().setHealth(0.0);
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
