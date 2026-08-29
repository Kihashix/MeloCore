package net.kihashix.meloCore.listener;

import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.skill.impl.FrostShot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
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
        // activate() trả false nếu skill đang tắt hoặc còn cooldown
        // (lúc đó skill đã tự gửi thông báo lỗi cho player)
        boolean activated = skill.activate(player);
        if (!activated) {
            // log mức FINE: không spam console, chỉ hiện khi bật log mức thấp
            plugin.getLogger().fine(() -> player.getName() + " kích hoạt " + skill.getId()
                    + " thất bại (skill đang tắt hoặc còn cooldown)");
        }
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
            event.getPlayer().sendMessage(Component.text()
                    .append(Component.text("Hàn Băng Chí Tiễn", NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Block đang bị đóng băng, không thể phá!", NamedTextColor.GRAY))
                    .build());
        }
    }
}
