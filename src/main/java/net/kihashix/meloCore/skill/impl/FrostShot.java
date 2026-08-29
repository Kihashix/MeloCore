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
 * Cooldown bắt đầu đếm NGAY lúc player BẮN (thả dây cung), không phải lúc tên
 * trúng đích. Mũi tên bị gỡ tag sau LẦN FREEZE ĐẦU TIÊN nên khi tên rơi và
 * chạm đất lần 2, 3... (do block freeze tự hồi) thì không gây freeze lại.
 * Khi cooldown hết: player nhận thông báo "chiêu đã hồi"; nếu lúc đó player
 * đang giữ shift (sneak) và cầm bow thì skill TỰ KÍCH HOẠT, không cần shift lại.
 * Trong {@link #FREEZE_DURATION_MS} (Slowness IV), người chơi KHÔNG THỂ NHẢY:
 * cú nhảy mới bị triệt tiêu ngay, còn đang giữa không trung khi bị freeze thì
 * rơi tự nhiên theo trọng lực.
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

    // key = "world:x:y:z" -> BlockState gốc trước khi bị đóng băng (giữ cả dữ liệu tile entity như rương, bảng...)
    private final Map<String, BlockState> frozenBlocks = new HashMap<>();

    /** uuid -> ms hết hạn "không được nhảy" (chỉ player, song song với 5s Slowness IV). */
    private final Map<UUID, Long> frozenNoJump = new HashMap<>();
    /** uuid -> lần kiểm tra trước đó có đang đứng đất không (để nhận diện cú nhảy MỚI). */
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();

    /** Bộ đếm tick của task housekeeping (chỉ task chạy trên main thread chạm vào). */
    private int housekeepingTicks;

    public FrostShot(MeloCore plugin) {
        super(plugin, ID, "Hàn Băng Chí Tiễn", 10_000L); // cooldown mặc định 10s
        this.plugin = plugin;
        this.arrowTagKey = new NamespacedKey(plugin, "frostshot_tagged");
        setRadius(DEFAULT_RADIUS);
        startHousekeepingTask();
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
        player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fĐã sẵn sàng!"));
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

    /** Hủy các trạng thái theo dõi (pending + cooldown) — gọi khi player chết hoặc rời game. */
    public void clearPending(Player player) {
        UUID uuid = player.getUniqueId();
        pending.remove(uuid);
        cooldownActive.remove(uuid);
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

    private void applySlowness(World world, Location center, int radius) {
        double d = radius * 2.0; // getNearbyEntities nhận kích thước HỘP, nên nhân đôi để phủ đúng bán kính
        for (Entity entity : world.getNearbyEntities(center, d, d, d)) {
            if (!(entity instanceof LivingEntity living)) continue;
            // Friendly fire: áp dụng cho MỌI entity, không phân biệt phe
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) SLOWNESS_DURATION_TICKS, SLOWNESS_AMPLIFIER,
                    false, true, true));
            // Chỉ người chơi bị chặn nhảy trong 5s (mobs vẫn nhảy bình thường)
            if (entity instanceof Player player) {
                frozenNoJump.put(player.getUniqueId(), System.currentTimeMillis() + FREEZE_DURATION_MS);
                // Gọi qua Entity — Player#isOnGround() đã deprecated (giá trị do client báo về)
                wasOnGround.put(player.getUniqueId(), entity.isOnGround());
            }
        }
    }

    private void playImpactEffects(World world, Location center, int radius) {
        world.spawnParticle(Particle.SNOWFLAKE, center, 80, radius, radius / 2.0, radius, 0.05);
        world.spawnParticle(Particle.BLOCK, center, 60, radius, 1, radius, Material.BLUE_ICE.createBlockData());
        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.6f);
        world.playSound(center, Sound.BLOCK_SNOW_BREAK, 1.5f, 0.5f);
    }

    /**
     * Task housekeeping chạy MỖI TICK (các map rất nhỏ, chi phí không đáng kể):
     * <ol>
     *   <li>Pending hết hạn 15s (chưa bắn) -> loại + nhắc nhẹ trên action bar.</li>
     *   <li>Nhắc action bar "sẵn sàng" cho player đang pending (mỗi 1s).</li>
     *   <li>Chặn nhảy MỚI của player đang freeze: nhận diện chuyển đất -> không trung
     *       kèm velocity.y &gt; 0 -> triệt tiêu thành 0. Player đã ở giữa không trung
     *       khi bị freeze (vừa nhảy, đi khỏi mép vực, bị đẩy...) thì rơi tự nhiên.</li>
     *   <li>Cooldown vừa HẾT -> thông báo "chiêu đã hồi" + sound. Nếu lúc đó player
     *       đang sneak (giữ shift liên tục) và cầm bow -> TỰ KÍCH HOẠT (vào trạng
     *       thái pending) — fix trường hợp player giữ shift xuyên suốt cooldown
     *       thì không bao giờ có event bật sneak để kích hoạt.</li>
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
                            sendActionBar(player, "&b&lHàn Băng Chí Tiễn &8» &7Đã hết sẵn sàng!");
                            broadcastDebug(player.getName() + " hết hạn pending (15s không bắn).");
                        }
                    }
                }

                // 2) Action bar "sẵn sàng" mỗi 20 ticks (1s)
                if (++housekeepingTicks % 20 == 0 && !pending.isEmpty()) {
                    for (UUID uuid : pending.keySet()) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) {
                            sendActionBar(player, "&b&lHàn Băng Chí Tiễn &8» &fSẵn sàng!");
                        }
                    }
                }

                // 3) Chặn nhảy của player đang freeze
                if (!frozenNoJump.isEmpty()) {
                    Iterator<Map.Entry<UUID, Long>> it = frozenNoJump.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<UUID, Long> entry = it.next();
                        UUID uuid = entry.getKey();
                        if (now >= entry.getValue()) {
                            it.remove();
                            wasOnGround.remove(uuid);
                            continue;
                        }
                        // getEntity trả null khi offline; dùng Entity để gọi isOnGround
                        // không deprecated (Player#isOnGround đã deprecated)
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity == null) {
                            it.remove();
                            wasOnGround.remove(uuid);
                            continue;
                        }
                        boolean onGround = entity.isOnGround();
                        boolean wasGround = wasOnGround.getOrDefault(uuid, onGround);
                        wasOnGround.put(uuid, onGround);
                        if (!onGround && wasGround && entity.getVelocity().getY() > 0) {
                            Vector v = entity.getVelocity();
                            v.setY(0);
                            entity.setVelocity(v);
                        }
                    }
                }

                // 4) Cooldown vừa hết -> thông báo "chiêu đã hồi" (+ auto kích hoạt)
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

                        player.sendMessage(color("&b&lHàn Băng Chí Tiễn &8» &fChiêu đã hồi!"));
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
