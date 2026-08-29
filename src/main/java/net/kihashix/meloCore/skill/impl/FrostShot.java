package net.kihashix.meloCore.skill.impl;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.skill.AbstractSkill;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.*;

/**
 * Frost Shot (Hàn Băng Chí Tiễn).
 * <p>
 * Shift để sẵn sàng, bắn cung để đóng băng vùng trúng đích:
 * <ul>
 *   <li>Nước -> Blue Ice</li>
 *   <li>Lava -> Obsidian</li>
 *   <li>Block full (khối đầy) -> Packed Ice</li>
 *   <li>Block không full (slab, thảm, cây, ...) -> Ice</li>
 * </ul>
 * Các block bị đóng băng sẽ hoàn nguyên sau {@link #FREEZE_DURATION_MS}.
 */
public class FrostShot extends AbstractSkill {

    /** ID skill — phân biệt hoa thường, trùng tên class. */
    public static final String ID = "FrostShot";

    /** Bán kính mặc định khi chưa có cấu hình. */
    public static final int DEFAULT_RADIUS = 5;

    private static final long FREEZE_DURATION_MS = 5_000L;
    private static final int SLOWNESS_AMPLIFIER = 3; // Slowness IV
    private static final long SLOWNESS_DURATION_TICKS = 5 * 20L;

    /** Block đặc biệt không bao giờ bị đóng băng (bedrock, portal, block admin...). */
    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK,
            Material.BARRIER,
            Material.STRUCTURE_VOID,
            Material.LIGHT,
            Material.NETHER_PORTAL,
            Material.END_PORTAL,
            Material.END_GATEWAY,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK,
            Material.JIGSAW
    );

    private final MeloCore plugin;
    private final NamespacedKey arrowTagKey;

    private final Set<UUID> pendingPlayers = new HashSet<>();
    // key = "world:x:y:z" -> BlockState gốc trước khi bị đóng băng (giữ cả dữ liệu tile entity như rương, bảng...)
    private final Map<String, BlockState> frozenBlocks = new HashMap<>();

    public FrostShot(MeloCore plugin) {
        super(plugin, ID, "Hàn Băng Chí Tiễn", 10_000L); // cooldown mặc định 10s
        this.plugin = plugin;
        this.arrowTagKey = new NamespacedKey(plugin, "frostshot_tagged");
        setRadius(DEFAULT_RADIUS);
    }

    @Override
    public boolean activate(Player player) {
        if (!isEnabled()) {
            player.sendMessage(color("&c&lHàn Băng Chí Tiễn &8» &7Skill hiện đang bị tắt."));
            return false;
        }
        if (isOnCooldown(player)) {
            player.sendMessage(color("&c&lHàn Băng Chí Tiễn &8» &7Còn &f&l"
                    + formatSeconds(getRemainingCooldownMs(player)) + "s &7cooldown."));
            return false;
        }

        // Đếm cooldown NGAY lúc kích hoạt (shift), theo yêu cầu
        startCooldown(player);
        pendingPlayers.add(player.getUniqueId());

        player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fĐã sẵn sàng! &7Bắn cung để đóng băng mục tiêu."));
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

        int radius = getRadius();
        freezeArea(world, center, radius);
        applySlowness(world, center, radius);
        playImpactEffects(world, center, radius);

        broadcastDebug("Impact tại " + formatLocation(center) + " (bán kính " + radius + ").");
    }

    private void freezeArea(World world, Location center, int radius) {
        int r2 = radius * radius;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        Map<Block, Material> toFreeze = new LinkedHashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material type = block.getType();
                    if (type.isAir()) continue;
                    if (PROTECTED.contains(type)) continue;
                    Material target = freezeTarget(block, type);
                    if (target == null || target == type) continue; // không cần / không thể đóng băng
                    toFreeze.put(block, target);
                }
            }
        }

        for (Map.Entry<Block, Material> entry : toFreeze.entrySet()) {
            Block block = entry.getKey();
            frozenBlocks.putIfAbsent(key(block), block.getState());
            block.setType(entry.getValue(), false);
        }

        broadcastDebug("Đã đóng băng " + toFreeze.size() + " block.");

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Block block : toFreeze.keySet()) {
                    BlockState original = frozenBlocks.remove(key(block));
                    if (original != null) {
                        original.update(true, false); // khôi phục cả dữ liệu tile entity (rương, biển...)
                    }
                }
                broadcastDebug("Đã hoàn nguyên " + toFreeze.size() + " block.");
            }
        }.runTaskLater(plugin, FREEZE_DURATION_MS / 50L);
    }

    /**
     * Quy tắc đóng băng:
     * Nước -> Blue Ice | Lava -> Obsidian | block full -> Packed Ice | block không full -> Ice.
     *
     * @return block thay thế, hoặc null nếu không đóng băng block này.
     */
    private Material freezeTarget(Block block, Material type) {
        switch (type) {
            case WATER, KELP, SEAGRASS, TALL_SEAGRASS, BUBBLE_COLUMN -> {
                return Material.BLUE_ICE;
            }
            case LAVA -> {
                return Material.OBSIDIAN;
            }
            case BLUE_ICE, PACKED_ICE, ICE, FROSTED_ICE, OBSIDIAN -> {
                return null; // đã là băng / đã xử lý
            }
            default -> {}
        }
        if (!type.isBlock()) return null;
        return type.isSolid() && isFullCube(block) ? Material.PACKED_ICE : Material.ICE;
    }

    /** Kiểm tra block có phải khối đầy (full cube) hay không — dựa trên collision shape. */
    private static boolean isFullCube(Block block) {
        try {
            VoxelShape shape = block.getCollisionShape();
            if (shape == null) return false;
            boolean hasBox = false;
            for (BoundingBox box : shape.getBoundingBoxes()) {
                hasBox = true;
                if (box.getMinX() > 1e-6 || box.getMinY() > 1e-6 || box.getMinZ() > 1e-6) return false;
                if (box.getMaxX() < 1.0 - 1e-6 || box.getMaxY() < 1.0 - 1e-6 || box.getMaxZ() < 1.0 - 1e-6) return false;
            }
            return hasBox;
        } catch (Exception e) {
            // Không lấy được shape (vd block không có collision) -> coi là không full
            return false;
        }
    }

    private void applySlowness(World world, Location center, int radius) {
        double d = radius * 2.0; // getNearbyEntities nhận kích thước HỘP, nên nhân đôi để phủ đúng bán kính
        for (Entity entity : world.getNearbyEntities(center, d, d, d)) {
            if (!(entity instanceof LivingEntity living)) continue;
            // Friendly fire: áp dụng cho MỌI entity, không phân biệt phe
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) SLOWNESS_DURATION_TICKS, SLOWNESS_AMPLIFIER,
                    false, true, true));
        }
    }

    private void playImpactEffects(World world, Location center, int radius) {
        world.spawnParticle(Particle.SNOWFLAKE, center, 80, radius, radius / 2.0, radius, 0.05);
        world.spawnParticle(Particle.BLOCK, center, 60, radius, 1, radius, Material.BLUE_ICE.createBlockData());
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
