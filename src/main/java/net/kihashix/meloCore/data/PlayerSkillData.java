package net.kihashix.meloCore.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PlayerSkillData {

    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration config;

    private final Map<UUID, Set<String>> cache = new HashMap<>();

    public PlayerSkillData(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerskills.yml");
        load();
    }

    public void load() {
        ensureFileExists();
        config = YamlConfiguration.loadConfiguration(file);
        cache.clear();
        ConfigurationSection section = config.getConfigurationSection("players");
        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                cache.put(UUID.fromString(uuidStr),
                        new HashSet<>(config.getStringList("players." + uuidStr)));
            }
        }
    }

    /** Tạo file dữ liệu nếu chưa có; log severe và bỏ qua khi hệ thống file lỗi. */
    private void ensureFileExists() {
        if (file.exists()) return;
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("Không thể tạo thư mục dữ liệu: " + folder.getAbsolutePath());
            return;
        }
        try {
            if (!file.createNewFile() && !file.exists()) {
                plugin.getLogger().severe("Không thể tạo playerskills.yml: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Không thể tạo playerskills.yml: " + e.getMessage());
        }
    }

    public void save() {
        for (Map.Entry<UUID, Set<String>> entry : cache.entrySet()) {
            config.set("players." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Không thể lưu playerskills.yml: " + e.getMessage());
        }
    }

    /** Đổi id một skill trong toàn bộ dữ liệu player (vd di trú "hanbangchitien" -> "frostshot"). */
    public void renameSkill(String oldId, String newId) {
        if (oldId.equalsIgnoreCase(newId)) return;
        boolean changed = false;
        for (Set<String> skills : cache.values()) {
            if (skills.remove(oldId)) {
                skills.add(newId);
                changed = true;
            }
        }
        if (changed) save();
    }

    public boolean hasSkill(UUID uuid, String skillId) {
        return cache.getOrDefault(uuid, Collections.emptySet()).contains(skillId);
    }

    public boolean addSkill(UUID uuid, String skillId) {
        boolean added = cache.computeIfAbsent(uuid, k -> new HashSet<>()).add(skillId);
        if (added) save();
        return added;
    }

    public boolean removeSkill(UUID uuid, String skillId) {
        Set<String> set = cache.get(uuid);
        if (set == null) return false;
        boolean removed = set.remove(skillId);
        if (removed) save();
        return removed;
    }

    public Set<String> getSkills(UUID uuid) {
        return new HashSet<>(cache.getOrDefault(uuid, Collections.emptySet()));
    }
}
