package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SCP-038, the Everything Tree. Offerings are made the botanical way: DROP
 * an item within three blocks of the trunk, and a moment later a copy falls
 * out of the branches (the original stays where it lies - the tree copies,
 * it does not eat). Five-minute cooldown per person; keycards copy fine
 * (the Foundation's nightmare), other anomalous items refuse to grow.
 * Right-clicking the trunk does nothing but rustle.
 */
public final class Scp038Listener implements Listener, Runnable {

    private static final long COOLDOWN_MS = 5 * 60 * 1000;
    private static final double RADIUS = 3.0;

    private final LabraPlugin plugin;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey seenKey;

    public Scp038Listener(LabraPlugin plugin) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "scp038_cd");
        this.seenKey = new NamespacedKey(plugin, "scp038_seen");
    }

    /** Every second: look for fresh offerings lying under the branches. */
    @Override
    public void run() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Item drop : world.getEntitiesByClass(Item.class)) {
                if (!drop.isOnGround()) continue;
                if (drop.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE)) continue;
                Interaction tree = null;
                for (Entity near : drop.getNearbyEntities(RADIUS, RADIUS, RADIUS)) {
                    if (near instanceof Interaction interaction
                        && interaction.getScoreboardTags().contains("lab.scp038")) {
                        tree = interaction;
                        break;
                    }
                }
                if (tree == null) continue;
                drop.getPersistentDataContainer().set(seenKey, PersistentDataType.BYTE, (byte) 1);
                consider(tree, drop);
            }
        }
    }

    private void consider(Interaction tree, Item drop) {
        UUID throwerId = drop.getThrower();
        Player thrower = throwerId == null ? null : plugin.getServer().getPlayer(throwerId);
        if (thrower == null) return; // no anonymous gifts, no dispenser farms

        long now = System.currentTimeMillis();
        long until = thrower.getPersistentDataContainer()
            .getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
        if (now < until) {
            ActionBars.message(thrower, Component.text("The branch is bare.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        if (refuses(drop.getItemStack())) {
            ActionBars.message(thrower, Component.text("It refuses.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            tree.getWorld().playSound(tree.getLocation(), Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.6f, 0.6f);
            return;
        }
        thrower.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + COOLDOWN_MS);

        ItemStack copy = drop.getItemStack().clone();
        copy.setAmount(1);
        var canopy = tree.getLocation().add(0, 1.9, 0);
        Item fruit = tree.getWorld().dropItem(canopy, copy);
        fruit.getPersistentDataContainer().set(seenKey, PersistentDataType.BYTE, (byte) 1);
        fruit.setVelocity(new Vector(
            ThreadLocalRandom.current().nextDouble(-0.08, 0.08), 0.12,
            ThreadLocalRandom.current().nextDouble(-0.08, 0.08)));
        tree.getWorld().spawnParticle(Particle.COMPOSTER, canopy, 12, 0.4, 0.3, 0.4);
        tree.getWorld().playSound(canopy, Sound.BLOCK_CAVE_VINES_PICK_BERRIES, 0.8f, 0.9f);
        ActionBars.message(thrower, Component.text("A fruit that is not a fruit.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Anomalous things refuse to grow - EXCEPT keycards, which copy fine
     *  (that is the whole security problem with this tree). ID cards are
     *  identities, not things; those it will not touch. */
    private boolean refuses(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        for (String s : item.getItemMeta().getCustomModelDataComponent().getStrings()) {
            if (s.equals("lab_idcard")) return true;
            if (s.contains("scp")) return true;
            if (s.startsWith("lab_syringe")) return true;
        }
        String components = item.getItemMeta().getAsString();
        return components.contains("scp009") || components.contains("scp207")
            || components.contains("scp500") || components.contains("scp008_syringe");
    }

    @EventHandler
    public void onTouch(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction tree)) return;
        if (!tree.getScoreboardTags().contains("lab.scp038")) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("The branches sway.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
        tree.getWorld().playSound(tree.getLocation(), Sound.BLOCK_AZALEA_LEAVES_STEP, 0.7f, 0.8f);
    }
}
