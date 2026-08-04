package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
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
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The SCP-1079 crate is a REAL 26.2 Sulfur Cube entity carrying the custom crate model. The sulfur
 * cube gives it genuine cube physics - players PUSH it around and PUNCH it (heavy, iron-block-like: it
 * barely budges, no big bounce). Its brain and fuse are off, so it never wanders or detonates, and it
 * can't be hurt or killed. Only an op in CREATIVE can pick it up by punching it. It's also a dispenser:
 * a hopper window that grows a gum packet every 30 min (starts with a few). Left alone away from its
 * placed spot, it returns home.
 */
public final class Scp1079Crate implements Listener {

    private static final String TAG = "lab.scp1079crate";        // the sulfur-cube body
    private static final String MODEL_TAG = "lab.scp1079model";  // the ItemDisplay riding inside it
    private static final int MAX_PACKETS = 5;
    private static final int START_PACKETS = 3;                  // a fresh crate holds more than one
    private static final long ACCRUE_MS = 30L * 60L * 1000L;
    private static final double TOUCH_RADIUS = 2.6;

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Set<UUID> crates = new HashSet<>();

    Scp1079Crate(LabraPlugin plugin, LabRegistry registry) { this.plugin = plugin; this.registry = registry; }

    private NamespacedKey packetsKey() { return plugin.keyOf("scp1079_crate_packets"); }
    private NamespacedKey accrueKey()  { return plugin.keyOf("scp1079_crate_accrue"); }
    private NamespacedKey touchedKey() { return plugin.keyOf("scp1079_crate_touched"); }
    private NamespacedKey homeKey()    { return plugin.keyOf("scp1079_crate_home"); }
    private NamespacedKey displayKey() { return plugin.keyOf("scp1079_crate_display"); }

    private long idleMs() { return Math.max(1, plugin.getConfig().getInt("scp1079.crate-idle-minutes", 5)) * 60_000L; }

    /** The real 26.2 sulfur cube type, or a magma cube as a fallback on an older server. */
    private EntityType cubeType() {
        EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("sulfur_cube"));
        return t != null ? t : EntityType.MAGMA_CUBE;
    }

    // ------------------------------------------------------------------ placement

    public void place(Player p, Location at) {
        at = at.getBlock().getLocation().add(0.5, 0, 0.5);
        spawn(at, START_PACKETS, System.currentTimeMillis(), homeString(at));
        if (registry.isScp1079Crate(p.getInventory().getItemInMainHand())) p.getInventory().getItemInMainHand().setAmount(0);
        else p.getInventory().getItemInOffHand().setAmount(0);
        at.getWorld().playSound(at, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
    }

    /** Spawn the sulfur-cube body + the custom crate model riding inside it. */
    private LivingEntity spawn(Location at, int packets, long accrueSince, String home) {
        long now = System.currentTimeMillis();
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildScp1079Crate());
            d.setTransformation(new Transformation(new Vector3f(0, 0f, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f(0, 0, 0, 1)));   // full cube in place of the hidden body
            d.setTeleportDuration(2);
            d.setPersistent(true);
            d.addScoreboardTag(MODEL_TAG);
        });
        Entity e = at.getWorld().spawnEntity(at, cubeType());
        // Keep it a REAL sulfur cube (natural push + its own hit sound). Only: hide the native model,
        // switch off the brain (no wandering, never primes its fuse), and make it unkillable via huge
        // health that we heal back each tick - so hitting it still plays the sulfur-cube sound.
        e.setInvisible(true);                 // native cube hidden; the custom model shows in its place
        e.setPersistent(true);
        e.addScoreboardTag(TAG);
        if (e instanceof Slime s) s.setSize(2);
        if (e instanceof Mob m) { m.setAware(false); m.setRemoveWhenFarAway(false); }
        if (e instanceof LivingEntity le) {
            le.setCollidable(true);           // players bump + shove it around naturally
            setAttr(le, Attribute.MAX_HEALTH, 400.0);
            le.setHealth(400.0);
        }
        var pdc = e.getPersistentDataContainer();
        pdc.set(packetsKey(), PersistentDataType.INTEGER, packets);
        pdc.set(accrueKey(), PersistentDataType.LONG, accrueSince);
        pdc.set(touchedKey(), PersistentDataType.LONG, now);
        pdc.set(homeKey(), PersistentDataType.STRING, home);
        pdc.set(displayKey(), PersistentDataType.STRING, display.getUniqueId().toString());
        display.teleport(modelPoint(e));
        crates.add(e.getUniqueId());
        return (LivingEntity) e;
    }

    private void setAttr(LivingEntity le, Attribute a, double v) {
        var inst = le.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private ItemDisplay displayOf(Entity body) {
        String id = body.getPersistentDataContainer().get(displayKey(), PersistentDataType.STRING);
        if (id == null) return null;
        Entity e = Bukkit.getEntity(UUID.fromString(id));
        return e instanceof ItemDisplay d ? d : null;
    }

    private Location modelPoint(Entity body) { return body.getLocation(); }

    private boolean hasPacket(Player p) {
        for (var it : p.getInventory().getContents()) if (registry.isScp1079Packet(it)) return true;
        return false;
    }

    private void touch(Entity body) {
        body.getPersistentDataContainer().set(touchedKey(), PersistentDataType.LONG, System.currentTimeMillis());
    }

    private boolean isCrate(Entity e) {
        return e instanceof LivingEntity && e.getScoreboardTags().contains(TAG);
    }

    // ------------------------------------------------------------------ packets

    private int accrue(Entity crate) {
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
        Entity crate = event.getRightClicked();
        if (!isCrate(crate)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        crates.add(crate.getUniqueId());
        touch(crate);
        open(event.getPlayer(), crate);
    }

    private void open(Player p, Entity crate) {
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
        Entity crate = Bukkit.getEntity(holder.crate());
        if (crate == null || !isCrate(crate)) { p.closeInventory(); return; }
        int count = accrue(crate);
        if (count <= 0) return;
        if (hasPacket(p)) {   // one packet per person - can't take another while you already carry one
            ActionBars.message(p, line("You can only carry one gum packet.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
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

    /** Punching lands for real - the sulfur cube plays its own hit sound and takes the knockback -
     *  but it never breaks (it's healed back to full each tick). ONLY an op in CREATIVE picks it up by
     *  punching; survival ops and everyone else just knock it around. */
    @EventHandler
    public void onCratePunch(EntityDamageByEntityEvent event) {
        Entity crate = event.getEntity();
        if (!isCrate(crate)) return;
        if (event.getDamager() instanceof Player p && p.isOp() && p.getGameMode() == GameMode.CREATIVE) {
            event.setCancelled(true);
            Location loc = crate.getLocation();
            despawn(crate);
            p.getInventory().addItem(registry.buildScp1079Crate()).values().forEach(l -> p.getWorld().dropItemNaturally(loc, l));
            loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.7f, 1.0f);
            return;
        }
        touch(crate);   // let the hit through: real sulfur-cube sound + knockback; it can't die
    }

    /** Environmental damage (fire/void/fall) can't kill it either - only entity hits land (for the
     *  sulfur-cube sound), and those never kill it because it's healed to full every tick. */
    @EventHandler
    public void onCrateHurt(EntityDamageEvent event) {
        if (isCrate(event.getEntity()) && !(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ follow + idle

    public void syncTick() {
        for (UUID id : new ArrayList<>(crates)) {
            Entity crate = Bukkit.getEntity(id);
            if (crate == null || !crate.getScoreboardTags().contains(TAG)) continue;
            if (crate.isDead()) { crates.remove(id); continue; }
            if (crate instanceof LivingEntity le) {   // unkillable: top the health back up
                var mh = le.getAttribute(Attribute.MAX_HEALTH);
                if (mh != null && le.getHealth() < mh.getValue() - 0.01) le.setHealth(mh.getValue());
            }
            // smooth push: gently shove it out from under any player pressed into it
            boolean near = false;
            for (Player pl : crate.getWorld().getNearbyPlayers(crate.getLocation(), 1.2)) {
                near = true;
                double dx = crate.getLocation().getX() - pl.getLocation().getX();
                double dz = crate.getLocation().getZ() - pl.getLocation().getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 > 0.0004 && d2 < 0.85 * 0.85) {
                    double len = Math.sqrt(d2);
                    crate.setVelocity(crate.getVelocity().add(new Vector(dx / len * 0.08, 0, dz / len * 0.08)));
                }
            }
            if (near) touch(crate);
            ItemDisplay d = displayOf(crate);
            if (d != null) d.teleport(modelPoint(crate));
        }
    }

    public void idleTick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(crates)) {
            Entity crate = Bukkit.getEntity(id);
            if (crate == null || crate.isDead() || !crate.getScoreboardTags().contains(TAG)) continue;
            var pdc = crate.getPersistentDataContainer();
            long touched = pdc.getOrDefault(touchedKey(), PersistentDataType.LONG, now);
            Location home = parseHome(pdc.get(homeKey(), PersistentDataType.STRING));
            if (home == null || now - touched < idleMs()) continue;
            if (crate.getWorld().equals(home.getWorld()) && crate.getLocation().distanceSquared(home) < 0.6) continue;
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

    private void despawn(Entity crate) {
        crates.remove(crate.getUniqueId());
        ItemDisplay d = displayOf(crate);
        if (d != null) d.remove();
        crate.remove();
    }

    // ------------------------------------------------------------------ lifecycle

    public void rescan() {
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e instanceof Mob && e.getScoreboardTags().contains(TAG)) crates.add(e.getUniqueId());
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
