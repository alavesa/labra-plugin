package fi.alavesa.labra;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * SCP-005, the Skeleton Key (a trial_key item). Right-click ANY openable - a
 * wooden or iron door, a trapdoor, a fence gate - and it opens or closes,
 * whether or not a keycard reader is bolted next to it. Iron doors, which no
 * hand can normally work, swing for the key like anything else. Both halves of
 * a two-tall door move together.
 *
 * The key is recognised by its custom_model_data string "scp005", exactly the
 * same marker every sibling plugin uses for its own 005 bypass, so a 914-forged
 * copy or a datapack give both count.
 */
public final class Scp005Listener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // fire once, not for both hands
        Block block = event.getClickedBlock();
        if (block == null || !(block.getBlockData() instanceof Openable)) return;
        Player player = event.getPlayer();
        if (!isSkeletonKey(player.getInventory().getItemInMainHand())) return;

        // We drive the open ourselves so it also works on iron doors, and so a
        // keycard reader bound to this door never gets a competing swipe.
        event.setCancelled(true);
        boolean nowOpen = toggle(block);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
            doorSound(block, nowOpen), 0.9f, 1.0f);
        player.swingMainHand();
    }

    /** Toggle the openable; return its new open state. Two-tall doors move as one. */
    private boolean toggle(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Door door) {
            boolean open = !door.isOpen();
            Block bottom = door.getHalf() == Door.Half.TOP ? block.getRelative(0, -1, 0) : block;
            Block top = door.getHalf() == Door.Half.TOP ? block : block.getRelative(0, 1, 0);
            setOpen(bottom, open);
            setOpen(top, open);
            return open;
        }
        if (data instanceof Openable op) {
            boolean open = !op.isOpen();
            op.setOpen(open);
            block.setBlockData(op, false);
            return open;
        }
        return false;
    }

    private void setOpen(Block block, boolean open) {
        if (block.getBlockData() instanceof Openable op) {
            op.setOpen(open);
            block.setBlockData(op, false);
        }
    }

    private Sound doorSound(Block block, boolean open) {
        String name = block.getType().name();
        boolean metal = name.startsWith("IRON_") || name.startsWith("COPPER_");
        if (name.endsWith("TRAPDOOR")) {
            return metal ? (open ? Sound.BLOCK_IRON_TRAPDOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_CLOSE)
                         : (open ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE);
        }
        if (name.endsWith("FENCE_GATE")) {
            return open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE;
        }
        return metal ? (open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE)
                     : (open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE);
    }

    private boolean isSkeletonKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp005");
    }
}
