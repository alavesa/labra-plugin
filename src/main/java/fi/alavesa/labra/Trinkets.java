package fi.alavesa.labra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;
import java.util.Map;

/**
 * The SCP:CB equip system for pocket anomalies. SCP-714, SCP-427 and
 * SCP-1033-RU are toggled ON and OFF by right-clicking with them in hand;
 * an active trinket works from ANY inventory slot, so several can be worn
 * at once - and it shows: the active state swaps the item's model to a
 * variant with a white frame around the icon, exactly the Containment
 * Breach "equipped" look (the _on textures live in the lab resource pack).
 */
public final class Trinkets implements Listener {

    private static final List<String> BASES = List.of("scp714", "scp427", "scp1033");

    private static final Map<String, String[]> LINES = Map.of(
        "scp714", new String[]{"Cold jade against your skin.", "You pull the ring free."},
        "scp427", new String[]{"It is warm.", "You close the locket."},
        "scp1033", new String[]{"Twelve needles.", "The clasps release."});

    /** The trinket's base id, active or not - null for non-trinkets. */
    public static String baseOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        for (String s : item.getItemMeta().getCustomModelDataComponent().getStrings()) {
            String base = s.endsWith("_on") ? s.substring(0, s.length() - 3) : s;
            if (BASES.contains(base)) return base;
        }
        return null;
    }

    public static boolean isActive(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        for (String s : item.getItemMeta().getCustomModelDataComponent().getStrings()) {
            if (s.endsWith("_on") && BASES.contains(s.substring(0, s.length() - 3))) return true;
        }
        return false;
    }

    /** Any ACTIVE instance of this trinket anywhere in the inventory. */
    public static boolean hasActive(Player player, String base) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (base.equals(baseOf(item)) && isActive(item)) return true;
        }
        return false;
    }

    public static void setActive(ItemStack item, boolean active) {
        String base = baseOf(item);
        if (base == null) return;
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(active ? base + "_on" : base));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
    }

    /** A dropped trinket always closes/deactivates: SCP-427's exposure is
     *  personal, so the next person to pick the locket up starts from their
     *  OWN clock - an open locket lying around must never ambush them. */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (baseOf(dropped) != null && isActive(dropped)) {
            setActive(dropped, false);
            event.getItemDrop().setItemStack(dropped);
        }
    }

    /** Death drops skip PlayerDropItemEvent - close those too, or the
     *  victim's own open locket ambushes whoever loots the body. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        for (ItemStack dropped : event.getDrops()) {
            if (baseOf(dropped) != null && isActive(dropped)) setActive(dropped, false);
        }
    }

    @EventHandler
    public void onToggle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        String base = baseOf(item);
        if (base == null) return;
        event.setCancelled(true);
        boolean nowActive = !isActive(item);
        setActive(item, nowActive);
        Player player = event.getPlayer();
        ActionBars.message(player, Component.text(LINES.get(base)[nowActive ? 0 : 1],
            NamedTextColor.GRAY, TextDecoration.ITALIC));
        player.playSound(player.getLocation(),
            nowActive ? Sound.ITEM_ARMOR_EQUIP_CHAIN : Sound.ITEM_ARMOR_EQUIP_LEATHER,
            0.7f, nowActive ? 1.3f : 0.9f);
    }
}
