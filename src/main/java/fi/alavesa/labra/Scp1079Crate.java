package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The SCP-1079 crate is a REAL pushable object: an invisible {@link Slime} body (permanently invisible -
 * no potion, no particles - with its brain switched off but its physics kept, so Minecraft itself pushes
 * it, drops it with gravity, and stops it at walls) carrying a custom-modelled sulfur cube. Shove it out
 * of the containment chamber for delivery/theft quests. It's also a dispenser: a hopper window that grows
 * one gum packet every 30 minutes (up to five, no restock countdown). Left alone away from its placed
 * spot, it returns home.
 */
public final class Scp1079Crate implements Listener {

    private static final String TAG = "lab.scp1079crate";
    private static final int MAX_PACKETS = 5;
    private static final long ACCRUE_MS = 30L * 60L * 1000L;   // one packet / 30 min
    private static final double TOUCH_RADIUS = 2.6;            // a player this close counts as "using" it

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Set<UUID> crates = new HashSet<>();   // tracked crate Slime ids (loaded ones)

    Scp1079Crate(LabraPlugin plugin, LabRegistry registry) { this.plugin = plugin; this.registry = registry; }

    private org.bukkit.NamespacedKey packetsKey() { return plugin.keyOf("scp1079_crate_packets"); }
    private org.bukkit.NamespacedKey accrueKey()  { return plugin.keyOf("scp1079_crate_accrue"); }
    private org.bukkit.NamespacedKey touchedKey() { return plugin.keyOf("scp1079_crate_touched"); }
    private org.bukkit.NamespacedKey homeKey()    { return plugin.keyOf("scp1079_crate_home"); }
    private org.bukkit.NamespacedKey displayKey() { return plugin.keyOf("scp1079_crate_display"); }

    private long idleMs() { return Math.max(1, plugin.getConfig().getInt("scp1079.crate-idle-minutes", 5)) * 60_000L; }

    // ------------------------------------------------------------------ placement

    public void place(Player p, Location at) {
        at = at.getBlock().getLocation().add(0.5, 0, 0.5);
        spawn(at, 1, System.currentTimeMillis(), homeString(at));
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** Spawn the invisible slime body + its sulfur-cube display, carrying packet count and home. */
    private Slime spawn(Location at, int packets, long accrueSince, String home) {
        long now = System.currentTimeMillis();
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildScp1079Crate());
            d.setTransformation(new Transformation(new Vector3f(0, 0f, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f(0, 0, 0, 1)));
            d.setTeleportDuration(2);   // follow the body smoothly as it's shoved around
            d.setPersistent(true);
            d.addScoreboardTag(TAG);
        });
        Slime slime = at.getWorld().spawn(at, Slime.class, s -> {
            s.setSize(2);                 // ~1-block cube hitbox
            s.setAware(false);            // no brain: it won't wander, but physics (gravity + being pushed) stay on
            s.setInvisible(true);         // permanent invisibility, no potion and NO particles
            s.setSilent(true);
            s.setCollidable(true);        // players bump into it and shove it
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.addScoreboardTag(TAG);
            var pdc = s.getPersistentDataContainer();
            pdc.set(packetsKey(), PersistentDataType.INTEGER, packets);
            pdc.set(accrueKey(), PersistentDataType.LONG, accrueSince);
            pdc.set(touchedKey(), PersistentDataType.LONG, now);
            pdc.set(homeKey(), PersistentDataType.STRING, home);
            pdc.set(displayKey(), PersistentDataType.STRING, display.getUniqueId().toString());
        });
        display.teleport(slime.getLocation());
        crates.add(slime.getUniqueId());
        return slime;
    }

    private ItemDisplay displayOf(Slime slime) {
        String id = slime.getPersistentDataContainer().get(displayKey(), PersistentDataType.STRING);
        if (id == null) return null;
        Entity e = Bukkit.getEntity(UUID.fromString(id));
        return e instanceof ItemDisplay d ? d : null;
    }

    private void touch(Slime slime) {
        slime.getPersistentDataContainer().set(touchedKey(), PersistentDataType.LONG, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ packets

    private int accrue(Slime crate) {
        var pdc = crate.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        int count = pdc.getOrDefault(packetsKey(), PersistentDataType.INTEGER, 0);
        long since = pdc.getOrDefault(accrueKey(), PersistentDataType.LONG, now);
        if (count < MAX_PACKETS) {
            long gained = (now - since) / ACCRUE_MS;
            if (gained > 0) {
                count = (int) Math.min(MAX_PACKETS, count + gained);
                since = count >= MAX_PACKETS ? now : since + gained * ACCRUE_MS;
                pdc.set(packetsKey(), PersistentDataType.INTEGER, count);
                pdc.set(accrueKey(), PersistentDataType.LONG, since);
            }
        } else {
            pdc.set(accrueKey(), PersistentDataType.LONG, now);
        }
        return count;
    }

    @EventHandler
    public void onCrateRightClick(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Slime crate) || !crate.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        crates.add(crate.getUniqueId());
        touch(crate);
        open(event.getPlayer(), crate);
    }

    private void open(Player p, Slime crate) {
        int count = accrue(crate);
        Inventory inv = Bukkit.createInventory(new CrateHolder(crate.getUniqueId()), InventoryType.HOPPER,
            Component.text("SCP-1079 Crate", NamedTextColor.LIGHT_PURPLE));
        for (int i = 0; i < count && i < MAX_PACKETS; i++) inv.setItem(i, registry.buildScp1079Packet());
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.1f);
    }

    @EventHandler
    public void onCrateClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CrateHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= MAX_PACKETS || !(event.getWhoClicked() instanceof Player p)) return;
        if (!registry.isScp1079Packet(event.getCurrentItem())) return;
        Entity ent = Bukkit.getEntity(holder.crate());
        if (!(ent instanceof Slime crate) || !crate.getScoreboardTags().contains(TAG)) { p.closeInventory(); return; }
        int count = accrue(crate);
        if (count <= 0) return;
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a packet.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        p.getInventory().addItem(registry.buildScp1079Packet());
        crate.getPersistentDataContainer().set(packetsKey(), PersistentDataType.INTEGER, count - 1);
        crate.getPersistentDataContainer().set(accrueKey(), PersistentDataType.LONG, System.currentTimeMillis());
        touch(crate);
        event.getInventory().setItem(slot, null);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.3f);
        ActionBars.message(p, line("You take a gum packet.", NamedTextColor.LIGHT_PURPLE));
    }

    /** Hitting the crate (the sulfur cube) reclaims it as an item. */
    @EventHandler
    public void onCrateAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Slime crate) || !crate.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p)) return;
        Location loc = crate.getLocation();
        despawn(crate);
        p.getInventory().addItem(registry.buildScp1079Crate()).values().forEach(l -> p.getWorld().dropItemNaturally(loc, l));
        loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.7f, 1.0f);
    }

    /** The crate never takes real damage - it can't be killed (a slime would split), only reclaimed. */
    @EventHandler
    public void onCrateHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Slime s && s.getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ follow + idle

    /** Every tick: glue each sulfur-cube display to its (physics-driven) slime body, and count a nearby
     *  player as interaction so a crate someone is pushing/using doesn't try to return home under them. */
    public void syncTick() {
        for (UUID id : new ArrayList<>(crates)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Slime crate)) continue;
            if (crate.isDead()) { crates.remove(id); continue; }
            ItemDisplay d = displayOf(crate);
            if (d != null) d.teleport(crate.getLocation());
            if (!crate.getWorld().getNearbyPlayers(crate.getLocation(), TOUCH_RADIUS).isEmpty()) touch(crate);
        }
    }

    /** Every so often: a crate left alone away from home despawns and reappears at its placed spot. */
    public void idleTick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(crates)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Slime crate) || crate.isDead()) continue;
            var pdc = crate.getPersistentDataContainer();
            long touched = pdc.getOrDefault(touchedKey(), PersistentDataType.LONG, now);
            Location home = parseHome(pdc.get(homeKey(), PersistentDataType.STRING));
            if (home == null || now - touched < idleMs()) continue;
            if (crate.getLocation().getWorld().equals(home.getWorld())
                && crate.getLocation().distanceSquared(home) < 0.6) continue;   // already home
            int packets = pdc.getOrDefault(packetsKey(), PersistentDataType.INTEGER, 0);
            long accrue = pdc.getOrDefault(accrueKey(), PersistentDataType.LONG, now);
            String homeStr = pdc.get(homeKey(), PersistentDataType.STRING);
            Location was = crate.getLocation();
            despawn(crate);
            was.getWorld().playSound(was, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.8f);
            spawn(home, packets, accrue, homeStr);
            home.getWorld().playSound(home, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.0f);
        }
    }

    private void despawn(Slime crate) {
        crates.remove(crate.getUniqueId());
        ItemDisplay d = displayOf(crate);
        if (d != null) d.remove();
        crate.remove();
    }

    // ------------------------------------------------------------------ lifecycle

    public void rescan() {
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e instanceof Slime && e.getScoreboardTags().contains(TAG)) crates.add(e.getUniqueId());
    }

    public void shutdown() { crates.clear(); }

    private String homeString(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ();
    }

    private Location parseHome(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        } catch (NumberFormatException ex) { return null; }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private record CrateHolder(UUID crate) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
