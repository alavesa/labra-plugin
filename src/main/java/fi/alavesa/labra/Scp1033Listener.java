package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SCP-1033-RU, the "Universal Protector": a ceramic bracelet that lets no
 * serious harm reach its carrier - twelve unseen probes see to it. Anything
 * that ATTACKS the carrier inside ten meters is answered instantly and
 * brutally (the "sense of aggressor"). None of it is free: every act of
 * protection is paid for in the carrier's blood, drained heartbeat by
 * heartbeat afterwards - a debt the bracelet always collects, up to and
 * including everything. Full exsanguination separates the bracelet.
 *
 * Toggled on/off by right-click like the other trinkets ({@link Trinkets});
 * active from any inventory slot.
 */
public final class Scp1033Listener implements Listener, Runnable {

    private static final double RETALIATION_DAMAGE = 20.0;
    private static final double RETALIATION_BLOOD = 4.0;
    private static final double DRAIN_PER_SECOND = 1.0;

    private final LabraPlugin plugin;
    /** Outstanding blood debt per carrier, in half-hearts. In-memory only:
     *  a relog forgives the remainder - the Foundation never said the
     *  paperwork was fair in the other direction. */
    private final Map<UUID, Double> debt = new HashMap<>();

    public Scp1033Listener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    /** All protection goes through here: harm is refused and billed. */
    @EventHandler(ignoreCancelled = true)
    public void onHarm(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player carrier)) return;
        if (!Trinkets.hasActive(carrier, "scp1033")) return;
        // the drain arrives via setHealth, not the damage system, so any
        // damage event here is genuinely external - refuse it and bill it
        event.setCancelled(true);
        addDebt(carrier, event.getDamage() * 0.75);

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity aggressor = byEntity.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter ? shooter : byEntity.getDamager();
            if (aggressor instanceof LivingEntity living && !living.equals(carrier)
                && living.getWorld().equals(carrier.getWorld())
                && living.getLocation().distanceSquared(carrier.getLocation()) <= 100) {
                living.damage(RETALIATION_DAMAGE, carrier);
                living.getWorld().playSound(living.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
                living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1, 0),
                    20, 0.3, 0.5, 0.3, 0.1);
                addDebt(carrier, RETALIATION_BLOOD);
            }
        }
    }

    private void addDebt(Player carrier, double amount) {
        debt.merge(carrier.getUniqueId(), amount, Double::sum);
        carrier.getWorld().spawnParticle(Particle.DUST, carrier.getLocation().add(0, 1, 0), 6,
            0.25, 0.4, 0.25, new Particle.DustOptions(org.bukkit.Color.fromRGB(140, 10, 10), 1.0f));
    }

    /** Once a second: the probes strengthen the carrier - and the bracelet
     *  collects what it is owed. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!Trinkets.hasActive(player, "scp1033")) continue;
            // twelve probes, working: the carrier is more than themselves
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.STRENGTH, 45, 1, true, false));
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.REGENERATION, 45, 1, true, false));
            Double owed = debt.get(player.getUniqueId());
            if (owed == null || owed <= 0) continue;
            double payment = Math.min(DRAIN_PER_SECOND, owed);
            debt.put(player.getUniqueId(), owed - payment);
            player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 4,
                0.2, 0.3, 0.2, new Particle.DustOptions(org.bukkit.Color.fromRGB(120, 8, 8), 0.8f));
            if (player.getHealth() - payment <= 0.5) {
                // full exsanguination: the bracelet separates on its own
                debt.remove(player.getUniqueId());
                for (ItemStack item : player.getInventory().getContents()) {
                    if ("scp1033".equals(Trinkets.baseOf(item)) && Trinkets.isActive(item)) {
                        Trinkets.setActive(item, false);
                    }
                }
                ActionBars.message(player, Component.text("The bracelet is satisfied.",
                    NamedTextColor.DARK_RED, TextDecoration.ITALIC));
                player.setHealth(0.0);
            } else {
                player.setHealth(player.getHealth() - payment);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT_DROWN, 0.4f, 0.6f);
            }
        }
    }
}
