package net.kihashix.meloCore.listener;

import net.kihashix.meloCore.item.CustomItem;
import net.kihashix.meloCore.item.ItemManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for custom item interactions.
 * Handles right-click events for all custom items.
 */
public class ItemListener implements Listener {

    private final ItemManager itemManager;

    public ItemListener(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    /**
     * Handle player interaction events.
     * Triggers on right-click (AIR_CLICK, PHYSICAL) for custom items.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        // Check main hand first
        ItemStack mainHand = event.getItem();
        if (mainHand != null && mainHand.getType() != Material.AIR && handleItem(player, mainHand)) {
            event.setCancelled(true);
            return;
        }

        // Check offhand
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() != Material.AIR && handleItem(player, offHand)) {
            event.setCancelled(true);
        }
    }

    /**
     * Check if an item is a registered custom item and trigger its action.
     *
     * @param player The player who clicked.
     * @param item The item that was clicked.
     * @return true if a custom item was handled.
     */
    private boolean handleItem(Player player, ItemStack item) {
        for (CustomItem customItem : itemManager.getAll().values()) {
            if (customItem.isThisItem(item)) {
                customItem.onRightClick(player);
                return true;
            }
        }
        return false;
    }
}
