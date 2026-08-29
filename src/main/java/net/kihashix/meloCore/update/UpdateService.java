package net.kihashix.meloCore.update;

import net.kihashix.meloCore.MeloCore;
import org.bukkit.Bukkit;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Tương tác GitHub Releases: kiểm tra bản mới nhất và tải asset .jar về update folder.
 * <p>
 * Cơ chế update folder (Bukkit/Paper): mọi jar trong thư mục update sẽ được server
 * tự động copy đè lên plugin cũ khi khởi động lại — lệnh chỉ cần đặt đúng file.
 * Paper khớp jar theo `name` trong plugin.yml nên tên file tải về không cần giống bản cũ.
 * <p>
 * Repo GitHub là cố định (Kihashix/MeloCore) theo quyết định thiết kế.
 */
public final class UpdateService {

    /** Kho chứa release — repo cố định của MeloCore. */
    public static final String REPOSITORY = "Kihashix/MeloCore";
    static final String PLUGIN_NAME = "MeloCore";

    private static final String RELEASES_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
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

    // ---------------------------------------------------------------- kết quả

    /** Bản release mới nhất ổn định (API /releases/latest tự bỏ draft & prerelease). */
    public record ReleaseInfo(String tag, String name, String htmlUrl, String body, ReleaseAsset asset) {
    }

    /** Asset .jar có thể tải về của một release. */
    public record ReleaseAsset(String name, String downloadUrl, long size, @Nullable String sha256) {
    }

    /** GitHub chưa có release nào (API trả 404). */
    public static final class NoReleaseException extends RuntimeException {
        NoReleaseException(String message) {
            super(message);
        }
    }

    /** Mọi lỗi khác (mạng, parse, tải file...) — message thân thiện với admin. */
    public static final class UpdateException extends RuntimeException {
        UpdateException(String message) {
            super(message);
        }

        UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---------------------------------------------------------------- kiểm tra

    /**
     * Gọi {@code GET /releases/latest} và trả về release mới nhất ổn định cùng asset .jar.
     * Phương thức chặn (blocking) — gọi từ task async của Bukkit, KHÔNG gọi từ main thread.
     */
    public ReleaseInfo fetchLatest() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_URL + "/latest"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MeloCore/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Yêu cầu bị ngắt khi kiểm tra bản mới.", e);
        } catch (IOException e) {
            throw new UpdateException("Không kết nối được GitHub: " + e.getMessage(), e);
        }

        int code = response.statusCode();
        if (code == 404) {
            throw new NoReleaseException("Chưa có bản phát hành nào trên GitHub.");
        }
        if (code == 403 || code == 429) {
            if (response.headers().firstValue("x-ratelimit-remaining").orElse("").equals("0")) {
                throw new UpdateException("GitHub đã hết lượt gọi API (giới hạn tần suất), thử lại sau khoảng 1 giờ.");
            }
            throw new UpdateException("GitHub từ chối yêu cầu (mã HTTP " + code + ").");
        }
        if (code != 200) {
            throw new UpdateException("GitHub trả về mã HTTP " + code + " khi kiểm tra bản mới.");
        }

        Map<String, Object> root;
        try {
            root = Json.asObject(response.body());
        } catch (RuntimeException e) {
            throw new UpdateException("Không đọc được phản hồi từ GitHub.", e);
        }

        String tag = str(root, "tag_name");
        if (tag == null || tag.isBlank()) {
            throw new UpdateException("Phản hồi từ GitHub không hợp lệ (thiếu tag_name).");
        }

        ReleaseAsset asset = findJarAsset(root);
        if (asset == null) {
            throw new UpdateException("Bản release " + tag + " không kèm file .jar để tải.");
        }

        return new ReleaseInfo(
                tag,
                str(root, "name"),
                str(root, "html_url"),
                str(root, "body"),
                asset
        );
    }

    /** Chọn asset .jar "thật" (không chọn -sources/-javadoc) của release. */
    private static @Nullable ReleaseAsset findJarAsset(Map<String, Object> root) {
        Object assets = root.get("assets");
        if (!(assets instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> asset = castMap(map);
            String name = str(asset, "name");
            String url = str(asset, "browser_download_url");
            if (name == null || url == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) continue;
            if (lower.endsWith("-sources.jar") || lower.endsWith("-javadoc.jar") || lower.endsWith("-dev.jar")) {
                continue;
            }
            long size = 0;
            if (asset.get("size") instanceof Number number) {
                size = number.longValue();
            }
            String sha256 = null;
            String digest = str(asset, "digest");
            if (digest != null && digest.regionMatches(true, 0, "sha256:", 0, 7)) {
                sha256 = digest.substring(7).trim();
            }
            return new ReleaseAsset(name, url, size, sha256);
        }
        return null;
    }

    // ---------------------------------------------------------------- tải file

    /** Bắt đầu một phiên download — chỉ một lệnh download được chạy tại một thời điểm. */
    public boolean tryBeginDownload() {
        return downloading.compareAndSet(false, true);
    }

    /** Kết thúc phiên download (luôn gọi trong finally). */
    public void endDownload() {
        downloading.set(false);
    }

    /**
     * Tải asset jar của release về thư mục update của server (mặc định {@code plugins/update}).
     * Trước khi đặt vào thư mục: kiểm tra kích thước, SHA-256 (nếu GitHub cung cấp) và
     * chắc chắn bên trong là plugin.yml của MeloCore. Trả về đường dẫn file đã lưu.
     * Phương thức chặn — gọi từ task async.
     */
    public Path download(ReleaseInfo release) {
        Path updateDir;
        try {
            updateDir = Bukkit.getUpdateFolderFile().toPath();
            Files.createDirectories(updateDir);
        } catch (IOException e) {
            throw new UpdateException("Không tạo được thư mục update ("
                    + Bukkit.getUpdateFolderFile() + "): " + e.getMessage(), e);
        }

        // Dọn jar MeloCore còn sót trước đó — Paper chỉ nhận một file cho mỗi plugin
        removeStaleCopies(updateDir);

        Path temp;
        try {
            temp = Files.createTempFile(updateDir, "melocore-", ".part");
        } catch (IOException e) {
            throw new UpdateException("Không tạo được file tạm trong thư mục update: " + e.getMessage(), e);
        }

        boolean moved = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(release.asset().downloadUrl()))
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

            try {
                verifyDownloadedFile(temp, release.asset());
                Path target = updateDir.resolve(sanitizeFileName(release.asset().name()));
                moveReplacing(temp, target);
                moved = true;
                return target;
            } catch (IOException e) {
                throw new UpdateException("Không thể hoàn tất việc lưu file update: " + e.getMessage(), e);
            }
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // file tạm rác còn lại cũng vô hại — server bỏ qua file .part
                }
            }
        }
    }

    /** Kiểm tra file tải về trước khi chấp nhận: kích thước, checksum, và đúng là jar MeloCore. */
    private void verifyDownloadedFile(Path file, ReleaseAsset asset) throws IOException {
        long size = Files.size(file);
        if (asset.size() > 0 && size != asset.size()) {
            throw new UpdateException("File tải về sai kích thước (mong đợi " + asset.size()
                    + " byte, nhận " + size + " byte).");
        }
        if (asset.sha256() != null) {
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(asset.sha256())) {
                throw new UpdateException("File tải về không khớp mã SHA-256 của GitHub — từ chối cài đặt "
                        + "(tránh file hỏng hoặc giả mạo).");
            }
        }
        if (!isMeloCoreJar(file)) {
            throw new UpdateException("File tải về không phải jar của plugin " + PLUGIN_NAME
                    + " (thiếu hoặc khác plugin.yml) — từ chối cài đặt.");
        }
    }

    /** Xóa các jar khác của MeloCore đang nằm sẵn trong update folder. */
    private void removeStaleCopies(Path updateDir) {
        try (Stream<Path> files = Files.list(updateDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(UpdateService::isMeloCoreJar)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Không xóa được file update cũ: " + path + " — " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("Không quét được thư mục update: " + e.getMessage());
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

    // ---------------------------------------------------------------- tiện ích

    /** So sánh hai version (bỏ tiền tố 'v', bỏ metadata '+...'): âm = current cũ hơn latest. */
    public static int compareVersions(String current, String latest) {
        String now = normalize(current);
        String then = normalize(latest);
        if (now.equals(then)) return 0;

        int base = compareNumericSegments(baseVersion(now), baseVersion(then));
        if (base != 0) return base;

        String cPre = preRelease(now);
        String lPre = preRelease(then);
        if (cPre.isEmpty() && !lPre.isEmpty()) return 1;  // 1.0.0 > 1.0.0-rc1
        if (!cPre.isEmpty() && lPre.isEmpty()) return -1;
        return cPre.compareToIgnoreCase(lPre);
    }

    private static String normalize(String version) {
        String value = version == null ? "" : version.trim();
        while (!value.isEmpty() && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            value = value.substring(1);
        }
        int plus = value.indexOf('+');
        if (plus >= 0) {
            value = value.substring(0, plus); // bỏ build metadata: 1.0.0+build == 1.0.0
        }
        return value.trim();
    }

    private static String baseVersion(String version) {
        int dash = version.indexOf('-');
        return (dash < 0 ? version : version.substring(0, dash)).trim();
    }

    private static String preRelease(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? "" : version.substring(dash + 1).trim();
    }

    private static int compareNumericSegments(String first, String second) {
        String[] a = first.split("[._+]");
        String[] b = second.split("[._+]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            Long nx = parseLong(x);
            Long ny = parseLong(y);
            if (nx != null && ny != null) {
                if (!nx.equals(ny)) return Long.compare(nx, ny);
            } else {
                int compare = x.compareToIgnoreCase(y);
                if (compare != 0) return compare;
            }
        }
        return 0;
    }

    private static @Nullable Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Máy chủ không hỗ trợ SHA-256", e); // không thể xảy ra
        }
    }

    /** Tên file an toàn: chỉ giữ ký tự chữ/số/dấu chấm, gạch, gạch dưới. */
    private static String sanitizeFileName(String name) {
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

    private static @Nullable String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String string ? string : null;
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        return Json.castMap(map);
    }
}
