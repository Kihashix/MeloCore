package net.kihashix.meloCore.skill;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.data.PlayerSkillData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class SkillManager {

    private final MeloCore plugin;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final File configFile;
    private FileConfiguration config;

    public SkillManager(MeloCore plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "skills.yml");
    }

    public void register(Skill skill) {
        skills.put(skill.getId(), skill);
    }

    public Optional<Skill> get(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public Map<String, Skill> getAll() {
        return skills;
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                plugin.getLogger().severe("Không thể tạo thư mục dữ liệu: " + folder.getAbsolutePath());
            }
            try {
                if (!configFile.createNewFile() && !configFile.exists()) {
                    plugin.getLogger().severe("Không thể tạo skills.yml: " + configFile.getAbsolutePath());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Không thể tạo skills.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        for (Skill skill : skills.values()) {
            skill.setCooldownMs(config.getLong(skill.getId() + ".cooldown-ms", skill.getCooldownMs()));
            skill.setEnabled(config.getBoolean(skill.getId() + ".enabled", skill.isEnabled()));
        }
    }

    public void saveConfig() {
        if (config == null) config = YamlConfiguration.loadConfiguration(configFile); // phòng hợp loadConfig() chưa chạy
        for (Skill skill : skills.values()) {
            config.set(skill.getId() + ".cooldown-ms", skill.getCooldownMs());
            config.set(skill.getId() + ".enabled", skill.isEnabled());
        }
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Không thể lưu skills.yml: " + e.getMessage());
        }
    }

    /**
     * Hiển thị cooldown còn lại lên action bar — LUÔN chạy bất kể debug bật/tắt,
     * và không đụng tới kênh debug (tránh spam debug theo yêu cầu).
     */
    public void startActionBarTask(PlayerSkillData skillData) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    for (String skillId : skillData.getSkills(player.getUniqueId())) {
                        Skill skill = skills.get(skillId);
                        if (!(skill instanceof AbstractSkill abstractSkill)) continue;
                        long remaining = abstractSkill.getRemainingCooldownMs(player);
                        if (remaining <= 0) continue;
                        abstractSkill.sendActionBar(player,
                                "&b" + skill.getDisplayName() + " &7- &f" + String.format("%.1f", remaining / 1000.0) + "s");
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // mỗi 0.5s
    }
}
