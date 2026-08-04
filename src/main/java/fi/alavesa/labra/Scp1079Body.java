package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCP-1079's toll. Chewing its gum is counted per SITTING (the count resets after an hour of playtime
 * without one, or when SCP-500 cures you). The first gum is mild; each one after blisters the skin
 * worse - pink blisters swell on the body (BlockDisplays that ride along and GROW, up to a limit),
 * dragging you down, dripping pink blood, building a fever. Chewing itself never quite kills you (like
 * poison it stops at half a heart); but from the fourth gum you slowly bleed OUT and die. Dying of it
 * leaves a small, compact, pink-smoking puddle that only SLOWS anyone who steps in it. SCP-500 heals
 * the blisters away GRADUALLY (they shrink and vanish). A death by any other cause clears it at once.
 */
public final class Scp1079Body implements Listener, Runnable {

    private static final String SPOT_TAG = "lab.scp1079spot";
    private static final String MESS_TAG = "lab.scp1079mess";
    private static final BlockData PINK = Material.PINK_CONCRETE.createBlockData();
    private static final Color PINK_RGB = Color.fromRGB(255, 105, 180);
    private static final double HEART = 2.0;
    private static final double MIN_HP = 1.0;      // chew damage floors here (half a heart, poison-style)
    private static final double MAX_BLISTER = 0.55; // hard cap on how big a blister can grow

    private final LabraPlugin plugin;
    private final Map<UUID, Blisters> blisters = new ConcurrentHashMap<>();
    private final List<Mess> messes = new ArrayList<>();
    private int upkeepTick;

    Scp1079Body(LabraPlugin plugin) { this.plugin = plugin; }

    /** A player's blister cluster: the displays, their current (animating) size, and whether SCP-500
     *  is currently shrinking them away. */
    private static final class Blisters {
        final List<BlockDisplay> parts = new ArrayList<>();
        double scale = 0.05;      // starts tiny and grows in
        boolean curing = false;   // SCP-500 shrinking them out of existence
    }

    // ------------------------------------------------------------------ fever (felt, not shown)
    private void applyFever(Player p, int c) {
        if (c < 2) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, Math.min(c - 2, 2), true, false, false));
        if (c >= 4) p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 60, 0, true, false, false));
    }

    private void clearFever(Player p) {
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.HUNGER);
    }

    // ------------------------------------------------------------------ config knobs
    private int    sittingResetTicks() { return Math.max(1, plugin.getConfig().getInt("scp1079.sitting-reset-minutes", 60)) * 60 * 20; }
    private int    messLifetime()      { return Math.max(5, plugin.getConfig().getInt("scp1079.mess-lifetime-seconds", 300)); }
    private double messRadius()        { return Math.max(0.4, plugin.getConfig().getDouble("scp1079.mess-radius", 1.2)); }
    private int    messSlowLevel()     { return Math.max(0, plugin.getConfig().getInt("scp1079.mess-slow-amplifier", 1)); }

    private org.bukkit.NamespacedKey chewsKey() { return plugin.keyOf("scp1079_chews"); }
    private org.bukkit.NamespacedKey lastPlayKey() { return plugin.keyOf("scp1079_last_play"); }

    private int chews(Player p) { return p.getPersistentDataContainer().getOrDefault(chewsKey(), PersistentDataType.INTEGER, 0); }
    private void setChews(Player p, int n) { p.getPersistentDataContainer().set(chewsKey(), PersistentDataType.INTEGER, n); }
    private int playTicks(Player p) { return p.getStatistic(Statistic.PLAY_ONE_MINUTE); }

    // ------------------------------------------------------------------ chewing

    /** Called after a gum is chewed. Runs the whole per-gum escalation for this sitting. */
    public void onChew(Player p) {
        int now = playTicks(p);
        int last = p.getPersistentDataContainer().getOrDefault(lastPlayKey(), PersistentDataType.INTEGER, now);
        if (chews(p) > 0 && now - last > sittingResetTicks()) setChews(p, 0);
        p.getPersistentDataContainer().set(lastPlayKey(), PersistentDataType.INTEGER, now);

        int c = chews(p) + 1;
        setChews(p, c);
        applyFever(p, c);

        switch (Math.min(c, 4)) {
            case 1 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 0, true, false, true));
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.7f, 1.3f);
                say(p, "You chew on the gum and feel tingly");
            }
            case 2 -> {
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.8f, 1.0f);
                p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.4f);
                say(p, "You chew on the gum and your skin grows blisters");
            }
            case 3 -> {
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.9f, 0.9f);
                say(p, "You chew on the gum and your skin splits and weeps");
            }
            default -> {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.7f);
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 1.0f, 0.8f);
                say(p, "You chew on the gum and you start bleeding");
            }
        }
        hurtCapped(p, HEART);   // the gum itself hurts but can NOT kill - it floors at half a heart
        if (c >= 2 && !p.isDead()) ensureBlisters(p);   // blisters appear at gum two and grow from there
    }

    /** Non-lethal harm: like poison, it drains health but never below half a heart. */
    private void hurtCapped(Player p, double amount) {
        p.setHealth(Math.max(MIN_HP, p.getHealth() - amount));
    }

    /** Lethal bleed: this is the slow exsanguination from four-plus gums; it CAN reach zero. */
    private void bleedLethal(Player p, double amount) {
        double nh = p.getHealth() - amount;
        if (nh <= 0.0) p.setHealth(0.0); else p.setHealth(nh);
    }

    private void say(Player p, String text) {
        ActionBars.message(p, Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }

    /** SCP-500 cures SCP-1079: the sitting count and fever clear at once, but the blisters shrink
     *  AWAY gradually (the upkeep animates them down to nothing). */
    public void cure(Player p) {
        setChews(p, 0);
        clearFever(p);
        Blisters b = blisters.get(p.getUniqueId());
        if (b != null) b.curing = true;
    }

    // ------------------------------------------------------------------ blisters that ride the body

    /** Make sure the player has one blister per gum (capped), without disturbing the ones already
     *  grown - new blisters start small and grow in with the rest. */
    private void ensureBlisters(Player p) {
        int want = Math.min(chews(p), 6);
        Blisters b = blisters.computeIfAbsent(p.getUniqueId(), k -> new Blisters());
        b.curing = false;
        while (b.parts.size() < want) b.parts.add(spawnSpot(p, b.parts.size(), b.scale));
    }

    private BlockDisplay spawnSpot(Player p, int i, double s) {
        BlockDisplay d = p.getWorld().spawn(bodyPoint(p, i), BlockDisplay.class, bd -> {
            bd.setBlock(PINK);
            bd.setBrightness(new Display.Brightness(15, 15));
            bd.setTeleportDuration(2);
            bd.setPersistent(false);
            bd.addScoreboardTag(SPOT_TAG);
        });
        d.setTransformation(transformFor(i, s));
        return d;
    }

    /** The centred, tilted transform for blister i at size s (rotation is fixed per index). */
    private Transformation transformFor(int i, double s) {
        AxisAngle4f rot = new AxisAngle4f((float) (i * 2.399963), 0.5774f, 0.5774f, 0.5774f);
        Vector3f half = new Vector3f((float) (s * 0.5), (float) (s * 0.5), (float) (s * 0.5));
        Vector3f centre = new Quaternionf(rot).transform(new Vector3f(half));
        return new Transformation(new Vector3f(-centre.x, -centre.y, -centre.z), rot,
            new Vector3f((float) s, (float) s, (float) s), new AxisAngle4f(0, 0, 0, 1));
    }

    /** Step a blister cluster's size toward its goal and animate it (grow toward the cap, or - while
     *  curing - shrink to nothing and disappear). */
    private void animateBlisters(Player p, Blisters b, int c) {
        double target = b.curing ? 0.0 : Math.min(MAX_BLISTER, 0.18 + (c - 1) * 0.10);
        if (Math.abs(b.scale - target) < 0.001) return;
        double step = b.curing ? 0.04 : 0.03;
        b.scale = b.scale < target ? Math.min(target, b.scale + step) : Math.max(target, b.scale - step);
        for (int i = 0; i < b.parts.size(); i++) {
            BlockDisplay d = b.parts.get(i);
            if (d == null || d.isDead()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);      // smoothly ease to the new size over the upkeep interval
            d.setTransformation(transformFor(i, b.scale));
        }
        if (b.curing && b.scale <= 0.06) removeBlisters(p.getUniqueId());
    }

    /** World position for blister i, rotated with the player's facing so it stays on the same body part. */
    private Location bodyPoint(Player p, int i) {
        double angle = i * 2.399963;
        double r = 0.30;
        double h = 0.45 + ((i * 0.37) % 1.05);
        double lx = Math.cos(angle) * r, lz = Math.sin(angle) * r;
        double yaw = Math.toRadians(p.getLocation().getYaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        Location base = p.getLocation();
        return new Location(p.getWorld(),
            base.getX() + (lx * cos - lz * sin), base.getY() + h, base.getZ() + (lx * sin + lz * cos));
    }

    private void removeBlisters(UUID id) {
        Blisters b = blisters.remove(id);
        if (b != null) b.parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
    }

    // ------------------------------------------------------------------ the floor puddle

    private void spawnMess(Location at) {
        World w = at.getWorld();
        double rad = messRadius();
        List<BlockDisplay> parts = new ArrayList<>();
        int blobs = (int) Math.round(5 + rad * 4);      // small and compact
        for (int i = 0; i < blobs; i++) {
            double a = i * 2.399963;
            double dist = rad * Math.sqrt((i + 0.5) / blobs) * 0.7;   // packed tight toward the centre
            double px = at.getX() + Math.cos(a) * dist;
            double pz = at.getZ() + Math.sin(a) * dist;
            float sx = 0.45f + (i % 4) * 0.12f, sz = 0.45f + (i % 3) * 0.12f;
            AxisAngle4f rot = new AxisAngle4f((float) a, 0, 1, 0);
            Vector3f half = new Vector3f(sx * 0.5f, 0.05f, sz * 0.5f);
            Vector3f centre = new Quaternionf(rot).transform(new Vector3f(half));
            Transformation xf = new Transformation(
                new Vector3f(-centre.x, -centre.y, -centre.z), rot, new Vector3f(sx, 0.1f, sz), new AxisAngle4f(0, 0, 0, 1));
            Location loc = new Location(w, px, at.getY() + 0.06, pz);
            BlockDisplay d = w.spawn(loc, BlockDisplay.class, bd -> {
                bd.setBlock(PINK);
                bd.setBrightness(new Display.Brightness(15, 15));
                bd.setPersistent(false);
                bd.addScoreboardTag(MESS_TAG);
            });
            d.setTransformation(xf);
            parts.add(d);
        }
        w.playSound(at, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.6f);
        synchronized (messes) {
            messes.add(new Mess(w.getUID(), at.getX(), at.getY(), at.getZ(), rad,
                System.currentTimeMillis() + messLifetime() * 1000L, parts));
        }
    }

    /** Keep brushing the nearest puddle near a point - called every couple of ticks while the mop is
     *  HELD, so a steady hold scrubs it away gradually. Each call rubs off a blob or two. Returns
     *  0 = nothing under the mop, 1 = still scrubbing, 2 = that call cleared the last of it. */
    public int brushClean(Location center, double radius) {
        double r2 = radius * radius;
        synchronized (messes) {
            Mess best = null; double bestD = Double.MAX_VALUE;
            for (Mess m : messes) {
                if (!m.world.equals(center.getWorld().getUID())) continue;
                double dx = m.x - center.getX(), dz = m.z - center.getZ(), d = dx * dx + dz * dz;
                if (d <= r2 && d < bestD) { bestD = d; best = m; }
            }
            if (best == null) return 0;
            for (int n = 0; n < 2 && !best.parts.isEmpty(); n++) {   // rub off a blob or two per tick
                BlockDisplay d = best.parts.remove(best.parts.size() - 1);
                if (d != null && !d.isDead()) d.remove();
            }
            if (best.parts.isEmpty()) { messes.remove(best); return 2; }
            return 1;
        }
    }

    // ------------------------------------------------------------------ upkeep tick (every ~half second)

    public void slowTick() {
        upkeepTick++;
        long now = System.currentTimeMillis();
        synchronized (messes) {
            for (Iterator<Mess> it = messes.iterator(); it.hasNext(); ) {
                Mess m = it.next();
                if (now > m.expireMs) {
                    m.parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
                    it.remove();
                    continue;
                }
                World w = plugin.getServer().getWorld(m.world);
                if (w == null) continue;
                // pink smoke curling up off the goo
                w.spawnParticle(Particle.DUST, m.x, m.y + 0.3, m.z, 4, m.radius * 0.4, 0.25, m.radius * 0.4, 0.0,
                    new Particle.DustOptions(PINK_RGB, 1.6f));
                for (Player p : w.getPlayers()) {
                    double dx = p.getLocation().getX() - m.x, dz = p.getLocation().getZ() - m.z;
                    if (Math.abs(p.getLocation().getY() - m.y) <= 1.2 && dx * dx + dz * dz <= m.radius * m.radius)
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, messSlowLevel(), true, false, false));
                }
            }
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Blisters b = blisters.get(p.getUniqueId());
            int c = chews(p);
            if (b != null && !p.isDead()) animateBlisters(p, b, c);   // grow/shrink even while curing (c==0)
            if (c <= 0 || p.isDead() || p.getHealth() <= 0.0) continue;
            applyFever(p, c);
            if (c >= 2) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, c >= 4 ? 1 : 0, true, false, false)); // blisters drag you
            drip(p, c);
            if (c == 3 && upkeepTick % 8 == 0) hurtCapped(p, 0.5);     // a sick trickle - never lethal at three
            if (c >= 4 && upkeepTick % 6 == 0) bleedLethal(p, 1.0);    // slow bleed-out to death from four
        }
    }

    private void drip(Player p, int c) {
        World w = p.getWorld();
        int intensity = Math.min(c, 4);
        int spotCount = Math.min(Math.max(c - 1, 1), 6);
        for (int i = 0; i < spotCount; i++)
            w.spawnParticle(Particle.FALLING_DUST, bodyPoint(p, i), intensity, 0.05, 0.05, 0.05, 0, PINK);
        w.spawnParticle(Particle.FALLING_DUST, p.getLocation().add(0, 0.1, 0), intensity * 2, 0.25, 0.02, 0.25, 0, PINK);
    }

    // ------------------------------------------------------------------ blister-follow tick (every tick)

    @Override
    public void run() {
        for (Map.Entry<UUID, Blisters> e : blisters.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            List<BlockDisplay> list = e.getValue().parts;
            for (int i = 0; i < list.size(); i++) {
                BlockDisplay d = list.get(i);
                if (d != null && !d.isDead()) d.teleport(bodyPoint(p, i));
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler public void onJoin(PlayerJoinEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { removeBlisters(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (chews(p) >= 4) spawnMess(p.getLocation());
        removeBlisters(p.getUniqueId());
        setChews(p, 0);
        clearFever(p);
    }

    private void restoreSoon(Player p) {
        UUID id = p.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null || !online.isOnline()) return;
            int c = chews(online);
            if (c >= 2) ensureBlisters(online);
            applyFever(online, c);
        }, 5L);
    }

    public void shutdown() {
        blisters.values().forEach(b -> b.parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); }));
        blisters.clear();
        synchronized (messes) {
            messes.forEach(m -> m.parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); }));
            messes.clear();
        }
        sweepOrphans();
    }

    public void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(SPOT_TAG) || e.getScoreboardTags().contains(MESS_TAG)) e.remove();
    }

    /** A floor puddle: mutable so the mop can scrub its blobs away a few at a time. */
    private static final class Mess {
        final UUID world; final double x, y, z, radius; final long expireMs;
        final List<BlockDisplay> parts;
        Mess(UUID world, double x, double y, double z, double radius, long expireMs, List<BlockDisplay> parts) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.radius = radius;
            this.expireMs = expireMs; this.parts = parts;
        }
    }
}
