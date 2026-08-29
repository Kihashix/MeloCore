package net.kihashix.meloCore.skill.impl;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.skill.AbstractSkill;
import net.kihashix.meloCore.skill.SkillConfigOption;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;

import java.util.*;

/**
 * Frost Shot (Hàn Băng Chí Tiễn).
 * <p>
 * Shift khi đang cầm bow (mainhand hoặc offhand) để sẵn sàng — trạng thái này
 * tự hết hạn sau {@link #PENDING_TIMEOUT_MS} nếu không bắn. Bắn cung để đóng
 * băng vùng trúng đích:
 * <ul>
 *   <li>Nước -> Blue Ice</li>
 *   <li>Lava -> Obsidian</li>
 *   <li>Block full (khối đầy) -> Packed Ice</li>
 *   <li>Block không full (slab, thảm, cây, ...) -> Ice</li>
 * </ul>
 * Vùng tác dụng là <b>KHỐI CẦU</b> bán kính {@code radius} quanh điểm trúng
 * đích — áp dụng cho cả block lẫn entity. Entity ở ngoài khối cầu (dù chỉ lệch
 * một góc) hoàn toàn KHÔNG bị ảnh hưởng.
 * <p>
 * Entity trong vùng: mọi LivingEntity dính Slowness cấp {@code slowness}
 * (amplifier 0-based như vanilla, mặc định 3 = Slowness IV). Riêng player còn
 * bị <b>CẤM NHẢY</b> trong suốt {@code freeze-time} bằng cách set attribute
 * {@code JUMP_STRENGTH} về 0, rồi khôi phục giá trị gốc khi hết giờ — thuần
 * Attribute, không can thiệp velocity. Attribute luôn được khôi phục ở mọi
 * lối thoát: hết giờ / chết / rời game / tắt plugin (nếu không sẽ mất nhảy
 * vĩnh viễn do playerdata lưu jump_strength = 0).
 * <p>
 * Cooldown bắt đầu đếm NGAY lúc player BẮN (thả dây cung), không phải lúc tên
 * trúng đích. Mũi tên bị gỡ tag sau LẦN FREEZE ĐẦU TIÊN nên khi tên rơi và
 * chạm đất lần 2, 3... (do block freeze tự hồi) thì không gây freeze lại.
 * Khi cooldown hết: player nhận thông báo "chiêu đã hồi"; nếu lúc đó player
 * đang giữ shift (sneak) và cầm bow thì skill TỰ KÍCH HOẠT, không cần shift lại.
 * <p>
 * Tham số chỉnh runtime qua {@code /mc skills config FrostShot <option> ...}:
 * {@code radius} (bán kính block), {@code freeze-time} (ms — block hoàn nguyên,
 * Slowness hết và hết cấm nhảy đều ĐỒNG BỘ theo giá trị này) và
 * {@code slowness} (amplifier 0-based). Đóng băng lại cùng vùng đang đông sẽ
 * GIÃN HẠN thời gian hoàn nguyên, không hồi giữa chừng.
 */
public class FrostShot extends AbstractSkill {

    /** ID skill — phân biệt hoa thường, trùng tên class. */
    public static final String ID = "FrostShot";

    /** Bán kính mặc định khi chưa có cấu hình. */
    public static final int DEFAULT_RADIUS = 5;

    /** Thời gian đóng băng mặc định (block + Slowness + cấm nhảy). */
    public static final long DEFAULT_FREEZE_TIME_MS = 5_000L;

    /** Amplifier Slowness mặc định — 0-based như vanilla (3 = Slowness IV). */
    public static final int DEFAULT_SLOWNESS_AMPLIFIER = 3;

    /** Giới hạn cấu hình (chống set giá trị phá game / phá server). */
    private static final int MAX_RADIUS = 32;
    private static final long MIN_FREEZE_TIME_MS = 500L;
    private static final long MAX_FREEZE_TIME_MS = 60_000L;
    private static final int MAX_SLOWNESS_AMPLIFIER = 10;

    /** Trạng thái "sẵn sàng" sau shift chỉ còn hiệu lực trong 15s; quá hạn phải shift lại. */
    private static final long PENDING_TIMEOUT_MS = 15_000L;

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

    /** uuid -> thời điểm (ms) kích hoạt (shift + cầm bow). Bắn cung thì xóa. */
    private final Map<UUID, Long> pending = new HashMap<>();

    /** uuid của các player ĐANG trong cooldown — để phát hiện moment cooldown HẾT. */
    private final Set<UUID> cooldownActive = new HashSet<>();

    /** Block đang bị đóng băng: key "world:x:y:z" -> state gốc + thời điểm hết hạn (ms). */
    private final Map<String, FrozenBlock> frozenBlocks = new HashMap<>();

    /**
     * Player đang bị đóng băng: uuid -> thời điểm hết cấm nhảy (ms) + jump
     * strength GỐC đã lưu để khôi phục khi tan băng.
     */
    private final Map<UUID, FrozenPlayer> frozenPlayers = new HashMap<>();

    /** Các vùng đóng băng đang hoạt động — để rải tuyết lơ lửng suốt freeze-time. */
    private final List<FreezeZone> freezeZones = new ArrayList<>();

    /** Bộ đếm tick của task housekeeping (chỉ task chạy trên main thread chạm vào). */
    private int housekeepingTicks;

    // ---- Tham số hiệu ứng (chỉnh qua /mc skills config, lưu trong skills.yml) ----
    private int radius = DEFAULT_RADIUS;
    private long freezeTimeMs = DEFAULT_FREEZE_TIME_MS;
    private int slownessAmplifier = DEFAULT_SLOWNESS_AMPLIFIER;

    /** Block đang đông: giữ state gốc và thời điểm hết hạn. */
    private record FrozenBlock(BlockState state, long untilMs) {}

    /** Player đang đông: thời điểm hết cấm nhảy và jump strength gốc để khôi phục. */
    private record FrozenPlayer(long untilMs, double jumpStrengthBase) {}

    /** Vùng đóng băng để rải hiệu ứng tuyết lơ lửng. */
    private record FreezeZone(World world, Location center, int radius, long untilMs) {}

    public FrostShot(MeloCore plugin) {
        super(plugin, ID, "Hàn Băng Chí Tiễn", 10_000L); // cooldown mặc định 10s
        this.plugin = plugin;
        this.arrowTagKey = new NamespacedKey(plugin, "frostshot_tagged");
        registerConfigOptions();
        startHousekeepingTask();
    }

    /** Đăng ký các tùy chọn chỉnh runtime — xem {@link SkillConfigOption}. */
    private void registerConfigOptions() {
        registerOption(SkillConfigOption.intOption("radius", "Bán kính vùng đóng băng (block)",
                1, MAX_RADIUS, () -> radius, value -> radius = value));
        registerOption(SkillConfigOption.longOption("freeze-time", "Thời gian đóng băng (ms)",
                MIN_FREEZE_TIME_MS, MAX_FREEZE_TIME_MS, () -> freezeTimeMs, value -> freezeTimeMs = value));
        registerOption(SkillConfigOption.intOption("slowness", "Cấp Slowness (amplifier 0-based, 3 = Slowness IV)",
                0, MAX_SLOWNESS_AMPLIFIER, () -> slownessAmplifier, value -> slownessAmplifier = value));
    }

    @Override
    public boolean activate(Player player) {
        // Kiểm tra bow TRƯỚC MỌI THỨ: không cầm bow thì im lặng tuyệt đối,
        // dù skill OFF hay đang cooldown — tránh xung đột với skill khác dùng sneak.
        if (!isHoldingBow(player)) {
            return false;
        }
        if (!isEnabled()) {
            player.sendMessage(color("&c&lHàn Băng Chí Tiễn &8» &7Skill hiện đang bị tắt."));
            return false;
        }
        if (isOnCooldown(player)) {
            return false; // im lặng — action bar đã đang đếm ngược cooldown
        }

        Long activatedAt = pending.get(player.getUniqueId());
        if (activatedAt != null && System.currentTimeMillis() - activatedAt < PENDING_TIMEOUT_MS) {
            // Đã sẵn sàng và chưa hết hạn: giữ nguyên, không phát lại sound/message
            broadcastDebug(player.getName() + " shift lần nữa — vẫn đang sẵn sàng, giữ trạng thái.");
            return true;
        }

        pending.put(player.getUniqueId(), System.currentTimeMillis());

        // Cooldown KHÔNG đếm ở đây — chỉ đếm từ lúc player BẮN (xem onBowShoot)
        // player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fĐã sẵn sàng!"));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GLASS_STEP, 0.8f, 1.6f);
        broadcastDebug(player.getName() + " đã kích hoạt (chờ bắn cung, hết hạn sau "
                + (PENDING_TIMEOUT_MS / 1000L) + "s). Cooldown sẽ chạy từ lúc bắn.");
        return true;
    }

    /**
     * Player có đang cầm bow ở mainhand hoặc offhand không.
     * Dùng ItemStack từ inventory (luôn @NotNull, trả AIR nếu trống) — không qua Equipment.
     */
    private boolean isHoldingBow(Player player) {
        PlayerInventory inv = player.getInventory();
        return inv.getItemInMainHand().getType() == Material.BOW
                || inv.getItemInOffHand().getType() == Material.BOW;
    }

    /**
     * Hủy toàn bộ trạng thái theo dõi của player (pending + cooldown + trạng thái
     * đóng băng: khôi phục jump strength gốc) — gọi khi player chết hoặc rời game.
     * <p>
     * Quit PHẢI khôi phục attribute TRƯỚC khi playerdata được ghi, nếu không
     * jump_strength = 0 sẽ theo player vĩnh viễn. Chết cũng khôi phục — đồng bộ
     * với Slowness bị clear khi chết theo vanilla.
     */
    public void clearPlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        pending.remove(uuid);
        cooldownActive.remove(uuid);
        FrozenPlayer frozen = frozenPlayers.remove(uuid);
        if (frozen != null) {
            AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
            if (jump != null) {
                jump.setBaseValue(frozen.jumpStrengthBase());
            }
        }
    }

    public void onBowShoot(EntityShootBowEvent event, Player player) {
        if (event.isCancelled()) return; // bắn bị hủy -> giữ nguyên trạng thái pending
        Long activatedAt = pending.remove(player.getUniqueId());
        if (activatedAt == null) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        arrow.getPersistentDataContainer().set(arrowTagKey, PersistentDataType.BYTE, (byte) 1);

        // Cooldown đếm từ LÚC BẮN (không đợi tên trúng đích)
        startCooldown(player);
        cooldownActive.add(player.getUniqueId());
        player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fHàn Sương Bạo Phát!"));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1.4f);
        broadcastDebug(player.getName() + " đã bắn mũi tên mang " + getDisplayName()
                + " — cooldown chạy từ lúc này.");
    }

    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        Byte tagged = arrow.getPersistentDataContainer().get(arrowTagKey, PersistentDataType.BYTE);
        if (tagged == null || tagged != 1) return;

        // Tên đã "dùng xong" — gỡ tag NGAY sau lần freeze đầu để mọi lần re-hit
        // (block freeze tự hồi -> tên cắm rơi xuống chạm đất lần 2, 3...) đều KHÔNG làm gì.
        arrow.getPersistentDataContainer().remove(arrowTagKey);

        Location center = arrow.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        // MỘT mốc hết hạn chung cho block + Slowness + cấm nhảy (đồng bộ freeze-time)
        long until = System.currentTimeMillis() + freezeTimeMs;

        freezeArea(world, center, radius, until);
        applyFreezeToEntities(world, center, radius, until);
        playImpactEffects(world, center, radius);
        freezeZones.add(new FreezeZone(world, center, radius, until));

        broadcastDebug("Kích trúng tại " + formatLocation(center) + " (bán kính " + radius
                + ", đóng băng " + freezeTimeMs + "ms).");
    }

    private void freezeArea(World world, Location center, int radius, long until) {
        int r2 = radius * radius;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        Map<Block, Material> toFreeze = new LinkedHashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue; // chỉ block TRONG khối cầu
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
            // Lần đầu: lưu state gốc + hạn hết. Block ĐANG đông bị freeze lại:
            // GIỮ state gốc (state hiện tại là băng) và chỉ GIÃN HẠN hạn hết.
            frozenBlocks.compute(key(block), (k, existing) -> existing == null
                    ? new FrozenBlock(block.getState(), until)
                    : new FrozenBlock(existing.state(), until));
            block.setType(entry.getValue(), false);
        }

        broadcastDebug("Đã đóng băng " + toFreeze.size() + " block.");
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
            // getCollisionShape() luôn trả về @NotNull theo API — không cần null-check.
            // Block không có collision sẽ trả về shape rỗng (0 box) -> hasBox = false.
            VoxelShape shape = block.getCollisionShape();
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

    /**
     * Áp Slowness (mọi LivingEntity) + cấm nhảy (player) cho các entity nằm
     * TRONG KHỐI CẦU bán kính radius quanh tâm.
     * <p>
     * Fix bug cũ: trước đây dùng {@code getNearbyEntities} với hộp r*2 — đó là
     * HÌNH LẬP PHƯƠNG, góc hộp cách tâm tới r*sqrt(3) nên player đứng ngoài
     * radius vẫn dính Slowness. Giờ tìm trong hộp MỞ RỘNG (chừa chỗ cho entity
     * to gần mép) rồi thử sphere chính xác qua
     * {@link #intersectsSphere(Location, double, BoundingBox)}.
     */
    private void applyFreezeToEntities(World world, Location center, int radius, long until) {
        // Hộp tìm kiếm lớn hơn sphere để không sót entity to (bounding box thò vào
        // sphere dù tâm entity ở xa). Sau đó lọc sphere chính xác bên dưới.
        double boxSize = (radius + 3.0) * 2.0;
        for (Entity entity : world.getNearbyEntities(center, boxSize, boxSize, boxSize)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!intersectsSphere(center, radius, entity.getBoundingBox())) continue;
            // Friendly fire: áp dụng cho MỌI entity trong khối cầu, không phân biệt phe
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, slownessDurationTicks(), slownessAmplifier,
                    false, true, true));
            // Chỉ người chơi bị cấm nhảy trong freeze-time (mob vẫn nhảy bình thường)
            if (living instanceof Player player) {
                freezePlayer(player, until);
            }
        }
    }

    /**
     * Bounding box của entity có CHẠM/CẮT khối cầu (tâm + bán kính) hay không:
     * tính khoảng cách từ tâm tới điểm GẦN NHẤT trên hộp (clamp từng trục) rồi so
     * bình phương với r² — chuẩn AoE kiểu nổ, không thòi góc như phép thử hộp.
     */
    private static boolean intersectsSphere(Location center, double radius, BoundingBox box) {
        double dx = clampDistance(center.getX(), box.getMinX(), box.getMaxX());
        double dy = clampDistance(center.getY(), box.getMinY(), box.getMaxY());
        double dz = clampDistance(center.getZ(), box.getMinZ(), box.getMaxZ());
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Khoảng cách từ value tới đoạn [min, max] — 0 nếu value nằm trong đoạn. */
    private static double clampDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }

    /**
     * Đóng băng player: set attribute {@code JUMP_STRENGTH} về 0 — không thể
     * nhảy nhưng vẫn đi/đá/lượm bình thường, thuần Attribute không đụng velocity.
     * <p>
     * Player đang đông mà bị freeze LẦN NỮA: reset hạn hết theo mốc mới nhưng
     * vẫn giữ jump strength GỐC đã lưu (không ghi đè bằng 0).
     */
    private void freezePlayer(Player player, long until) {
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump == null) return; // attribute bị thiếu (datapack lạ) -> bỏ cấm nhảy, vẫn dính Slowness
        // Lần đầu: lưu jump strength GỐC. Player đang đông bị freeze LẦN NỮA:
        // GIỮ base gốc đã lưu (không ghi đè bằng 0) và chỉ reset hạn hết theo mốc mới.
        frozenPlayers.compute(player.getUniqueId(), (uuid, existing) -> existing == null
                ? new FrozenPlayer(until, jump.getBaseValue())
                : new FrozenPlayer(until, existing.jumpStrengthBase()));
        jump.setBaseValue(0.0);
    }

    /** Khôi phục jump strength gốc cho player (nếu còn online). */
    private void restoreJump(UUID uuid, double base) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return; // offline: attribute đã khôi phục khi quit
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            jump.setBaseValue(base);
        }
    }

    /** Số tick Slowness — khớp freeze-time (+1 tick lệch an toàn để không hết sớm hơn cấm nhảy). */
    private int slownessDurationTicks() {
        return (int) (freezeTimeMs / 50L) + 1;
    }

    /** Hiệu ứng nổ băng lúc trúng đích — nhiều lớp, scale theo bán kính. */
    private void playImpactEffects(World world, Location center, int radius) {
        // 1) Bụi trắng dày + tuyết bay tơi + tro tuyết + mảnh băng Blue Ice văng
        world.spawnParticle(Particle.CLOUD, center, 40 + radius * 5,
                radius * 0.4, radius * 0.3, radius * 0.4, 0.08);
        world.spawnParticle(Particle.SNOWFLAKE, center, 120 + radius * 12,
                radius * 0.7, radius * 0.5, radius * 0.7, 0.06);
        world.spawnParticle(Particle.WHITE_ASH, center, 50 + radius * 6,
                radius * 0.6, radius * 0.4, radius * 0.6, 0.02);
        world.spawnParticle(Particle.BLOCK, center, 80 + radius * 6,
                radius * 0.8, 0.8, radius * 0.8, Material.BLUE_ICE.createBlockData());

        // 2) Sóng xung kích hình cầu lan từ tâm ra mép bán kính
        spawnFrostWave(world, center, radius);

        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.6f);
        world.playSound(center, Sound.BLOCK_SNOW_BREAK, 1.5f, 0.5f);
        world.playSound(center, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.2f, 0.9f);
    }

    /**
     * Sóng xung kích hình CẦU lan từ tâm ra hết bán kính trong ~0.5s — vừa đẹp
     * vừa cho người chơi thấy CHÍNH XÁC khối cầu vừa bị đóng băng. Số điểm mỗi
     * tick trần 60 để nhẹ server (chỉ phát một lần mỗi phát trúng đích).
     */
    private void spawnFrostWave(World world, Location center, int radius) {
        new BukkitRunnable() {
            private double current = 0.75;

            @Override
            public void run() {
                if (current > radius) {
                    cancel();
                    return;
                }
                // Mật độ điểm tỉ lệ bán kính sóng, chặn trong [12, 60] điểm/tick để nhẹ server
                int points = Math.clamp((int) (12.0 * current), 12, 60);
                for (int i = 0; i < points; i++) {
                    Location point = center.clone().add(randomUnitVector().multiply(current));
                    world.spawnParticle(i % 3 == 0 ? Particle.SNOWFLAKE : Particle.WHITE_ASH,
                            point, 1, 0, 0, 0, 0.01);
                }
                current += 0.75;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Vector đơn vị ngẫu nhiên, phân bố đều trên mặt cầu (phương pháp Marsaglia). */
    private static Vector randomUnitVector() {
        while (true) {
            double x = Math.random() * 2.0 - 1.0;
            double y = Math.random() * 2.0 - 1.0;
            double z = Math.random() * 2.0 - 1.0;
            double lenSq = x * x + y * y + z * z;
            if (lenSq >= 1.0e-4 && lenSq <= 1.0) {
                double len = Math.sqrt(lenSq);
                return new Vector(x / len, y / len, z / len);
            }
        }
    }

    /**
     * Task housekeeping chạy MỖI TICK (các map rất nhỏ, chi phí không đáng kể):
     * <ol>
     *   <li>Pending hết hạn 15s (chưa bắn) -> loại + nhắc nhẹ trên action bar.</li>
     *   <li>Nhắc action bar "sẵn sàng" cho player đang pending (mỗi 1s).</li>
     *   <li>Tan băng hết hạn: khôi phục jump strength cho player, hoàn nguyên
     *       block, dừng tuyết lơ lửng (block hoàn nguyên theo mốc của lần
     *       freeze GẦN NHẤT — freeze lại sẽ gia hạn).</li>
     *   <li>Hiệu ứng ambient mỗi 0.25s: tuyết rơi lơ lửng trong vùng đóng băng
     *       + vòng băng xoay quanh player đang bị đóng băng.</li>
     *   <li>Cooldown vừa HẾT -> thông báo "chiêu đã hồi" + sound. Nếu lúc đó
     *       player đang sneak (giữ shift liên tục) và cầm bow -> TỰ KÍCH HOẠT.</li>
     * </ol>
     */
    private void startHousekeepingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                // 1) Pending timeout
                if (!pending.isEmpty()) {
                    Iterator<Map.Entry<UUID, Long>> it = pending.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<UUID, Long> entry = it.next();
                        if (now - entry.getValue() < PENDING_TIMEOUT_MS) continue;
                        it.remove();
                        Player player = Bukkit.getPlayer(entry.getKey());
                        if (player != null) {
                            sendActionBar(player, "&b&lHàn Băng Chí Tiễn &8» &fHàn Ý Giải Trừ!");
                            broadcastDebug(player.getName() + " hết hạn chờ đợi (15s không bắn).");
                        }
                    }
                }

                // 2) Action bar "sẵn sàng" mỗi 20 ticks (1s)
                if (++housekeepingTicks % 20 == 0 && !pending.isEmpty()) {
                    for (UUID uuid : pending.keySet()) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) {
                            sendActionBar(player, "&b&lHàn Băng Chí Tiễn &8» &fBăng Phong Quy Vị!");
                        }
                    }
                }

                // 3) Tan băng hết hạn (player + block + vùng hiệu ứng)
                thawExpired(now);

                // 4) Hiệu ứng ambient mỗi 5 ticks (0.25s)
                if (housekeepingTicks % 5 == 0) {
                    playAmbientEffects(now);
                }

                // 5) Cooldown vừa hết -> thông báo "chiêu đã hồi" (+ auto kích hoạt)
                if (!cooldownActive.isEmpty()) {
                    Iterator<UUID> it = cooldownActive.iterator();
                    while (it.hasNext()) {
                        UUID uuid = it.next();
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null) {
                            it.remove(); // offline -> bỏ theo dõi
                            continue;
                        }
                        if (isOnCooldown(player)) continue; // chưa hết
                        it.remove();

                        player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fBăng Tâm Phục Nguyên!"));
                        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.6f);
                        broadcastDebug(player.getName() + " cooldown kết thúc.");

                        // Auto kích hoạt: player đang giữ shift (sneak) và cầm bow.
                        // Gọi qua Entity để dùng isSneaking() không deprecated.
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity != null && entity.isSneaking() && isHoldingBow(player)) {
                            broadcastDebug(player.getName() + " đang sneak + cầm bow — tự kích hoạt.");
                            activate(player);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Hoàn tác các trạng thái đóng băng đã hết hạn (gọi mỗi tick):
     * <ul>
     *   <li>Player: khôi phục jump strength gốc + hiệu ứng "tan băng" nhẹ.</li>
     *   <li>Block: hoàn nguyên BlockState gốc (kèm cả dữ liệu tile entity).</li>
     *   <li>Vùng: dừng rải tuyết lơ lửng.</li>
     * </ul>
     */
    private void thawExpired(long now) {
        if (!frozenPlayers.isEmpty()) {
            Iterator<Map.Entry<UUID, FrozenPlayer>> it = frozenPlayers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, FrozenPlayer> entry = it.next();
                if (now < entry.getValue().untilMs()) continue;
                it.remove();
                UUID uuid = entry.getKey();
                restoreJump(uuid, entry.getValue().jumpStrengthBase());
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    // Báo hiệu hết cấm nhảy: hơi nước trắng tan ra
                    player.getWorld().spawnParticle(Particle.CLOUD,
                            player.getLocation().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0.02);
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SNOW_BREAK, 0.7f, 1.4f);
                }
            }
        }
        if (!frozenBlocks.isEmpty()) {
            Iterator<Map.Entry<String, FrozenBlock>> it = frozenBlocks.entrySet().iterator();
            int reverted = 0;
            while (it.hasNext()) {
                Map.Entry<String, FrozenBlock> entry = it.next();
                if (now < entry.getValue().untilMs()) continue;
                it.remove();
                // khôi phục cả dữ liệu tile entity (rương, bảng...)
                entry.getValue().state().update(true, false);
                reverted++;
            }
            if (reverted > 0) {
                broadcastDebug("Đã hoàn nguyên " + reverted + " block.");
            }
        }
        freezeZones.removeIf(zone -> now >= zone.untilMs());
    }

    /**
     * Hiệu ứng ambient mỗi 0.25s suốt freeze-time:
     * <ul>
     *   <li>Tuyết + tro trắng lơ lửng rơi khắp vùng đang đóng băng.</li>
     *   <li>Vòng tuyết xoay quanh thân player đang bị đóng băng (biết mình bị
     *       đóng băng + không nhảy được là do skill, không phải lag).</li>
     * </ul>
     */
    private void playAmbientEffects(long now) {
        // 1) Tuyết rơi lơ lửng trong các vùng đang đóng băng
        for (FreezeZone zone : freezeZones) {
            if (now >= zone.untilMs()) continue;
            double r = zone.radius();
            Location c = zone.center();
            int flakes = Math.min(40, 10 + (int) (r * 4));
            zone.world().spawnParticle(Particle.SNOWFLAKE, c, flakes, r * 0.75, r * 0.6, r * 0.75, 0.02);
            zone.world().spawnParticle(Particle.WHITE_ASH, c, flakes / 2, r * 0.75, r * 0.6, r * 0.75, 0.01);
        }

        // 2) Vòng tuyết xoay quanh player bị đóng băng
        double baseAngle = housekeepingTicks * 0.15; // ~1 vòng / 2s
        for (UUID uuid : frozenPlayers.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Location feet = player.getLocation();
            for (int i = 0; i < 8; i++) {
                double theta = baseAngle + (Math.PI * 2.0 * i / 8.0);
                // vòng tròn quanh thân, cao độ dao động nhẹ cho cảm giác băng "thở"
                double y = 0.3 + 0.6 * (0.5 + 0.5 * Math.sin(housekeepingTicks * 0.1 + i));
                player.getWorld().spawnParticle(Particle.SNOWFLAKE,
                        feet.getX() + 0.85 * Math.cos(theta),
                        feet.getY() + y,
                        feet.getZ() + 0.85 * Math.sin(theta),
                        1, 0, 0, 0, 0);
            }
            player.getWorld().spawnParticle(Particle.CLOUD,
                    feet.clone().add(0, 0.15, 0), 2, 0.3, 0.1, 0.3, 0.01);
        }
    }

    /**
     * Hoàn tác TOÀN BỘ trạng thái tạm thời khi plugin disable — bắt buộc với
     * cách làm attribute: nếu không khôi phục, jump_strength = 0 bị LƯU vào
     * playerdata (player mất khả năng nhảy vĩnh viễn) và block băng mắc vĩnh viễn.
     */
    @Override
    public void shutdown() {
        for (Map.Entry<UUID, FrozenPlayer> entry : frozenPlayers.entrySet()) {
            restoreJump(entry.getKey(), entry.getValue().jumpStrengthBase());
        }
        frozenPlayers.clear();
        for (FrozenBlock frozen : frozenBlocks.values()) {
            frozen.state().update(true, false);
        }
        frozenBlocks.clear();
        freezeZones.clear();
        pending.clear();
        cooldownActive.clear();
    }

    /** Dùng cho BlockBreakEvent — chặn phá block đang bị đóng băng. */
    public boolean isFrozen(Block block) {
        return frozenBlocks.containsKey(key(block));
    }

    private String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String formatLocation(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
