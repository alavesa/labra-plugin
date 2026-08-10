package fi.alavesa.labra;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ANIMATED JAVA player rig.
 *
 * Binds an Animated Java model (exported from a .ajmodel Blockbench project as a DATAPACK + resource
 * pack) to a player as a third-person body double. Animated Java has no Java API - it ships a datapack
 * whose functions summon the rig and play its animations - so this class drives it entirely through
 * command dispatch:
 *
 *   summon:     execute as &lt;player&gt; at @s run function animated_java:&lt;ns&gt;/summon
 *   animate:    execute as &lt;rootUUID&gt; run function animated_java:&lt;ns&gt;/animations/&lt;anim&gt;/play
 *   stop:       execute as &lt;rootUUID&gt; run function animated_java:&lt;ns&gt;/animations/&lt;anim&gt;/stop
 *   remove:     execute as &lt;rootUUID&gt; run function animated_java:&lt;ns&gt;/remove
 *
 * The exact namespace + function layout depend on your AJ export and AJ version, so every path is
 * configurable under `ajrig:` in config.yml (see CUSTOM-MODELS-AND-ANIMATIONS.md). Nothing here works
 * until you've exported an AJ datapack with a matching namespace and reloaded it - this class only
 * dispatches the functions AJ generates.
 *
 * Lifecycle: {@link #spawn} runs the summon function at the player, finds the freshly-summoned root
 * entity (by AJ's root tag), tags it as ours and makes the vanilla player invisible. Every tick
 * {@link #run} teleports the root onto the player and picks a locomotion animation (idle/walk/run).
 * {@link #playOneShot} fires a one-shot clip (reload/fire) that holds until it finishes, then
 * locomotion resumes. {@link #despawn} removes the rig and unhides the player.
 */
public final class AjRig implements Listener, Runnable {

    private final LabraPlugin plugin;
    private final Map<UUID, Bound> rigs = new ConcurrentHashMap<>();

    AjRig(LabraPlugin plugin) { this.plugin = plugin; }

    /** State of one player's bound rig. */
    private static final class Bound {
        UUID root;            // the AJ rig's root entity
        String locomotion = "";   // current locomotion animation key (idle/walk/run)
        String oneShot = "";      // a playing one-shot clip (reload/fire), or "" if none
        long oneShotUntil;    // tick timestamp the one-shot ends
        Location last;        // to measure walk speed
    }

    // ---- config helpers -------------------------------------------------

    private String ns()       { return plugin.getConfig().getString("ajrig.namespace", "player_rig"); }
    private String rootTag()  { return plugin.getConfig().getString("ajrig.root-tag", "aj." + ns() + ".root"); }
    private String anim(String key, String def) { return plugin.getConfig().getString("ajrig.animations." + key, def); }
    /** function path templates - {ns} and {anim} are substituted. */
    private String fnSummon() { return plugin.getConfig().getString("ajrig.fn.summon", "animated_java:{ns}/summon"); }
    private String fnRemove() { return plugin.getConfig().getString("ajrig.fn.remove", "animated_java:{ns}/remove"); }
    private String fnPlay()   { return plugin.getConfig().getString("ajrig.fn.play", "animated_java:{ns}/animations/{anim}/play"); }
    private String fnStop()   { return plugin.getConfig().getString("ajrig.fn.stop", "animated_java:{ns}/animations/{anim}/stop"); }

    private String fn(String template, String anim) {
        return template.replace("{ns}", ns()).replace("{anim}", anim == null ? "" : anim);
    }

    public boolean hasRig(Player p) { return rigs.containsKey(p.getUniqueId()); }

    // ---- lifecycle ------------------------------------------------------

    /** Summon an AJ rig and bind it to the player. Returns false if the root couldn't be found (usually
     *  means the AJ datapack isn't installed / the namespace is wrong). */
    public boolean spawn(Player player) {
        if (rigs.containsKey(player.getUniqueId())) return true;
        // remember which roots already exist so we can identify the new one
        var before = nearbyRoots(player);
        console("execute as " + player.getName() + " at @s run function " + fn(fnSummon(), null));

        // AJ summons on the same tick; grab the new root next tick and bind it
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Entity root = null;
            for (Entity e : player.getNearbyEntities(3, 3, 3))
                if (e.getScoreboardTags().contains(rootTag()) && !before.contains(e.getUniqueId())) { root = e; break; }
            if (root == null) {
                player.sendMessage("§cAJ rig summon failed - is the '" + ns() + "' datapack installed? (/reload)");
                return;
            }
            root.addScoreboardTag("labaj." + player.getUniqueId());
            Bound b = new Bound();
            b.root = root.getUniqueId();
            b.last = player.getLocation();
            rigs.put(player.getUniqueId(), b);
            player.setInvisible(true);
            pose(player, b);
            player.sendMessage("§aAJ rig bound (" + ns() + ").");
        });
        return true;
    }

    public void despawn(Player player) {
        Bound b = rigs.remove(player.getUniqueId());
        if (b != null && b.root != null) {
            console("execute as " + b.root + " run function " + fn(fnRemove(), null));
            Entity e = Bukkit.getEntity(b.root);
            if (e != null && !e.isDead()) e.remove();   // belt-and-suspenders if the AJ remove fn is absent
        }
        if (player.isOnline()) player.setInvisible(false);
    }

    /** Roots of AJ rigs near the player (used to find the one we just summoned). */
    private java.util.Set<UUID> nearbyRoots(Player player) {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        for (Entity e : player.getNearbyEntities(3, 3, 3))
            if (e.getScoreboardTags().contains(rootTag())) out.add(e.getUniqueId());
        return out;
    }

    // ---- per-tick follow + locomotion ----------------------------------

    @Override
    public void run() {
        for (Map.Entry<UUID, Bound> e : rigs.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            pose(p, e.getValue());
        }
    }

    private void pose(Player player, Bound b) {
        Entity root = Bukkit.getEntity(b.root);
        if (root == null || root.isDead()) { rigs.remove(player.getUniqueId()); if (player.isOnline()) player.setInvisible(false); return; }

        // follow: put the rig root on the player, facing the player's body yaw. AJ interpolates its
        // own bones; a short teleport keeps the root's motion smooth.
        Location at = player.getLocation();
        root.teleport(at);
        // (root is an AJ display root; body yaw is applied via teleport rotation)

        // locomotion state machine (skipped while a one-shot clip is playing)
        long tick = player.getWorld().getFullTime();
        if (!b.oneShot.isEmpty()) {
            if (tick >= b.oneShotUntil) { stop(b, b.oneShot); b.oneShot = ""; b.locomotion = ""; }
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
            if (!b.locomotion.isEmpty()) stop(b, b.locomotion);
            play(b, want);
            b.locomotion = want;
        }
    }

    // ---- animation control ---------------------------------------------

    /** Play a looping/locomotion animation by AJ animation name on this rig. */
    private void play(Bound b, String animName) {
        if (b.root == null || animName == null || animName.isEmpty()) return;
        console("execute as " + b.root + " run function " + fn(fnPlay(), animName));
    }

    private void stop(Bound b, String animName) {
        if (b.root == null || animName == null || animName.isEmpty()) return;
        console("execute as " + b.root + " run function " + fn(fnStop(), animName));
    }

    /**
     * Fire a ONE-SHOT clip (reload / fire) that owns the rig until it finishes, then locomotion
     * resumes. durationTicks should match the animation's length (frames / 20 * ... ) - keep it in the
     * config so it can be tuned to the exported clip.
     */
    public void playOneShot(Player player, String key) {
        Bound b = rigs.get(player.getUniqueId());
        if (b == null) return;
        String animName = anim(key, key);
        int durationTicks = plugin.getConfig().getInt("ajrig.durations." + key, 20);
        if (!b.locomotion.isEmpty()) { stop(b, b.locomotion); b.locomotion = ""; }
        if (!b.oneShot.isEmpty()) stop(b, b.oneShot);
        play(b, animName);
        b.oneShot = animName;
        b.oneShotUntil = player.getWorld().getFullTime() + Math.max(1, durationTicks);
    }

    /** Trigger a one-shot on a player's rig by KEY (e.g. from the Guns plugin on fire/reload). */
    public void trigger(Player player, String key) { playOneShot(player, key); }

    // ---- first-person bypass (DESIGN ONLY - not implemented yet) --------

    /**
     * FIRST-PERSON BYPASS (planned, not implemented).
     *
     * Display/AJ entities visibly stutter in the OWNER's own first-person view (they interpolate a tick
     * behind the camera). The plan:
     *   1. Hide the whole AJ rig from its owner only - iterate the rig's part entities and call
     *      player.hideEntity(plugin, part) for the owner. Every OTHER player still sees the AJ rig and
     *      its animations normally (the datapack broadcasts them to all viewers).
     *   2. Give the owner a normal first-person weapon item whose recoil/reload are the client-side
     *      flipbook / CMD-frame models (see the Guns equip-anim system) - no display-entity stutter.
     *   3. Keep the AJ rig's animation STATE in sync so observers see reload/fire while the owner sees
     *      the first-person item animation for the same action (call trigger() and the item swap together).
     *
     * Implementation note: AJ's rig parts don't expose a per-owner visibility API, so this hides the
     * bone entities directly via the Bukkit hideEntity API once we can enumerate them (they share the
     * rig's instance tag). Left as a follow-up per the request.
     */
    public void applyFirstPersonBypass(Player owner) {
        // intentionally unimplemented for now - see javadoc above
    }

    // ---- helpers --------------------------------------------------------

    private void console(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /** No hard dependency on Guns: read its item tag to know if a gun is held. */
    private boolean isHoldingGun(Player p) {
        var it = p.getInventory().getItemInMainHand();
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer()
            .has(new org.bukkit.NamespacedKey("guns", "id"), org.bukkit.persistence.PersistentDataType.STRING);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { despawn(e.getPlayer()); }

    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(rigs.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) despawn(p);
            else {
                Bound b = rigs.remove(id);
                if (b != null && b.root != null) {
                    Entity e = Bukkit.getEntity(b.root);
                    if (e != null) e.remove();
                }
            }
        }
    }
}
