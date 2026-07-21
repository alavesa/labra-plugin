package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fire that lasts, and the extinguishers that put it out. Any fire (SCP-457,
 * accidents, anything) is kept alive for 30 minutes - the vanilla fade is
 * cancelled while it's young - so a breach fire is a real problem you have to
 * deal with, not something that flickers out on its own.
 *
 * The fire extinguisher item sprays a cone of retardant: it clears fire blocks
 * and douses burning entities in front of the user. Extinguishers hang in
 * custom-modelled WALL MOUNTS - right-click a mount to take the extinguisher out
 * or slot it back in for next time.
 */
public final class FireManager implements Listener, Runnable {

    private static final long FIRE_LIFETIME_MS = 30L * 60L * 1000L;   // 30 minutes
    private static final String TAG_MOUNT = "lab.extmount";
    private static final String TAG_MOUNT_CAN = "lab.extmount.can";

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Map<String, Long> fires = new HashMap<>();   // block key -> ignite time
    private final Map<UUID, Long> sprayCd = new HashMap<>();

    public FireManager(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ------------------------------------------------------- persistent fire

    @EventHandler
    public void onIgnite(BlockIgniteEvent event) {
        fires.put(key(event.getBlock()), System.currentTimeMillis());
    }

    /** Fire fades on its own after seconds - cancel that until it's 30 min old. */
    @EventHandler
    public void onFade(BlockFadeEvent event) {
        if (event.getBlock().getType() != Material.FIRE) return;
        String k = key(event.getBlock());
        Long lit = fires.get(k);
        long now = System.currentTimeMillis();
        if (lit == null) { fires.put(k, now); event.setCancelled(true); return; }
        if (now - lit < FIRE_LIFETIME_MS) event.setCancelled(true);
        else fires.remove(k);
    }

    /** Housekeeping: drop tracking for blocks that are no longer fire or have expired. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        fires.entrySet().removeIf(e -> {
            if (now - e.getValue() >= FIRE_LIFETIME_MS) return true;
            String[] p = e.getKey().split(":");
            var w = Bukkit.getWorld(p[0]);
            if (w == null) return true;
            return w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]))
                .getType() != Material.FIRE;
        });
    }

    // ------------------------------------------------------------- spraying

    @EventHandler
    public void onSpray(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        var a = event.getAction();
        if (a != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && a != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!registry.isExtinguisher(held)) return;
        event.setCancelled(true);   // no vanilla brick placement
        long now = System.currentTimeMillis();
        Long until = sprayCd.get(player.getUniqueId());
        if (until != null && now < until) return;
        sprayCd.put(player.getUniqueId(), now + 120);

        int charge = registry.extinguisherCharge(held);
        if (charge <= 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.7f, 1.4f);
            player.sendActionBar(Component.text("The extinguisher is empty.", NamedTextColor.GRAY));
            return;
        }
        registry.setExtinguisherCharge(held, charge - 1);
        spray(player);
        player.sendActionBar(Component.text("Extinguisher charge: "
            + Math.round((charge - 1) / (float) LabRegistry.EXTINGUISHER_MAX * 100) + "%", NamedTextColor.GRAY));
    }

    private void spray(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        // aim point: the block we're looking at, or 6m ahead
        RayTraceResult ray = player.getWorld().rayTraceBlocks(eye, dir, 6.0);
        Location aim = ray != null ? ray.getHitPosition().toLocation(player.getWorld())
            : eye.clone().add(dir.clone().multiply(6));

        // the jet of retardant
        for (double d = 1.0; d <= eye.distance(aim) + 0.5; d += 0.5) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            player.getWorld().spawnParticle(Particle.CLOUD, p, 3, 0.08, 0.08, 0.08, 0.02);
        }
        player.getWorld().playSound(eye, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.8f, 1.1f);

        // clear fire blocks around the aim point
        int r = 3;
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            Block b = aim.getBlock().getRelative(x, y, z);
            if (b.getType() == Material.FIRE) {
                b.setType(Material.AIR);
                fires.remove(key(b));
                b.getWorld().spawnParticle(Particle.SMOKE, b.getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0.01);
            }
        }
        // douse anything burning nearby (players, mobs)
        for (var ent : player.getWorld().getNearbyEntities(aim, 4, 4, 4)) {
            if (ent instanceof LivingEntity le && le.getFireTicks() > 0) le.setFireTicks(0);
        }
    }

    // ------------------------------------------------------- ceiling sprinklers

    private final Map<String, Long> sprinklers = new HashMap<>();   // block key -> active until
    private static final long SPRINKLER_MS = 15_000;                // a burst runs 15 seconds
    private static final int SPRINKLER_RADIUS = 8;                  // a button wakes sprinklers this near

    /** A button press wakes every hanging-roots sprinkler in the room. (Sprinklers ARE
     *  hanging_roots blocks - re-textured in the pack - so any button in the room is a
     *  fire-alarm pull.) */
    @EventHandler
    public void onButton(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block b = event.getClickedBlock();
        if (b == null || !org.bukkit.Tag.BUTTONS.isTagged(b.getType())) return;
        long until = System.currentTimeMillis() + SPRINKLER_MS;
        int found = 0;
        int r = SPRINKLER_RADIUS;
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            Block bl = b.getRelative(x, y, z);
            if (bl.getType() == Material.HANGING_ROOTS) { sprinklers.put(key(bl), until); found++; }
        }
        if (found > 0) {
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.4f);
            for (Player p : b.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(b.getLocation()) < 400)
                    p.sendActionBar(Component.text("Sprinklers activated.", NamedTextColor.AQUA));
            }
        }
    }

    /** Scheduled: active sprinklers rain down, clearing fire and dousing anyone burning. */
    public void sprinklerTick() {
        if (sprinklers.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = sprinklers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now > e.getValue()) { it.remove(); continue; }
            String[] p = e.getKey().split(":");
            var w = Bukkit.getWorld(p[0]);
            if (w == null) { it.remove(); continue; }
            Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            if (b.getType() != Material.HANGING_ROOTS) { it.remove(); continue; }
            Location spout = b.getLocation().add(0.5, 0, 0.5);
            w.spawnParticle(Particle.FALLING_WATER, spout.clone().subtract(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0);
            w.spawnParticle(Particle.SPLASH, spout.clone().subtract(0, 1.2, 0), 4, 0.25, 0.1, 0.25, 0);
            // clear fire in the column below (down to the floor), and any that spread nearby
            for (int dy = 0; dy <= 10; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Block below = b.getRelative(dx, -dy, dz);
                if (below.getType() == Material.FIRE) { below.setType(Material.AIR); fires.remove(key(below)); }
            }
            for (var ent : w.getNearbyEntities(spout.clone().subtract(0, 4, 0), 2.5, 5, 2.5)) {
                if (ent instanceof LivingEntity le && le.getFireTicks() > 0) le.setFireTicks(0);
            }
        }
    }

    // -------------------------------------------------------------- mounts

    /** Place a wall mount where the player is looking, cradling one extinguisher. */
    public boolean placeMount(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
            player.getEyeLocation().getDirection(), 5.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return false;
        var face = ray.getHitBlockFace();
        Block wall = ray.getHitBlock();
        Location at = wall.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
        // face outward from the wall
        float yaw = switch (face) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> 90f; case EAST -> -90f; default -> player.getYaw();
        };
        at.setYaw(yaw);

        ItemDisplay bracket = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildMountItem());
            d.addScoreboardTag(TAG_MOUNT);
            d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0),
                new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f()));
        });
        // The Interaction's position is the BOTTOM of its hitbox, so drop it half a
        // height below the model's centre - otherwise the box floats above the
        // canister (which is what made the hitbox sit too high).
        at.getWorld().spawn(at.clone().subtract(0, 0.35, 0), Interaction.class, i -> {
            i.addScoreboardTag(TAG_MOUNT);
            i.setInteractionWidth(0.6f);
            i.setInteractionHeight(0.7f);
            i.getPersistentDataContainer().set(plugin.keyOf("mount_full"), PersistentDataType.BYTE, (byte) 1);
            i.getPersistentDataContainer().set(plugin.keyOf("mount_display"), PersistentDataType.STRING,
                bracket.getUniqueId().toString());
        });
        showCanister(at, yaw, true);
        return true;
    }

    private void showCanister(Location at, float yaw, boolean on) {
        // a small canister in front of the bracket when the mount is full
        if (!on) return;
        Vector out = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))).multiply(0.15);
        at.getWorld().spawn(at.clone().add(out), ItemDisplay.class, d -> {
            d.setItemStack(registry.buildExtinguisher());
            d.addScoreboardTag(TAG_MOUNT_CAN);
            d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0),
                new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        });
    }

    @EventHandler
    public void onMountClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction mount)) return;
        if (!mount.getScoreboardTags().contains(TAG_MOUNT)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        var pdc = mount.getPersistentDataContainer();
        boolean full = pdc.getOrDefault(plugin.keyOf("mount_full"), PersistentDataType.BYTE, (byte) 0) == 1;

        if (full) {
            // take it out
            player.getInventory().addItem(registry.buildExtinguisher()).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            pdc.set(plugin.keyOf("mount_full"), PersistentDataType.BYTE, (byte) 0);
            removeCanistersNear(mount.getLocation());
            player.getWorld().playSound(mount.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 1.2f);
        } else {
            // put one back
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!registry.isExtinguisher(held)) {
                player.sendActionBar(Component.text("Hold an extinguisher to slot it in.", NamedTextColor.GRAY));
                return;
            }
            held.setAmount(held.getAmount() - 1);
            pdc.set(plugin.keyOf("mount_full"), PersistentDataType.BYTE, (byte) 1);
            float yaw = mount.getLocation().getYaw();
            showCanister(mount.getLocation(), yaw, true);
            player.getWorld().playSound(mount.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 0.9f);
        }
    }

    private void removeCanistersNear(Location at) {
        for (var e : at.getWorld().getNearbyEntities(at, 0.6, 0.6, 0.6)) {
            if (e instanceof ItemDisplay d && d.getScoreboardTags().contains(TAG_MOUNT_CAN)) d.remove();
        }
    }

    /** Remove the mount (bracket + interaction + canister) the player looks at. */
    public boolean removeMount(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceEntities(player.getEyeLocation(),
            player.getEyeLocation().getDirection(), 5.0, 0.3,
            e -> e.getScoreboardTags().contains(TAG_MOUNT));
        if (ray == null || ray.getHitEntity() == null) return false;
        Location at = ray.getHitEntity().getLocation();
        for (var e : at.getWorld().getNearbyEntities(at, 0.8, 0.8, 0.8)) {
            if (e.getScoreboardTags().contains(TAG_MOUNT) || e.getScoreboardTags().contains(TAG_MOUNT_CAN)) e.remove();
        }
        return true;
    }
}
