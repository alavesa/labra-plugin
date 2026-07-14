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
 * them and spends the syringe - the ending: any player who dies while
 * infected gets back up as an SCP-008 Host (wearing their name), right where
 * they fell - and the SPREADING: host claws infect on hit, unless a full
 * hazmat suit is between the claw and the skin. The suit is not forever:
 * every blocked hit chews a random piece, and pieces tear off entirely.
 */
public final class Scp008Listener implements Listener {

    private final LabraPlugin plugin;
    private final LabRegistry registry;

    public Scp008Listener(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
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
        ActionBars.message(attacker, Component.text("The plunger goes down.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** A host's claws carry the prion. Hazmat blocks it - while it lasts. */
    @EventHandler
    public void onHostClaw(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Zombie zombie)) return;
        if (!zombie.getScoreboardTags().contains("scp008.host")) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (registry.hasFullHazmat(victim)) {
            switch (registry.wearHazmat(victim)) {
                case 0 -> ActionBars.message(victim, Component.text("Claws rake across the suit.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
                case 1 -> {
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
                    ActionBars.message(victim, Component.text("The suit tears open.",
                        NamedTextColor.RED, TextDecoration.ITALIC));
                }
            }
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "execute as " + victim.getUniqueId() + " run function lab:scp008/infect");
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
            // the fallen player, back on their feet - the name makes it personal
            zombie.customName(Component.text(victim.getName(), NamedTextColor.DARK_GREEN));
            zombie.setCustomNameVisible(false);
            zombie.addScoreboardTag("scp008.host");
        });
        at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_AMBIENT, 1.2f, 0.6f);
    }
}
