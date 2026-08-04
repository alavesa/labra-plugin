package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The SCP-1079 crate: a custom-modelled sulfur cube you can PUSH around by walking into it (shove it
 * out of its containment chamber for a delivery/theft quest). It's also a dispenser - a hopper window
 * that grows one gum packet every 30 minutes, up to five, with no restock countdown shown. Left alone
 * for a while away from its placed spot, it despawns and reappears back home.
 */
public final class Scp1079Crate implements Listener {

    private static final String TAG = "lab.scp1079crate";
    private static final int MAX_PACKETS = 5;
    private static final long ACCRUE_MS = 30L * 60L * 1000L;   // one packet / 30 min
    private static final double PUSH_RADIUS = 0.85;            // how close a player must be to shove it
    private static final double PUSH_STEP = 0.14;              // metres per physics tick while pushed
    private static final double GRAVITY_STEP = 0.25;

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Set<UUID> crates = new HashSet<>();   // tracked crate ArmorStand ids (loaded ones)
    private int tick;

    Scp1079Crate(LabraPlugin plugin, LabRegistry registry) { this.plugin = plugin; this.registry = registry; }

    private org.bukkit.NamespacedKey packetsKey() { return plugin.keyOf("scp1079_crate_packets"); }
    private org.bukkit.NamespacedKey accrueKey()  { return plugin.keyOf("scp1079_crate_accrue"); }
    private org.bukkit.NamespacedKey touchedKey() { return plugin.keyOf("scp1079_crate_touched"); }
    private org.bukkit.NamespacedKey homeKey()    { return plugin.keyOf("scp1079_crate_home"); }
    private org.bukkit.NamespacedKey displayKey() { return plugin.keyOf("scp1079_crate_display"); }

    private long idleMs() { return Math.max(1, plugin.getConfig().getInt("scp1079.crate-idle-minutes", 5)) * 60_000L; }

    // ------------------------------------------------------------------ placement

    /** Place a crate from the held item, homing it here. */
    public void place(Player p, Location at) {
        at = at.getBlock().getLocation().add(0.5, 0, 0.5);   // centre on the block
        spawn(at, 1, System.currentTimeMillis(), homeString(at));
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** Spawn the paired display + hitbox for a crate at a spot, carrying its packet count and home. */
    private ArmorStand spawn(Location at, int packets, long accrueSince, String home) {
        long now = System.currentTimeMillis();
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildScp1079Crate());
            d.setTransformation(new Transformation(new Vector3f(0, 0f, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f(0, 0, 0, 1)));
            d.setPersistent(true);
            d.addScoreboardTag(TAG);
        });
        ArmorStand stand = at.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setVisible(false); a.setGravity(false); a.setBasePlate(false); a.setMarker(false);
            a.setPersistent(true); a.setRemoveWhenFarAway(false);
            a.addScoreboardTag(TAG);
            var pdc = a.getPersistentDataContainer();
            pdc.set(packetsKey(), PersistentDataType.INTEGER, packets);
            pdc.set(accrueKey(), PersistentDataType.LONG, accrueSince);
            pdc.set(touchedKey(), PersistentDataType.LONG, now);
            pdc.set(homeKey(), PersistentDataType.STRING, home);
            pdc.set(displayKey(), PersistentDataType.STRING, display.getUniqueId().toString());
        });
        crates.add(stand.getUniqueId());
        return stand;
    }

    private ItemDisplay displayOf(ArmorStand stand) {
        String id = stand.getPersistentDataContainer().get(displayKey(), PersistentDataType.STRING);
        if (id == null) return null;
        Entity e = Bukkit.getEntity(UUID.fromString(id));
        return e instanceof ItemDisplay d ? d : null;
    }

    private void touch(ArmorStand stand) {
        stand.getPersistentDataContainer().set(touchedKey(), PersistentDataType.LONG, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ packets

    /** How many packets the crate holds now, topping up any accrued (one per 30 min, up to five). */
    private int accrue(ArmorStand crate) {
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
        Entity e = event.getRightClicked();
        if (!(e instanceof ArmorStand stand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        crates.add(stand.getUniqueId());
        touch(stand);
        open(event.getPlayer(), stand);
    }

    private void open(Player p, ArmorStand crate) {
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
        if (!(ent instanceof ArmorStand crate) || !crate.getScoreboardTags().contains(TAG)) { p.closeInventory(); return; }
        int count = accrue(crate);
        if (count <= 0) return;
        if (p.getInventory().firstEmpty() == -1) {
            ActionBars.message(p, line("No room for a packet."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        p.getInventory().addItem(registry.buildScp1079Packet());
        crate.getPersistentDataContainer().set(packetsKey(), PersistentDataType.INTEGER, count - 1);
        crate.getPersistentDataContainer().set(accrueKey(), PersistentDataType.LONG, System.currentTimeMillis());
        touch(crate);
        event.getInventory().setItem(slot, null);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.7f, 1.3f);
        ActionBars.message(p, line("You take a gum packet.").color(NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onBreak(EntityDamageByEntityEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p)) return;
        Location loc = e.getLocation();
        crates.remove(e.getUniqueId());
        for (Entity part : loc.getNearbyEntities(1.5, 2.0, 1.5)) if (part.getScoreboardTags().contains(TAG)) part.remove();
        p.getInventory().addItem(registry.buildScp1079Crate()).values().forEach(l -> p.getWorld().dropItemNaturally(loc, l));
        loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.7f, 1.0f);
    }

    // ------------------------------------------------------------------ physics: pushing + gravity

    /** Every couple of ticks: shove each crate away from any player pressing into it, and let it fall
     *  to the ground. Being pushed counts as interaction (resets the idle-return timer). */
    public void physicsTick() {
        tick++;
        for (UUID id : new ArrayList<>(crates)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof ArmorStand stand)) { continue; }   // unloaded/gone - rescan will refresh
            if (stand.isDead()) { crates.remove(id); continue; }
            Location loc = stand.getLocation();
            double nx = loc.getX(), ny = loc.getY(), nz = loc.getZ();
            boolean moved = false;

            // gravity: fall while the block under the crate's base is passable
            if (loc.clone().add(0, -0.1, 0).getBlock().isPassable() && loc.clone().add(0, -1, 0).getBlock().isPassable()) {
                ny -= GRAVITY_STEP; moved = true;
            }

            // push: find the closest player pressed into the crate, shove it away from them
            Player pusher = null; double best = PUSH_RADIUS * PUSH_RADIUS;
            for (Player p : loc.getWorld().getNearbyPlayers(loc, 1.4)) {
                double dx = loc.getX() - p.getLocation().getX(), dz = loc.getZ() - p.getLocation().getZ();
                double d2 = dx * dx + dz * dz;
                if (Math.abs(loc.getY() - p.getLocation().getY()) <= 1.6 && d2 < best) { best = d2; pusher = p; }
            }
            if (pusher != null) {
                double dx = loc.getX() - pusher.getLocation().getX(), dz = loc.getZ() - pusher.getLocation().getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.05) {
                    double tx = nx + dx / len * PUSH_STEP, tz = nz + dz / len * PUSH_STEP;
                    Location dest = new Location(loc.getWorld(), tx, ny + 0.1, tz);
                    Location destTop = dest.clone().add(0, 0.9, 0);
                    if (dest.getBlock().isPassable() && destTop.getBlock().isPassable()) {   // don't shove into walls
                        nx = tx; nz = tz; moved = true;
                        touch(stand);
                        if (tick % 6 == 0) loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_STEP, 0.5f, 0.8f);
                    }
                }
            }

            if (moved) {
                Location dest = new Location(loc.getWorld(), nx, ny, nz, loc.getYaw(), 0f);
                stand.teleport(dest);
                ItemDisplay d = displayOf(stand);
                if (d != null) d.teleport(dest);
            }
        }
    }

    /** Every so often: a crate left alone away from home despawns and reappears at its placed spot. */
    public void idleTick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(crates)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof ArmorStand stand) || stand.isDead()) continue;
            var pdc = stand.getPersistentDataContainer();
            long touched = pdc.getOrDefault(touchedKey(), PersistentDataType.LONG, now);
            Location home = parseHome(pdc.get(homeKey(), PersistentDataType.STRING));
            if (home == null || now - touched < idleMs()) continue;
            if (stand.getLocation().distanceSquared(home) < 0.5) continue;   // already home
            int packets = pdc.getOrDefault(packetsKey(), PersistentDataType.INTEGER, 0);
            long accrue = pdc.getOrDefault(accrueKey(), PersistentDataType.LONG, now);
            String homeStr = pdc.get(homeKey(), PersistentDataType.STRING);
            Location was = stand.getLocation();
            despawn(stand);
            was.getWorld().spawnParticle(Particle.CLOUD, was.clone().add(0, 0.5, 0), 15, 0.2, 0.3, 0.2, 0.02);
            was.getWorld().playSound(was, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.8f);
            spawn(home, packets, accrue, homeStr);
            home.getWorld().spawnParticle(Particle.CLOUD, home.clone().add(0, 0.5, 0), 15, 0.2, 0.3, 0.2, 0.02);
            home.getWorld().playSound(home, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.0f);
        }
    }

    private void despawn(ArmorStand stand) {
        crates.remove(stand.getUniqueId());
        ItemDisplay d = displayOf(stand);
        if (d != null) d.remove();
        stand.remove();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Rebuild the tracked set from every loaded world (catches crates whose chunks just loaded). */
    public void rescan() {
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e instanceof ArmorStand && e.getScoreboardTags().contains(TAG)) crates.add(e.getUniqueId());
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

    private Component line(String text) {
        return Component.text(text, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
    }

    private record CrateHolder(UUID crate) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
