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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * SCP-268, the Cap of Neglect. Worn on the head, the wearer simply isn't
 * worth anyone's attention: invisible (no particles), and no mob will hold a
 * grudge long enough to attack. The neglect is fragile - swing a fist or
 * open something that belongs to somebody, and the cap slips off and sulks
 * for half a minute before it will work again.
 *
 * Deviation from the SCP text (and the task): worn armor cannot be hidden
 * through the Bukkit API without per-player equipment packets, so equipment
 * stays visible; the cap itself is the only thing on the wearer's head.
 */
public final class Scp268Listener implements Listener, Runnable {

    private static final long COOLDOWN_MS = 30_000L;

    private final LabraPlugin plugin;
    private final NamespacedKey cooldownKey;

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
            if (!isCap(player.getInventory().getHelmet())) continue;
            if (onCooldown(player)) {
                ActionBars.message(player, Component.text("Not yet. It remembers.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 45, 0, true, false));
        }
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

    private void slip(Player player) {
        ItemStack cap = player.getInventory().getHelmet();
        if (!isCap(cap)) return;
        player.getInventory().setHelmet(null);
        player.getInventory().addItem(cap).values().forEach(left ->
            player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG,
            System.currentTimeMillis() + COOLDOWN_MS);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.7f);
        ActionBars.message(player, Component.text("The cap slips from your head.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }
}
