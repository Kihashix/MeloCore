package net.kihashix.meloCore;

import net.kihashix.meloCore.command.SkillCommand;
import net.kihashix.meloCore.command.SkillTabCompleter;
import net.kihashix.meloCore.data.PlayerSkillData;
import net.kihashix.meloCore.listener.HanBangChiTienListener;
import net.kihashix.meloCore.skill.SkillManager;
import net.kihashix.meloCore.skill.impl.HanBangChiTien;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeloCore extends JavaPlugin {

    private SkillManager skillManager;
    private PlayerSkillData playerSkillData;

    @Override
    public void onEnable() {
        this.skillManager = new SkillManager(this);
        this.playerSkillData = new PlayerSkillData(this);

        HanBangChiTien hanBangChiTien = new HanBangChiTien(this);
        skillManager.register(hanBangChiTien);
        skillManager.loadConfig();

        getServer().getPluginManager().registerEvents(
                new HanBangChiTienListener(hanBangChiTien, playerSkillData, this), this);

        PluginCommand mcCommand = getCommand("mc");
        if (mcCommand != null) {
            mcCommand.setExecutor(new SkillCommand(skillManager, playerSkillData));
            mcCommand.setTabCompleter(new SkillTabCompleter(skillManager));
        } else {
            getLogger().severe("Không tìm thấy lệnh 'mc' trong plugin.yml!");
        }

        skillManager.startActionBarTask(playerSkillData);

        getLogger().info("MeloCore đã bật thành công.");
    }

    @Override
    public void onDisable() {
        if (skillManager != null) skillManager.saveConfig();
        if (playerSkillData != null) playerSkillData.save();
    }
}
