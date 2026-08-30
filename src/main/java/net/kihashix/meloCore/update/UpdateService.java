package net.kihashix.meloCore.update;

import net.kihashix.meloCore.MeloCore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Tải file .jar update từ URL trực tiếp và đặt vào thư mục {@code plugins/}
 * để ghi đè lên bản cũ khi server restart.
 * <p>
 * Không còn phụ thuộc GitHub Releases API — người dùng tự cung cấp URL tải.
 */
public final class UpdateService {

    static final String PLUGIN_NAME = "MeloCore";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final MeloCore plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    public UpdateService(MeloCore plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------------------------------------------------------------- ngoại lệ

    /** Mọi lỗi (mạng, tải file, xác thực...) — message thân thiện với admin. */
    public static final class UpdateException extends RuntimeException {
        UpdateException(String message) {
            super(message);
        }

        UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---------------------------------------------------------------- quản lý phiên tải

    /** Bắt đầu một phiên download — chỉ một lệnh download được chạy tại một thời điểm. */
    public boolean tryBeginDownload() {
        return downloading.compareAndSet(false, true);
    }

    /** Kết thúc phiên download (luôn gọi trong finally). */
    public void endDownload() {
        downloading.set(false);
    }

    // ---------------------------------------------------------------- tải file

    /**
     * Tải file .jar từ URL trực tiếp, xác nhận đúng là MeloCore, rồi đặt vào
     * thư mục {@code plugins/} để ghi đè bản cũ.
     * <p>
     * Phương thức chặn (blocking) — gọi từ task async của Bukkit, KHÔNG gọi từ main thread.
     *
     * @param url URL tải file .jar (hỗ trợ redirect)
     * @return đường dẫn file đã lưu trong {@code plugins/}
     * @throws UpdateException nếu tải, xác thực hoặc lưu file thất bại
     */
    public Path downloadFromUrl(String url) {
        Path pluginsDir = plugin.getDataFolder().getParentFile().toPath();

        // Tải vào file tạm trước — chỉ ghi đè bản cũ khi đã xác nhận hợp lệ
        Path temp;
        try {
            temp = Files.createTempFile(pluginsDir, "melocore-", ".part");
        } catch (IOException e) {
            throw new UpdateException("Không tạo được file tạm trong thư mục plugins: " + e.getMessage(), e);
        }

        boolean moved = false;
        try {
            // 1. Tải file
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", "MeloCore/" + plugin.getPluginMeta().getVersion())
                    .GET()
                    .build();

            HttpResponse<Path> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpdateException("Tải bản update bị ngắt.", e);
            } catch (IOException e) {
                throw new UpdateException("Tải file thất bại: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new UpdateException("Tải file thất bại (mã HTTP " + response.statusCode() + ").");
            }

            // 2. Xác nhận đúng là MeloCore jar
            if (!isMeloCoreJar(temp)) {
                throw new UpdateException("File tải về không phải jar của plugin " + PLUGIN_NAME
                        + " (thiếu hoặc khác plugin.yml) — từ chối cài đặt.");
            }

            // 3. Xóa bản cũ trong plugins/
            removeStaleCopies(pluginsDir);

            // 4. Di chuyển file tạm thành file chính thức
            String fileName = extractFileName(url);
            Path target = pluginsDir.resolve(sanitizeFileName(fileName));
            try {
                moveReplacing(temp, target);
            } catch (IOException e) {
                throw new UpdateException("Không thể lưu file update: " + e.getMessage(), e);
            }
            moved = true;
            return target;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // file tạm rác còn lại cũng vô hại
                }
            }
        }
    }

    // ---------------------------------------------------------------- tiện ích

    /** Xóa các jar khác của MeloCore đang nằm sẵn trong thư mục plugins. */
    private void removeStaleCopies(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(UpdateService::isMeloCoreJar)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Không xóa được file cũ: " + path + " — " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("Không quét được thư mục plugins: " + e.getMessage());
        }
    }

    /** File có phải jar của MeloCore không (đọc plugin.yml bên trong). */
    static boolean isMeloCoreJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            var entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return false;
            try (InputStream in = jar.getInputStream(entry)) {
                YamlConfiguration description = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                String name = description.getString("name");
                return name != null && name.equalsIgnoreCase(PLUGIN_NAME);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Trích tên file từ URL (bỏ query string & fragment). */
    private static @Nullable String extractFileName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) return null;
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tên file an toàn: chỉ giữ ký tự chữ/số/dấu chấm, gạch, gạch dưới. */
    private static String sanitizeFileName(@Nullable String name) {
        if (name == null || name.isBlank()) return "melocore.jar";
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "melocore.jar" : safe;
    }

    /** Di chuyển file, ưu tiên atomic; fallback nếu hệ thống file không hỗ trợ. */
    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
