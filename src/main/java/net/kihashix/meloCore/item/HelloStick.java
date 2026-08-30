package net.kihashix.meloCore.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * HelloStick - Custom item that displays "Hello World" in chat when right-clicked.
 */
public class HelloStick implements CustomItem {

    /** Unique identifier for this item type. */
    public static final String ID = "hellostick";

    /** Display name shown on the item. */
    private static final String DISPLAY_NAME = "&6&lHelloStick";

    /** Lore lines shown below the item name. */
    private static final List<String> LORE = List.of(
            "&7Right-click to say hello!",
            "&a&l>>> Hello World <<<"
    );

    /** The Minecraft material ID. */
    private static final Material MATERIAL = Material.STICK;

    private final NamespacedKey itemTagKey;

    public HelloStick(JavaPlugin plugin) {
        this.itemTagKey = new NamespacedKey(plugin, "hellostick_tagged");
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public Material getMaterial() {
        return MATERIAL;
    }

    /**
     * Check if the given item is a HelloStick (implements CustomItem interface).
     */
    @Override
    public boolean isThisItem(ItemStack item) {
        if (item == null || item.getType() != MATERIAL) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte tagged = meta.getPersistentDataContainer().get(itemTagKey, PersistentDataType.BYTE);
        return tagged != null && tagged == 1;
    }

    /**
     * Creates a new HelloStick item stack.
     *
     * @return A configured ItemStack with the HelloStick item.
     */
    public @NotNull ItemStack createItem() {
        ItemStack item = new ItemStack(MATERIAL);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Set display name with color
            meta.displayName(parseLegacy(DISPLAY_NAME));

            // Set lore
            meta.lore(parseLegacyLore());

            // Add persistent tag to identify this custom item
            meta.getPersistentDataContainer().set(itemTagKey, PersistentDataType.BYTE, (byte) 1);

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Called when a player right-clicks with HelloStick.
     * Displays "Hello World" message in chat.
     *
     * @param player The player who clicked.
     */
    @Override
    public void onRightClick(Player player) {
        // Display "Hello World" in chat with styling
        player.sendMessage(Component.text()
                .append(Component.text("[HelloStick] ", NamedTextColor.GOLD))
                .append(Component.text("Hello World!", NamedTextColor.GREEN))
                .build());

        // Play a sound effect
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    /**
     * Parse legacy color codes (&) to Component.
     */
    private Component parseLegacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /**
     * Parse legacy color codes for lore list.
     */
    private List<Component> parseLegacyLore() {
        return LORE.stream()
                .map(this::parseLegacy)
                .toList();
    }
}
