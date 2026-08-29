package net.kihashix.meloCore.skill;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractSkill implements Skill {

    /** Parse chuỗi màu dạng '&' (vd: "&bTên skill &7- &f3.2s") sang Component. */
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final String id;
    private final String displayName;
    private long cooldownMs;
    private boolean enabled = true;
    private boolean debug = false;

    // Lưu mốc thời gian kích hoạt gần nhất của từng player
    private final Map<UUID, Long> lastActivation = new HashMap<>();

    protected AbstractSkill(Plugin plugin, String id, String displayName, long defaultCooldownMs) {
        this.plugin = plugin;
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
        player.sendActionBar(LEGACY_AMPERSAND.deserialize(message));
    }

    /** Gửi cho toàn bộ admin có quyền debug + log console. Chỉ hoạt động khi debug đang bật. */
    protected void broadcastDebug(String message) {
        if (!debug) return;
        Component formatted = Component.text()
                .append(Component.text("[Debug:" + id + "] ", NamedTextColor.GRAY))
                .append(Component.text(message))
                .build();
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("melocore.admin.debug")) {
                admin.sendMessage(formatted);
            }
        }
        // message luôn là chuỗi thuần (không mã màu) nên log trực tiếp
        plugin.getLogger().info("[MeloCore][" + id + "] " + message);
    }
}
