package net.kihashix.meloCore.skill;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractSkill implements Skill {

    private final String id;
    private final String displayName;
    private long cooldownMs;
    private boolean enabled = true;
    private boolean debug = false;

    // Lưu mốc thời gian kích hoạt gần nhất của từng player
    private final Map<UUID, Long> lastActivation = new HashMap<>();

    protected AbstractSkill(String id, String displayName, long defaultCooldownMs) {
        this.id = id;
        this.displayName = displayName;
        this.cooldownMs = defaultCooldownMs;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public long getCooldownMs() {
        return cooldownMs;
    }

    @Override
    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isDebug() {
        return debug;
    }

    @Override
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /** Số ms còn lại của cooldown. 0 nếu đã sẵn sàng dùng. */
    public long getRemainingCooldownMs(Player player) {
        Long last = lastActivation.get(player.getUniqueId());
        if (last == null) return 0L;
        long remaining = cooldownMs - (System.currentTimeMillis() - last);
        return Math.max(remaining, 0L);
    }

    protected boolean isOnCooldown(Player player) {
        return getRemainingCooldownMs(player) > 0;
    }

    /** Bắt đầu tính cooldown NGAY lúc kích hoạt (shift), không đợi hiệu ứng kết thúc. */
    protected void startCooldown(Player player) {
        lastActivation.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Hiển thị lên action bar — dùng cho cả cooldown display (luôn bật, không phụ thuộc debug). */
    public void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message)));
    }

    /** Gửi cho toàn bộ admin có quyền debug + log console. Chỉ hoạt động khi debug đang bật. */
    protected void broadcastDebug(String message) {
        if (!debug) return;
        String formatted = ChatColor.GRAY + "[Debug:" + id + "] " + ChatColor.RESET + message;
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("melocore.admin.debug")) {
                admin.sendMessage(formatted);
            }
        }
        Bukkit.getLogger().info("[MeloCore][" + id + "] " + ChatColor.stripColor(message));
    }
}
