package net.kihashix.meloCore.skill;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public interface Skill {

    String getId();

    String getDisplayName();

    long getCooldownMs();

    void setCooldownMs(long cooldownMs);

    boolean isEnabled();

    void setEnabled(boolean enabled);

    boolean isDebug();

    void setDebug(boolean debug);

    /**
     * Gọi khi player kích hoạt skill (vd: shift).
     * Trả về true nếu kích hoạt thành công (không bị cooldown, đang bật...).
     */
    boolean activate(Player player);

    /**
     * Các tùy chọn cấu hình runtime của skill (vd: radius, freeze-time, slowness) —
     * dùng chung cho lệnh /mc skills config, tab-complete, /mc skills info và skills.yml.
     * Skill không có tùy chọn nào trả về danh sách rỗng.
     */
    List<SkillConfigOption> getConfigOptions();

    /** Tìm option theo key, không phân biệt hoa thường. */
    default Optional<SkillConfigOption> getOption(String key) {
        for (SkillConfigOption option : getConfigOptions()) {
            if (option.getKey().equalsIgnoreCase(key)) return Optional.of(option);
        }
        return Optional.empty();
    }

    /**
     * Được gọi khi plugin disable — để skill hoàn tác các thay đổi tạm thời lên
     * player/world (attribute, block đóng băng...). Mặc định không làm gì.
     */
    default void shutdown() {}
}
