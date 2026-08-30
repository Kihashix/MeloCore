package net.kihashix.meloCore.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Base interface for custom items in MeloCore.
 */
public interface CustomItem {

    /**
     * Get the unique identifier for this item.
     *
     * @return The item ID.
     */
    String getId();

    /**
     * Get the display name of the item.
     *
     * @return The display name.
     */
    String getDisplayName();

    /**
     * Get the Minecraft material for this item.
     *
     * @return The Material.
     */
    Material getMaterial();

    /**
     * Create an item stack of this custom item.
     *
     * @return A new ItemStack.
     */
    @NotNull ItemStack createItem();

    /**
     * Check if an item stack is this custom item.
     *
     * @param item The item to check.
     * @return true if it matches this custom item.
     */
    boolean isThisItem(ItemStack item);

    /**
     * Handle right-click action.
     *
     * @param player The player who clicked.
     */
    void onRightClick(Player player);
}
