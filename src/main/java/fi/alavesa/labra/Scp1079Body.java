package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
 * SCP-1079's toll. Chewing its gum is counted per SITTING (a run of gums; the count resets after an
 * hour of playtime without one, or when SCP-500 cures you). The first gum is mild, but each one after
 * blisters the skin worse - pink-concrete spots grow on the body (BlockDisplays that ride along like
 * the ID-card hologram), pink blood drips off, a fever climbs, and from the fourth gum the subject
 * bleeds out. Dying of it splatters a big harmless mess of SCP-1079 that only SLOWS anyone who steps
 * in it. A death by any OTHER cause just clears the whole thing - it never follows you past the grave.
 */
public final class Scp1079Body implements Listener, Runnable {

    private static final String SPOT_TAG = "lab.scp1079spot";
    private static final String MESS_TAG = "lab.scp1079mess";
    private static final BlockData PINK = Material.PINK_CONCRETE.createBlockData();
    private static final int GUM_COOLDOWN_TICKS = 40;   // 2 seconds between chews (native icon sweep)
    private static final double HEART = 2.0;

    private final LabraPlugin plugin;
    private final Map<UUID, List<BlockDisplay>> spots = new ConcurrentHashMap<>();
    private final List<Mess> messes = new ArrayList<>();
    private int upkeepTick;

    Scp1079Body(LabraPlugin plugin) { this.plugin = plugin; }

    /** The fever: sickly effects that build with the gum count. No temperature readout (that was
     *  removed) - you FEEL it (dizzy, weak, queasy). Re-applied each upkeep so it persists. */
    private void applyFever(Player p, int c) {
        if (c < 2) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, Math.min(c - 2, 2), true, false, false));
        if (c >= 3) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, true, false, false));
        if (c >= 4) p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 60, 0, true, false, false));
    }

    private void clearFever(Player p) {
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.NAUSEA);
        p.removePotionEffect(PotionEffectType.HUNGER);
    }

    // ------------------------------------------------------------------ config knobs
    private int   sittingResetTicks() { return Math.max(1, plugin.getConfig().getInt("scp1079.sitting-reset-minutes", 60)) * 60 * 20; }
    private int   messLifetime()      { return Math.max(5, plugin.getConfig().getInt("scp1079.mess-lifetime-seconds", 300)); }
    private double messRadius()       { return Math.max(0.5, plugin.getConfig().getDouble("scp1079.mess-radius", 2.5)); }
    private int   messSlowLevel()     { return Math.max(0, plugin.getConfig().getInt("scp1079.mess-slow-amplifier", 1)); }

    private org.bukkit.NamespacedKey chewsKey() { return plugin.keyOf("scp1079_chews"); }
    private org.bukkit.NamespacedKey lastPlayKey() { return plugin.keyOf("scp1079_last_play"); }

    private int chews(Player p) { return p.getPersistentDataContainer().getOrDefault(chewsKey(), PersistentDataType.INTEGER, 0); }
    private void setChews(Player p, int n) { p.getPersistentDataContainer().set(chewsKey(), PersistentDataType.INTEGER, n); }
    private int playTicks(Player p) { return p.getStatistic(Statistic.PLAY_ONE_MINUTE); }

    // ------------------------------------------------------------------ chewing

    /** Called after a gum is chewed. Runs the whole per-gum escalation for this sitting. */
    public void onChew(Player p) {
        // New sitting? Reset the count if it's been over an hour of playtime since the last gum.
        int now = playTicks(p);
        int last = p.getPersistentDataContainer().getOrDefault(lastPlayKey(), PersistentDataType.INTEGER, now);
        if (chews(p) > 0 && now - last > sittingResetTicks()) setChews(p, 0);
        p.getPersistentDataContainer().set(lastPlayKey(), PersistentDataType.INTEGER, now);

        int c = chews(p) + 1;
        setChews(p, c);

        applyFever(p, c);   // the fever grows with every gum (dizzy/weak/queasy, no readout)

        switch (Math.min(c, 4)) {
            case 1 -> {
                // The first gum is nearly all upside - a small pick-me-up - but it stings.
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 0, true, false, true));
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.7f, 1.3f);
                say(p, "You chew on the gum and feel tingly");
                bleed(p, HEART);
            }
            case 2 -> {
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.8f, 1.0f);
                p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.4f);
                say(p, "You chew on the gum and your skin grows blisters");
                bleed(p, HEART);
            }
            case 3 -> {
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.9f, 0.9f);
                say(p, "You chew on the gum and your skin splits and weeps");
                bleed(p, HEART);
            }
            default -> {   // 4th gum and beyond: exsanguination
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.7f);
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 1.0f, 0.8f);
                say(p, "You chew on the gum and you start bleeding");
                bleed(p, HEART);
            }
        }
        if (c >= 2 && !p.isDead()) rebuildSpots(p);   // spots appear at gum two, and grow from there
    }

    /** Internal bleeding - ignores armour; dropping to zero triggers a normal (mess-spawning) death. */
    private void bleed(Player p, double amount) {
        double nh = p.getHealth() - amount;
        if (nh <= 0.0) p.setHealth(0.0); else p.setHealth(nh);
    }

    private void say(Player p, String text) {
        ActionBars.message(p, Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }

    /** SCP-500 cures SCP-1079 outright: spots gone, fever gone, sitting count back to zero. */
    public void cure(Player p) {
        if (chews(p) <= 0 && !spots.containsKey(p.getUniqueId())) return;
        clearSpots(p);
        setChews(p, 0);
        clearFever(p);
    }

    // ------------------------------------------------------------------ spots that ride the body

    /** (Re)build a player's spots: count and size both grow with the gum count. */
    private void rebuildSpots(Player p) {
        clearSpots(p);
        int c = chews(p);
        if (c < 2) return;
        int count = Math.min(c, 6);
        float scale = (float) (0.20 + (c - 2) * 0.12);   // small cube at gum 2, bigger every gum after
        List<BlockDisplay> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(spawnSpot(p, i, scale));
        spots.put(p.getUniqueId(), list);
    }

    private BlockDisplay spawnSpot(Player p, int i, float s) {
        double angle = i * 2.399963;                       // golden angle - spreads spots around the body
        AxisAngle4f rot = new AxisAngle4f((float) angle, 0.5774f, 0.5774f, 0.5774f);
        Vector3f half = new Vector3f(s * 0.5f, s * 0.5f, s * 0.5f);
        Vector3f centre = new Quaternionf(rot).transform(new Vector3f(half));
        Transformation xf = new Transformation(
            new Vector3f(-centre.x, -centre.y, -centre.z), rot, new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 1));
        BlockDisplay d = p.getWorld().spawn(bodyPoint(p, i), BlockDisplay.class, bd -> {
            bd.setBlock(PINK);
            bd.setBrightness(new Display.Brightness(15, 15));
            bd.setTeleportDuration(2);                     // smooth catch-up as the player moves
            bd.setPersistent(false);
            bd.addScoreboardTag(SPOT_TAG);
        });
        d.setTransformation(xf);
        return d;
    }

    /** World position for spot i, rotated with the player's facing so it stays on the same body part. */
    private Location bodyPoint(Player p, int i) {
        double angle = i * 2.399963;
        double r = 0.30;
        double h = 0.45 + ((i * 0.37) % 1.05);             // knees up to the shoulders
        double lx = Math.cos(angle) * r, lz = Math.sin(angle) * r;
        double yaw = Math.toRadians(p.getLocation().getYaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        Location base = p.getLocation();
        return new Location(p.getWorld(),
            base.getX() + (lx * cos - lz * sin), base.getY() + h, base.getZ() + (lx * sin + lz * cos));
    }

    private void clearSpots(Player p) {
        List<BlockDisplay> list = spots.remove(p.getUniqueId());
        if (list != null) list.forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
    }

    // ------------------------------------------------------------------ the floor mess

    private void spawnMess(Location at) {
        World w = at.getWorld();
        double rad = messRadius();
        List<BlockDisplay> parts = new ArrayList<>();
        int blobs = (int) Math.round(6 + rad * 4);         // a properly big splat
        for (int i = 0; i < blobs; i++) {
            double a = i * 2.399963;
            double dist = rad * Math.sqrt((i + 0.5) / blobs);   // even area coverage
            double px = at.getX() + Math.cos(a) * dist;
            double pz = at.getZ() + Math.sin(a) * dist;
            float sx = 0.6f + (i % 4) * 0.18f, sz = 0.6f + (i % 3) * 0.2f;
            AxisAngle4f rot = new AxisAngle4f((float) a, 0, 1, 0);   // flat, spun on the ground
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

    // ------------------------------------------------------------------ upkeep tick (every ~half second)

    /** Slow anyone in a mess, drip pink blood off the afflicted, and bleed out the worst cases. */
    public void slowTick() {
        upkeepTick++;
        long now = System.currentTimeMillis();
        // messes: expire, and slow anyone standing in them
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
                for (Player p : w.getPlayers()) {
                    double dx = p.getLocation().getX() - m.x, dz = p.getLocation().getZ() - m.z;
                    if (Math.abs(p.getLocation().getY() - m.y) <= 1.2 && dx * dx + dz * dz <= m.radius * m.radius)
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, messSlowLevel(), true, false, false));
                }
            }
        }
        // afflicted players: pink drip + (worse cases) passive bleed. Never touch the dead.
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            int c = chews(p);
            if (c <= 0 || p.isDead() || p.getHealth() <= 0.0) continue;
            applyFever(p, c);   // keep the fever topped up
            drip(p, c);
            if (upkeepTick % 4 == 0 && c >= 3) bleed(p, c >= 4 ? HEART : 0.5);   // 3 = a trickle, 4+ = bleeding out
        }
    }

    /** Pink blood weeping off the spots and pooling at the feet - heavier the more gums were chewed. */
    private void drip(Player p, int c) {
        World w = p.getWorld();
        int intensity = Math.min(c, 4);
        int spotCount = Math.min(Math.max(c - 1, 1), 6);
        for (int i = 0; i < spotCount; i++)
            w.spawnParticle(Particle.FALLING_DUST, bodyPoint(p, i), intensity, 0.05, 0.05, 0.05, 0, PINK);
        w.spawnParticle(Particle.FALLING_DUST, p.getLocation().add(0, 0.1, 0), intensity * 2, 0.25, 0.02, 0.25, 0, PINK);
    }

    // ------------------------------------------------------------------ spot-follow tick (every tick)

    @Override
    public void run() {
        for (Map.Entry<UUID, List<BlockDisplay>> e : spots.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            List<BlockDisplay> list = e.getValue();
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
    @EventHandler public void onQuit(PlayerQuitEvent e) { clearSpots(e.getPlayer()); }

    /** Death wipes SCP-1079 clean - it must not follow you to respawn. A death while bleeding out
     *  (four-plus gums) splatters the mess; any other death just clears the spots and fever. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (chews(p) >= 4) spawnMess(p.getLocation());
        clearSpots(p);
        setChews(p, 0);
        clearFever(p);
    }

    /** Recreate spots + fever a tick later (the player's world/location has settled by then). */
    private void restoreSoon(Player p) {
        UUID id = p.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null || !online.isOnline()) return;
            int c = chews(online);
            if (c >= 2) rebuildSpots(online);
            applyFever(online, c);
        }, 5L);
    }

    /** Remove every spot/mess we still own, and sweep any orphaned display left in a loaded world. */
    public void shutdown() {
        spots.values().forEach(l -> l.forEach(d -> { if (d != null && !d.isDead()) d.remove(); }));
        spots.clear();
        synchronized (messes) {
            messes.forEach(m -> m.parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); }));
            messes.clear();
        }
        sweepOrphans();
    }

    /** Drop any tagged displays left behind by a crash/reload (we recreate live ones on join). */
    public void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(SPOT_TAG) || e.getScoreboardTags().contains(MESS_TAG)) e.remove();
    }

    private record Mess(UUID world, double x, double y, double z, double radius, long expireMs, List<BlockDisplay> parts) {}
}
