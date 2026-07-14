package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Objective;

/**
 * SCP-427, the Lovers' Talisman. Held in the off hand it heals everything:
 * Regeneration II, and the lab's slow deaths (SCP-009 frost, the wrong cola,
 * the SCP-008 fever) are wound backwards two seconds for every one. What the
 * locket never mentions is that every second of use is written down, the
 * ledger never resets, and at the bottom of the page there is a thing called
 * SCP-427-1 wearing what used to be a person.
 */
public final class Scp427Listener implements Listener, Runnable {

    private static final int WARN_AT = 120;
    private static final int TRANSFORM_AT = 180;

    private final LabraPlugin plugin;
    private final NamespacedKey exposureKey;

    public Scp427Listener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.exposureKey = new NamespacedKey(plugin, "scp427_exposure");
    }

    private boolean isTalisman(ItemStack item) {
        if (item == null || item.getType() != Material.HEART_OF_THE_SEA || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp427");
    }

    /** Once a second, for every hand the locket rests in. */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!Trinkets.hasActive(player, "scp427")) continue;
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 1, true, false));
            rewind(player, "lab.inf");
            rewind(player, "lab.cola");
            rewind(player, "lab.z008");
            int exposure = player.getPersistentDataContainer()
                .getOrDefault(exposureKey, PersistentDataType.INTEGER, 0) + 1;
            player.getPersistentDataContainer().set(exposureKey, PersistentDataType.INTEGER, exposure);
            if (exposure == WARN_AT) {
                player.sendActionBar(Component.text("Your flesh hums.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
            }
            if (exposure >= TRANSFORM_AT) transform(player);
        }
    }

    /** Wind a datapack affliction backwards by 2 (null-guarded, floor 0). */
    private void rewind(Player player, String objectiveName) {
        Objective objective = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objectiveName);
        if (objective == null) return;
        var score = objective.getScore(player.getName());
        if (!score.isScoreSet() || score.getScore() <= 0) return;
        score.setScore(Math.max(0, score.getScore() - 2));
    }

    /** The ledger comes due. */
    private void transform(Player player) {
        Location at = player.getLocation();
        player.damage(10000.0);
        if (!player.isDead() && player.getHealth() > 0) return; // a totem only buys a second
        at.getWorld().spawn(at, Ravager.class, beast -> {
            beast.customName(Component.text("SCP-427-1", NamedTextColor.DARK_RED));
            beast.setCustomNameVisible(false);
            beast.setPersistent(true);
            beast.setRemoveWhenFarAway(false);
            beast.addScoreboardTag("scp427.beast");
        });
        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.7f);
    }
}
