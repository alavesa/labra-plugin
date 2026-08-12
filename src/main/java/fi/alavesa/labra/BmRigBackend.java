package fi.alavesa.labra;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The BetterModel-touching half of the rig (only loaded when BetterModel is installed - see {@link BmRig}).
 *
 * Drives a fully-rigged third-person player body:
 *  - LOCOMOTION (priority 0, looping): idle / walk / run / jump, chosen from speed + airborne state.
 *  - HIP POINTING: the movement DIRECTION relative to where you look is written into a bone named
 *    "hip" via a local-rotation modifier registered once at spawn. So one walk/run clip (legs striding
 *    straight forward) is aimed wherever you actually move - W = 0, W+D = 45, D = 90, S = 180.
 *  - OVERLAY (priority 10, looping, arm bones only): hold_item / hold_gun / aim, layered on top of the
 *    locomotion clip so "gun while running" = run (legs) + hold_gun (arms) at once.
 *  - ONE-SHOTS (priority 20, play once): fire / reload, fired via {@link #trigger}; they layer over the
 *    arms for a configured duration while the legs keep moving.
 *
 * All animation names + the hip bone + one-shot durations are config-driven (bmrig.*), so whatever you
 * author in Blockbench maps straight through. Any extra animation is rigged just by naming it.
 */
final class BmRigBackend implements BmRig.RigBackend {

    private final LabraPlugin plugin;
    private final Map<UUID, Bound> rigs = new ConcurrentHashMap<>();

    private static final AnimationModifier LOCO = AnimationModifier.builder().priority(0).type(AnimationIterator.Type.LOOP).build();
    private static final AnimationModifier OVERLAY = AnimationModifier.builder().priority(10).type(AnimationIterator.Type.LOOP).build();
    private static final AnimationModifier ONESHOT = AnimationModifier.builder().priority(20).type(AnimationIterator.Type.PLAY_ONCE).build();

    BmRigBackend(LabraPlugin plugin) { this.plugin = plugin; }

    private static final class Bound {
        EntityTracker tracker;
        volatile float hipYaw;   // radians, hip offset from the look yaw, read every frame by the modifier
        float bodyYaw;           // WORLD yaw the lower body currently faces (holds when idle - no snap-back)
        String locomotion = "";
        String overlay = "";
        String oneShot = "";
        long oneShotUntil;
        Location last;
    }

    private static float wrap(float deg) { return ((deg + 180f) % 360f + 360f) % 360f - 180f; }
    /** Move an angle toward a target the short way round, by fraction t. */
    private static float approachAngle(float cur, float target, float t) { return cur + wrap(target - cur) * t; }

    private String modelName() { return plugin.getConfig().getString("bmrig.model", "player_rig"); }
    private String hipBone() { return plugin.getConfig().getString("bmrig.hip-bone", "hip"); }
    private double hipSign() { return plugin.getConfig().getDouble("bmrig.hip-sign", 1.0); }
    private String anim(String key, String def) { return plugin.getConfig().getString("bmrig.animations." + key, def); }
    private int oneShotTicks(String key) { return plugin.getConfig().getInt("bmrig.durations." + key, 20); }

    @Override
    public boolean has(Player p) { return rigs.containsKey(p.getUniqueId()); }

    @Override
    public boolean spawn(Player player) {
        if (rigs.containsKey(player.getUniqueId())) return true;
        ModelRenderer renderer = BetterModel.model(modelName()).orElse(null);
        if (renderer == null) {
            player.sendMessage("§cBetterModel model '" + modelName() + "' not found. Load it into BetterModel and set bmrig.model.");
            return false;
        }
        PlatformPlayer pp = BetterModel.platform().adapter().player(player.getUniqueId());
        EntityTracker tracker = renderer.getOrCreate(pp);   // creates + auto-tracks the rig on the player
        player.setInvisible(true);

        Bound b = new Bound();
        b.tracker = tracker;
        b.last = player.getLocation();

        // HIP POINTING: one local-rotation modifier on the "hip" bone, reading this rig's live hipYaw.
        String hip = hipBone();
        if (hip != null && !hip.isEmpty()) {
            final Bound bref = b;
            tracker.getPipeline().addLocalRotModifier(
                BonePredicate.name(hip).withoutChildren(),          // the hip bone; its children inherit the turn
                q -> new Quaternionf(q).rotateY(bref.hipYaw));
        }

        b.bodyYaw = player.getLocation().getYaw();
        rigs.put(player.getUniqueId(), b);
        b.locomotion = anim("idle", "idle");
        safeAnimate(b, b.locomotion, LOCO);
        return true;
    }

    @Override
    public void despawn(Player player) {
        Bound b = rigs.remove(player.getUniqueId());
        if (b != null && b.tracker != null) { try { b.tracker.close(); } catch (Exception ignored) { } }
        if (player.isOnline()) player.setInvisible(false);
    }

    @Override
    public void tickAll() {
        for (Map.Entry<UUID, Bound> e : rigs.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            tick(p, e.getValue());
        }
    }

    private void tick(Player player, Bound b) {
        if (b.tracker == null || b.tracker.isClosed()) {
            rigs.remove(player.getUniqueId());
            if (player.isOnline()) player.setInvisible(false);
            return;
        }

        Location at = player.getLocation();
        double dx = at.getX() - b.last.getX(), dz = at.getZ() - b.last.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        b.last = at.clone();

        // BODY/HIP FACING: the lower body has its OWN world yaw. While moving it turns toward the
        // movement direction; when you stop it HOLDS there (no snap back to where you look). It only
        // follows the head when you look more than body-turn-limit degrees away from the body, and then
        // just enough to stay within that limit. The head bone (BetterModel head-tracking) still faces
        // your look independently. hipYaw is that body yaw expressed relative to the look yaw.
        float lookYaw = at.getYaw();
        if (speed > 0.02) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));   // movement direction (world)
            b.bodyYaw = approachAngle(b.bodyYaw, moveYaw, 0.3f);
        }
        float limit = (float) plugin.getConfig().getDouble("bmrig.body-turn-limit", 90.0);
        float diff = wrap(lookYaw - b.bodyYaw);
        if (Math.abs(diff) > limit) {                              // looked too far around -> drag the body along
            b.bodyYaw = approachAngle(b.bodyYaw, lookYaw - Math.signum(diff) * limit, 0.5f);
        }
        b.hipYaw = (float) (Math.toRadians(wrap(b.bodyYaw - lookYaw)) * hipSign());

        long now = player.getWorld().getFullTime();

        // Expire a finished one-shot (fire/reload) - legs kept moving underneath the whole time.
        if (!b.oneShot.isEmpty() && now >= b.oneShotUntil) { safeStop(b, b.oneShot); b.oneShot = ""; }

        // LOCOMOTION (priority 0). Default clip names match a typical .bbmodel: idle/walking/running/jumping.
        boolean airborne = !player.isOnGround();
        String loco = airborne ? anim("jump", "jumping")
            : speed > 0.18 ? anim("run", "running")
            : speed > 0.02 ? anim("walk", "walking")
            : anim("idle", "idle");
        if (!loco.equals(b.locomotion)) {
            if (!b.locomotion.isEmpty()) safeStop(b, b.locomotion);
            safeAnimate(b, loco, LOCO);
            b.locomotion = loco;
        }

        // OVERLAY (priority 10, arm bones) - what's in your hands
        String ov = player.isSneaking() && isHoldingGun(player) ? anim("aim", "aim")
            : isHoldingGun(player) ? anim("hold_gun", "hold_gun")
            : hasHandItem(player) ? anim("hold_item", "hold_item")
            : "";
        if (!ov.equals(b.overlay)) {
            if (!b.overlay.isEmpty()) safeStop(b, b.overlay);
            if (!ov.isEmpty()) safeAnimate(b, ov, OVERLAY);
            b.overlay = ov;
        }
    }

    @Override
    public void trigger(Player player, String key) {
        Bound b = rigs.get(player.getUniqueId());
        if (b == null || b.tracker == null) return;
        String animName = anim(key, key);
        safeAnimate(b, animName, ONESHOT);   // layers over the arms; legs keep their locomotion
        b.oneShot = animName;
        b.oneShotUntil = player.getWorld().getFullTime() + Math.max(1, oneShotTicks(key));
    }

    private void safeAnimate(Bound b, String name, AnimationModifier modifier) {
        if (name == null || name.isEmpty()) return;
        try { b.tracker.animate(name, modifier); } catch (Exception ignored) { }
    }

    private void safeStop(Bound b, String name) {
        if (name == null || name.isEmpty()) return;
        try { b.tracker.stopAnimation(name); } catch (Exception ignored) { }
    }

    private boolean isHoldingGun(Player p) {
        var it = p.getInventory().getItemInMainHand();
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer()
            .has(new org.bukkit.NamespacedKey("guns", "id"), org.bukkit.persistence.PersistentDataType.STRING);
    }

    private boolean hasHandItem(Player p) {
        var it = p.getInventory().getItemInMainHand();
        return it != null && !it.getType().isAir();
    }

    @Override
    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(rigs.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) despawn(p);
            else {
                Bound b = rigs.remove(id);
                if (b != null && b.tracker != null) { try { b.tracker.close(); } catch (Exception ignored) { } }
            }
        }
    }
}
