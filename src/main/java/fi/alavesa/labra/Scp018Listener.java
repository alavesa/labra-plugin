package fi.alavesa.labra;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SCP-018, the Super Ball. Thrown, it starts as a light hop and does not stop:
 * every bounce off a surface multiplies its speed by sqrt(2), and since bounce
 * HEIGHT scales with speed squared, each bounce is twice as high as the last -
 * a gentle first hop that escalates into chaos over ~9 bounces before the cap.
 *
 * It only smashes through glass, panes, doors and trapdoors once it has built
 * up enough energy ({@link #BREAK_MIN_SPEED}); a slow ball just bounces off
 * them. Solid walls get a progressively deepening, non-destructive crack
 * overlay. Everything it breaks or cracks is restored after five minutes (or on
 * shutdown). Black stained glass is a deliberate wall: it cracks, never breaks.
 *
 * Implementation: the ball is NOT a projectile - a thrown egg gets discarded by
 * Minecraft's own egg-hit mechanic the instant it touches a wall (that was the
 * "despawns after 2 seconds" bug). Instead we consume the thrown egg and drive
 * an ItemDisplay ourselves in {@link #ballTick}: our own gravity, a swept
 * ray-cast for collision, reflection + escalation, and speed-gated breaking, all
 * in one place. An ItemDisplay never self-despawns, so it lasts its full life.
 */
public final class Scp018Listener implements Listener {

    private static final double INITIAL_SPEED = 0.40;  // blocks/tick, first light hop
    /** After a bounce, at least this fraction of the speed must point AWAY from
     *  the wall, so the ball always clears the surface instead of micro-bouncing
     *  in place / grinding into a corner. */
    private static final double MIN_OUTWARD = 0.55;
    private static final double MAX_SPEED = 4.0;        // blocks/tick, cap
    /** Speed multiplier per bounce. Height ~ speed^2, so x sqrt(2) DOUBLES the
     *  bounce height each time. */
    private static final double BOUNCE_GROWTH = 1.41421356;
    /** Gravity applied to the ball each tick (blocks/tick^2). */
    private static final double GRAVITY = 0.08;
    /** Below this speed the ball just bounces off glass/doors; at or above it,
     *  it smashes through. So a slow ball can't break windows - only a fast one. */
    private static final double BREAK_MIN_SPEED = 0.85;
    private static final long LIFETIME_MS = 180_000L;  // 3 minutes of bouncing
    private static final long RESTORE_MS = 300_000L;   // restore 5 minutes after damage
    private static final double CRACK_RANGE = 24.0;    // who sees the crack overlay
    private static final float CRACK_STEP = 0.12f;     // crack deepens this much per repeat hit

    private final LabraPlugin plugin;

    /** Live balls: ItemDisplay UUID -> its authoritative motion state. */
    private final Map<UUID, Ball> balls = new ConcurrentHashMap<>();

    /** Restore registry: block location -> pending restore (broken block data to
     *  put back, or a crack overlay to clear). */
    private final Map<Location, Restore> registry = new ConcurrentHashMap<>();

    /** How many times the ball has cracked each block, so repeat hits deepen the
     *  crack; cleared when the wall heals. */
    private final Map<Location, Integer> crackLevel = new ConcurrentHashMap<>();

    /** One ball's state. We own the velocity + position entirely. */
    private static final class Ball {
        Vector vel;           // blocks/tick, authoritative
        double bounceSpeed;   // the launch speed of the last bounce (escalates)
        final long bornMs;
        final UUID shooterId; // for damage attribution (nullable)
        int damageCd;         // ticks until it can strike an entity again
        Ball(Vector vel, double bounceSpeed, long bornMs, UUID shooterId) {
            this.vel = vel; this.bounceSpeed = bounceSpeed;
            this.bornMs = bornMs; this.shooterId = shooterId;
        }
    }

    private enum Kind { BROKEN, CRACKED }

    private record Restore(Kind kind, BlockData original, long expireEpochMs) {}

    public Scp018Listener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isBallItem(ItemStack item) {
        if (item == null || item.getType() != Material.EGG || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp018");
    }

    /** The player throws the 018 egg; we swap the doomed projectile for an
     *  ItemDisplay we drive ourselves. */
    @EventHandler
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        if (!isBallItem(egg.getItem())) return;
        ItemStack item = egg.getItem().clone();
        item.setAmount(1);
        Location loc = egg.getLocation();
        World world = loc.getWorld();
        Vector dir = egg.getVelocity();
        ProjectileSource shooter = egg.getShooter();
        UUID shooterId = shooter instanceof Entity se ? se.getUniqueId() : null;
        egg.remove();   // kill the projectile before it can be discarded on a wall

        Vector v = (dir.lengthSquared() > 1.0e-9 ? dir.clone().normalize()
            : new Vector(0, -1, 0)).multiply(INITIAL_SPEED);
        ItemDisplay disp = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setTeleportDuration(1);   // interpolate each tick -> smooth motion
            d.setPersistent(false);
            d.setGravity(false);
        });
        balls.put(disp.getUniqueId(), new Ball(v, INITIAL_SPEED, System.currentTimeMillis(), shooterId));
        world.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.6f);
    }

    /**
     * The whole simulation, once per tick. For each ball: apply gravity, strike
     * any body it touches, then swept-ray-cast along its velocity. Breakable
     * glass/door at speed smashes through; anything else is a BOUNCE - reflect
     * off the face, multiply the speed (bounce height doubles), crack a solid
     * wall, and land just shy of the surface. Then teleport the display.
     */
    public void ballTick() {
        if (balls.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Ball>> it = balls.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ball> e = it.next();
            Entity ent = plugin.getServer().getEntity(e.getKey());
            if (!(ent instanceof ItemDisplay disp) || disp.isDead() || !disp.isValid()) { it.remove(); continue; }
            Ball ball = e.getValue();
            if (now - ball.bornMs > LIFETIME_MS) { disp.remove(); it.remove(); continue; }

            World world = disp.getWorld();
            Location pos = disp.getLocation();

            // Un-stick: if it ever ends a tick inside a solid, non-breakable
            // block, eject it upward so it can never grind in place forever.
            Material hereMat = pos.getBlock().getType();
            if (hereMat.isSolid() && !isBreakable(hereMat)) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                ball.vel = new Vector(r.nextDouble(-0.3, 0.3), 1.0, r.nextDouble(-0.3, 0.3))
                    .normalize().multiply(Math.max(ball.bounceSpeed, INITIAL_SPEED));
                disp.teleport(pos.clone().add(0, 1.0, 0));
                continue;
            }

            ball.vel.setY(ball.vel.getY() - GRAVITY);
            double speed = ball.vel.length();
            if (speed > MAX_SPEED) { ball.vel.multiply(MAX_SPEED / speed); }

            // strike a body it's touching (short cooldown so it doesn't shred),
            // and bounce off it, escalating.
            if (ball.damageCd > 0) ball.damageCd--;
            if (ball.damageCd == 0) {
                for (Entity near : world.getNearbyEntities(pos, 0.7, 0.7, 0.7)) {
                    if (!(near instanceof LivingEntity living)) continue;
                    if (near.getUniqueId().equals(ball.shooterId)) continue;
                    double dmg = Math.max(2.0, ball.bounceSpeed * 6.0);
                    LivingEntity src = ball.shooterId != null
                        && plugin.getServer().getEntity(ball.shooterId) instanceof LivingEntity le ? le : null;
                    if (src != null) living.damage(dmg, src); else living.damage(dmg);
                    Vector away = pos.toVector().subtract(living.getLocation().toVector());
                    if (away.lengthSquared() < 1.0e-6) away = new Vector(0, 1, 0);
                    away.setY(Math.abs(away.getY()) + 0.3);
                    ball.bounceSpeed = Math.min(MAX_SPEED, ball.bounceSpeed * BOUNCE_GROWTH);
                    ball.vel = away.normalize().multiply(ball.bounceSpeed);
                    world.playSound(pos, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.6f);
                    ball.damageCd = 8;
                    break;
                }
            }

            speed = ball.vel.length();
            Location target = pos.clone().add(ball.vel);
            if (speed > 1.0e-4) {
                Vector dir = ball.vel.clone().multiply(1.0 / speed);
                RayTraceResult hit = world.rayTraceBlocks(pos, dir, speed + 0.1,
                    FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) {
                    Block b = hit.getHitBlock();
                    Material m = b.getType();
                    BlockFace face = hit.getHitBlockFace();
                    if (isBreakable(m) && ball.bounceSpeed >= BREAK_MIN_SPEED) {
                        breakThrough(b);   // enough energy: smash through, keep going
                    } else {
                        if (!isBreakable(m)) crackWall(b);
                        else world.playSound(b.getLocation(), Sound.BLOCK_GLASS_HIT, 0.5f, 1.4f);
                        Vector normal = face != null ? face.getDirection() : dir.clone().multiply(-1);
                        Vector reflected = dir.clone().subtract(
                            normal.clone().multiply(2 * dir.dot(normal)));
                        ThreadLocalRandom rng = ThreadLocalRandom.current();
                        reflected.add(new Vector(rng.nextDouble(-0.15, 0.15),
                            rng.nextDouble(-0.1, 0.15), rng.nextDouble(-0.15, 0.15)));
                        // GUARANTEE it leaves the surface: force a strong outward
                        // (along-normal) component so it can't graze/stick/corner-grind.
                        double along = reflected.dot(normal);
                        if (along < MIN_OUTWARD) {
                            reflected.add(normal.clone().multiply(MIN_OUTWARD - along));
                        }
                        if (reflected.lengthSquared() < 1.0e-9) reflected = normal.clone();
                        ball.bounceSpeed = Math.min(MAX_SPEED, ball.bounceSpeed * BOUNCE_GROWTH);
                        ball.vel = reflected.normalize().multiply(ball.bounceSpeed);
                        // land well clear of the wall so next tick doesn't re-hit it
                        target = hit.getHitPosition().toLocation(world)
                            .add(normal.clone().multiply(0.3));
                        world.playSound(target, Sound.BLOCK_NOTE_BLOCK_HAT,
                            (float) Math.min(1.0, 0.3 + ball.bounceSpeed * 0.3), 1.8f);
                    }
                }
            }
            target.setDirection(ball.vel.lengthSquared() > 1.0e-9 ? ball.vel : new Vector(0, 0, 1));
            disp.teleport(target);
        }
    }

    // --- breakable / solid classification -------------------------------------

    /** Glass, glass panes, doors and trapdoors the ball can punch through -
     *  EXCEPT black stained glass and its pane, deliberate walls (solid). */
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

    /** Crack (don't break) a solid wall: a hairline on the first hit, deepening
     *  toward nearly-shattered with each repeat strike on the same block. */
    private void crackWall(Block block) {
        Location loc = block.getLocation();
        int level = crackLevel.merge(loc, 1, Integer::sum);
        float progress = (float) Math.min(0.95, 0.1 + (level - 1) * CRACK_STEP);
        for (Player p : block.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= CRACK_RANGE * CRACK_RANGE) {
                p.sendBlockDamage(loc, progress);
            }
        }
        float pitch = Math.max(0.5f, 0.9f - level * 0.05f);
        block.getWorld().playSound(loc, Sound.BLOCK_STONE_HIT, 0.8f, pitch);
        registry.merge(loc, new Restore(Kind.CRACKED, null, System.currentTimeMillis() + RESTORE_MS),
            (existing, incoming) -> existing.kind() == Kind.BROKEN
                ? new Restore(Kind.BROKEN, existing.original(), incoming.expireEpochMs())
                : incoming);
    }

    /** Repeating restore sweep: put back broken blocks (if still air) and clear
     *  expired crack overlays. */
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
        // despawn any live balls so they don't linger as orphan displays
        for (UUID id : balls.keySet()) {
            Entity ent = plugin.getServer().getEntity(id);
            if (ent != null) ent.remove();
        }
        registry.clear();
        balls.clear();
        crackLevel.clear();
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
        crackLevel.remove(loc);   // a healed wall starts fresh next time
    }
}
