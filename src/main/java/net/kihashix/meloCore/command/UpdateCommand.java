package net.kihashix.meloCore.command;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.update.UpdateService;
import net.kihashix.meloCore.update.UpdateService.UpdateException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Lệnh {@code /mc update download <url>} — tải bản mới từ URL trực tiếp.
 * <p>
 * Bị điều hướng từ {@link SkillCommand} khi người dùng gõ {@code /mc update ...}.
 * Mọi lệnh gọi mạng đều chạy async (Bukkit scheduler) — không bao giờ block main thread;
 * thông báo được đưa về main thread trước khi gửi.
 */
public final class UpdateCommand {

    private static final String PERMISSION = "melocore.admin.update";
    private static final String USAGE = "/mc update download <url>";

    private final MeloCore plugin;
    private final UpdateService updateService;

    public UpdateCommand(MeloCore plugin, UpdateService updateService) {
        this.plugin = plugin;
        this.updateService = updateService;
    }

    /**
     * args[0] đã là "update" (SkillCommand điều hướng sang đây).
     * Luôn trả về {@code true} — đúng hợp đồng {@code CommandExecutor}: lệnh đã được xử lý,
     * không để Bukkit in lại usage mặc định.
     */
    @SuppressWarnings("SameReturnValue")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, "Bạn không có quyền.", NamedTextColor.RED);
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("download")) {
            send(sender, "Dùng: " + USAGE, NamedTextColor.RED);
            return true;
        }
        if (args.length < 3 || args[2].isBlank()) {
            send(sender, "Thiếu URL. Dùng: " + USAGE, NamedTextColor.RED);
            return true;
        }
        download(sender, args[2].trim());
        return true;
    }

    // ---------------------------------------------------------------- download

    private void download(CommandSender sender, String url) {
        if (!updateService.tryBeginDownload()) {
            send(sender, "Đang có một lần tải khác chạy, vui lòng chờ hoàn tất.", NamedTextColor.YELLOW);
            return;
        }
        send(sender, "Đang tải bản update...", NamedTextColor.AQUA);
        runAsync(() -> {
            try {
                Path saved = updateService.downloadFromUrl(url);
                runOnMain(() -> sendDownloaded(sender, saved));
            } catch (UpdateException e) {
                runOnMain(() -> send(sender, "Tải thất bại: " + e.getMessage(), NamedTextColor.RED));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Lỗi không mong đợi khi tải update", e);
                runOnMain(() -> send(sender, "Tải thất bại: lỗi không xác định ("
                        + e.getClass().getSimpleName() + ").", NamedTextColor.RED));
            } finally {
                updateService.endDownload();
            }
        });
    }

    private void sendDownloaded(CommandSender sender, Path saved) {
        sender.sendMessage(Component.text("Đã tải ", NamedTextColor.GREEN)
                .append(Component.text(saved.getFileName().toString(), NamedTextColor.WHITE))
                .append(Component.text(" vào:", NamedTextColor.GREEN)));
        sender.sendMessage(Component.text(saved.toAbsolutePath().toString(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Restart server để cài bản mới.", NamedTextColor.YELLOW));
    }

    // ---------------------------------------------------------------- tiện ích

    /** Chạy trên thread async; mọi thông báo phải gửi qua runOnMain. */
    private void runAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void runOnMain(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void send(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message, color));
    }
}
