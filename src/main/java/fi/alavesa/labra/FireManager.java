package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private static final String TAG_SPRINK_BTN = "lab.sprinkbtn";

    private static final long SPRINKLER_MS = 30_000;          // a burst runs 30 seconds
    private static final long SPRINKLER_COOLDOWN_MS = 300_000; // 5-minute cooldown per button
    private static final int MOUNT_REFILL_STEP = LabRegistry.EXTINGUISHER_MAX / 10; // 10% per tick
    private static final int NOXIOUS_FIRE_THRESHOLD = 8;      // fire blocks near a player before smoke bites
    private static final int NOXIOUS_RADIUS = 6;             // widest smoke reach (scales up with fire count)

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final Map<String, Long> fires = new HashMap<>();   // block key -> ignite time
    private final Map<UUID, Long> sprayCd = new HashMap<>();
    /** Admin currently wiring a button: their UUID -> the button entity being linked. */
    private final Map<UUID, UUID> pendingLink = new HashMap<>();
    /** Seconds each player has been breathing smoke (drives the escalating harm). */
    private final Map<UUID, Integer> smoke = new HashMap<>();
    private static final int SMOKE_DYING_AFTER = 8;   // seconds of smoke before it starts to kill
    /** Last position of each player, for trailing fire when they're alight. */
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private static final Material DUCT_LOG = Material.STRIPPED_PALE_OAK_LOG;   // vent openings (re-textured)
    private static final Material DUCT_PIPE = Material.STRIPPED_PALE_OAK_WOOD; // vent piping (re-textured)

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

    /** Fire never eats blocks: it burns for its 30 minutes but the facility is left
     *  standing. (It's the smoke that's dangerous, not the structural damage.) */
    @EventHandler
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        event.setCancelled(true);
    }

    /** You can pat out a lone flame, but a fire with other fire around it is too big
     *  to smother by hand - try and it just catches YOU alight. */
    @EventHandler
    public void onFireBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.FIRE) return;
        if (adjacentFire(event.getBlock()) == 0) {
            fires.remove(key(event.getBlock()));   // isolated single flame - let them smother it
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        p.setFireTicks(Math.max(p.getFireTicks(), 60));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.2f);
        // Through the ActionBars hub so it composes with the meters instead of flickering.
        ActionBars.message(p, Component.text("You were unable to put out the fire", NamedTextColor.GRAY));
    }

    /** Paint the worn gas-mask's first-person overlay (per tier) via the hub, so the
     *  three masks look different and the overlay composes with the meters/messages
     *  instead of the vanilla pumpkin blur (now cleared). Scheduled every few ticks. */
    public void maskTick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String tier = registry.gasMaskTier(p.getInventory().getHelmet());
            if (tier == null) { nvgOverlay(p); continue; }
            char ch = switch (tier) { case "super" -> ''; case "heavy" -> ''; default -> ''; };
            Component glyph = Component.text(String.valueOf(ch))
                .font(net.kyori.adventure.key.Key.key("lab", "gasmask"))
                .color(NamedTextColor.WHITE);
            showTitleGlyph(p, glyph);
        }
    }

    private final java.util.Set<UUID> overlayShown = new java.util.HashSet<>();

    /** The full-screen headgear overlay, painted as a custom-textured SUBTITLE
     *  message (the glyph's own ascent/height fill the view). A subtitle renders
     *  the big centered glyph reliably; re-sent while the mask/NVG is worn. */
    private void showTitleGlyph(Player p, Component glyph) {
        overlayShown.add(p.getUniqueId());
        p.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), glyph,
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ZERO, java.time.Duration.ofMillis(1500), java.time.Duration.ZERO)));
    }

    /** The worn NVG's per-type overlay (green/red/blue), or clear our overlay title. */
    private void nvgOverlay(Player p) {
        String nvg = registry.nvgType(p.getInventory().getHelmet());
        if (nvg == null) {
            if (overlayShown.remove(p.getUniqueId())) p.clearTitle();
            return;
        }
        char ch = switch (nvg) { case "red" -> ''; case "blue" -> ''; default -> ''; };
        showTitleGlyph(p, Component.text(String.valueOf(ch))
            .font(net.kyori.adventure.key.Key.key("lab", "nvg")).color(NamedTextColor.WHITE));
    }

    /** How many of the 26 surrounding blocks are also fire. */
    private int adjacentFire(Block b) {
        int n = 0;
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if ((x | y | z) == 0) continue;
            if (b.getRelative(x, y, z).getType() == Material.FIRE) n++;
        }
        return n;
    }

    // ------------------------------------------------------------- noxious smoke

    /** Too much fire nearby and the smoke gets to you - nausea and a stumble at
     *  first, then it starts to kill the longer you breathe it. A gas mask
     *  (a worn carved-pumpkin) filters it out entirely. Scheduled every second. */
    public void noxiousTick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
                smoke.remove(id);
                continue;
            }
            if (registry.isGasMask(p.getInventory().getHelmet())) { smoke.put(id, 0); continue; }
            // Count the fire around the player AND how near the closest flame is. The
            // more fire there is, the further its smoke reaches - a wall of flame gasses
            // you from across the room, a campfire only up close.
            double[] scan = scanFire(p.getLocation(), NOXIOUS_RADIUS);
            int fire = (int) scan[0];
            double nearest = scan[1];
            double smokeRadius = Math.min((double) NOXIOUS_RADIUS, 3.0 + fire / 3.0);
            if (fire < NOXIOUS_FIRE_THRESHOLD || nearest > smokeRadius) {
                int s = smoke.getOrDefault(id, 0);
                if (s > 0) smoke.put(id, Math.max(0, s - 2));   // fresh air clears it faster than it built
                continue;
            }
            int exposure = smoke.getOrDefault(id, 0) + 1;
            smoke.put(id, exposure);
            int sev = Math.min(3, fire / 12);
            // short durations, re-applied each second while in smoke, so they clear
            // within ~1.5s of stepping out - no lingering effect once the fire's gone
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 30, 0, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, sev, true, false, false));
            ActionBars.message(p, Component.text("You inhale the smoke.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            if (exposure > SMOKE_DYING_AFTER) {
                p.damage(1.0 + Math.min(3.0, (exposure - SMOKE_DYING_AFTER) / 4.0));   // choking, escalating
            }
        }
    }

    /** A player who's on fire trails flame as they run: touch a blaze, flee, and you
     *  spread it behind you. Scheduled every few ticks. */
    public void spreadTick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (p.getFireTicks() <= 0 || p.getGameMode() == GameMode.SPECTATOR
                    || p.getGameMode() == GameMode.CREATIVE) {
                lastPos.remove(id);
                continue;
            }
            Location prev = lastPos.get(id);
            Location cur = p.getLocation();
            lastPos.put(id, cur.clone());
            if (prev == null || prev.getWorld() != cur.getWorld()) continue;
            if (prev.distanceSquared(cur) < 0.4) continue;   // must actually be moving away
            Block at = prev.getBlock();
            Block floor = at.getRelative(0, -1, 0);
            if (at.getType() == Material.AIR && floor.getType().isSolid()
                    && java.util.concurrent.ThreadLocalRandom.current().nextInt(2) == 0) {
                at.setType(Material.FIRE);
                fires.put(key(at), System.currentTimeMillis());
            }
        }
    }

    /** Fire travels through the ventilation ducts: a flame touching a
     *  stripped_pale_oak_log (a vent) creeps along the stripped_pale_oak_wood
     *  piping to the other vents in the run and breaks out there. Scheduled slowly. */
    public void ductSpreadTick() {
        if (fires.isEmpty()) return;
        var rng = java.util.concurrent.ThreadLocalRandom.current();
        // pick a handful of live fires that sit against a duct vent this pass
        java.util.List<Block> seeds = new java.util.ArrayList<>();
        for (String k : fires.keySet()) {
            String[] pr = k.split(":");
            var w = Bukkit.getWorld(pr[0]);
            if (w == null) continue;
            Block f = w.getBlockAt(Integer.parseInt(pr[1]), Integer.parseInt(pr[2]), Integer.parseInt(pr[3]));
            if (f.getType() != Material.FIRE) continue;
            Block vent = adjacentDuctVent(f);
            if (vent != null) seeds.add(vent);
            if (seeds.size() >= 6) break;
        }
        for (Block vent : seeds) {
            // walk the connected duct network (logs + wood pipes), bounded
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<Block> queue = new java.util.ArrayDeque<>();
            queue.add(vent); seen.add(key(vent));
            int lit = 0;
            while (!queue.isEmpty() && seen.size() < 48 && lit < 3) {
                Block b = queue.poll();
                if (b.getType() == DUCT_LOG) {
                    // a vent opening - break fire out into an adjacent air pocket
                    for (org.bukkit.block.BlockFace face : FACES) {
                        Block air = b.getRelative(face);
                        if (air.getType() == Material.AIR && rng.nextInt(3) == 0) {
                            air.setType(Material.FIRE);
                            fires.put(key(air), System.currentTimeMillis());
                            lit++;
                            break;
                        }
                    }
                }
                for (org.bukkit.block.BlockFace face : FACES) {
                    Block nb = b.getRelative(face);
                    if ((nb.getType() == DUCT_LOG || nb.getType() == DUCT_PIPE) && seen.add(key(nb))) {
                        queue.add(nb);
                    }
                }
            }
        }
    }

    private static final org.bukkit.block.BlockFace[] FACES = {
        org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
        org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
        org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST };

    private Block adjacentDuctVent(Block fire) {
        for (org.bukkit.block.BlockFace face : FACES) {
            Block b = fire.getRelative(face);
            if (b.getType() == DUCT_LOG || b.getType() == DUCT_PIPE) return b;
        }
        return null;
    }

    /** {fire count, distance to the nearest flame} within r blocks. */
    private double[] scanFire(Location center, int r) {
        int count = 0;
        double nearest = Double.MAX_VALUE;
        Block base = center.getBlock();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            if (base.getRelative(x, y, z).getType() != Material.FIRE) continue;
            count++;
            double d = Math.sqrt(x * x + y * y + z * z);
            if (d < nearest) nearest = d;
            if (count > 300) return new double[]{count, nearest};
        }
        return new double[]{count, nearest};
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
            ActionBars.message(player, Component.text("Fire Extinguisher - empty", NamedTextColor.GRAY));
            return;
        }
        registry.setExtinguisherCharge(held, charge - 1);
        spray(player);
        // Through the hub so it composes with the sprint/blink bars, no flicker.
        ActionBars.message(player, Component.text("Fire Extinguisher - "
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

    private final Map<String, Long> sprinklers = new HashMap<>();   // sprinkler block key -> active until

    /** Place a custom sprinkler-control button on the wall you're looking at; it also
     *  becomes your active button for linking. */
    public boolean placeSprinklerButton(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
            player.getEyeLocation().getDirection(), 5.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return false;
        var face = ray.getHitBlockFace();
        Location at = ray.getHitBlock().getRelative(face).getLocation().add(0.5, 0.5, 0.5);
        float yaw = switch (face) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> 90f; case EAST -> -90f; default -> player.getYaw();
        };
        at.setYaw(yaw);
        ItemDisplay disp = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(registry.buildSprinklerButtonItem());
            d.addScoreboardTag(TAG_SPRINK_BTN);
            d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0),
                new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
        });
        Interaction btn = at.getWorld().spawn(at.clone().subtract(0, 0.3, 0), Interaction.class, i -> {
            i.addScoreboardTag(TAG_SPRINK_BTN);
            i.setInteractionWidth(0.6f);
            i.setInteractionHeight(0.6f);
            i.getPersistentDataContainer().set(plugin.keyOf("sprink_disp"),
                PersistentDataType.STRING, disp.getUniqueId().toString());
        });
        pendingLink.put(player.getUniqueId(), btn.getUniqueId());
        return true;
    }

    /** Aim at an existing button to make it your active one for linking. */
    public boolean selectSprinklerButton(Player player) {
        Interaction btn = raytraceTagged(player, TAG_SPRINK_BTN);
        if (btn == null) return false;
        pendingLink.put(player.getUniqueId(), btn.getUniqueId());
        return true;
    }

    /** Link the hanging_roots sprinkler you're looking at to your active button. */
    public String linkSprinkler(Player player) {
        UUID btnId = pendingLink.get(player.getUniqueId());
        if (btnId == null) return "Place or select a button first: /lab sprinkler button | select.";
        if (!(Bukkit.getEntity(btnId) instanceof Interaction btn)) return "Your active button is gone - select another.";
        RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
            player.getEyeLocation().getDirection(), 6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlock().getType() != Material.HANGING_ROOTS) {
            return "Look at a hanging_roots sprinkler to link it.";
        }
        String k = key(ray.getHitBlock());
        var pdc = btn.getPersistentDataContainer();
        String links = pdc.getOrDefault(plugin.keyOf("sprink_links"), PersistentDataType.STRING, "");
        java.util.List<String> list = new java.util.ArrayList<>(java.util.Arrays.asList(
            links.isEmpty() ? new String[0] : links.split(";")));
        if (!list.contains(k)) list.add(k);
        pdc.set(plugin.keyOf("sprink_links"), PersistentDataType.STRING, String.join(";", list));
        return String.valueOf(list.size());   // success: returns the new link count
    }

    /** Remove the button (display + interaction) you're looking at. */
    public boolean removeSprinklerButton(Player player) {
        Interaction btn = raytraceTagged(player, TAG_SPRINK_BTN);
        if (btn == null) return false;
        String dispId = btn.getPersistentDataContainer().get(plugin.keyOf("sprink_disp"), PersistentDataType.STRING);
        if (dispId != null && Bukkit.getEntity(UUID.fromString(dispId)) != null) {
            Bukkit.getEntity(UUID.fromString(dispId)).remove();
        }
        btn.remove();
        return true;
    }

    private Interaction raytraceTagged(Player player, String tag) {
        RayTraceResult ray = player.getWorld().rayTraceEntities(player.getEyeLocation(),
            player.getEyeLocation().getDirection(), 5.0, 0.3,
            e -> e instanceof Interaction && e.getScoreboardTags().contains(tag));
        return ray != null && ray.getHitEntity() instanceof Interaction i ? i : null;
    }

    /** Right-click the button: fire its linked sprinklers for 30s, then a 5-min cooldown. */
    private void pressSprinklerButton(Interaction btn, Player player) {
        var pdc = btn.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        long cdUntil = pdc.getOrDefault(plugin.keyOf("sprink_cd"), PersistentDataType.LONG, 0L);
        if (now < cdUntil) {
            player.sendActionBar(Component.text("Sprinklers recharging - " + ((cdUntil - now) / 1000) + "s left",
                NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.7f, 1.2f);
            return;
        }
        String links = pdc.getOrDefault(plugin.keyOf("sprink_links"), PersistentDataType.STRING, "");
        if (links.isEmpty()) {
            player.sendActionBar(Component.text("No sprinklers linked. /lab sprinkler link while aiming at them.",
                NamedTextColor.GRAY));
            return;
        }
        long until = now + SPRINKLER_MS;
        int n = 0;
        for (String k : links.split(";")) if (!k.isEmpty()) { sprinklers.put(k, until); n++; }
        pdc.set(plugin.keyOf("sprink_cd"), PersistentDataType.LONG, now + SPRINKLER_COOLDOWN_MS);
        btn.getWorld().playSound(btn.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.4f);
        for (Player p : btn.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(btn.getLocation()) < 900)
                p.sendActionBar(Component.text("Sprinklers activated (" + n + ").", NamedTextColor.AQUA));
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
            w.spawnParticle(Particle.FALLING_WATER, spout.clone().subtract(0, 0.2, 0), 14, 1.2, 0.1, 1.2, 0);
            w.spawnParticle(Particle.SPLASH, spout.clone().subtract(0, 2.0, 0), 8, 1.4, 0.2, 1.4, 0);
            // A wide cone below the sprinkler head so it doesn't miss spots: 7x7 across,
            // all the way to the floor.
            int rad = 3;
            for (int dy = 0; dy <= 12; dy++) for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                Block below = b.getRelative(dx, -dy, dz);
                if (below.getType() == Material.FIRE) { below.setType(Material.AIR); fires.remove(key(below)); }
            }
            for (var ent : w.getNearbyEntities(spout.clone().subtract(0, 5, 0), 4.0, 6, 4.0)) {
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
            i.getPersistentDataContainer().set(plugin.keyOf("mount_charge"),
                PersistentDataType.INTEGER, LabRegistry.EXTINGUISHER_MAX);
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
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        var tags = interaction.getScoreboardTags();
        Player player = event.getPlayer();
        if (tags.contains(TAG_SPRINK_BTN)) {
            event.setCancelled(true);
            pressSprinklerButton(interaction, player);
            return;
        }
        if (!tags.contains(TAG_MOUNT)) return;
        event.setCancelled(true);
        var pdc = interaction.getPersistentDataContainer();
        // -1 = empty; 0..MAX = a docked extinguisher's current charge.
        int charge = pdc.getOrDefault(plugin.keyOf("mount_charge"), PersistentDataType.INTEGER, -1);

        if (charge >= 0) {
            // take it out - with whatever charge the mount refilled it to
            ItemStack ext = registry.buildExtinguisher();
            registry.setExtinguisherCharge(ext, charge);
            player.getInventory().addItem(ext).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            pdc.set(plugin.keyOf("mount_charge"), PersistentDataType.INTEGER, -1);
            removeCanistersNear(interaction.getLocation());
            player.getWorld().playSound(interaction.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 1.2f);
        } else {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!registry.isExtinguisher(held)) {
                player.sendActionBar(Component.text("Hold an extinguisher to slot it in.", NamedTextColor.GRAY));
                return;
            }
            pdc.set(plugin.keyOf("mount_charge"), PersistentDataType.INTEGER, registry.extinguisherCharge(held));
            held.setAmount(held.getAmount() - 1);
            showCanister(interaction.getLocation(), interaction.getLocation().getYaw(), true);
            player.getWorld().playSound(interaction.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 0.9f);
        }
    }

    /** Slowly recharge docked extinguishers: +10% of a full tank per call (10s). */
    public void refillMounts() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Interaction i : w.getEntitiesByClass(Interaction.class)) {
                if (!i.getScoreboardTags().contains(TAG_MOUNT)) continue;
                var pdc = i.getPersistentDataContainer();
                int charge = pdc.getOrDefault(plugin.keyOf("mount_charge"), PersistentDataType.INTEGER, -1);
                if (charge < 0 || charge >= LabRegistry.EXTINGUISHER_MAX) continue;   // empty or already full
                pdc.set(plugin.keyOf("mount_charge"), PersistentDataType.INTEGER,
                    Math.min(LabRegistry.EXTINGUISHER_MAX, charge + MOUNT_REFILL_STEP));
            }
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
