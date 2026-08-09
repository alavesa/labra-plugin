package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
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
 * Server-side player RIG: the vanilla player is made invisible and puppeteered by SIX ItemDisplay
 * parts — two legs, two arms, one torso, one head — each showing a custom model (a CustomModelData on
 * an otherwise-unused base item, default LEATHER_HORSE_ARMOR). Every tick the server positions the
 * parts on the player and rotates the limbs (walk/run swing now; recoil/other clips hook in the same
 * way), then teleports them with a 1-tick interpolation so the vanilla client renders it smoothly with
 * no client mod. The SAME rig can be spawned as a standing "body double" for Terminal's CCTV feature.
 *
 * The part MODELS come from the resource pack: give a Blockbench model each of the custom_model_data
 * ids below (rig_head/rig_torso/rig_arm_left/rig_arm_right/rig_leg_left/rig_leg_right) on the base item.
 * Until then the parts show as the base item so you can still see the rig move and tune it.
 */
public final class PlayerRig implements Listener, Runnable {

    /** part id -> the six body parts. Arms/legs are separate entities (two each) per the spec. */
    private static final String[] PARTS = {"head", "torso", "arm_left", "arm_right", "leg_left", "leg_right"};
    private static final String TAG = "lab.rigpart";

    private final LabraPlugin plugin;
    private final Map<UUID, Rig> rigs = new ConcurrentHashMap<>();

    PlayerRig(LabraPlugin plugin) { this.plugin = plugin; }

    private static final class Rig {
        final Map<String, ItemDisplay> parts = new java.util.HashMap<>();
        double phase;        // walk-cycle phase
        double swingAmt;     // current swing amplitude (eases in/out with movement)
        Location last;       // previous location, to measure walk speed
    }

    public boolean hasRig(Player p) { return rigs.containsKey(p.getUniqueId()); }

    /** Turn a player into a puppet: invisible body + six display parts riding it. The parts are also
     *  hidden from the OWNER, so the player never sees their own rig clipping through their first-person
     *  camera (the first-person arms+gun are drawn client-side from the held item model instead). Other
     *  players see the full rig. */
    public void spawn(Player p) {
        if (rigs.containsKey(p.getUniqueId())) return;
        p.setInvisible(true);
        Rig r = new Rig();
        r.last = p.getLocation();
        for (String part : PARTS) {
            ItemDisplay d = spawnPart(p, p.getLocation(), part);
            r.parts.put(part, d);
            p.hideEntity(plugin, d);        // invisible to yourself; everyone else sees it
        }
        rigs.put(p.getUniqueId(), r);
        pose(p, r);
    }

    public void despawn(Player p) {
        Rig r = rigs.remove(p.getUniqueId());
        if (r != null) r.parts.values().forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
        if (p.isOnline()) p.setInvisible(false);
    }

    /** Every part is a PLAYER_HEAD (head entity), but ONLY the head carries the player's own skin and
     *  so renders the real player-head model. The other five limbs are CUSTOM-TEXTURED head entities:
     *  no player skin, just a custom_model_data rig_&lt;part&gt; that the resource pack maps to a
     *  Blockbench-modelled, custom-textured body part. Until those models exist they show as a plain
     *  head placeholder so you can still see the rig move. */
    private ItemDisplay spawnPart(Player owner, Location at, String part) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = head.getItemMeta();
        if (part.equals("head")) {
            if (m instanceof SkullMeta skull) skull.setOwningPlayer(owner);   // real player skin - head only
        } else {
            CustomModelDataComponent cmd = m.getCustomModelDataComponent();   // custom-textured limb model
            cmd.setStrings(List.of("rig_" + part));
            m.setCustomModelDataComponent(cmd);
        }
        head.setItemMeta(m);
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(head);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);          // 1-tick interpolation = smooth follow, no client mod
            d.setInterpolationDuration(1);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
        });
    }

    /** Rough head-cube proportions per body part (a real Blockbench head-model replaces this look). */
    private static Vector3f scale(String part) {
        return switch (part) {
            case "head"      -> new Vector3f(1.0f, 1.0f, 1.0f);
            case "torso"     -> new Vector3f(1.0f, 1.5f, 0.55f);
            case "arm_left", "arm_right" -> new Vector3f(0.5f, 1.5f, 0.5f);
            default          -> new Vector3f(0.5f, 1.6f, 0.5f);   // legs
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

    /** Local body-part anchors (x=right, y=up, z=forward) relative to the player's feet. */
    private static Vector3f anchor(String part) {
        return switch (part) {
            case "head"      -> new Vector3f(0f, 1.5f, 0f);
            case "torso"     -> new Vector3f(0f, 1.1f, 0f);
            case "arm_left"  -> new Vector3f(-0.31f, 1.4f, 0f);
            case "arm_right" -> new Vector3f(0.31f, 1.4f, 0f);
            case "leg_left"  -> new Vector3f(-0.12f, 0.75f, 0f);
            default          -> new Vector3f(0.12f, 0.75f, 0f);   // leg_right
        };
    }

    private void pose(Player p, Rig r) {
        Location base = p.getLocation();
        float yaw = base.getYaw();
        double yawRad = Math.toRadians(yaw);
        double cos = Math.cos(yawRad), sin = Math.sin(yawRad);

        // walk speed drives the swing amplitude (eases in/out so stopping is smooth)
        double dx = base.getX() - r.last.getX(), dz = base.getZ() - r.last.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        r.last = base.clone();
        double targetAmt = Math.min(1.0, speed * 8.0);              // scale to a sane swing
        r.swingAmt += (targetAmt - r.swingAmt) * 0.3;              // ease
        if (r.swingAmt > 0.02) r.phase += 0.35 + speed * 4.0;      // faster cadence when running
        double swing = Math.sin(r.phase) * r.swingAmt * 0.9;       // radians

        // STANCE STATES: the pose changes with what the player is doing. Crouching drops the whole rig
        // and bends the torso/legs; holding a gun raises both arms forward into an aim pose that tracks
        // the look pitch. (These are the third-person, seen-by-others stances - first person is drawn
        // client-side from the held item model; see CUSTOM-MODELS-AND-ANIMATIONS.md.)
        boolean sneaking = p.isSneaking();
        boolean aiming = holdingGun(p);
        float pitchRad = (float) Math.toRadians(base.getPitch());
        double crouchDrop = sneaking ? -0.28 : 0.0;                 // matches vanilla sneak lowering

        for (String part : PARTS) {
            ItemDisplay d = r.parts.get(part);
            if (d == null || d.isDead()) continue;
            Vector3f a = anchor(part);
            // rotate the horizontal anchor by the body yaw so parts stay on the body as it turns
            double wx = a.x * cos - a.z * sin, wz = a.x * sin + a.z * cos;
            Location loc = new Location(base.getWorld(), base.getX() + wx, base.getY() + a.y + crouchDrop, base.getZ() + wz);
            loc.setYaw(yaw);
            // limb angle about the local X axis (pivot = the part's own origin - author models hanging
            // from their pivot). Blends the walk swing with the current stance.
            float limb = switch (part) {
                case "arm_right", "arm_left" ->
                    aiming ? -1.45f + pitchRad                       // raised forward, holding + aiming the gun
                        : (part.equals("arm_right") ? (float) swing : (float) -swing);
                case "leg_left"  -> (float) swing * (sneaking ? 0.4f : 1f) + (sneaking ? 0.5f : 0f);
                case "leg_right" -> (float) -swing * (sneaking ? 0.4f : 1f) + (sneaking ? 0.5f : 0f);
                case "torso"     -> sneaking ? 0.5f : 0f;           // lean forward when crouching
                default          -> 0f;                             // head
            };
            if (part.equals("head")) loc.setPitch(base.getPitch());
            d.teleport(loc);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new AxisAngle4f(limb, 1, 0, 0),
                scale(part), new AxisAngle4f(0, 0, 0, 1)));
        }
    }

    /** True if the player's main hand holds a Guns-plugin weapon (read from its guns:id tag - no hard
     *  dependency on the Guns plugin, just its PDC key). Drives the gun-holding stance. */
    private static final NamespacedKey GUN_ID = new NamespacedKey("guns", "id");
    private boolean holdingGun(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        return it != null && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(GUN_ID, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- lifecycle

    @EventHandler public void onQuit(PlayerQuitEvent e) { despawn(e.getPlayer()); }

    public void shutdown() {
        rigs.values().forEach(r -> r.parts.values().forEach(d -> { if (d != null && !d.isDead()) d.remove(); }));
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
