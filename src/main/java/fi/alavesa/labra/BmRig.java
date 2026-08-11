package fi.alavesa.labra;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * BETTERMODEL player rig - public front end. This class references NO BetterModel types, so it always
 * loads even when BetterModel isn't installed. All BetterModel API usage lives in {@link BmRigBackend},
 * which is only instantiated (and therefore only class-linked) when the plugin is actually present -
 * otherwise loading a BetterModel type here would crash Labra's enable on servers without BetterModel.
 *
 * A BetterModel rig is a third-person body double built from a Blockbench model loaded into BetterModel.
 * BetterModel tracks the player entity itself, so the rig follows and renders to viewers automatically;
 * we only choose the locomotion animation each tick and fire one-shots (fire/reload) on demand.
 * Enable/use with /lab bmrig and /lab bmanim. See CUSTOM-MODELS-AND-ANIMATIONS.md.
 */
public final class BmRig implements Listener, Runnable {

    private final RigBackend backend;   // null when BetterModel isn't installed

    /** BM-free contract the backend implements, so this front end never touches a BetterModel class. */
    interface RigBackend {
        boolean spawn(Player player);
        boolean has(Player player);
        void despawn(Player player);
        void trigger(Player player, String key);
        void tickAll();
        void shutdown();
    }

    BmRig(LabraPlugin plugin) {
        RigBackend b = null;
        if (Bukkit.getPluginManager().getPlugin("BetterModel") != null) {
            b = new BmRigBackend(plugin);   // only now is any BetterModel class linked
            plugin.getLogger().info("BetterModel detected - /lab bmrig available.");
        }
        this.backend = b;
    }

    public boolean available() { return backend != null; }
    public boolean hasRig(Player p) { return backend != null && backend.has(p); }
    public boolean spawn(Player p) { return backend != null && backend.spawn(p); }
    public void despawn(Player p) { if (backend != null) backend.despawn(p); }
    public void trigger(Player p, String key) { if (backend != null) backend.trigger(p, key); }

    @Override public void run() { if (backend != null) backend.tickAll(); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { if (backend != null) backend.despawn(e.getPlayer()); }
    public void shutdown() { if (backend != null) backend.shutdown(); }
}
