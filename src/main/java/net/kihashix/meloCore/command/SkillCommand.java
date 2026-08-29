package net.kihashix.meloCore.command;

import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.Skill;
import net.kihashix.meloCore.skill.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class SkillCommand implements CommandExecutor {

    private final SkillManager skillManager;
    private final PlayerSkillData skillData;

    public SkillCommand(SkillManager skillManager, PlayerSkillData skillData) {
        this.skillManager = skillManager;
        this.skillData = skillData;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("skills")) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills <give|remove|list|cooldown|toggle|info|debug> ...");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.RED + "Thiếu tham số. /mc skills <give|remove|list|cooldown|toggle|info|debug>");
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "cooldown" -> handleCooldown(sender, args);
            case "toggle" -> handleToggle(sender, args);
            case "info" -> handleInfo(sender, args);
            case "debug" -> handleDebug(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Lệnh con không hợp lệ: " + args[1]);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills give <player> <skill>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        String skillId = args[3].toLowerCase();
        if (skillManager.get(skillId).isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Không tìm thấy skill: " + skillId);
            return;
        }
        boolean added = skillData.addSkill(target.getUniqueId(), skillId);
        sender.sendMessage(added
                ? ChatColor.GREEN + "Đã cấp " + skillId + " cho " + args[2] + "."
                : ChatColor.YELLOW + args[2] + " đã có skill này rồi.");
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills remove <player> <skill>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        boolean removed = skillData.removeSkill(target.getUniqueId(), args[3].toLowerCase());
        sender.sendMessage(removed
                ? ChatColor.GREEN + "Đã gỡ " + args[3] + " khỏi " + args[2] + "."
                : ChatColor.YELLOW + args[2] + " không có skill này.");
    }

    private void handleList(CommandSender sender) {
        if (skillManager.getAll().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Chưa có skill nào được đăng ký.");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "Danh sách skill:");
        for (Skill skill : skillManager.getAll().values()) {
            String status = skill.isEnabled() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + skill.getId()
                    + ChatColor.GRAY + " (" + skill.getDisplayName() + ") [" + status + ChatColor.GRAY + "]");
        }
    }

    private void handleCooldown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền.");
            return;
        }
        if (args.length < 5 || !args[2].equalsIgnoreCase("set")) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills cooldown set <skill> <ms>");
            return;
        }
        Skill skill = skillManager.get(args[3].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(ChatColor.RED + "Không tìm thấy skill: " + args[3]);
            return;
        }
        long ms;
        try {
            ms = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "<ms> phải là số nguyên.");
            return;
        }
        skill.setCooldownMs(ms);
        skillManager.saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Đã đặt cooldown " + skill.getId() + " = " + ms + "ms.");
    }

    private void handleToggle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.skills")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills toggle <skill> <on|off>");
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(ChatColor.RED + "Không tìm thấy skill: " + args[2]);
            return;
        }
        boolean newState = args[3].equalsIgnoreCase("on");
        skill.setEnabled(newState);
        skillManager.saveConfig();
        sender.sendMessage(ChatColor.GREEN + skill.getId() + " đã " + (newState ? "BẬT" : "TẮT") + ".");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills info <skill>");
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(ChatColor.RED + "Không tìm thấy skill: " + args[2]);
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "== " + skill.getDisplayName() + " (" + skill.getId() + ") ==");
        sender.sendMessage(ChatColor.GRAY + "Trạng thái: " + (skill.isEnabled() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(ChatColor.GRAY + "Cooldown: " + ChatColor.WHITE + skill.getCooldownMs() + "ms");
        sender.sendMessage(ChatColor.GRAY + "Debug: " + (skill.isDebug() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("melocore.admin.debug")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Dùng: /mc skills debug <skill>");
            return;
        }
        Skill skill = skillManager.get(args[2].toLowerCase()).orElse(null);
        if (skill == null) {
            sender.sendMessage(ChatColor.RED + "Không tìm thấy skill: " + args[2]);
            return;
        }
        boolean newState = !skill.isDebug();
        skill.setDebug(newState); // KHÔNG saveConfig() -> tự reset về false sau khi restart
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Debug " + skill.getId() + " đã "
                + (newState ? "BẬT" : "TẮT") + " (chỉ tạm thời, không lưu file).");
    }
}
