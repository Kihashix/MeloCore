package net.kihashix.meloCore.listener;

import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.impl.FrostShot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;

public class FrostShotListener implements Listener {

    private final FrostShot skill;
    private final PlayerSkillData skillData;
    private final Plugin plugin;

    public FrostShotListener(FrostShot skill, PlayerSkillData skillData, Plugin plugin) {
        this.skill = skill;
        this.skillData = skillData;
        this.plugin = plugin;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return; // chỉ bắt lúc BẮT ĐẦU shift
        Player player = event.getPlayer();
        if (!skillData.hasSkill(player.getUniqueId(), skill.getId())) return;
        // activate() trả false nếu KHÔNG cầm bow (im lặng — tránh xung đột skill khác),
        // skill đang tắt (có thông báo), hoặc còn cooldown (im lặng — action bar đang đếm ngược)
        boolean activated = skill.activate(player);
        if (!activated) {
            // log mức FINE: không spam console, chỉ hiện khi bật log mức thấp
            plugin.getLogger().fine(() -> player.getName() + " kích hoạt " + skill.getId()
                    + " thất bại (skill đang tắt, còn cooldown, hoặc không cầm bow)");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // Player chết -> hủy trạng thái sẵn sàng (bow rơi) + khôi phục jump
        // strength nếu đang bị đóng băng (đồng bộ với Slowness bị clear khi chết)
        skill.clearPlayerState(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Player rời game -> hủy trạng thái theo dõi. BẮT BUỘC khôi phục jump
        // strength TRƯỚC khi playerdata được ghi, nếu không jump_strength = 0
        // sẽ bị lưu vĩnh viễn (player mất khả năng nhảy).
        skill.clearPlayerState(event.getPlayer());
    }

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        skill.onBowShoot(event, player);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        skill.onProjectileHit(event);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // Block đang freeze KHÔNG THỂ phá — hủy im lặng (không chat, tránh spam/xung đột).
        if (skill.isFrozen(event.getBlock())) {
            event.setCancelled(true);
        }
    }
}
