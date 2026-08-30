package net.kihashix.meloCore.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manager for all custom items in MeloCore.
 * Handles item registration and provides item lookup.
 */
public class ItemManager {

    private final JavaPlugin plugin;
    private final Map<String, CustomItem> items;

    public ItemManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.items = new LinkedHashMap<>();
    }

    /**
     * Register all custom items.
     * Call this during plugin initialization.
     */
    public void register() {
        // Register HelloStick
        HelloStick helloStick = new HelloStick(plugin);
        items.put(HelloStick.ID.toLowerCase(), helloStick);

        plugin.getLogger().info("Đã đăng ký " + items.size() + " custom item(s).");
    }

    /**
     * Get an item by its ID.
     *
     * @param id The item ID (case-insensitive).
     * @return The CustomItem, or null if not found.
     */
    public CustomItem get(String id) {
        return items.get(id.toLowerCase());
    }

    /**
     * Get all registered items.
     *
     * @return Map of item IDs to CustomItems.
     */
    public Map<String, CustomItem> getAll() {
        return items;
    }

    /**
     * Format item list for display in chat.
     *
     * @param sender The command sender who will see the list.
     */
    public void sendListTo(CommandSender sender) {
        sender.sendMessage(Component.text("Danh sách custom items:", NamedTextColor.AQUA));
        for (Map.Entry<String, CustomItem> entry : items.entrySet()) {
            CustomItem item = entry.getValue();
            sender.sendMessage(Component.text()
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(entry.getKey(), NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text(item.getDisplayName(), NamedTextColor.GOLD))
                    .append(Component.text(") [", NamedTextColor.GRAY))
                    .append(Component.text(item.getMaterial().name(), NamedTextColor.DARK_GRAY))
                    .append(Component.text("]", NamedTextColor.GRAY))
                    .build());
        }
    }
}
