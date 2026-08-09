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

    PlayerRig(LabraPlugin plugin) { this.plugin = plugin; }

    private static final class Rig {
        final Map<String, ItemDisplay> parts = new java.util.HashMap<>();
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
            d.setTeleportDuration(1);          // 1-tick interpolation = smooth follow, no client mod
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

    /** Local part anchors (x=right, y=up, z=forward) relative to the player's feet. Arms/legs centred. */
    private static Vector3f anchor(String part) {
        return switch (part) {
            case "head"  -> new Vector3f(0f, 1.5f, 0f);
            case "torso" -> new Vector3f(0f, 1.1f, 0f);
            case "arms"  -> new Vector3f(0f, 1.4f, 0f);
            default      -> new Vector3f(0f, 0.75f, 0f);   // legs
        };
    }

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
        // into an aim pose that tracks the look pitch. Finer per-bone walk/aim clips are authored in
        // Blockbench; this is the live server fallback so the rig always moves.
        boolean sneaking = p.isSneaking();
        boolean aiming = holdingGun(p);
        float pitchRad = (float) Math.toRadians(pitch);
        double crouchDrop = sneaking ? -0.28 : 0.0;

        for (String part : PARTS) {
            ItemDisplay d = r.parts.get(part);
            if (d == null || d.isDead()) continue;
            Vector3f a = anchor(part);
            double wx = a.x * cos - a.z * sin, wz = a.x * sin + a.z * cos;
            Location loc = new Location(base.getWorld(), base.getX() + wx, base.getY() + a.y + crouchDrop, base.getZ() + wz);
            loc.setYaw(yaw);
            float limb = switch (part) {
                case "arms"  -> aiming ? -1.45f + pitchRad : (float) swing * 0.4f;   // both arms as one
                case "legs"  -> sneaking ? 0.4f : 0f;                                // crouch bend
                case "torso" -> sneaking ? 0.5f : 0f;                                // crouch lean
                default      -> 0f;                                                  // head
            };
            if (part.equals("head")) loc.setPitch(pitch);
            d.teleport(loc);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new AxisAngle4f(limb, 1, 0, 0),
                scale(part), new AxisAngle4f(0, 0, 0, 1)));
        }

        // DUAL RENDERING: show the held gun on the rig's arms for third-person viewers.
        if (aiming) {
            ItemStack held = p.getInventory().getItemInMainHand();
            if (r.gun == null || r.gun.isDead()) r.gun = spawnGun(base);
            String sig = gunSig(held);
            if (!sig.equals(r.gunSig)) { r.gun.setItemStack(held.clone()); r.gunSig = sig; }
            Vector3f h = new Vector3f(0.32f, 1.30f, 0.40f);   // right-hand hold point
            double hx = h.x * cos - h.z * sin, hz = h.x * sin + h.z * cos;
            Location gloc = new Location(base.getWorld(), base.getX() + hx, base.getY() + h.y + crouchDrop, base.getZ() + hz);
            gloc.setYaw(yaw);
            gloc.setPitch(pitch);
            r.gun.teleport(gloc);
            r.gun.setInterpolationDelay(0);
            r.gun.setInterpolationDuration(1);
        } else if (r.gun != null) {
            r.gun.remove();
            r.gun = null;
            r.gunSig = null;
        }
    }

    private ItemDisplay spawnGun(Location at) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
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
