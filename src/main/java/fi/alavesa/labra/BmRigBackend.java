package fi.alavesa.labra;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
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
 * The BetterModel-touching half of the rig (only loaded when BetterModel is installed - see {@link BmRig}).
 *
 * HEAD/BODY ROTATION is handled by BetterModel's NATIVE body rotator in PLAYER MODE, so it behaves like
 * a vanilla player: the head turns freely, the body only follows once the head passes a threshold, and
 * when moving the body postures toward the movement direction. All limits/timing are configurable, so
 * we don't hand-rotate a "hip" bone anymore (which snapped and translated the legs).
 *
 * ANIMATIONS layer by bone + priority:
 *  - LOCOMOTION (priority 0, loop): idle/walking/running/jumping, its PLAYBACK SPEED scaled by how fast
 *    the player actually moves (a live FloatSupplier).
 *  - OVERLAY (priority 10, loop, override=false so it only touches the bones it keyframes): hold_item/
 *    hold_gun/aim on the arms - layers on top of locomotion so the legs keep moving.
 *  - ONE-SHOTS (priority 20, play once, override=false): fire/reload on the arms.
 * Every animation starts with a short blend-in (transition ticks) instead of snapping.
 * All names/speeds/timing are config-driven (bmrig.*) and editable live with /lab bmset.
 */
final class BmRigBackend implements BmRig.RigBackend {

    private final LabraPlugin plugin;
    private final Map<UUID, Bound> rigs = new ConcurrentHashMap<>();

    BmRigBackend(LabraPlugin plugin) { this.plugin = plugin; }

    private static final class Bound {
        EntityTracker tracker;
        volatile float locoSpeed = 1f;   // live playback-speed multiplier for the locomotion clip
        String locomotion = "";
        String overlay = "";
        String oneShot = "";
        long oneShotUntil;
        Location last;
    }

    // ---- config ----
    private String modelName() { return plugin.getConfig().getString("bmrig.model", "player_rig"); }
    private String anim(String key, String def) { return plugin.getConfig().getString("bmrig.animations." + key, def); }
    private int oneShotTicks(String key) { return plugin.getConfig().getInt("bmrig.durations." + key, 20); }
    private int transitionTicks() { return Math.max(0, plugin.getConfig().getInt("bmrig.transition-ticks", 3)); }
    private double speedScale() { return plugin.getConfig().getDouble("bmrig.speed-scale", 1.0); }
    private double walkRef() { return plugin.getConfig().getDouble("bmrig.speed.walk-ref", 0.12); }
    private double runRef() { return plugin.getConfig().getDouble("bmrig.speed.run-ref", 0.22); }

    // ---- modifiers (rebuilt per animate() so live config edits take effect on the next state change) ----
    private AnimationModifier loco(Bound b) {
        int t = transitionTicks();
        return AnimationModifier.builder().priority(0).type(AnimationIterator.Type.LOOP)
            .start(t).end(t).speed(() -> b.locoSpeed).build();
    }
    // override=false SHOULD mean an overlay only touches the bones it keyframes (so hold_gun on the arms
    // layers over walking legs). If layering still doesn't work in your model, flip bmrig.overlay-override
    // in-game and see which behaves - it can't be verified headless.
    private boolean overlayOverride() { return plugin.getConfig().getBoolean("bmrig.overlay-override", false); }
    private AnimationModifier overlay() {
        int t = transitionTicks();
        return AnimationModifier.builder().priority(10).type(AnimationIterator.Type.LOOP)
            .start(t).end(t).override(overlayOverride()).build();
    }
    private AnimationModifier oneShot() {
        int t = transitionTicks();
        return AnimationModifier.builder().priority(20).type(AnimationIterator.Type.PLAY_ONCE)
            .start(t).end(t).override(overlayOverride()).build();
    }

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
        // THE real cause of "my held item vanishes from the hotbar and my own hand": BetterModel's own
        // config key `cancel-player-model-inventory` (default TRUE) makes it cancel the player-model's
        // inventory, which clears the selected slot in the CLIENT container - and both the 2D hotbar icon
        // AND the first-person hand render from that container, so both go empty. We can't override it from
        // here (config is read-only via the API); it must be turned off in BetterModel's config.yml. Warn
        // the player with the exact fix instead of silently shipping a broken rig.
        try {
            if (BetterModel.config().cancelPlayerModelInventory()) {
                player.sendMessage("§e[Labra] §fBetterModel piilottaa käteen otetun esineen. Korjaa: "
                    + "§bplugins/BetterModel/config.yml §f-> §acancel-player-model-inventory: false §f"
                    + "ja aja §b/bettermodel reload§f (tai käynnistä serveri uudelleen).");
                plugin.getLogger().warning("BetterModel 'cancel-player-model-inventory' is TRUE - the rigged "
                    + "player's held item will vanish from their hotbar/first-person hand. Set it to false in "
                    + "plugins/BetterModel/config.yml and /bettermodel reload.");
            }
        } catch (Throwable ignored) { }
        PlatformPlayer pp = BetterModel.platform().adapter().player(player.getUniqueId());
        EntityTracker tracker = renderer.getOrCreate(pp);
        applyRotator(tracker);                              // vanilla-like head/body turning
        // BetterModel's default hide option hides the entity's EQUIPMENT - including the held item, so
        // the selected hotbar slot looked empty. Turn hiding OFF: the held item must stay in hand
        // (first person is the real item). We hide only the armour ourselves, below.
        try { tracker.hideOption(kr.toxicity.model.api.tracker.EntityHideOption.FALSE); } catch (Exception ignored) { }
        // Item bones (pri_right_item/pli_left_item) make BetterModel render the player's held item on the
        // model AND hide the real one - which is why the held item vanished from the hotbar. Disable those
        // mappers so the real item stays in hand/hotbar (first person is the real item). The gun won't
        // ride the rig's hand for others - acceptable trade-off (the rig hides from the owner later).
        try {
            for (var top : tracker.getPipeline())
                for (var bone : top.flattenBones())
                    bone.setItemMapper(kr.toxicity.model.api.bone.BoneItemMapper.EMPTY);
        } catch (Exception ignored) { }
        player.setInvisible(true);
        hideEquipment(player);                             // hide only ARMOR from other viewers (not the hand)

        Bound b = new Bound();
        b.tracker = tracker;
        b.last = player.getLocation();
        rigs.put(player.getUniqueId(), b);
        b.locomotion = anim("idle", "idle");
        safeAnimate(b, b.locomotion, loco(b));
        return true;
    }

    /** Configure BetterModel's native body rotator to act like a vanilla player (head turns first, body
     *  follows past a limit; postures toward movement). Tunable via bmrig.body.* / bmset. */
    private void applyRotator(EntityTracker tracker) {
        try {
            var cfg = plugin.getConfig();
            float maxHead = (float) cfg.getDouble("bmrig.body.max-head", 70.0);
            float maxBody = (float) cfg.getDouble("bmrig.body.max-body", 45.0);
            float stable = (float) cfg.getDouble("bmrig.body.stable", 0.0);
            int rotDelay = cfg.getInt("bmrig.body.rotation-delay", 2);
            int rotDur = cfg.getInt("bmrig.body.rotation-duration", 6);
            tracker.bodyRotator().setValue(d -> {
                d.setPlayerMode(true);
                d.setMinHead(-maxHead); d.setMaxHead(maxHead);
                d.setMinBody(-maxBody); d.setMaxBody(maxBody);
                d.setStable(stable);
                d.setRotationDelay(rotDelay);
                d.setRotationDuration(rotDur);
            });
        } catch (Exception ignored) { }
    }

    @Override
    public void despawn(Player player) {
        Bound b = rigs.remove(player.getUniqueId());
        if (b != null && b.tracker != null) { try { b.tracker.close(); } catch (Exception ignored) { } }
        if (player.isOnline()) { player.setInvisible(false); showEquipment(player); }
    }

    /** Only the ARMOUR slots are hidden - never the hands. The held item must stay in hand: first person
     *  is the real item (with its own animations), and it must never look like it vanished. */
    private static final org.bukkit.inventory.EquipmentSlot[] ARMOR = {
        org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
        org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET
    };

    /** Hide the rigged player's worn ARMOUR from other viewers (visual only; real protection untouched)
     *  so it doesn't render on the vanilla body under the rig. Held items are left alone. */
    private void hideEquipment(Player p) {
        org.bukkit.inventory.ItemStack air = new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR);
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.equals(p)) continue;
            for (var slot : ARMOR) try { v.sendEquipmentChange(p, slot, air); } catch (Exception ignored) { }
        }
    }

    /** Restore the real armour visuals when the rig is removed. */
    private void showEquipment(Player p) {
        var eq = p.getEquipment();
        if (eq == null) return;
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.equals(p)) continue;
            for (var slot : ARMOR) try { v.sendEquipmentChange(p, slot, eq.getItem(slot)); } catch (Exception ignored) { }
        }
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

        long now = player.getWorld().getFullTime();
        if (now % 20L == 0L) {
            applyRotator(b.tracker);
            hideEquipment(player);   // keep body-turn config + hide new viewers' armour
            // Re-assert that BetterModel is NOT hiding the entity's equipment - the held item kept
            // vanishing from the selected hotbar slot because the default hide option hides it.
            try { b.tracker.hideOption(kr.toxicity.model.api.tracker.EntityHideOption.FALSE); } catch (Exception ignored) { }
        }
        // The held item kept vanishing from the SELECTED hotbar slot under the rig. The hotbar HUD is
        // driven by the inventory container (not equipment packets), and BetterModel was hiding the
        // vanilla main hand, so re-sync the container to the client so the real item shows in the slot.
        player.updateInventory();
        if (!b.oneShot.isEmpty() && now >= b.oneShotUntil) { safeStop(b, b.oneShot); b.oneShot = ""; }

        // LOCOMOTION + speed scaling (running plays faster the faster you actually move)
        boolean airborne = !player.isOnGround();
        String locoName;
        if (airborne) { locoName = anim("jump", "jumping"); b.locoSpeed = 1f; }
        else if (speed > 0.16) { locoName = anim("run", "running"); b.locoSpeed = speedMult(speed, runRef()); }
        else if (speed > 0.015) { locoName = anim("walk", "walking"); b.locoSpeed = speedMult(speed, walkRef()); }
        else { locoName = anim("idle", "idle"); b.locoSpeed = 1f; }
        if (!locoName.equals(b.locomotion)) {
            if (!b.locomotion.isEmpty()) safeStop(b, b.locomotion);
            safeAnimate(b, locoName, loco(b));
            b.locomotion = locoName;
        }

        // OVERLAY (arms) - what's in your hands, layered over the legs
        String ov = player.isSneaking() && isHoldingGun(player) ? anim("aim", "aim")
            : isHoldingGun(player) ? anim("hold_gun", "hold_gun")
            : hasHandItem(player) ? anim("hold_item", "hold_item")
            : "";
        if (!ov.equals(b.overlay)) {
            if (!b.overlay.isEmpty()) safeStop(b, b.overlay);
            if (!ov.isEmpty()) safeAnimate(b, ov, overlay());
            b.overlay = ov;
        }
    }

    /** Playback multiplier from actual speed vs the clip's reference speed, clamped to a sane range. */
    private float speedMult(double speed, double ref) {
        double m = (ref <= 0 ? 1.0 : speed / ref) * speedScale();
        return (float) Math.max(0.4, Math.min(2.5, m));
    }

    @Override
    public void trigger(Player player, String key) {
        Bound b = rigs.get(player.getUniqueId());
        if (b == null || b.tracker == null) return;
        String animName = anim(key, key);
        safeAnimate(b, animName, oneShot());   // arms only (override=false); legs keep their locomotion
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
