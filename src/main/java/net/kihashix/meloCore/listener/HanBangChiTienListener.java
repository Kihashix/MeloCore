package net.kihashix.meloCore.listener;

import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.impl.HanBangChiTien;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public class HanBangChiTienListener implements Listener {

    private final HanBangChiTien skill;
    private final PlayerSkillData skillData;

    public HanBangChiTienListener(HanBangChiTien skill, PlayerSkillData skillData) {
        this.skill = skill;
        this.skillData = skillData;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return; // chỉ bắt lúc BẮT ĐẦU shift
        Player player = event.getPlayer();
        if (!skillData.hasSkill(player.getUniqueId(), skill.getId())) return;
        skill.activate(player);
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
        if (skill.isFrozen(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.AQUA + "Block đang bị đóng băng, không thể phá!");
        }
    }
}
