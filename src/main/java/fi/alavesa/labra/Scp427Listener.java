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
import org.bukkit.event.entity.PlayerDeathEvent;
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
    /** Who was already active last tick - re-activation is what clamps. */
    private final java.util.Set<java.util.UUID> recentlyActive = new java.util.HashSet<>();

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
            if (!Trinkets.hasActive(player, "scp427")) {
                // the flesh forgets, slowly: old exposure fades while the
                // locket is closed, so nobody gets executed for last week
                int old = player.getPersistentDataContainer()
                    .getOrDefault(exposureKey, PersistentDataType.INTEGER, 0);
                recentlyActive.remove(player.getUniqueId());
                if (old > 0) {
                    player.getPersistentDataContainer().set(exposureKey,
                        PersistentDataType.INTEGER, old - 1);
                }
                continue;
            }
            // always at least a warning's worth of time after re-opening:
            // exposure re-enters clamped 10s below the transformation line
            int clamped = player.getPersistentDataContainer()
                .getOrDefault(exposureKey, PersistentDataType.INTEGER, 0);
            if (clamped > TRANSFORM_AT - 10 && !recentlyActive.contains(player.getUniqueId())) {
                player.getPersistentDataContainer().set(exposureKey,
                    PersistentDataType.INTEGER, TRANSFORM_AT - 10);
            }
            recentlyActive.add(player.getUniqueId());
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 1, true, false));
            rewind(player, "lab.inf");
            rewind(player, "lab.cola");
            rewind(player, "lab.z008");
            int exposure = player.getPersistentDataContainer()
                .getOrDefault(exposureKey, PersistentDataType.INTEGER, 0) + 1;
            player.getPersistentDataContainer().set(exposureKey, PersistentDataType.INTEGER, exposure);
            if (exposure >= TRANSFORM_AT - 30 && exposure < TRANSFORM_AT) {
                // the last thirty seconds: the body knows before the mind -
                // sweat pours, and then it is very sudden (no downed state:
                // the conclusion arrives via setHealth, skipping the
                // collapse entirely - one moment a person, the next not)
                player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH,
                    player.getLocation().add(0, 1.4, 0), 10, 0.25, 0.35, 0.25, 0.02);
                player.getWorld().spawnParticle(org.bukkit.Particle.FALLING_WATER,
                    player.getLocation().add(0, 1.6, 0), 3, 0.2, 0.1, 0.2);
                if (exposure == TRANSFORM_AT - 30) {
                    ActionBars.message(player, Component.text("You are sweating. It smells wrong.",
                        NamedTextColor.GRAY, TextDecoration.ITALIC));
                }
            }
            if (exposure == WARN_AT) {
                ActionBars.message(player, Component.text("Your flesh hums.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
            }
            if (exposure >= TRANSFORM_AT) transform(player);
        }
    }

    /** Death wipes the ledger: exposure belongs to the flesh, and this is
     *  new flesh. Without this, dying to the locket meant every later
     *  pickup executed you on the spot. */
    @org.bukkit.event.EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getPlayer().getPersistentDataContainer().remove(exposureKey);
        recentlyActive.remove(event.getPlayer().getUniqueId());
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
        // setHealth, not damage: no armor, no totem, no SCP-1033-RU bracelet
        // arguing - the transformation is not an attack, it is a conclusion
        player.setHealth(0.0);
        at.setPitch(0); // displays inherit pitch - never let the flesh tilt
        Ravager beast = at.getWorld().spawn(at, Ravager.class, b -> {
            b.customName(Component.text("SCP-427-1", NamedTextColor.DARK_RED));
            b.setCustomNameVisible(false);
            b.setPersistent(true);
            b.setRemoveWhenFarAway(false);
            b.setInvisible(true); // the ravager is the engine, the display is the flesh
            b.addScoreboardTag("scp427.beast");
        });
        org.bukkit.entity.ItemDisplay skin = at.getWorld().spawn(at,
            org.bukkit.entity.ItemDisplay.class, d -> {
                d.setPersistent(true);
                org.bukkit.inventory.ItemStack item =
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
                var meta = item.getItemMeta();
                meta.setItemModel(new NamespacedKey("lab", "scp427_1"));
                item.setItemMeta(meta);
                d.setItemStack(item);
                d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(0, 1.1f, 0), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(2.4f, 2.4f, 2.4f), new org.joml.Quaternionf()));
                d.addScoreboardTag("scp427.beast");
            });
        beast.addPassenger(skin);
        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.7f);
    }
}
