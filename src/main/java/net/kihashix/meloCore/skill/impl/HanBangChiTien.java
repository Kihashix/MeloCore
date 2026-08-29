package net.kihashix.meloCore.skill.impl;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.skill.AbstractSkill;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class HanBangChiTien extends AbstractSkill {

    public static final String ID = "hanbangchitien";

    private static final int FREEZE_RADIUS = 5;
    private static final long FREEZE_DURATION_MS = 5_000L;
    private static final int SLOWNESS_AMPLIFIER = 3; // Slowness IV
    private static final long SLOWNESS_DURATION_TICKS = 5 * 20L;

    private final MeloCore plugin;
    private final NamespacedKey arrowTagKey;

    private final Set<UUID> pendingPlayers = new HashSet<>();
    // key = "world:x:y:z" -> BlockData gốc trước khi bị đóng băng
    private final Map<String, BlockData> frozenBlocks = new HashMap<>();

    public HanBangChiTien(MeloCore plugin) {
        super(plugin, ID, "Hàn Băng Chi Tiễn", 10_000L); // cooldown mặc định 10s
        this.plugin = plugin;
        this.arrowTagKey = new NamespacedKey(plugin, "hbct_tagged");
    }

    @Override
    public boolean activate(Player player) {
        if (!isEnabled()) {
            player.sendMessage(Component.text(getDisplayName() + " hiện đang bị tắt.", NamedTextColor.RED));
            return false;
        }
        if (isOnCooldown(player)) {
            player.sendMessage(Component.text("Còn " + formatSeconds(getRemainingCooldownMs(player)) + "s cooldown.", NamedTextColor.RED));
            return false;
        }

        // Đếm cooldown NGAY lúc kích hoạt (shift), theo yêu cầu
        startCooldown(player);
        pendingPlayers.add(player.getUniqueId());

        player.sendMessage(Component.text()
                .append(Component.text(getDisplayName(), NamedTextColor.AQUA))
                .append(Component.text(" sẵn sàng — bắn cung để kích hoạt!", NamedTextColor.GRAY))
                .build());
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_STEP, 0.8f, 1.6f);
        broadcastDebug(player.getName() + " đã kích hoạt (chờ bắn cung). Cooldown bắt đầu chạy.");
        return true;
    }

    public void onBowShoot(EntityShootBowEvent event, Player player) {
        if (!pendingPlayers.remove(player.getUniqueId())) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        arrow.getPersistentDataContainer().set(arrowTagKey, PersistentDataType.BYTE, (byte) 1);

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1.4f);
        broadcastDebug(player.getName() + " đã bắn mũi tên mang " + getDisplayName() + ".");
    }

    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        Byte tagged = arrow.getPersistentDataContainer().get(arrowTagKey, PersistentDataType.BYTE);
        if (tagged == null || tagged != 1) return;

        Location center = arrow.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        freezeArea(world, center);
        applySlowness(world, center);
        playImpactEffects(world, center);

        broadcastDebug("Impact tại " + formatLocation(center) + " (bán kính " + FREEZE_RADIUS + ").");
    }

    private void freezeArea(World world, Location center) {
        int radius = FREEZE_RADIUS;
        int r2 = radius * radius;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        List<Block> toFreeze = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material type = block.getType();
                    if (type == Material.AIR) continue;
                    if (type == Material.WATER || type == Material.LAVA) continue; // tạm thời chưa cập nhật
                    if (type == Material.PACKED_ICE) continue; // đã đóng băng rồi
                    toFreeze.add(block);
                }
            }
        }

        for (Block block : toFreeze) {
            String key = key(block);
            frozenBlocks.putIfAbsent(key, block.getBlockData().clone());
            block.setType(Material.PACKED_ICE, false);
        }

        broadcastDebug("Đã đóng băng " + toFreeze.size() + " block.");

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Block block : toFreeze) {
                    String key = key(block);
                    BlockData original = frozenBlocks.remove(key);
                    if (original != null) {
                        block.setBlockData(original, false);
                    }
                }
                broadcastDebug("Đã hoàn nguyên " + toFreeze.size() + " block.");
            }
        }.runTaskLater(plugin, FREEZE_DURATION_MS / 50L);
    }

    private void applySlowness(World world, Location center) {
        double r = FREEZE_RADIUS;
        for (Entity entity : world.getNearbyEntities(center, r, r, r)) {
            if (!(entity instanceof LivingEntity living)) continue;
            // Friendly fire: áp dụng cho MỌI entity, không phân biệt phe
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) SLOWNESS_DURATION_TICKS, SLOWNESS_AMPLIFIER,
                    false, true, true));
        }
    }

    private void playImpactEffects(World world, Location center) {
        // Chỉ là gợi ý, bạn có thể chỉnh lại particle/sound tùy ý
        world.spawnParticle(Particle.SNOWFLAKE, center, 80, FREEZE_RADIUS, FREEZE_RADIUS / 2.0, FREEZE_RADIUS, 0.05);
        world.spawnParticle(Particle.BLOCK, center, 60, FREEZE_RADIUS, 1, FREEZE_RADIUS, Material.PACKED_ICE.createBlockData());
        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.6f);
        world.playSound(center, Sound.BLOCK_SNOW_BREAK, 1.5f, 0.5f);
    }

    /** Dùng cho BlockBreakEvent — chặn phá block đang bị đóng băng. */
    public boolean isFrozen(Block block) {
        return frozenBlocks.containsKey(key(block));
    }

    private String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String formatSeconds(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }

    private String formatLocation(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
