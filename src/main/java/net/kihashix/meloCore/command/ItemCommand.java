package net.kihashix.meloCore.command;

import net.kihashix.meloCore.item.CustomItem;
import net.kihashix.meloCore.item.ItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Command handler for /mc items give/list.
 */
public class ItemCommand implements CommandExecutor {

    private static final String USAGE = "/mc items <give|list> ...";

    private final ItemManager itemManager;

    public ItemCommand(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("items")) {
            sender.sendMessage(Component.text("Dùng: " + USAGE, NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Thiếu tham số. " + USAGE, NamedTextColor.RED));
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "list" -> handleList(sender);
            default -> sender.sendMessage(Component.text("Lệnh con không hợp lệ: " + args[1], NamedTextColor.RED));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.items")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Dùng: /mc items give <player> <item> [amount]", NamedTextColor.RED));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        CustomItem item = itemManager.get(args[3]);

        if (item == null) {
            sender.sendMessage(Component.text("Không tìm thấy item: " + args[3], NamedTextColor.RED));
            return;
        }

        // Default amount is 1, max is 64
        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Integer.parseInt(args[4]);
                if (amount < 1) amount = 1;
                if (amount > 64) amount = 64;
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Số lượng phải là số nguyên (1-64).", NamedTextColor.RED));
                return;
            }
        }

        // Give the item to the player
        if (!target.isOnline()) {
            sender.sendMessage(Component.text("Người chơi " + args[2] + " hiện không online. Item sẽ không được trao.", NamedTextColor.YELLOW));
            return;
        }

        org.bukkit.entity.Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            var itemStack = item.createItem();
            itemStack.setAmount(amount);

            // Try to give in inventory, drop if full
            java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> overflow =
                    onlineTarget.getInventory().addItem(itemStack);

            if (overflow.isEmpty()) {
                sender.sendMessage(Component.text("Đã trao " + amount + "x " + item.getId() + " cho " + target.getName() + ".", NamedTextColor.GREEN));
            } else {
                // Drop excess items at player's location
                for (org.bukkit.inventory.ItemStack drop : overflow.values()) {
                    onlineTarget.getWorld().dropItemNaturally(onlineTarget.getLocation(), drop);
                }
                sender.sendMessage(Component.text("Đã trao " + (amount - overflow.size()) + "x " + item.getId() + " cho " + target.getName() + " (một số rơi ra đất do túi đồ đầy).", NamedTextColor.YELLOW));
            }
        }
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(Component.text("=== Custom Items ===", NamedTextColor.AQUA));
        itemManager.sendListTo(sender);
        sender.sendMessage(Component.text("Sử dụng /mc items give <player> <item> [amount] để trao item.", NamedTextColor.GRAY));
    }
}
