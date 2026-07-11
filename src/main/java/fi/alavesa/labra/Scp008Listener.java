package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;

/**
 * The sharp end of SCP-008. The lab-datapack owns the disease itself (the
 * lab.z008 timeline, the SCP-500 cure); this listener adds what a datapack
 * cannot: STABBING - hitting another player while holding the syringe infects
 * them and spends the syringe - and the ending: any player who dies while
 * infected gets back up as an SCP-008 Host, right where they fell.
 */
public final class Scp008Listener implements Listener {

    private final LabraPlugin plugin;

    public Scp008Listener(LabraPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isSyringe(ItemStack item) {
        if (item == null || item.getType() != Material.GHAST_TEAR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_syringe_008");
    }

    @EventHandler
    public void onStab(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        ItemStack held = attacker.getInventory().getItemInMainHand();
        if (!isSyringe(held)) return;
        held.setAmount(held.getAmount() - 1);
        event.setDamage(1.0); // a pinprick, not a sword
        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 0.8f, 0.7f);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute as " + victim.getUniqueId() + " run function lab:scp008/infect");
        attacker.sendActionBar(Component.text("The plunger goes down.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Dying while infected is not the end of the story. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Objective objective = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("lab.z008");
        if (objective == null) return;
        var score = objective.getScore(victim.getName());
        if (!score.isScoreSet() || score.getScore() <= 0) return;
        score.setScore(0);
        Location at = victim.getLocation();
        at.getWorld().spawn(at, Zombie.class, zombie -> {
            zombie.setAdult();
            zombie.setPersistent(true);
            zombie.setRemoveWhenFarAway(false);
            zombie.setShouldBurnInDay(false);
            zombie.customName(Component.text("SCP-008 Host", NamedTextColor.DARK_GREEN));
            zombie.setCustomNameVisible(false);
            zombie.addScoreboardTag("scp008.host");
        });
        at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_AMBIENT, 1.2f, 0.6f);
    }
}
