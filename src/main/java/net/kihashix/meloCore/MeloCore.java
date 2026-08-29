package net.kihashix.meloCore;

import net.kihashix.meloCore.command.SkillCommand;
import net.kihashix.meloCore.command.SkillTabCompleter;
import net.kihashix.meloCore.command.UpdateCommand;
import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.listener.FrostShotListener;
import net.kihashix.meloCore.skill.SkillManager;
import net.kihashix.meloCore.skill.impl.FrostShot;
import net.kihashix.meloCore.update.UpdateService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeloCore extends JavaPlugin {

    private SkillManager skillManager;
    private PlayerSkillData playerSkillData;

    @Override
    public void onEnable() {
        this.skillManager = new SkillManager(this);
        this.playerSkillData = new PlayerSkillData(this);

        // Di trú dữ liệu từ id cũ "hanbangchitien" (và "frostshot" nếu có) -> "FrostShot"
        playerSkillData.renameSkill("hanbangchitien", FrostShot.ID);
        playerSkillData.renameSkill("frostshot", FrostShot.ID);

        FrostShot frostShot = new FrostShot(this);
        skillManager.register(frostShot);
        skillManager.loadConfig();

        getServer().getPluginManager().registerEvents(
                new FrostShotListener(frostShot, playerSkillData, this), this);

        PluginCommand mcCommand = getCommand("mc");
        if (mcCommand != null) {
            mcCommand.setExecutor(new SkillCommand(skillManager, playerSkillData,
                    new UpdateCommand(this, new UpdateService(this))));
            mcCommand.setTabCompleter(new SkillTabCompleter(skillManager));
        } else {
            getLogger().severe("Không tìm thấy lệnh 'mc' trong plugin.yml!");
        }

        skillManager.startActionBarTask(playerSkillData);

        getLogger().info("MeloCore đã bật thành công.");
    }

    @Override
    public void onDisable() {
        // shutdown() TRƯỚC saveConfig(): các skill hoàn tác thay đổi tạm thời
        // (khôi phục JUMP_STRENGTH đang = 0, hoàn nguyên block băng) — nếu không,
        // playerdata sẽ lưu jump_strength = 0 và block băng mắc vĩnh viễn.
        if (skillManager != null) {
            skillManager.shutdown();
            skillManager.saveConfig();
        }
        if (playerSkillData != null) playerSkillData.save();
    }
}
