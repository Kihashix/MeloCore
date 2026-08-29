package net.kihashix.meloCore.command;

import net.kihashix.meloCore.MeloCore;
import net.kihashix.meloCore.update.UpdateService;
import net.kihashix.meloCore.update.UpdateService.NoReleaseException;
import net.kihashix.meloCore.update.UpdateService.ReleaseInfo;
import net.kihashix.meloCore.update.UpdateService.UpdateException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Lệnh {@code /mc update check|download} — kiểm tra và tải bản mới từ GitHub Releases.
 * <p>
 * Bị điều hướng từ {@link SkillCommand} khi người dùng gõ {@code /mc update ...}.
 * Mọi lệnh gọi mạng đều chạy async (Bukkit scheduler) — không bao giờ block main thread;
 * thông báo được đưa về main thread trước khi gửi.
 */
public final class UpdateCommand {

    private static final String PERMISSION = "melocore.admin.update";
    private static final String USAGE = "/mc update <check|download>";

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
        if (args.length < 2) {
            send(sender, "Dùng: " + USAGE, NamedTextColor.RED);
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "check" -> check(sender);
            case "download" -> download(sender);
            default -> send(sender, "Lệnh con không hợp lệ: " + args[1] + ". Dùng: " + USAGE, NamedTextColor.RED);
        }
        return true;
    }

    // ---------------------------------------------------------------- check

    private void check(CommandSender sender) {
        send(sender, "Đang kiểm tra bản mới trên GitHub...", NamedTextColor.AQUA);
        runAsync(() -> {
            try {
                ReleaseInfo release = updateService.fetchLatest();
                String current = plugin.getPluginMeta().getVersion();
                if (UpdateService.compareVersions(current, release.tag()) < 0) {
                    runOnMain(() -> sendUpdateAvailable(sender, release, current));
                } else {
                    runOnMain(() -> send(sender, "Bạn đang dùng bản mới nhất: " + release.tag() + ".",
                            NamedTextColor.GREEN));
                }
            } catch (NoReleaseException e) {
                runOnMain(() -> send(sender, e.getMessage(), NamedTextColor.YELLOW));
            } catch (UpdateException e) {
                runOnMain(() -> send(sender, "Không kiểm tra được: " + e.getMessage(), NamedTextColor.RED));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Lỗi không mong đợi khi kiểm tra update", e);
                runOnMain(() -> send(sender, "Không kiểm tra được: lỗi không xác định ("
                        + e.getClass().getSimpleName() + ").", NamedTextColor.RED));
            }
        });
    }

    private void sendUpdateAvailable(CommandSender sender, ReleaseInfo release, String current) {
        Component header = Component.text("Đã có bản mới: ", NamedTextColor.GREEN)
                .append(Component.text(release.tag(), NamedTextColor.WHITE));
        if (release.name() != null && !release.name().isBlank()
                && !release.name().equalsIgnoreCase(release.tag())) {
            header = header.append(Component.text(" (" + release.name() + ")", NamedTextColor.GRAY));
        }
        if (release.htmlUrl() != null && !release.htmlUrl().isBlank()) {
            header = header.append(Component.text("  "))
                    .append(Component.text("[mở trang release]", NamedTextColor.AQUA)
                            .decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE)
                            .clickEvent(ClickEvent.openUrl(release.htmlUrl())));
        }
        sender.sendMessage(header);
        sender.sendMessage(Component.text("Bạn đang dùng: ", NamedTextColor.GRAY)
                .append(Component.text(current, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Gõ /mc update download để tải, sau đó restart server để cài bản mới.",
                NamedTextColor.YELLOW));
    }

    // ---------------------------------------------------------------- download

    private void download(CommandSender sender) {
        if (!updateService.tryBeginDownload()) {
            send(sender, "Đang có một lần tải khác chạy, vui lòng chờ hoàn tất.", NamedTextColor.YELLOW);
            return;
        }
        send(sender, "Đang kiểm tra bản mới trước khi tải...", NamedTextColor.AQUA);
        runAsync(() -> {
            try {
                ReleaseInfo release = updateService.fetchLatest();
                String current = plugin.getPluginMeta().getVersion();
                if (UpdateService.compareVersions(current, release.tag()) >= 0) {
                    runOnMain(() -> send(sender, "Bạn đang dùng bản mới nhất (" + release.tag()
                            + "), không có gì để tải.", NamedTextColor.GREEN));
                    return;
                }
                runOnMain(() -> send(sender, "Đang tải " + release.asset().name()
                        + " (" + megaBytes(release.asset().size()) + " MB)...", NamedTextColor.AQUA));
                Path saved = updateService.download(release);
                runOnMain(() -> sendDownloaded(sender, saved, release));
            } catch (NoReleaseException e) {
                runOnMain(() -> send(sender, e.getMessage(), NamedTextColor.YELLOW));
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

    private void sendDownloaded(CommandSender sender, Path saved, ReleaseInfo release) {
        sender.sendMessage(Component.text("Đã tải ", NamedTextColor.GREEN)
                .append(Component.text(saved.getFileName().toString(), NamedTextColor.WHITE))
                .append(Component.text(" (" + megaBytes(release.asset().size()) + " MB) vào:", NamedTextColor.GREEN)));
        sender.sendMessage(Component.text(saved.toAbsolutePath().toString(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Restart server để cài bản mới — server sẽ tự thay thế bản cũ.",
                NamedTextColor.YELLOW));
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

    private static String megaBytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
