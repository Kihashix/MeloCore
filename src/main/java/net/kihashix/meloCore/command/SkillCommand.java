package net.kihashix.meloCore.command;

import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.Skill;
import net.kihashix.meloCore.skill.SkillConfigOption;
import net.kihashix.meloCore.skill.SkillManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.stream.Collectors;

public class SkillCommand implements CommandExecutor {

    private static final String USAGE = "/mc skills <give|remove|list|cooldown|toggle|config|info|debug> ...";

    private final SkillManager skillManager;
    private final PlayerSkillData skillData;

    public SkillCommand(SkillManager skillManager, PlayerSkillData skillData) {
        this.skillManager = skillManager;
        this.skillData = skillData;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("skills")) {
            sender.sendMessage(Component.text("Dùng: " + USAGE, NamedTextColor.RED));
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(Component.text("Thiếu tham số. " + USAGE, NamedTextColor.RED));
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "cooldown" -> handleCooldown(sender, args);
            case "toggle" -> handleToggle(sender, args);
            case "config" -> handleConfig(sender, args);
            case "info" -> handleInfo(sender, args);
            case "debug" -> handleDebug(sender, args);
            default -> sender.sendMessage(Component.text("Lệnh con không hợp lệ: " + args[1], NamedTextColor.RED));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Dùng: /mc skills give <player> <skill>", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        Skill skill = skillManager.get(args[3]).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[3], NamedTextColor.RED));
            return;
        }
        boolean added = skillData.addSkill(target.getUniqueId(), skill.getId());
        sender.sendMessage(added
                ? Component.text("Đã cấp " + skill.getId() + " cho " + args[2] + ".", NamedTextColor.GREEN)
                : Component.text(args[2] + " đã có skill này rồi.", NamedTextColor.YELLOW));
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Dùng: /mc skills remove <player> <skill>", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        Skill skill = skillManager.get(args[3]).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[3], NamedTextColor.RED));
            return;
        }
        boolean removed = skillData.removeSkill(target.getUniqueId(), skill.getId());
        sender.sendMessage(removed
                ? Component.text("Đã gỡ " + skill.getId() + " khỏi " + args[2] + ".", NamedTextColor.GREEN)
                : Component.text(args[2] + " không có skill này.", NamedTextColor.YELLOW));
    }

    private void handleList(CommandSender sender) {
        if (skillManager.getAll().isEmpty()) {
            sender.sendMessage(Component.text("Chưa có skill nào được đăng ký.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Danh sách skill:", NamedTextColor.AQUA));
        for (Skill skill : skillManager.getAll().values()) {
            Component status = skill.isEnabled()
                    ? Component.text("ON", NamedTextColor.GREEN)
                    : Component.text("OFF", NamedTextColor.RED);
            sender.sendMessage(Component.text()
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(skill.getId(), NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(displayName(skill))
                    .append(Component.text(") [", NamedTextColor.GRAY))
                    .append(status)
                    .append(Component.text("]", NamedTextColor.GRAY))
                    .build());
        }
    }

    /** Tên hiển thị có tô màu &l (bold) + xanh băng — parse cả mã màu '&' nếu có. */
    private Component displayName(Skill skill) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize("&b&l" + skill.getDisplayName());
    }

    private void handleCooldown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 5 || !args[2].equalsIgnoreCase("set")) {
            sender.sendMessage(Component.text("Dùng: /mc skills cooldown set <skill> <ms>", NamedTextColor.RED));
            return;
        }
        Skill skill = skillManager.get(args[3].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[3], NamedTextColor.RED));
            return;
        }
        long ms;
        try {
            ms = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("<ms> phải là số nguyên.", NamedTextColor.RED));
            return;
        }
        skill.setCooldownMs(ms);
        skillManager.saveConfig();
        sender.sendMessage(Component.text("Đã đặt cooldown " + skill.getId() + " = " + ms + "ms.", NamedTextColor.GREEN));
    }

    private void handleToggle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Dùng: /mc skills toggle <skill> <on|off>", NamedTextColor.RED));
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[2], NamedTextColor.RED));
            return;
        }
        boolean newState = args[3].equalsIgnoreCase("on");
        skill.setEnabled(newState);
        skillManager.saveConfig();
        sender.sendMessage(Component.text(skill.getId() + " đã " + (newState ? "BẬT" : "TẮT") + ".", NamedTextColor.GREEN));
    }

    private void handleConfig(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(Component.text("Dùng: /mc skills config <skill> <option> <giá trị>", NamedTextColor.RED));
            return;
        }
        Skill skill = skillManager.get(args[2]).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[2], NamedTextColor.RED));
            return;
        }
        SkillConfigOption option = skill.getOption(args[3]).orElse(null);
        if (option == null) {
            String keys = skill.getConfigOptions().stream()
                    .map(SkillConfigOption::getKey)
                    .collect(Collectors.joining(", "));
            sender.sendMessage(Component.text("Skill " + skill.getId() + " không có option '" + args[3]
                    + "'. Khả dụng: " + (keys.isEmpty() ? "(không có)" : keys), NamedTextColor.RED));
            return;
        }
        // Parse + kiểm tra giới hạn + áp dụng — lỗi trả về dưới dạng thông báo
        String error = option.apply(args[4]);
        if (error != null) {
            sender.sendMessage(Component.text(error, NamedTextColor.RED));
            return;
        }
        skillManager.saveConfig();
        sender.sendMessage(Component.text("Đã đặt " + option.getKey() + " của " + skill.getId()
                + " = " + describeValue(option) + ".", NamedTextColor.GREEN));
    }

    /**
     * Giá trị kèm giải thích thân thiện cho các option quen thuộc
     * (chỉ phần hiển thị — giá trị lưu/đặt vẫn là số gốc).
     */
    private String describeValue(SkillConfigOption option) {
        String value = option.getValue();
        switch (option.getKey()) {
            case "slowness" -> {
                // amplifier 0-based -> hiển thị cấp La Mã (3 = Slowness IV)
                return value + " (Slowness " + roman((int) Long.parseLong(value) + 1) + ")";
            }
            case "freeze-time" -> {
                return value + " (" + String.format(Locale.ROOT, "%.1f", Long.parseLong(value) / 1000.0) + "s)";
            }
            default -> {
                return value;
            }
        }
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"};

    /** Số La Mã 1..11 (slowness amplifier tối đa 10 -> cấp tối đa XI). */
    private static String roman(int level) {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level);
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Dùng: /mc skills info <skill>", NamedTextColor.RED));
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[2], NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("== ", NamedTextColor.AQUA)
                .append(displayName(skill))
                .append(Component.text(" (" + skill.getId() + ") ==", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("Trạng thái: ", NamedTextColor.GRAY)
                .append(skill.isEnabled()
                        ? Component.text("ON", NamedTextColor.GREEN)
                        : Component.text("OFF", NamedTextColor.RED)));
        sender.sendMessage(Component.text("Cooldown: ", NamedTextColor.GRAY)
                .append(Component.text(skill.getCooldownMs() + "ms", NamedTextColor.WHITE)));
        // Các option cấu hình riêng của skill (radius, freeze-time, slowness...)
        for (SkillConfigOption option : skill.getConfigOptions()) {
            sender.sendMessage(Component.text(option.getLabel() + ": ", NamedTextColor.GRAY)
                    .append(Component.text(describeValue(option), NamedTextColor.WHITE)));
        }
        sender.sendMessage(Component.text("Debug: ", NamedTextColor.GRAY)
                .append(skill.isDebug()
                        ? Component.text("ON", NamedTextColor.GREEN)
                        : Component.text("OFF", NamedTextColor.RED)));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.debug")) {
            sender.sendMessage(Component.text("Bạn không có quyền.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Dùng: /mc skills debug <skill>", NamedTextColor.RED));
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(Component.text("Không tìm thấy skill: " + args[2], NamedTextColor.RED));
            return;
        }
        boolean newState = !skill.isDebug();
        skill.setDebug(newState); // KHÔNG saveConfig() -> tự reset về false sau khi restart
        sender.sendMessage(Component.text("Debug " + skill.getId() + " đã "
                + (newState ? "BẬT" : "TẮT"), NamedTextColor.LIGHT_PURPLE));
    }
}
