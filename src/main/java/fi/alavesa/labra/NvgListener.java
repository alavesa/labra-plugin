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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Night-vision goggles, now a leather-helmet item with a per-type first-person
 * overlay (drawn by FireManager's headwear pass through the ActionBars hub, NOT
 * the vanilla pumpkin blur). Three types:
 *   green - the classic NVG, drains a battery (a fresh pair lasts 30 min); a 9V
 *           in hand right-clicks a fresh cell in.
 *   red   - infinite battery (never runs dry).
 *   blue  - infinite; "recon" goggles that make nearby SCPs glow through walls.
 */
public final class NvgListener implements Listener, Runnable {

    private static final int FULL_CHARGE_SECONDS = 30 * 60;

    private final LabraPlugin plugin;
    private final LabRegistry registry;
    private final NamespacedKey chargeKey;
    private final Set<UUID> wasSeeing = new HashSet<>();
    private final java.util.Map<UUID, Integer> lastSegments = new java.util.HashMap<>();
    private final Set<UUID> glowing = new HashSet<>();   // SCP entities we're lighting up for blue NVG

    public NvgListener(LabraPlugin plugin, LabRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.chargeKey = new NamespacedKey(plugin, "nvg_charge");
    }

    private boolean isBattery(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_battery");
    }

    private int charge(ItemStack goggles) {
        Integer stored = goggles.getItemMeta().getPersistentDataContainer()
            .get(chargeKey, PersistentDataType.INTEGER);
        return stored == null ? FULL_CHARGE_SECONDS : stored;
    }

    private void setCharge(ItemStack goggles, int seconds) {
        var meta = goggles.getItemMeta();
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER,
            Math.max(0, Math.min(FULL_CHARGE_SECONDS, seconds)));
        goggles.setItemMeta(meta);
    }

    /** Once a second: run the worn goggles - sight, battery drain and the blue
     *  see-through-walls sweep. The green tint/overlay itself is painted elsewhere. */
    @Override
    public void run() {
        Set<UUID> shouldGlow = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack helmet = player.getInventory().getHelmet();
            String type = registry.nvgType(helmet);
            if (type == null) {
                if (lastSegments.remove(player.getUniqueId()) != null) ActionBars.clearPersistent(player);
                if (wasSeeing.remove(player.getUniqueId())) player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                continue;
            }
            boolean infinite = !type.equals("green");
            int left = infinite ? FULL_CHARGE_SECONDS : charge(helmet) - 1;
            if (!infinite) setCharge(helmet, left);

            if (left > 0) {
                wasSeeing.add(player.getUniqueId());
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, true, false));
                int segments = infinite ? 10 : (int) Math.ceil(left * 10.0 / FULL_CHARGE_SECONDS);
                Integer prev = lastSegments.put(player.getUniqueId(), segments);
                ActionBars.persistent(player, batteryGlyph(segments), 46);
                if (!infinite && prev != null && segments <= 2 && prev != segments) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 0.6f);
                }
            } else {
                if (wasSeeing.remove(player.getUniqueId())) {
                    player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7f, 0.5f);
                }
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                ActionBars.persistent(player, batteryGlyph(0), 46);
            }

            // blue recon: light up nearby SCPs so they read through walls
            if (type.equals("blue")) {
                for (Entity e : player.getNearbyEntities(48, 48, 48)) {
                    if (isScp(e)) { e.setGlowing(true); shouldGlow.add(e.getUniqueId()); }
                }
            }
        }
        // stop glowing SCPs that no blue goggles can see any more
        glowing.removeIf(id -> {
            if (shouldGlow.contains(id)) return false;
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.setGlowing(false);
            return true;
        });
        glowing.addAll(shouldGlow);
    }

    private boolean isScp(Entity e) {
        for (String tag : e.getScoreboardTags()) if (tag.startsWith("scp")) return true;
        return false;
    }

    /** A 9V in hand: right-click feeds green goggles a fresh cell (red/blue never need it). */
    @EventHandler
    public void onRecharge(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack battery = event.getItem();
        if (!isBattery(battery)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();

        ItemStack goggles = null;
        if (registry.isNvg(player.getInventory().getHelmet())) {
            goggles = player.getInventory().getHelmet();
        } else {
            for (ItemStack item : player.getInventory().getContents()) {
                if (registry.isNvg(item)) { goggles = item; break; }
            }
        }
        if (goggles == null) { ActionBars.message(player, line("Nothing to power.")); return; }
        if (!"green".equals(registry.nvgType(goggles))) {
            ActionBars.message(player, line("These don't take a cell."));
            return;
        }
        if (charge(goggles) >= FULL_CHARGE_SECONDS - 5) { ActionBars.message(player, line("Still charged.")); return; }
        setCharge(goggles, FULL_CHARGE_SECONDS);
        battery.setAmount(battery.getAmount() - 1);
        ActionBars.message(player, line("Fresh cell. The dark turns green."));
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.7f, 1.6f);
    }

    private Component batteryGlyph(int segments) {
        return Component.text(String.valueOf((char) (0xE200 + segments)))
            .font(net.kyori.adventure.key.Key.key("lab", "hud"))
            .color(NamedTextColor.WHITE);
    }

    private Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
