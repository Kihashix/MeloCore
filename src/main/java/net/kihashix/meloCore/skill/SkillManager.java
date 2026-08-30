package net.kihashix.meloCore.skill;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.impl.FrostShot;
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

    /**
     * Gọi khi plugin disable — để các skill hoàn tác thay đổi tạm thời lên
     * player/world (vd: khôi phục attribute, hoàn nguyên block đóng băng).
     */
    public void shutdown() {
        for (Skill skill : skills.values()) {
            skill.shutdown();
        }
    }

    /** Tìm skill theo id — khớp chính xác trước, sau đó khớp không phân biệt hoa thường. */
    public Optional<Skill> get(String id) {
        Skill exact = skills.get(id);
        if (exact != null) return Optional.of(exact);
        for (Skill skill : skills.values()) {
            if (skill.getId().equalsIgnoreCase(id)) return Optional.of(skill);
        }
        return Optional.empty();
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

        // Di trú cấu hình từ id cũ "hanbangchitien" (và "frostshot" nếu có) -> "FrostShot"
        if (config.contains("hanbangchitien") && !config.contains(FrostShot.ID)) {
            config.set(FrostShot.ID + ".cooldown-ms", config.get("hanbangchitien.cooldown-ms"));
            config.set(FrostShot.ID + ".enabled", config.get("hanbangchitien.enabled"));
        }
        config.set("hanbangchitien", null); // xóa section cũ
        config.set("frostshot", null);

        for (Skill skill : skills.values()) {
            skill.setCooldownMs(config.getLong(skill.getId() + ".cooldown-ms", skill.getCooldownMs()));
            skill.setEnabled(config.getBoolean(skill.getId() + ".enabled", skill.isEnabled()));
            // Các option riêng của skill (radius, freeze-time, slowness...) —
            // giá trị thiếu/không hợp lệ sẽ giữ nguyên default của skill.
            for (SkillConfigOption option : skill.getConfigOptions()) {
                option.load(config, skill.getId() + "." + option.getKey());
            }
        }
    }

    public void saveConfig() {
        if (config == null) config = YamlConfiguration.loadConfiguration(configFile); // phòng hợp loadConfig() chưa chạy
        for (Skill skill : skills.values()) {
            config.set(skill.getId() + ".cooldown-ms", skill.getCooldownMs());
            config.set(skill.getId() + ".enabled", skill.isEnabled());
            for (SkillConfigOption option : skill.getConfigOptions()) {
                config.set(skill.getId() + "." + option.getKey(), option.currentValue());
            }
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
                                "&b&l" + skill.getDisplayName() + " &8» &f&l" + String.format("%.1f", remaining / 1000.0) + "s");
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L); // mỗi 0.1s — countdown mịn: 10.0 -> 9.9 -> 9.8 ...
    }
}
