package fi.alavesa.labra;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The BetterModel-touching half of the rig. This class is ONLY loaded when BetterModel is installed
 * (see {@link BmRig}), so it may reference the BetterModel API freely.
 *
 * BetterModel API (3.4.1):
 *   ModelRenderer r = BetterModel.model(name).orElse(null);
 *   PlatformPlayer pp = BetterModel.platform().adapter().player(uuid);
 *   EntityTracker t = r.getOrCreate(pp);          // creates + auto-tracks the rig on the player
 *   t.animate("walk"); t.stopAnimation("walk");   // loop control
 *   t.animate("fire", AnimationModifier.DEFAULT_WITH_PLAY_ONCE);   // one-shot
 *   t.close();                                     // remove the rig
 */
final class BmRigBackend implements BmRig.RigBackend {

    private final LabraPlugin plugin;
    private final Map<UUID, Bound> rigs = new ConcurrentHashMap<>();

    BmRigBackend(LabraPlugin plugin) { this.plugin = plugin; }

    private static final class Bound {
        EntityTracker tracker;
        String locomotion = "";
        String oneShot = "";
        long oneShotUntil;
        Location last;
    }

    private String modelName() { return plugin.getConfig().getString("bmrig.model", "player_rig"); }
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
        player.setInvisible(true);                          // hide the vanilla body; the model stands in
        Bound b = new Bound();
        b.tracker = tracker;
        b.last = player.getLocation();
        b.locomotion = anim("idle", "idle");
        rigs.put(player.getUniqueId(), b);
        safeAnimate(b, b.locomotion, false);
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
        long now = player.getWorld().getFullTime();
        Location at = player.getLocation();
        if (!b.oneShot.isEmpty()) {
            if (now >= b.oneShotUntil) { b.oneShot = ""; b.locomotion = ""; }   // let the loop reassert locomotion
            else { b.last = at.clone(); return; }
        }
        double dx = at.getX() - b.last.getX(), dz = at.getZ() - b.last.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        b.last = at.clone();
        String want = player.isSneaking() && isHoldingGun(player) ? anim("aim", "aim")
            : speed > 0.18 ? anim("run", "run")
            : speed > 0.02 ? anim("walk", "walk")
            : anim("idle", "idle");
        if (!want.equals(b.locomotion)) {
            if (!b.locomotion.isEmpty()) safeStop(b, b.locomotion);
            safeAnimate(b, want, false);
            b.locomotion = want;
        }
    }

    @Override
    public void trigger(Player player, String key) {
        Bound b = rigs.get(player.getUniqueId());
        if (b == null || b.tracker == null) return;
        String animName = anim(key, key);
        if (!b.locomotion.isEmpty()) { safeStop(b, b.locomotion); b.locomotion = ""; }
        safeAnimate(b, animName, true);
        b.oneShot = animName;
        b.oneShotUntil = player.getWorld().getFullTime() + Math.max(1, oneShotTicks(key));
    }

    private void safeAnimate(Bound b, String name, boolean once) {
        if (name == null || name.isEmpty()) return;
        try { b.tracker.animate(name, once ? AnimationModifier.DEFAULT_WITH_PLAY_ONCE : AnimationModifier.DEFAULT); }
        catch (Exception ignored) { }
    }

    private void safeStop(Bound b, String name) {
        try { b.tracker.stopAnimation(name); } catch (Exception ignored) { }
    }

    private boolean isHoldingGun(Player p) {
        var it = p.getInventory().getItemInMainHand();
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer()
            .has(new org.bukkit.NamespacedKey("guns", "id"), org.bukkit.persistence.PersistentDataType.STRING);
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
