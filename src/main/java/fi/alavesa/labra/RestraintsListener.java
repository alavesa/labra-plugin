package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Zipties and handcuffs - two different tools for two different jobs,
 * modeled on the old server's cuffs.sk and the same 2-second hold-from-
 * behind capture as before.
 *
 * ZIPTIES - crowd control. Single use each, tie as many people as you have
 * ties. The bound can still WALK (slowly) but their hands and pockets are
 * useless: no attacking, no item use, no inventory access. A determined
 * prisoner struggles out (hold sneak ~20s).
 *
 * HANDCUFFS - prisoner transport. Reusable, never consumed, ONE prisoner
 * per captor. The cuffed cannot move AT ALL on their own - they are stuck
 * to their captor: sneak (or walk far enough) and the prisoner is pulled to
 * your position, exactly like the old script. Only being released frees
 * them; there is no struggling out of steel.
 */
public final class RestraintsListener implements Listener, Runnable {

    private static final double BEHIND_DOT = -0.35;
    private static final int STRUGGLE_SECONDS = 20;

    private final LabraPlugin plugin;
    private final NamespacedKey restrainedKey;            // "ziptie" | "handcuffs" on the victim
    private final Map<UUID, Integer> struggling = new HashMap<>();
    private final Map<UUID, UUID> prisonerOf = new HashMap<>(); // captor -> cuffed victim
    private final Map<UUID, UUID> captorOf = new HashMap<>();   // victim -> captor

    public RestraintsListener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.restrainedKey = new NamespacedKey(plugin, "restrained");
    }

    private String restraintType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var strings = item.getItemMeta().getCustomModelDataComponent().getStrings();
        if (strings.contains("lab_ziptie")) return "ziptie";
        if (strings.contains("lab_handcuffs")) return "handcuffs";
        return null;
    }

    public boolean isRestrained(Player player) {
        return player.getPersistentDataContainer().has(restrainedKey, PersistentDataType.STRING);
    }

    private String restraintOf(Player player) {
        return player.getPersistentDataContainer().get(restrainedKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------ detaining

    /** The 2-second right-click hold completes as a "consume" - intercept it. */
    @EventHandler
    public void onChargeComplete(PlayerItemConsumeEvent event) {
        String type = restraintType(event.getItem());
        if (type == null) return;
        event.setCancelled(true); // never actually eaten
        Player captor = event.getPlayer();
        if (type.equals("handcuffs") && prisonerOf.containsKey(captor.getUniqueId())) {
            ActionBars.message(captor, line("You already have a prisoner.", NamedTextColor.GRAY));
            return;
        }
        Entity target = captor.getTargetEntity(3);
        if (!(target instanceof Player victim) || isRestrained(victim)) {
            ActionBars.message(captor, line("No one within reach.", NamedTextColor.GRAY));
            return;
        }
        Vector toCaptor = captor.getLocation().toVector()
            .subtract(victim.getLocation().toVector()).setY(0);
        if (toCaptor.lengthSquared() < 0.01
            || toCaptor.normalize().dot(victim.getLocation().getDirection().setY(0).normalize()) > BEHIND_DOT) {
            ActionBars.message(captor, line("Not from the front.", NamedTextColor.GRAY));
            return;
        }
        victim.getPersistentDataContainer().set(restrainedKey, PersistentDataType.STRING, type);
        if (type.equals("ziptie")) {
            ItemStack hand = captor.getInventory().getItemInMainHand();
            if (restraintType(hand) != null) hand.setAmount(hand.getAmount() - 1);
        } else {
            prisonerOf.put(captor.getUniqueId(), victim.getUniqueId());
            captorOf.put(victim.getUniqueId(), captor.getUniqueId());
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1f, 0.7f);
        ActionBars.message(victim, line(type.equals("ziptie")
            ? "Your hands are tied." : "You have been cuffed.", NamedTextColor.RED));
        ActionBars.message(captor, line("Detained.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------- captivity

    /** Once a second: hold the bound, drag the cuffed, let ties be fought. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String type = restraintOf(player);
            if (type == null) continue;
            if (type.equals("handcuffs")) {
                // the cuffs.sk freeze: slowness off the scale + a jump boost
                // so high the jump never happens - the prisoner goes NOWHERE
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 254, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 45, 127, true, false));
                UUID captorId = captorOf.get(player.getUniqueId());
                Player captor = captorId == null ? null : plugin.getServer().getPlayer(captorId);
                if (captor != null && captor.getWorld().equals(player.getWorld())
                    && (captor.isSneaking()
                        || captor.getLocation().distance(player.getLocation()) > 2.0)) {
                    // pulled along: the prisoner arrives at the captor's spot,
                    // keeping their own view direction (straight from cuffs.sk)
                    var to = captor.getLocation().clone();
                    to.setYaw(player.getLocation().getYaw());
                    to.setPitch(player.getLocation().getPitch());
                    player.teleport(to);
                }
            } else {
                // zipties: slow but mobile - the hands are the problem
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 2, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, 1, true, false));
                if (player.isSneaking()) {
                    int seconds = struggling.merge(player.getUniqueId(), 1, Integer::sum);
                    if (seconds % 5 == 0 && seconds < STRUGGLE_SECONDS) {
                        ActionBars.message(player, line("The tie digs in...", NamedTextColor.GRAY));
                    }
                    if (seconds >= STRUGGLE_SECONDS) {
                        free(player);
                        ActionBars.message(player, line("The tie snaps.", NamedTextColor.GRAY));
                        player.getWorld().playSound(player.getLocation(),
                            Sound.ENTITY_LEASH_KNOT_BREAK, 1f, 1.4f);
                    }
                } else {
                    struggling.remove(player.getUniqueId());
                }
            }
        }
    }

    private void free(Player player) {
        player.getPersistentDataContainer().remove(restrainedKey);
        struggling.remove(player.getUniqueId());
        UUID captorId = captorOf.remove(player.getUniqueId());
        if (captorId != null) prisonerOf.remove(captorId);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    /** Release: right-click the prisoner (empty hand or the restraint). */
    @EventHandler
    public void onRelease(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player victim) || !isRestrained(victim)) return;
        Player releaser = event.getPlayer();
        ItemStack hand = releaser.getInventory().getItemInMainHand();
        boolean emptyHand = hand.getType().isAir();
        boolean holdsRestraint = restraintType(hand) != null;
        if (!emptyHand && !holdsRestraint) return;
        // holding a restraint on a FREE target starts a capture, not a
        // release - only clicks on the restrained land here anyway
        event.setCancelled(true);
        free(victim);
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 1f, 1.1f);
        ActionBars.message(victim, line("Your hands are free.", NamedTextColor.GRAY));
        ActionBars.message(releaser, line("Released.", NamedTextColor.GRAY));
    }

    // ------------------------------------------- what bound hands cannot do

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
            ActionBars.message(player, line("Your hands are bound.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getItem() != null && isRestrained(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(line("Your hands are bound.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestrained(event.getPlayer())) event.setCancelled(true);
    }

    /** Bound hands can't dig through pockets either: no inventory access. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
            ActionBars.message(player, line("Your hands are bound.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestrained(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // a quitting captor leaves the prisoner cuffed where they stand;
        // a quitting prisoner keeps the restraint (PDC survives the relog)
        UUID victimId = prisonerOf.remove(event.getPlayer().getUniqueId());
        if (victimId != null) captorOf.remove(victimId);
        struggling.remove(event.getPlayer().getUniqueId());
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
