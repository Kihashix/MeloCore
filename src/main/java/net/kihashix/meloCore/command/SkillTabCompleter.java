package net.kihashix.meloCore.command;

import net.kihashix.meloCore.skill.Skill;
import net.kihashix.meloCore.skill.SkillConfigOption;
import net.kihashix.meloCore.skill.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SkillTabCompleter implements TabCompleter {

    private final SkillManager skillManager;

    public SkillTabCompleter(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) return filter(List.of("skills"), args[0]);
        if (args.length == 2) return filter(List.of("give", "remove", "list", "cooldown", "toggle", "config", "info", "debug"), args[1]);

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "give", "remove" -> {
                if (args.length == 3) return filter(playerNames(), args[2]);
                if (args.length == 4) return filter(skillIds(), args[3]);
            }
            case "toggle" -> {
                if (args.length == 3) return filter(skillIds(), args[2]);
                if (args.length == 4) return filter(List.of("on", "off"), args[3]);
            }
            case "config" -> {
                if (args.length == 3) return filter(skillIds(), args[2]);
                if (args.length == 4) {
                    // Gợi ý các option của skill (radius, freeze-time, slowness...)
                    return skillManager.get(args[2].toLowerCase())
                            .map(s -> filter(s.getConfigOptions().stream()
                                    .map(SkillConfigOption::getKey)
                                    .collect(Collectors.toList()), args[3]))
                            .orElse(new ArrayList<>());
                }
                if (args.length == 5) {
                    // Gợi ý giá trị hiện tại của option
                    return skillManager.get(args[2].toLowerCase())
                            .flatMap(s -> s.getOption(args[3]))
                            .map(option -> filter(List.of(option.getValue()), args[4]))
                            .orElse(new ArrayList<>());
                }
            }
            case "info", "debug" -> {
                if (args.length == 3) return filter(skillIds(), args[2]);
            }
            case "cooldown" -> {
                if (args.length == 3) return filter(List.of("set"), args[2]);
                if (args.length == 4) return filter(skillIds(), args[3]);
                if (args.length == 5) {
                    // Gợi ý <ms> hiện tại của skill thay vì placeholder
                    return skillManager.get(args[3].toLowerCase())
                            .map(s -> filter(List.of(String.valueOf(s.getCooldownMs())), args[4]))
                            .orElse(new ArrayList<>());
                }
            }
            default -> {}
        }
        return new ArrayList<>();
    }

    private List<String> skillIds() {
        return skillManager.getAll().values().stream().map(Skill::getId).collect(Collectors.toList());
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String current) {
        return options.stream()
                .filter(o -> o.toLowerCase().startsWith(current.toLowerCase()))
                .collect(Collectors.toList());
    }
}
