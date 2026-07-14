package fi.alavesa.labra;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * SCP-018, the Super Ball. Thrown, it does not stop: every impact reflects
 * it off the surface FASTER (x1.15, capped), with a sharp click and a bruise
 * for anything standing within arm's reach of the impact. It burns itself
 * out after 45 seconds or 80 bounces and leaves nothing behind.
 *
 * Implementation: the thrown egg is consumed on every hit, so each bounce
 * removes the old projectile and launches a fresh tagged egg with the
 * reflected velocity. Tagged eggs never hatch chickens.
 */
public final class Scp018Listener implements Listener {

    private static final double MAX_SPEED = 2.5;   // blocks per tick
    private static final int MAX_BOUNCES = 80;
    private static final long LIFETIME_MS = 45_000L;

    private final NamespacedKey ballKey;
    private final NamespacedKey bounceKey;
    private final NamespacedKey bornKey;

    public Scp018Listener(LabraPlugin plugin) {
        this.ballKey = new NamespacedKey(plugin, "scp018");
        this.bounceKey = new NamespacedKey(plugin, "scp018_bounces");
        this.bornKey = new NamespacedKey(plugin, "scp018_born");
    }

    private boolean isBallItem(ItemStack item) {
        if (item == null || item.getType() != Material.EGG || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp018");
    }

    private void tag(Egg egg, int bounces, long born) {
        egg.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        egg.getPersistentDataContainer().set(bounceKey, PersistentDataType.INTEGER, bounces);
        egg.getPersistentDataContainer().set(bornKey, PersistentDataType.LONG, born);
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        if (egg.getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE)) return; // a bounce respawn
        if (!isBallItem(egg.getItem())) return;
        tag(egg, 0, System.currentTimeMillis());
    }

    /** The ball is not an egg. Nothing hatches. */
    @EventHandler
    public void onHatch(PlayerEggThrowEvent event) {
        if (event.getEgg().getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE)) {
            event.setHatching(false);
        }
    }

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        var pdc = egg.getPersistentDataContainer();
        if (!pdc.has(ballKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true); // no break, no hatch - we do the bouncing

        Vector velocity = egg.getVelocity();
        Location at = egg.getLocation();
        ItemStack item = egg.getItem();
        ProjectileSource shooter = egg.getShooter();
        int bounces = pdc.getOrDefault(bounceKey, PersistentDataType.INTEGER, 0) + 1;
        long born = pdc.getOrDefault(bornKey, PersistentDataType.LONG, System.currentTimeMillis());
        egg.remove();

        // the impact: a sharp click and a bruise, both scaled with speed
        double speed = velocity.length();
        at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT,
            (float) Math.min(1.0, 0.3 + speed * 0.3), 1.8f);
        for (Entity near : at.getWorld().getNearbyEntities(at, 1.2, 1.2, 1.2)) {
            if (near instanceof LivingEntity living) {
                if (shooter instanceof LivingEntity source) living.damage(speed * 5, source);
                else living.damage(speed * 5);
            }
        }

        // spent: 45 seconds of terror or 80 bounces, then nothing at all
        if (bounces >= MAX_BOUNCES || System.currentTimeMillis() - born > LIFETIME_MS) return;

        Vector reflected;
        Vector normal = event.getHitBlockFace() != null ? event.getHitBlockFace().getDirection() : null;
        if (normal != null) {
            reflected = velocity.clone().subtract(normal.clone().multiply(2 * velocity.dot(normal)));
        } else {
            reflected = velocity.clone().multiply(-1); // off a body: straight back
        }
        reflected.multiply(1.15);
        if (reflected.lengthSquared() < 1.0e-6) return; // dead stop - the ball rests

        var rng = java.util.concurrent.ThreadLocalRandom.current();
        double speed018 = reflected.length();
        if (rng.nextDouble() < 0.30) {
            // seek: a third of the bounces turn toward the nearest player
            org.bukkit.entity.Player mark = null;
            double best = 10 * 10;
            for (org.bukkit.entity.Player p : at.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(at);
                if (d < best) { best = d; mark = p; }
            }
            if (mark != null) {
                reflected = mark.getEyeLocation().toVector().subtract(at.toVector())
                    .normalize().multiply(speed018);
            }
        } else {
            // scatter: never twice off the same wall the same way
            reflected.add(new Vector(rng.nextDouble(-0.35, 0.35),
                rng.nextDouble(-0.2, 0.35), rng.nextDouble(-0.35, 0.35))
                .multiply(speed018 * 0.6));
            reflected = reflected.normalize().multiply(speed018);
        }
        if (reflected.length() > MAX_SPEED) reflected = reflected.normalize().multiply(MAX_SPEED);

        Location from = normal != null
            ? at.clone().add(normal.clone().multiply(0.15))
            : at.clone().add(reflected.clone().normalize().multiply(0.15));
        Vector send = reflected;
        Egg next = at.getWorld().spawn(from, Egg.class, spawned -> {
            spawned.setItem(item);
            spawned.setShooter(shooter);
            tag(spawned, bounces, born);
        });
        next.setVelocity(send);
    }
}
