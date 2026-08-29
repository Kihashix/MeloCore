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
     * Gọi khi player kích hoạt skill (vd: shift).
     * Trả về true nếu kích hoạt thành công (không bị cooldown, đang bật...).
     */
    boolean activate(Player player);
}
