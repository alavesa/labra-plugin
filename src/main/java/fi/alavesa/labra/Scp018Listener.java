package fi.alavesa.labra;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCP-018, the Super Ball. Thrown, it starts almost lazily and does not stop:
 * every impact DOUBLES its speed (from ~0.25 up to ~3.0 blocks/tick), so the
 * first bounces crawl and give the thrower time to run before it accelerates
 * into chaos. It reflects off surfaces (scatter + player-seek), punches CLEAN
 * THROUGH glass, panes, doors and trapdoors (breaking them), and CRACKS solid
 * walls it can't break with a non-destructive vanilla crack overlay. Everything
 * it breaks or cracks is restored after five minutes (or immediately on plugin
 * shutdown). It bounces for three minutes, then despawns, leaving nothing.
 *
 * Black stained glass (block and pane) is a deliberate wall material on this
 * server: it is treated as solid - it cracks, it never breaks.
 *
 * Implementation: the thrown egg is consumed on every hit, so each bounce
 * removes the old projectile and launches a fresh tagged egg with the new
 * velocity. Tagged eggs never hatch chickens.
 */
public final class Scp018Listener implements Listener {

    private static final double INITIAL_SPEED = 0.25;  // blocks per tick, first throw
    private static final double MAX_SPEED = 3.0;       // blocks per tick, cap
    private static final long LIFETIME_MS = 180_000L;  // 3 minutes of bouncing
    private static final long RESTORE_MS = 300_000L;   // restore 5 minutes after damage
    private static final double SEEK_RANGE_SQ = 10.0 * 10.0;
    private static final double CRACK_RANGE = 24.0;    // who sees the crack overlay
    private static final float CRACK_STAGE = 0.6f;

    private final LabraPlugin plugin;
    private final NamespacedKey ballKey;
    private final NamespacedKey bounceKey;
    private final NamespacedKey bornKey;
    private final NamespacedKey speedKey;

    /**
     * Restore registry: block location -> pending restore. BROKEN entries carry
     * the original BlockData (put back if the spot is still AIR); CRACKED
     * entries just clear the crack overlay for nearby players. A single map keyed
     * by location keeps at most one entry per block; re-hitting a block refreshes
     * its expiry.
     */
    private final Map<Location, Restore> registry = new ConcurrentHashMap<>();

    private enum Kind { BROKEN, CRACKED }

    private record Restore(Kind kind, BlockData original, long expireEpochMs) {}

    public Scp018Listener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.ballKey = new NamespacedKey(plugin, "scp018");
        this.bounceKey = new NamespacedKey(plugin, "scp018_bounces");
        this.bornKey = new NamespacedKey(plugin, "scp018_born");
        this.speedKey = new NamespacedKey(plugin, "scp018_speed");
    }

    private boolean isBallItem(ItemStack item) {
        if (item == null || item.getType() != Material.EGG || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp018");
    }

    private void tag(Egg egg, int bounces, long born, double speed) {
        var pdc = egg.getPersistentDataContainer();
        pdc.set(ballKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(bounceKey, PersistentDataType.INTEGER, bounces);
        pdc.set(bornKey, PersistentDataType.LONG, born);
        pdc.set(speedKey, PersistentDataType.DOUBLE, speed);
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        if (egg.getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE)) return; // a bounce respawn
        if (!isBallItem(egg.getItem())) return;
        tag(egg, 0, System.currentTimeMillis(), INITIAL_SPEED);
        // Start it crawling: override the throw impulse with our tiny initial speed.
        Vector dir = egg.getVelocity();
        if (dir.lengthSquared() > 1.0e-9) {
            egg.setVelocity(dir.normalize().multiply(INITIAL_SPEED));
        }
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
        double prevSpeed = pdc.getOrDefault(speedKey, PersistentDataType.DOUBLE, INITIAL_SPEED);
        egg.remove();

        // the impact: a sharp click and a bruise, both scaled with speed
        double impactSpeed = velocity.length();
        at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT,
            (float) Math.min(1.0, 0.3 + impactSpeed * 0.3), 1.8f);
        for (Entity near : at.getWorld().getNearbyEntities(at, 1.2, 1.2, 1.2)) {
            if (near instanceof LivingEntity living) {
                if (shooter instanceof LivingEntity source) living.damage(impactSpeed * 5, source);
                else living.damage(impactSpeed * 5);
            }
        }

        // spent: three minutes of terror, then nothing at all
        if (System.currentTimeMillis() - born > LIFETIME_MS) return;

        // Speed DOUBLES every hit (block or entity), capped.
        double nextSpeed = Math.min(MAX_SPEED, prevSpeed * 2.0);

        // What did we hit? A block we can break, a wall we crack, or a body.
        Block hitBlock = event.getHitBlock();
        BlockFace face = event.getHitBlockFace();
        boolean passThrough = false;

        if (hitBlock != null) {
            Material mat = hitBlock.getType();
            if (isBreakable(mat)) {
                breakThrough(hitBlock);
                passThrough = true; // punch straight through, no reflection
            } else if (hitBlock.getType().isSolid()) {
                crackWall(hitBlock);
            }
        }

        Vector direction;
        if (passThrough) {
            // Through the now-broken block: keep the same heading, just faster.
            direction = velocity.clone().normalize();
        } else {
            Vector normal = face != null ? face.getDirection() : null;
            Vector reflected;
            if (normal != null && normal.lengthSquared() > 1.0e-9) {
                reflected = velocity.clone().subtract(normal.clone().multiply(2 * velocity.dot(normal)));
            } else {
                reflected = velocity.clone().multiply(-1); // off a body: straight back
            }
            if (reflected.lengthSquared() < 1.0e-9) return; // dead stop - the ball rests
            direction = reflected.normalize();

            var rng = java.util.concurrent.ThreadLocalRandom.current();
            if (rng.nextDouble() < 0.30) {
                // seek: a third of the bounces turn toward the nearest player
                Player mark = null;
                double best = SEEK_RANGE_SQ;
                for (Player p : at.getWorld().getPlayers()) {
                    double d = p.getLocation().distanceSquared(at);
                    if (d < best) { best = d; mark = p; }
                }
                if (mark != null) {
                    direction = mark.getEyeLocation().toVector().subtract(at.toVector()).normalize();
                }
            } else {
                // scatter: never twice off the same wall the same way
                direction.add(new Vector(rng.nextDouble(-0.35, 0.35),
                    rng.nextDouble(-0.2, 0.35), rng.nextDouble(-0.35, 0.35)).multiply(0.6));
                if (direction.lengthSquared() < 1.0e-9) return;
                direction.normalize();
            }
        }

        Vector send = direction.clone().multiply(nextSpeed);

        // Nudge the spawn slightly along the travel direction so the fresh egg
        // doesn't immediately re-collide with the same face / broken hole edge.
        Location from = at.clone().add(direction.clone().multiply(0.15));
        double finalSpeed = nextSpeed;
        Egg next = at.getWorld().spawn(from, Egg.class, spawned -> {
            spawned.setItem(item);
            spawned.setShooter(shooter);
            tag(spawned, bounces, born, finalSpeed);
        });
        next.setVelocity(send);
    }

    // --- breakable / solid classification -------------------------------------

    /** Glass, glass panes, doors and trapdoors the ball punches through - EXCEPT
     *  black stained glass and its pane, which are deliberate walls (solid). */
    private static boolean isBreakable(Material mat) {
        String name = mat.name();
        if (name.equals("BLACK_STAINED_GLASS") || name.equals("BLACK_STAINED_GLASS_PANE")) {
            return false;
        }
        if (name.endsWith("_GLASS_PANE") || name.equals("GLASS_PANE")) return true;
        if (name.endsWith("_GLASS") || name.equals("GLASS") || name.equals("TINTED_GLASS")) return true;
        if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")) return true; // incl. IRON_DOOR
        return false;
    }

    // --- damage + restore registry --------------------------------------------

    /** Break a window/door: remember its data, set it to air, punch through. */
    private void breakThrough(Block block) {
        Location loc = block.getLocation();
        BlockData original = block.getBlockData();
        boolean woody = block.getType().name().endsWith("_DOOR")
            || block.getType().name().endsWith("_TRAPDOOR");
        block.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5),
            30, 0.3, 0.3, 0.3, 0.0, original);
        block.setType(Material.AIR, false);
        block.getWorld().playSound(loc, woody ? Sound.BLOCK_WOOD_BREAK : Sound.BLOCK_GLASS_BREAK, 1.0f, 1.0f);
        registry.put(loc, new Restore(Kind.BROKEN, original, System.currentTimeMillis() + RESTORE_MS));
    }

    /** Crack (don't break) a solid wall: show the vanilla crack overlay to
     *  nearby players and register it for later clearing. */
    private void crackWall(Block block) {
        Location loc = block.getLocation();
        for (Player p : block.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= CRACK_RANGE * CRACK_RANGE) {
                p.sendBlockDamage(loc, CRACK_STAGE);
            }
        }
        block.getWorld().playSound(loc, Sound.BLOCK_STONE_HIT, 0.8f, 0.9f);
        // A crack has no BlockData to restore; keep any existing BROKEN entry.
        registry.merge(loc, new Restore(Kind.CRACKED, null, System.currentTimeMillis() + RESTORE_MS),
            (existing, incoming) -> existing.kind() == Kind.BROKEN
                ? new Restore(Kind.BROKEN, existing.original(), incoming.expireEpochMs())
                : incoming);
    }

    /**
     * Repeating restore sweep (wire in onEnable via runTaskTimer). Puts back
     * broken blocks whose spot is still air and clears expired crack overlays.
     */
    public void restoreTick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Location, Restore>> it = registry.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Location, Restore> e = it.next();
            if (e.getValue().expireEpochMs() > now) continue;
            applyRestore(e.getKey(), e.getValue());
            it.remove();
        }
    }

    /** Restore everything now (called from LabraPlugin.onDisable). */
    public void shutdown() {
        for (Map.Entry<Location, Restore> e : registry.entrySet()) {
            applyRestore(e.getKey(), e.getValue());
        }
        registry.clear();
    }

    private void applyRestore(Location loc, Restore r) {
        if (loc.getWorld() == null) return;
        if (r.kind() == Kind.BROKEN) {
            if (loc.getBlock().getType() == Material.AIR && r.original() != null) {
                loc.getBlock().setBlockData(r.original(), false);
            }
        } else { // CRACKED: clear the overlay for anyone in range
            for (Player p : loc.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= CRACK_RANGE * CRACK_RANGE) {
                    p.sendBlockDamage(loc, 0f);
                }
            }
        }
    }
}
