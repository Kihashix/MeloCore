package net.kihashix.meloCore.skill;

import org.bukkit.entity.Player;

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
     * Bán kính (block) của vùng hiệu ứng skill.
     * Skill nào không có khái niệm bán kính trả về 0.
     */
    int getRadius();

    /**
     * Đặt bán kính vùng hiệu ứng (block). Giá trị 0 nghĩa là skill không dùng bán kính.
     */
    void setRadius(int radius);

    /**
     * Gọi khi player kích hoạt skill (vd: shift).
     * Trả về true nếu kích hoạt thành công (không bị cooldown, đang bật...).
     */
    boolean activate(Player player);
}
