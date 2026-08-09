package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side player RIG: the vanilla player is made invisible and puppeteered by FOUR ItemDisplay
 * parts - one head, one torso, ONE arms part (both arms in a single model) and ONE legs part (both
 * legs in a single model). Four parts instead of six keeps the rig easy to animate as one skeleton in
 * Blockbench. Every tick the server positions the parts on the player and applies the current stance
 * (walk lean, crouch, gun aim), teleporting with a 1-tick interpolation so the vanilla client renders
 * it smoothly - no client mod. The same rig can stand in as a "body double" for Terminal's CCTV.
 *
 * PART MODELS (resource pack):
 *   - HEAD carries the player's own skin (the real player-head model) - nothing else does.
 *   - TORSO / ARMS / LEGS are custom-textured head entities: PLAYER_HEAD + custom_model_data
 *     "&lt;prefix&gt;_torso|arms|legs", which the pack maps to your Blockbench body parts.
 *   - The &lt;prefix&gt; is TEAM-BASED: a guard's body uses "rig_guard_*", etc. (the head always stays
 *     the player's skin). Configure under rig.team-models (group -> model prefix); default "rig".
 *
 * DUAL RENDERING for guns (rare on gun servers, wanted here): while a player holds a gun, a separate
 * gun ItemDisplay is shown on the rig's arms so EVERYONE ELSE sees them holding it in third person. In
 * first person the player just holds the normal Minecraft gun item, whose recoil/reload are animated
 * client-side by the flipbook (animated-texture .mcmeta) method - see CUSTOM-MODELS-AND-ANIMATIONS.md.
 */
public final class PlayerRig implements Listener, Runnable {

    /** The four body parts: head + torso + a single arms model + a single legs model. */
    private static final String[] PARTS = {"head", "torso", "arms", "legs"};
    private static final String TAG = "lab.rigpart";

    private final LabraPlugin plugin;
    private final Map<UUID, Rig> rigs = new ConcurrentHashMap<>();

    /** Parts that can be fine-tuned live with /lab rigtune (head/torso/arms/legs + the gun display). */
    static final String[] TUNABLE = {"head", "torso", "arms", "legs", "gun"};
    static final String[] FIELDS = {"x", "y", "z", "yaw", "pitch", "scale"};
    private final Map<String, Tune> tunes = new java.util.HashMap<>();

    PlayerRig(LabraPlugin plugin) { this.plugin = plugin; loadTunes(); }

    /** Per-part alignment: position offset (x=right, y=up, z=forward), yaw/pitch offset in degrees, and
     *  a uniform scale multiplier. Adjusted in-game and saved to config so the rig lines up perfectly. */
    static final class Tune { double x, y, z, yaw, pitch, scale = 1.0; }

    /** Built-in defaults (also the /lab rigtune reset values). Head yaw 180 so the face points the way
     *  the player faces (a player-head display otherwise renders facing backwards). */
    private static Tune defaults(String part) {
        Tune t = new Tune();
        switch (part) {
            case "head"  -> { t.y = 1.5;  t.yaw = 180; }
            case "torso" -> { t.y = 1.1; }
            case "arms"  -> { t.y = 1.4; }
            case "legs"  -> { t.y = 0.75; }
            case "gun"   -> { t.x = 0.32; t.y = 1.30; t.z = 0.40; }
            default -> { }
        }
        return t;
    }

    private void loadTunes() {
        tunes.clear();
        for (String part : TUNABLE) {
            Tune d = defaults(part);
            String base = "rig.tune." + part + ".";
            Tune t = new Tune();
            t.x     = plugin.getConfig().getDouble(base + "x", d.x);
            t.y     = plugin.getConfig().getDouble(base + "y", d.y);
            t.z     = plugin.getConfig().getDouble(base + "z", d.z);
            t.yaw   = plugin.getConfig().getDouble(base + "yaw", d.yaw);
            t.pitch = plugin.getConfig().getDouble(base + "pitch", d.pitch);
            t.scale = plugin.getConfig().getDouble(base + "scale", d.scale);
            tunes.put(part, t);
        }
    }

    // ---------------------------------------------------------------- live fine-tuning (/lab rigtune)

    static boolean isTunablePart(String part) {
        for (String x : TUNABLE) if (x.equals(part)) return true;
        return false;
    }
    static boolean isField(String field) {
        for (String x : FIELDS) if (x.equals(field)) return true;
        return false;
    }

    /** Set one field of one part - absolute, or relative (nudge) when relative=true - then persist it
     *  and apply it live to every active rig. Returns false for an unknown part/field. */
    public boolean setTune(String part, String field, double value, boolean relative) {
        if (!isTunablePart(part) || !isField(field)) return false;
        Tune t = tunes.get(part);
        double v = relative ? currentField(t, field) + value : value;
        switch (field) {
            case "x" -> t.x = v;
            case "y" -> t.y = v;
            case "z" -> t.z = v;
            case "yaw" -> t.yaw = v;
            case "pitch" -> t.pitch = v;
            case "scale" -> t.scale = v;
        }
        plugin.getConfig().set("rig.tune." + part + "." + field, v);
        plugin.saveConfig();
        for (Rig r : rigs.values()) r.retransform = true;
        return true;
    }

    /** Reset one part (or everything, when part is null/"all") to the built-in defaults. */
    public void resetTune(String part) {
        if (part == null || part.equals("all")) plugin.getConfig().set("rig.tune", null);
        else if (isTunablePart(part)) plugin.getConfig().set("rig.tune." + part, null);
        else return;
        plugin.saveConfig();
        loadTunes();
        for (Rig r : rigs.values()) r.retransform = true;
    }

    private static double currentField(Tune t, String field) {
        return switch (field) {
            case "x" -> t.x;
            case "y" -> t.y;
            case "z" -> t.z;
            case "yaw" -> t.yaw;
            case "pitch" -> t.pitch;
            default -> t.scale;
        };
    }

    /** Current values for one part, or every part when part is null - one line each, for the command. */
    public List<String> tuneStatus(String part) {
        List<String> out = new java.util.ArrayList<>();
        for (String pt : TUNABLE) {
            if (part != null && !part.equals(pt)) continue;
            Tune t = tunes.get(pt);
            out.add(String.format("%-6s x=%.2f y=%.2f z=%.2f yaw=%.0f pitch=%.0f scale=%.2f",
                pt, t.x, t.y, t.z, t.yaw, t.pitch, t.scale));
        }
        return out;
    }

    private static final class Rig {
        final Map<String, ItemDisplay> parts = new java.util.HashMap<>();
        final Map<String, Float> limbNow = new java.util.HashMap<>();      // eased limb angle per part
        final Map<String, Float> limbApplied = new java.util.HashMap<>();  // last angle actually sent
        boolean retransform;  // force a transform re-send next tick (after a tune change)
        ItemDisplay gun;     // the third-person "you're holding a gun" display (dual rendering)
        String gunSig;       // signature of the gun item currently shown, to avoid rebuilding each tick
        String prefix;       // the team model prefix the limbs were last built with
        double phase;        // walk-cycle phase
        double swingAmt;     // current swing amplitude (eases in/out with movement)
        Location last;       // previous location, to measure walk speed
    }

    public boolean hasRig(Player p) { return rigs.containsKey(p.getUniqueId()); }

    /** Turn a player into a puppet: invisible body + four display parts riding it. For now the rig is
     *  left VISIBLE to everyone including its owner, so it can be tested in-game. (Hiding it from the
     *  owner in first person is a later step - the server can't read the F5 camera perspective.) */
    public void spawn(Player p) {
        if (rigs.containsKey(p.getUniqueId())) return;
        p.setInvisible(true);
        Rig r = new Rig();
        r.last = p.getLocation();
        r.prefix = teamModelPrefix(p);
        for (String part : PARTS) r.parts.put(part, spawnPart(p, p.getLocation(), part, r.prefix));
        rigs.put(p.getUniqueId(), r);
        pose(p, r);
    }

    public void despawn(Player p) {
        Rig r = rigs.remove(p.getUniqueId());
        if (r != null) {
            r.parts.values().forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
            if (r.gun != null && !r.gun.isDead()) r.gun.remove();
        }
        if (p.isOnline()) p.setInvisible(false);
    }

    /** Every part is a PLAYER_HEAD (head entity), but ONLY the head carries the player's own skin and so
     *  renders the real player-head model. Torso/arms/legs are custom-textured head entities: no skin,
     *  just a custom_model_data "&lt;prefix&gt;_&lt;part&gt;" the pack maps to a Blockbench body part. */
    private ItemDisplay spawnPart(Player owner, Location at, String part, String prefix) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        applyPartModel(head, owner, part, prefix);
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(head);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);          // 2-tick lerp = smooth follow while updating every tick
            d.setInterpolationDuration(1);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
        });
    }

    /** Head -> player skin (no model); other limbs -> "&lt;prefix&gt;_&lt;part&gt;" custom model, no skin. */
    private void applyPartModel(ItemStack head, Player owner, String part, String prefix) {
        ItemMeta m = head.getItemMeta();
        if (part.equals("head")) {
            if (m instanceof SkullMeta skull) skull.setOwningPlayer(owner);
        } else {
            CustomModelDataComponent cmd = m.getCustomModelDataComponent();
            cmd.setStrings(List.of(prefix + "_" + part));
            m.setCustomModelDataComponent(cmd);
        }
        head.setItemMeta(m);
    }

    /** Team -> limb model prefix. A member of LuckPerms group X has permission "group.X", so a guard
     *  (group.guard) gets the "rig_guard" body while their head stays their own skin. Configure the
     *  mapping under rig.team-models; falls back to a built-in guard example, then rig.default-model. */
    private String teamModelPrefix(Player p) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("rig.team-models");
        if (sec != null) {
            for (String group : sec.getKeys(false))
                if (p.hasPermission("group." + group)) return sec.getString(group, "rig");
        } else if (p.hasPermission("group.guard")) {
            return "rig_guard";
        }
        return plugin.getConfig().getString("rig.default-model", "rig");
    }

    /** Placeholder proportions per part (a real Blockbench model replaces this look). Arms/legs are now
     *  single models spanning both sides, so they're wider than the old per-side parts. */
    private static Vector3f scale(String part) {
        return switch (part) {
            case "head"  -> new Vector3f(1.0f, 1.0f, 1.0f);
            case "torso" -> new Vector3f(1.0f, 1.5f, 0.55f);
            case "arms"  -> new Vector3f(1.1f, 1.4f, 0.5f);
            default      -> new Vector3f(0.8f, 1.5f, 0.5f);   // legs
        };
    }

    // ---------------------------------------------------------------- per-tick posing

    @Override
    public void run() {
        for (Map.Entry<UUID, Rig> e : rigs.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            pose(p, e.getValue());
        }
    }

    /** Interpolation window (ticks) for stance blends - long enough to look smooth, short enough to
     *  feel responsive. The rig ticks every tick, so restarting this each tick low-pass-smooths motion. */
    private static final int LERP = 3;
    private static final float EASE = 0.25f;   // per-tick approach toward the target stance angle

    private void pose(Player p, Rig r) {
        Location base = p.getLocation();
        float yaw = base.getYaw(), pitch = base.getPitch();
        double yawRad = Math.toRadians(yaw);
        double cos = Math.cos(yawRad), sin = Math.sin(yawRad);

        // a mid-game team change re-textures the limbs (head stays the player skin)
        String prefix = teamModelPrefix(p);
        if (!prefix.equals(r.prefix)) {
            r.prefix = prefix;
            for (String part : PARTS) {
                ItemDisplay d = r.parts.get(part);
                if (d == null || d.isDead()) continue;
                ItemStack it = new ItemStack(Material.PLAYER_HEAD);
                applyPartModel(it, p, part, prefix);
                d.setItemStack(it);
            }
        }

        // walk speed drives the swing amplitude (eases in/out so stopping is smooth)
        double dx = base.getX() - r.last.getX(), dz = base.getZ() - r.last.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        r.last = base.clone();
        double targetAmt = Math.min(1.0, speed * 8.0);
        r.swingAmt += (targetAmt - r.swingAmt) * 0.3;
        if (r.swingAmt > 0.02) r.phase += 0.35 + speed * 4.0;
        double swing = Math.sin(r.phase) * r.swingAmt * 0.9;       // radians

        // STANCE STATES (third-person, seen by others): crouch drops + leans; a held gun raises the arms
        // into an aim pose that tracks the look pitch. Each angle is EASED toward its target so poses
        // blend instead of snapping (that was the twitch), and transforms are only re-sent when they
        // actually change so a settled rig doesn't restart its interpolation every tick.
        boolean sneaking = p.isSneaking();
        boolean aiming = holdingGun(p);
        float pitchRad = (float) Math.toRadians(pitch);
        double crouchDrop = sneaking ? -0.28 : 0.0;

        for (String part : PARTS) {
            ItemDisplay d = r.parts.get(part);
            if (d == null || d.isDead()) continue;
            Tune tn = tunes.get(part);

            // position: tuned offset (x/z rotated by body yaw so left/right track the body)
            double wx = tn.x * cos - tn.z * sin, wz = tn.x * sin + tn.z * cos;
            Location loc = new Location(base.getWorld(), base.getX() + wx, base.getY() + tn.y + crouchDrop, base.getZ() + wz);
            loc.setYaw(yaw + (float) tn.yaw);
            if (part.equals("head")) loc.setPitch(pitch + (float) tn.pitch);
            d.teleport(loc);

            // stance angle, eased toward its target
            float target = switch (part) {
                case "arms"  -> aiming ? -1.45f + pitchRad : (float) swing * 0.4f;
                case "legs"  -> sneaking ? 0.4f : 0f;
                case "torso" -> sneaking ? 0.5f : 0f;
                default      -> 0f;   // head
            };
            float now = r.limbNow.getOrDefault(part, target);
            now += (target - now) * EASE;
            if (Math.abs(target - now) < 0.002f) now = target;
            Float applied = r.limbApplied.get(part);
            if (applied == null || Math.abs(now - applied) > 0.001f || r.retransform) {
                r.limbNow.put(part, now);
                r.limbApplied.put(part, now);
                Vector3f sc = scale(part).mul((float) tn.scale);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(LERP);
                d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new AxisAngle4f(now, 1, 0, 0),
                    sc, new AxisAngle4f(0, 0, 0, 1)));
            } else {
                r.limbNow.put(part, now);
            }
        }

        // DUAL RENDERING: show the held gun on the rig's hand for third-person viewers.
        if (aiming) {
            ItemStack held = p.getInventory().getItemInMainHand();
            if (r.gun == null || r.gun.isDead()) { r.gun = spawnGun(base); r.gunSig = null; }
            String sig = gunSig(held);
            if (!sig.equals(r.gunSig)) {
                r.gun.setItemStack(held.clone());
                r.gunSig = sig;
                Tune gt = tunes.get("gun");
                r.gun.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f((float) gt.scale), new AxisAngle4f(0, 0, 0, 1)));
            }
            Tune gt = tunes.get("gun");
            double hx = gt.x * cos - gt.z * sin, hz = gt.x * sin + gt.z * cos;
            Location gloc = new Location(base.getWorld(), base.getX() + hx, base.getY() + gt.y + crouchDrop, base.getZ() + hz);
            gloc.setYaw(yaw + (float) gt.yaw);
            gloc.setPitch(pitch + (float) gt.pitch);
            r.gun.teleport(gloc);
            r.gun.setInterpolationDelay(0);
            r.gun.setInterpolationDuration(LERP);
        } else if (r.gun != null) {
            r.gun.remove();
            r.gun = null;
            r.gunSig = null;
        }

        r.retransform = false;
    }

    private ItemDisplay spawnGun(Location at) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
        });
    }

    /** A cheap identity of the shown gun (type + custom-model-data) so we only rebuild it on change. */
    private static String gunSig(ItemStack it) {
        if (it == null) return "";
        String cmd = it.hasItemMeta() ? String.valueOf(it.getItemMeta().getCustomModelDataComponent().getStrings()) : "";
        return it.getType().name() + cmd;
    }

    /** True if the player's main hand holds a Guns-plugin weapon (read from its guns:id tag - no hard
     *  dependency on the Guns plugin, just its PDC key). Drives the aim stance + dual-render gun. */
    private static final NamespacedKey GUN_ID = new NamespacedKey("guns", "id");
    private boolean holdingGun(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        return it != null && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(GUN_ID, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- lifecycle

    @EventHandler public void onQuit(PlayerQuitEvent e) { despawn(e.getPlayer()); }

    public void shutdown() {
        rigs.values().forEach(r -> {
            r.parts.values().forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
            if (r.gun != null && !r.gun.isDead()) r.gun.remove();
        });
        rigs.clear();
        sweepOrphans();
    }

    public void sweepOrphans() {
        for (var w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    Component line(String text) { return Component.text(text, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false); }
}
