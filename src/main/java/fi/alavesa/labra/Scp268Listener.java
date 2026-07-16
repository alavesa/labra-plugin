package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * SCP-268, the Cap of Neglect. Worn on the head, the wearer simply isn't
 * worth anyone's attention: invisible (no particles), and no mob will hold a
 * grudge long enough to attack. The neglect is fragile - swing a fist or
 * open something that belongs to somebody, and the cap slips off and sulks
 * for half a minute before it will work again.
 *
 * Bukkit invisibility does NOT hide worn armor, so while the cap is active we
 * also blank out the wearer's equipment for every OTHER player via per-player
 * equipment packets (sendEquipmentChange). Whoever is currently hidden is
 * tracked so their real gear can be restored the instant the neglect breaks.
 */
public final class Scp268Listener implements Listener, Runnable {

    private static final long COOLDOWN_MS = 30_000L;
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    private final LabraPlugin plugin;
    private final NamespacedKey cooldownKey;
    /** Wearers whose equipment is currently blanked for other players. */
    private final Set<UUID> hidden = new HashSet<>();

    public Scp268Listener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "scp268_cd");
    }

    private boolean isCap(ItemStack item) {
        if (item == null || item.getType() != Material.LEATHER_HELMET || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp268");
    }

    private boolean onCooldown(Player player) {
        return System.currentTimeMillis() < player.getPersistentDataContainer()
            .getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
    }

    /** Wearing the cap and the cap is currently willing. */
    public boolean isNeglected(Player player) {
        return isCap(player.getInventory().getHelmet()) && !onCooldown(player);
    }

    /** Once a second: keep the neglect painted on whoever wears the cap. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isCap(player.getInventory().getHelmet())) {
                if (hidden.contains(player.getUniqueId())) restore(player);
                continue;
            }
            if (onCooldown(player)) {
                if (hidden.contains(player.getUniqueId())) restore(player);
                ActionBars.message(player, Component.text("Not yet. It remembers.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 45, 0, true, false));
            // invisibility alone leaves worn armor showing - blank the wearer's
            // equipment for every OTHER player each tick so it stays hidden as
            // their clients re-sync
            hideEquipment(player);
        }
    }

    /** Send AIR for all six equipment slots of the wearer to every other player. */
    private void hideEquipment(Player wearer) {
        ItemStack air = new ItemStack(Material.AIR);
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == wearer) continue;
            // FULL invisibility: remove the wearer's entity entirely from
            // every other client - no translucent shimmer, no armor, nothing
            other.hidePlayer(plugin, wearer);
            for (EquipmentSlot slot : SLOTS) {
                other.sendEquipmentChange(wearer, slot, air);
            }
        }
        hidden.add(wearer.getUniqueId());
    }

    /** Resend the wearer's real equipment to every other player. */
    private void restore(Player wearer) {
        hidden.remove(wearer.getUniqueId());
        var inv = wearer.getInventory();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == wearer) continue;
            other.showPlayer(plugin, wearer); // back into view
            other.sendEquipmentChange(wearer, EquipmentSlot.HEAD, orAir(inv.getHelmet()));
            other.sendEquipmentChange(wearer, EquipmentSlot.CHEST, orAir(inv.getChestplate()));
            other.sendEquipmentChange(wearer, EquipmentSlot.LEGS, orAir(inv.getLeggings()));
            other.sendEquipmentChange(wearer, EquipmentSlot.FEET, orAir(inv.getBoots()));
            other.sendEquipmentChange(wearer, EquipmentSlot.HAND, orAir(inv.getItemInMainHand()));
            other.sendEquipmentChange(wearer, EquipmentSlot.OFF_HAND, orAir(inv.getItemInOffHand()));
        }
    }

    private ItemStack orAir(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item;
    }

    /** Nothing hunts what it does not notice. */
    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && isNeglected(player)) {
            event.setCancelled(true);
        }
    }

    /** Violence is attention. The cap wants none of it. */
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isNeglected(player)) {
            slip(player);
        }
    }

    /** Opening a container is taking; taking gets you noticed. */
    @EventHandler
    public void onOpenContainer(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Container)) return;
        if (isNeglected(event.getPlayer())) slip(event.getPlayer());
    }

    /** Same for the lab machines (their hitboxes are lab.* interactions). */
    @EventHandler
    public void onUseMachine(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        if (interaction.getScoreboardTags().stream().noneMatch(t -> t.startsWith("lab."))) return;
        if (isNeglected(event.getPlayer())) slip(event.getPlayer());
    }

    /** Restore hidden equipment on logout so nobody keeps a ghost of them. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (hidden.contains(event.getPlayer().getUniqueId())) restore(event.getPlayer());
    }

    /** Called from LabraPlugin#onDisable: un-hide everyone before shutdown. */
    public void shutdown() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (hidden.contains(player.getUniqueId())) restore(player);
        }
    }

    private void slip(Player player) {
        ItemStack cap = player.getInventory().getHelmet();
        if (!isCap(cap)) return;
        player.getInventory().setHelmet(null);
        player.getInventory().addItem(cap).values().forEach(left ->
            player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (hidden.contains(player.getUniqueId())) restore(player);
        player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG,
            System.currentTimeMillis() + COOLDOWN_MS);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.7f);
        ActionBars.message(player, Component.text("The cap slips from your head.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }
}
