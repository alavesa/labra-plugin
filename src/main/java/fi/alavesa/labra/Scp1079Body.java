package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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
 * SCP-1079's body-horror side. Chewing more than one gum leaves PINK spots stuck to your body
 * (pink-concrete {@link BlockDisplay}s that ride along, like the ID card's hologram); one more spot
 * per gum. Chew too many and it kills you, splattering a big harmless mess of SCP-1079 on the floor
 * that only SLOWS anyone who steps in it. The chew count lives in a player PDC so the spots survive
 * relogs and deaths (only an overdose - the thing that kills you - wipes it clean again).
 */
public final class Scp1079Body implements Listener, Runnable {

    private static final String SPOT_TAG = "lab.scp1079spot";
    private static final String MESS_TAG = "lab.scp1079mess";
    private static final BlockData PINK = Material.PINK_CONCRETE.createBlockData();

    private final LabraPlugin plugin;
    /** Live spot displays per player, so we can move and clean them up. */
    private final Map<UUID, List<BlockDisplay>> spots = new ConcurrentHashMap<>();
    /** Active floor messes, expired lazily on the slow tick. */
    private final List<Mess> messes = new ArrayList<>();

    Scp1079Body(LabraPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ config knobs
    private int lethalChews()   { return Math.max(2, plugin.getConfig().getInt("scp1079.lethal-chews", 6)); }
    private int messLifetime()  { return Math.max(5, plugin.getConfig().getInt("scp1079.mess-lifetime-seconds", 300)); }
    private double messRadius()  { return Math.max(0.5, plugin.getConfig().getDouble("scp1079.mess-radius", 2.5)); }
    private int messSlowLevel() { return Math.max(0, plugin.getConfig().getInt("scp1079.mess-slow-amplifier", 1)); }

    private org.bukkit.NamespacedKey chewsKey() { return plugin.keyOf("scp1079_chews"); }

    private int chews(Player p) {
        return p.getPersistentDataContainer().getOrDefault(chewsKey(), PersistentDataType.INTEGER, 0);
    }
    private void setChews(Player p, int n) {
        p.getPersistentDataContainer().set(chewsKey(), PersistentDataType.INTEGER, n);
    }

    // ------------------------------------------------------------------ chewing

    /** Called from {@link Scp1079Listener} after a gum is chewed. Returns true if it was the lethal one. */
    public boolean onChew(Player p) {
        int c = chews(p) + 1;
        setChews(p, c);
        if (c >= lethalChews()) { overdose(p); return true; }
        if (c >= 2) rebuildSpots(p);   // the second gum is the first visible spot
        return false;
    }

    /** Kill the player and splatter a harmless slowing mess where they stood. */
    private void overdose(Player p) {
        Location loc = p.getLocation();
        clearSpots(p);
        setChews(p, 0);
        spawnMess(loc);
        p.getWorld().playSound(loc, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.6f);
        p.getWorld().playSound(loc, Sound.ENTITY_PLAYER_HURT, 1.0f, 0.7f);
        p.sendMessage(Component.text("One gum too many. SCP-1079 takes you apart.", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, true));
        p.setHealth(0.0);
    }

    // ------------------------------------------------------------------ spots that ride the body

    /** Rebuild a player's spots so there are exactly (chews - 1) of them. */
    private void rebuildSpots(Player p) {
        clearSpots(p);
        int want = Math.min(chews(p) - 1, lethalChews() - 1);
        if (want <= 0) return;
        List<BlockDisplay> list = new ArrayList<>(want);
        for (int i = 0; i < want; i++) list.add(spawnSpot(p, i));
        spots.put(p.getUniqueId(), list);
    }

    private BlockDisplay spawnSpot(Player p, int i) {
        float s = 0.26f + (i % 3) * 0.05f;                 // a little size variety
        double angle = i * 2.399963;                       // golden angle - spreads spots around the body
        // a fixed tilt so the cube looks stuck on at an angle, centred on the entity origin
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
        synchronized (messes) {
            messes.add(new Mess(w.getUID(), at.getX(), at.getY(), at.getZ(), rad,
                System.currentTimeMillis() + messLifetime() * 1000L, parts));
        }
    }

    /** Slow anyone standing in a mess; expire old messes. Scheduled every ~half second. */
    public void slowTick() {
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
                for (Player p : w.getPlayers()) {
                    double dx = p.getLocation().getX() - m.x, dz = p.getLocation().getZ() - m.z;
                    double dy = Math.abs(p.getLocation().getY() - m.y);
                    if (dy <= 1.2 && dx * dx + dz * dz <= m.radius * m.radius) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, messSlowLevel(), true, false, false));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ follow tick

    @Override
    public void run() {
        for (Map.Entry<UUID, List<BlockDisplay>> e : spots.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            List<BlockDisplay> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                BlockDisplay d = list.get(i);
                if (d == null || d.isDead()) continue;
                d.teleport(bodyPoint(p, i));
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler public void onJoin(PlayerJoinEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { restoreSoon(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { clearSpots(e.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { clearSpots(e.getEntity()); }

    /** Recreate spots a tick later (the player's world/location has settled by then). */
    private void restoreSoon(Player p) {
        UUID id = p.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online != null && online.isOnline() && chews(online) >= 2) rebuildSpots(online);
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
