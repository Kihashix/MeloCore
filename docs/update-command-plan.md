# Kế hoạch: Lệnh `/mc update` — kiểm tra & tải bản mới từ GitHub Releases

> Trạng thái: **đã triển khai** (xem mục 7). Các quyết định thiết kế nằm ở mục 6.
> Repo: `Kihashix/MeloCore` · Version hiện tại: `1.0.0` (pom.xml → plugin.yml) · Paper 1.21.11

## 1. Mục tiêu

| Lệnh | Hành vi |
|---|---|
| `/mc update check` | Gọi GitHub Releases API → so sánh tag mới nhất với version hiện tại trong `plugin.yml` (`getDescription().getVersion()`, hiện `1.0.0`) → báo cho admin có bản mới không. |
| `/mc update download` | Tải asset `.jar` của release mới nhất về thư mục update của Bukkit (`plugins/update/` mặc định) → server tự thay thế plugin cũ khi restart. |

## 2. Cơ chế update folder — đã kiểm chứng trên Paper 1.21.11

Trong source Paper `ver/1.21.11`:

- `FileProviderSource.checkUpdate()` (paper-server): khi nạp plugin, Paper mở từng jar trong thư mục update, đọc `plugin.yml`, **khớp theo `name` của plugin** (không phải tên file) → nếu trùng `MeloCore`:
  1. copy đè lên file plugin đang chạy,
  2. đổi tên file plugin theo tên file trong update,
  3. xóa file trong `plugins/update/`,
  4. nạp plugin mới từ lần restart sau.
- Đường dẫn update folder lấy từ `settings.update-folder` trong `bukkit.yml` (mặc định `update`, nằm trong `plugins/`). Trên API: `Bukkit.getUpdateFolderFile()` → lệnh tự tôn trọng cấu hình, kể cả khi admin đổi tên folder.
- Kết luận quan trọng: **file tải về không cần trùng tên file cũ** — chỉ cần bên trong jar có `plugin.yml` với `name: MeloCore`. Có thể tải về với tên `melocore-<version>.jar`.

⚠️ Lưu ý: cơ chế chạy khi **restart** (không phải reload `/reload`). Tải xong, admin chỉ cần restart server.

## 3. Thiết kế

### 3.1 File mới / sửa

| File | Việc |
|---|---|
| `net.kihashix.meloCore.update.UpdateService` (mới) | Toàn bộ logic GitHub: call API, parse JSON, so sánh version, tải file, verify. |
| `net.kihashix.meloCore.update.Json` (mới, package-private) | JSON parser tối giản — không dependency ngoài. |
| `net.kihashix.meloCore.command.UpdateCommand` (mới) | Xử lý `/mc update check|download`, kiểm tra permission, gửi thông báo (đưa về main thread bằng Bukkit Scheduler). |
| `SkillCommand` (sửa) | Root dispatch: `args[0] == "update"` → chuyển cho `UpdateCommand`; cập nhật `USAGE`. |
| `SkillTabCompleter` (sửa) | Mức 1 thêm `update`; mức 2 thêm `check`, `download`. |
| `plugin.yml` (sửa) | `usage: /mc <skills|update> ...` + permission mới `melocore.admin.update` (default: op). |
| `MeloCore` (sửa) | Khởi tạo `UpdateService` + `UpdateCommand` và cấp cho executor của `/mc`. |
| `pom.xml` | **Không đổi** — không thêm dependency. |

### 3.2 `UpdateService` — chi tiết

**HTTP**: dùng `java.net.http.HttpClient` có sẵn trong Java 21 (không cần thêm thư viện HTTP). Header:
`User-Agent: MeloCore/<version>` + `Accept: application/vnd.github+json`. Timeout: connect 10s, request 15s (download 5 phút).

**JSON**: tự viết parser tối giản `update/Json.java` — chỉ đủ đọc phản hồi GitHub, **không thêm dependency** (plugin giữ nguyên 0 dependency ngoài; tránh phải shade Gson). Parser phủ escapes `\" \\ \/ \b \f \n \r \t \uXXXX`, object/array/số/boolean/null — gặp dữ liệu lạ thì báo lỗi chứ không đoán mò.

**API**:
- `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
  - Tự bỏ draft & prerelease (đúng ngữ nghĩa "bản mới nhất ổn định").
  - 404 → chưa có release → báo "chưa có bản phát hành nào".
  - Trả về: `tag_name`, `name`, `html_url`, `body` (changelog), `assets[]` (`name`, `browser_download_url`, `size`, `digest` sha256 nếu GitHub cung cấp).
- Chọn asset: ưu tiên file kết thúc `.jar`; chỉ tải `.jar`.

**So sánh version** (helper trong `UpdateService`):
- Bỏ tiền tố `v`/`V` của tag (`v1.2.3` ↔ `1.2.3`).
- So numeric `major.minor.patch`; phần trước dấu `-` nếu có suffix (alpha/beta/rc/snapshot).
- Suffix prerelease thấp hơn bản release cùng số (`1.2.0-rc1 < 1.2.0`).

**Tải file**:
- Chạy **async** (không bao giờ block main thread): lệnh gọi qua `runTaskAsynchronously`, bên trong `HttpClient.send` + `BodyHandlers.ofFile` vào file tạm `.part`, xong `Files.move(..., ATOMIC_MOVE)` → không để lại file hỏng.
- Verify trước khi đặt vào update folder:
  1. Đọc `plugin.yml` trong jar (`JarFile`) → `name` phải là `MeloCore` (chặn nhầm asset sources/javadoc).
  2. Nếu GitHub cung cấp `digest: sha256:...` → so sánh SHA-256 file tải về; lệch → xóa và báo lỗi.
- Copy vào `Bukkit.getUpdateFolderFile()` (tự `mkdirs()` nếu thiếu).
- **Dọn jar cũ cùng plugin name** trong update folder trước khi ghi — nếu có 2 jar MeloCore, Paper chỉ chọn một theo thứ tự walk (dễ cài nhầm bản cũ).

**Trạng thái tải** lưu in-memory (mỗi tiến trình server): tránh 2 lệnh download chạy đồng thời.

### 3.3 UX thông báo (Component + NamedTextColor, tiếng Việt như code hiện có)

- `check` khi có bản mới:
  ```
  [MeloCore] Đã có bản mới: v1.0.1 (MeloCore 1.0.1)
  Bạn đang dùng: v1.0.0
  Xem chi tiết: <html_url>
  Gõ /mc update download để chuẩn bị cài, rồi restart server.
  ```
- `check` khi đã mới nhất: xanh lá, kèm version hiện tại.
- `download` thành công: `Đã tải melocore-1.0.1.jar (2.4 MB) vào plugins/update/. Restart server để cài đặt.`
- `download` khi đã mới nhất: báo "không có gì để tải".
- Lỗi mạng / rate-limit / release 404: báo rõ, không crash server, không spam message.

### 3.4 Permission

Nguyên tắc: tách khỏi `melocore.admin.skills` → `melocore.admin.update` (default: op), để sau này có thể cho người quản lý server mà không cấp quyền sửa skill. (Đã chốt — mục 6.)

## 4. Edge cases & xử lý

| Tình huống | Xử lý |
|---|---|
| Chưa có release (API 404) | "Chưa có bản phát hành nào trên GitHub." |
| Rate limit / token hết quota (403) | Báo lỗi, nhắc thử lại sau; không retry spam. |
| Mất mạng giữa lúc tải | Xóa file `.part`, báo "tải thất bại, thử lại". |
| Asset không phải `.jar` | Bỏ qua asset đó; không có jar → báo lỗi. |
| Jar tải về thiếu `plugin.yml` / sai tên plugin | Từ chối, xóa file, báo lỗi. |
| Đã có file trong `plugins/update/` | Ghi đè bản mới hơn; nếu giống hệt → báo "đã sẵn sàng, chỉ cần restart". |
| Update folder bị đổi/trống/trùng `plugins` | Tự tạo folder; dùng `Bukkit.getUpdateFolderFile()`; không tạo được → báo lỗi. |
| Version scheme khác (`1.0.0-SNAPSHOT`, `v1.0.0`) | Normalize trước khi so sánh. |
| Người chạy `/mc update` không phải OP | Chặn bằng permission, message "Bạn không có quyền." |

## 5. Góp ý bổ sung (đề xuất, không đưa vào vòng này trừ khi bạn muốn)

1. **Auto-check khi server khởi động**: log + chỉ báo cho admin đang online một lần (không quảng cáo cho player). Nên có cờ bật/tắt trong config, mặc định **tắt** để yên tĩnh. — *Đã chốt KHÔNG làm ở vòng này (Q2), để dành cho sau.*
2. **`/mc update status`**: liệt kê file update đang chờ → admin biết "đã sẵn sàng, chỉ cần restart".
3. **`--prerelease` / `--force`**: tải luôn bản pre-release; tải lại khi đã mới nhất.
4. **Changelog ngắn**: in 3–5 dòng đầu `body` của release sau `check` để admin đọc trước khi cài.
5. **Release workflow CI**: đảm bảo mỗi release đều đính kèm `melocore-<version>.jar` (không phải `-sources.jar`). Nếu là thủ công, thêm checklist vào mô tả release.
6. **Verify chữ ký**: nếu sau này ký jar (jarsigner) → có thể xác thực chữ ký server trước khi chấp nhận bản tải về.
7. **Không hỗ trợ token/private repo** trong config — token dễ lộ qua config/admin chat. Nếu cần repo private, làm riêng.
8. **Test**: thêm vài JUnit cho hàm so sánh version (pom chưa có test infra — có thể thêm sau; vòng này chủ yếu smoke-test trên server dev).

## 6. Câu hỏi đã chốt (hỏi trước khi code)

| # | Câu hỏi | Quyết định |
|---|---|---|
| 1 | Repo GitHub cấu hình hay cứng? | **Cứng `Kihashix/MeloCore`** trong code (đơn giản, đúng repo chính). |
| 2 | Auto-check khi server khởi động? | **Không** — chỉ kiểm tra khi gõ `/mc update check`. |
| 3 | Sau khi tải có thêm `/mc update install`? | **Không** — chỉ đặt vào `plugins/update/` + nhắc restart (an toàn, đúng cơ chế Bukkit). |
| 4 | Quyền dùng chung hay riêng? | **Riêng `melocore.admin.update`** (default: op). |

## 7. Trạng thái triển khai (đã code)

Đã triển khai trong nhánh này:

- `update/UpdateService.java` — API `/releases/latest`, chọn asset `.jar` (bỏ `-sources/-javadoc`), so sánh version (bỏ `v`, bỏ `+build`, prerelease < release), tải qua `HttpClient` + verify: kích thước, **SHA-256** (nếu GitHub gửi `digest`), và `plugin.yml` đúng `name: MeloCore`; đề phòng 2 lệnh tải song song; tự dọn jar cũ trong update folder.
- `update/Json.java` — JSON parser tối giản, không dependency ngoài.
- `command/UpdateCommand.java` — `/mc update check|download`, async (không block main), thông báo tiếng Việt, link release click được.
- `SkillCommand` / `SkillTabCompleter` — dispatch `/mc update ...` + complete `check|download`.
- `plugin.yml` — usage mới + permission `melocore.admin.update` (op).
- `MeloCore.java` — nối các thành phần.

**Đã kiểm tra trong sandbox**: cú pháp Java (parser) 6 file, payload JSON GitHub thật qua parser, bảng test so sánh version (10 trường hợp), YAML `plugin.yml`.
**Chưa kiểm tra được**: `mvn clean package` (sandbox không có JDK/Maven và bị chặn Maven Central/papermc repo) — cần chạy `mvn clean package` trên máy có Maven 3.9+ và JDK 21, và smoke-test trên server dev trước khi release.

### Cách test tay (sau khi build)

1. `mvn clean package` → lấy `target/melocore-1.0.0.jar` đặt vào `plugins/`.
2. `/mc update check` → hiện tại = v1.0.0 = tag v1.0.0 → "đang dùng bản mới nhất".
3. Bump `pom.xml` lên `1.0.1`, release GitHub tag `v1.0.1` kèm asset `melocore-1.0.1.jar`, `/mc update check` → "có bản mới", `/mc update download` → file vào `plugins/update/`, restart → Paper tự thay thế.
4. Thử các nhánh lỗi: tắt mạng, xoá folder `plugins/update`, tải release không có asset `.jar`.
