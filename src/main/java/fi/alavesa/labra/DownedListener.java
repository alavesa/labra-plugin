package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The downed state. Ordinary lethal damage no longer kills outright: the
 * victim collapses instead - one heart, crawling pace, useless hands, sixty
 * seconds on the clock. Three ways out: a MEDKIT (three-second hold, on them
 * or by their own hand if they still have one), a FINISHER (any hit while
 * down is the end), or the clock.
 *
 * Anomalies don't do "downed": massive single hits (SCP kill routines use
 * damage in the hundreds), the void and direct setHealth deaths pass
 * straight through - SCP-173 does not leave survivors, and the 008/049
 * reanimations keep firing off real deaths exactly as before.
 */
public final class DownedListener implements Listener, Runnable {

    private static final long RECOVERY_MS = 60_000L;
    private static final double ANOMALY_THRESHOLD = 100.0; // damage this big is fate

    private final LabraPlugin plugin;
    private final NamespacedKey healthCapKey;
    private final Map<UUID, Long> downed = new HashMap<>(); // player -> recovery time
    private final Map<UUID, Location> crawlCeiling = new HashMap<>(); // faked block per player

    public DownedListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.healthCapKey = new NamespacedKey(plugin, "downed_cap");
    }

    public boolean isDowned(Player player) {
        return downed.containsKey(player.getUniqueId());
    }

    /**
     * Bring a downed player back to their feet: clear the downed flag, lift the
     * 3-heart cap and the crawl/potion effects, and heal to a few hearts. Shared
     * by the medkit path and the SCP-500 panacea. No-op if not downed.
     */
    public void revive(Player player) {
        if (downed.remove(player.getUniqueId()) == null) return;
        clearEffects(player); // lifts the 3-heart cap before healing past it
        double target = Math.min(12.0, player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setHealth(target);
    }

    private boolean isMedkit(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_medkit");
    }

    private boolean isScp500(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp500");
    }

    // ------------------------------------------------------------- going down

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getFinalDamage() < victim.getHealth()) return; // survivable
        if (event.getDamage() >= ANOMALY_THRESHOLD) return;      // anomalies kill
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) return;

        if (isDowned(victim)) {
            // a hit while down IS the finisher - let this one kill for real
            downed.remove(victim.getUniqueId());
            clearEffects(victim);
            return;
        }
        event.setCancelled(true);
        var maxHealth = victim.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getModifier(healthCapKey) == null) {
            // three hearts is all a downed body gets to work with
            maxHealth.addTransientModifier(new AttributeModifier(healthCapKey,
                6.0 - maxHealth.getBaseValue(), AttributeModifier.Operation.ADD_NUMBER));
        }
        victim.setHealth(1.0);
        downed.put(victim.getUniqueId(), System.currentTimeMillis() + RECOVERY_MS);
        victim.setPose(Pose.SWIMMING, true); // face-down on the floor
        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false));
        blood(victim, 24); // the moment of collapse leaves a mark
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_BIG_FALL, 1f, 0.6f);
        ActionBars.message(victim, line("You are down.", NamedTextColor.RED));
        // (no "They're down. Finish it - or don't." popup for the attacker anymore)
    }

    /** Once a second: the crawl, the countdown, the clock running out. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Long deadline = downed.get(player.getUniqueId());
            if (deadline == null) continue;
            long left = (deadline - now) / 1000;
            if (left <= 0) {
                // you stayed away from whatever did this for a full minute -
                // the reward is getting up on your own, shaken but alive
                downed.remove(player.getUniqueId());
                clearEffects(player);
                player.setHealth(Math.min(4.0, player.getAttribute(Attribute.MAX_HEALTH).getValue()));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 300, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0, true, false));
                ActionBars.message(player, line("You pull yourself together.", NamedTextColor.GRAY));
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BREATH, 0.8f, 0.8f);
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 3, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, 2, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, 1, true, false));
            blood(player, 6);
            ActionBars.message(player, Component.text("You are down. ", NamedTextColor.RED,
                    TextDecoration.ITALIC)
                .append(Component.text("Hold on. " + left + "s", NamedTextColor.DARK_RED, TextDecoration.ITALIC)));
            if (left % 2 == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.5f, 0.9f);
            }
        }
    }

    /** Dark red pools and falling drips around the body. */
    private void blood(Player player, int amount) {
        var at = player.getLocation().add(0, 0.15, 0);
        player.getWorld().spawnParticle(Particle.DUST, at, amount, 0.45, 0.1, 0.45,
            new Particle.DustOptions(Color.fromRGB(122, 8, 8), 1.4f));
        player.getWorld().spawnParticle(Particle.FALLING_DUST, at.clone().add(0, 0.6, 0),
            Math.max(1, amount / 6), 0.3, 0.2, 0.3,
            org.bukkit.Material.REDSTONE_BLOCK.createBlockData());
    }

    /**
     * Runs every 3 ticks (registered separately): a CLIENT-SIDE barrier is
     * faked into the block above each downed player's head, so their own
     * client physically forces the crawl - setPose alone only convinces the
     * spectators. The fake block follows them and is un-faked on exit.
     */
    public void crawlTick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean down = downed.containsKey(player.getUniqueId());
            Location current = crawlCeiling.get(player.getUniqueId());
            if (!down) {
                if (current != null) {
                    player.sendBlockChange(current, current.getBlock().getBlockData());
                    crawlCeiling.remove(player.getUniqueId());
                }
                continue;
            }
            Location above = player.getLocation().getBlock().getLocation().add(0, 1, 0);
            if (current != null && current.getBlockX() == above.getBlockX()
                && current.getBlockY() == above.getBlockY()
                && current.getBlockZ() == above.getBlockZ()) {
                continue; // already faked here
            }
            if (current != null) {
                player.sendBlockChange(current, current.getBlock().getBlockData());
            }
            if (above.getBlock().getType() == Material.AIR) {
                player.sendBlockChange(above, Material.BARRIER.createBlockData());
                crawlCeiling.put(player.getUniqueId(), above);
            } else {
                crawlCeiling.remove(player.getUniqueId()); // a real ceiling does the job
            }
        }
    }

    private void clearEffects(Player player) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getModifier(healthCapKey) != null) {
            maxHealth.removeModifier(healthCapKey);
        }
        Location faked = crawlCeiling.remove(player.getUniqueId());
        if (faked != null) player.sendBlockChange(faked, faked.getBlock().getBlockData());
        player.setPose(Pose.STANDING, false); // release the crawl
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    // ------------------------------------------------------------- the medkit

    /**
     * Per-tick while the medkit is being held (consumed): paint the "how long to hold"
     * METER in the middle of the screen as a title + subtitle - the title says who
     * you're treating (yourself or a downed player), the subtitle is a 10-segment bar
     * that fills over the 3-second hold. Publishes lab.medkit=1 so the FireManager HUD
     * yields the title line to us (no clashing with the credits/gas-mask overlays);
     * cleared the instant the hold ends. Schedule this every ~2 ticks.
     */
    public void medkitTick() {
        var board = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Objective obj = board.getObjective("lab.medkit");
        if (obj == null) {
            try {
                obj = board.registerNewObjective("lab.medkit", org.bukkit.scoreboard.Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text("medkit"));
            } catch (IllegalArgumentException e) { obj = board.getObjective("lab.medkit"); }
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            java.util.UUID id = p.getUniqueId();
            boolean using = p.isHandRaised() && isMedkit(p.getActiveItem());
            // Only show the meter when there is actually something to treat - so a player who
            // is too healthy never sees a "Treating yourself" bar even if the hold sneaks past
            // the interact block (the meter, the block and the heal all agree on ONE rule).
            Player target = using ? treatmentTarget(p) : null;
            if (target == null) {
                MEDKIT_METER.remove(id);
                if (obj != null) obj.getScoreboard().resetScores(p.getName());
                continue;
            }
            if (obj != null) obj.getScore(p.getName()).setScore(1);
            float progress = Math.max(0f, Math.min(1f, p.getHandRaisedTime() / 60f));   // 3s = 60t
            boolean onOther = target != p;
            String who = onOther ? target.getName() : "yourself";
            int pct = Math.round(progress * 100);
            // Plain text only - no bar glyphs of any kind. "Treating X  70%" counting up
            // over the 3-second hold. Published for FireManager to compose with the credits.
            net.kyori.adventure.text.Component meter = net.kyori.adventure.text.Component
                .text("Treating ", NamedTextColor.WHITE)
                .append(net.kyori.adventure.text.Component.text(who,
                    onOther ? NamedTextColor.AQUA : NamedTextColor.GREEN))
                .append(net.kyori.adventure.text.Component.text("  " + pct + "%", NamedTextColor.GREEN));
            MEDKIT_METER.put(id, meter);
        }
    }

    /** The current medkit meter line for a player (subtitle content), or null when they
     *  aren't treating. FireManager reads this and composes it with the credits HUD. */
    private static final java.util.Map<java.util.UUID, net.kyori.adventure.text.Component>
        MEDKIT_METER = new java.util.concurrent.ConcurrentHashMap<>();
    public static net.kyori.adventure.text.Component medkitMeter(java.util.UUID id) {
        return MEDKIT_METER.get(id);
    }

    /** The one rule for "can this medic use a medkit right now", used by the meter, the
     *  start-block AND the heal so they never disagree. Returns who would be treated, or
     *  null when there's nothing to treat: a downed player you're looking at, or a downed
     *  self, or a HURT self (below 9 hearts). At 9+ hearts with nobody downed -> null. */
    private Player treatmentTarget(Player medic) {
        Entity tgt = medic.getTargetEntity(4);
        if (tgt instanceof Player tp && tp != medic && isDowned(tp)) return tp;
        if (isDowned(medic)) return medic;
        if (medic.getHealth() < 18.0) return medic;   // hurt (< 9 hearts) -> can patch self
        return null;                                   // healthy and nobody downed
    }

    /** Block STARTING a medkit when there's nothing to treat (best effort - the meter and
     *  the heal enforce the same rule, so a hold that slips past here still does nothing). */
    @EventHandler(ignoreCancelled = true)
    public void onMedkitStart(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        if (!isMedkit(event.getItem())) return;
        Player p = event.getPlayer();
        if (treatmentTarget(p) != null) return;              // something to treat: allow
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        ActionBars.message(p, line("You're too healthy to need a medkit.", NamedTextColor.GRAY));
    }

    /** The 3-second medkit hold completes as a "consume" - never eaten. */
    @EventHandler
    public void onMedkit(PlayerItemConsumeEvent event) {
        if (!isMedkit(event.getItem())) return;
        event.setCancelled(true);
        Player medic = event.getPlayer();
        Player patient = treatmentTarget(medic);
        if (patient == null) {
            ActionBars.message(medic, line("You're too healthy to need a medkit.", NamedTextColor.GRAY));
            return;                                          // nothing to treat -> kit NOT spent
        }
        if (!isDowned(patient)) {
            // a hurt (not downed) self: patch scrapes, don't run the revive path
            patient.setHealth(Math.min(20.0, patient.getHealth() + 8.0));
            spend(medic);
            ActionBars.message(medic, line("Patched up.", NamedTextColor.GRAY));
            return;
        }
        revive(patient); // clears the downed flag, lifts the cap, heals to a few hearts
        patient.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0, true, false));
        spend(medic);
        patient.getWorld().playSound(patient.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        ActionBars.message(patient, line("Back on your feet.", NamedTextColor.GRAY));
        if (patient != medic) ActionBars.message(medic, line("They'll live.", NamedTextColor.GRAY));
    }

    /**
     * SCP-500 (the panacea) is the datapack's job - it heals and clears
     * infections - but the datapack cannot lift the plugin-side downed state
     * (the 3-heart cap + flag), so its heal would be clamped and the player
     * would stay down. Un-down them here on consume; the event is NOT cancelled
     * so the datapack's advancement->consumed function still runs.
     */
    @EventHandler
    public void onScp500(PlayerItemConsumeEvent event) {
        if (!isScp500(event.getItem())) return;
        Player player = event.getPlayer();
        if (isDowned(player)) revive(player);
    }

    private void spend(Player medic) {
        ItemStack hand = medic.getInventory().getItemInMainHand();
        if (isMedkit(hand)) hand.setAmount(hand.getAmount() - 1);
        else {
            ItemStack off = medic.getInventory().getItemInOffHand();
            if (isMedkit(off)) off.setAmount(off.getAmount() - 1);
        }
    }

    // ---------------------------------------------- useless hands, no escape

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isDowned(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        // a downed player may still start their OWN medkit - dying with
        // supplies in hand and no help coming shouldn't be a death sentence
        if (!isDowned(event.getPlayer())) return;
        if (isMedkit(event.getItem())) return;
        if (event.getItem() != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isDowned(event.getPlayer())) event.setCancelled(true);
    }

    /** Logging out while down: you come back exactly as down as you left. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Long deadline = downed.get(event.getPlayer().getUniqueId());
        if (deadline != null) {
            // pause the clock generously: rejoining restarts the full minute
            downed.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + RECOVERY_MS);
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
