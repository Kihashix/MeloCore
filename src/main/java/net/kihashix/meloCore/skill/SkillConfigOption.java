package net.kihashix.meloCore.skill;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Một tùy chọn cấu hình runtime của skill (vd: radius, freeze-time, slowness).
 * <p>
 * Dùng chung cho: lệnh {@code /mc skills config <skill> <key> <giá trị>},
 * tab-complete, hiển thị {@code /mc skills info} và lưu/đọc skills.yml.
 * Skill mới muốn thêm option chỉ cần {@code registerOption(...)} trong constructor —
 * không phải sửa lệnh, tab-completer hay SkillManager.
 */
public final class SkillConfigOption {

    /** Kiểu giá trị — quyết định cách parse từ lệnh và đọc skills.yml. */
    public enum Type { INT, LONG }

    private final String key;
    private final String label;
    private final Type type;
    private final long min; // hợp lệ kể cả min
    private final long max; // hợp lệ kể cả max
    private final Supplier<Long> getter;
    private final Consumer<Long> setter;

    private SkillConfigOption(String key, String label, Type type, long min, long max,
                              Supplier<Long> getter, Consumer<Long> setter) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    /** Option kiểu int (vd: radius, slowness amplifier). */
    public static SkillConfigOption intOption(String key, String label, int min, int max,
                                              Supplier<Integer> getter, Consumer<Integer> setter) {
        return new SkillConfigOption(key, label, Type.INT, min, max,
                () -> getter.get().longValue(), value -> setter.accept(value.intValue()));
    }

    /** Option kiểu long (vd: freeze-time tính bằng ms). */
    public static SkillConfigOption longOption(String key, String label, long min, long max,
                                               Supplier<Long> getter, Consumer<Long> setter) {
        return new SkillConfigOption(key, label, Type.LONG, min, max, getter, setter);
    }

    /** Key dùng trong lệnh & skills.yml (chữ thường, vd: "freeze-time"). */
    public String getKey() {
        return key;
    }

    /** Nhãn tiếng Việt hiển thị trong /mc skills info (kèm đơn vị). */
    public String getLabel() {
        return label;
    }

    /** Giá trị hiện tại dạng chuỗi — dùng cho tab-complete gợi ý & /mc skills info. */
    public String getValue() {
        return String.valueOf(getter.get());
    }

    /** Giá trị hiện tại dạng số — dùng khi ghi skills.yml. */
    public long currentValue() {
        return getter.get();
    }

    /**
     * Parse + kiểm tra giới hạn + áp dụng giá trị mới (từ lệnh).
     *
     * @return {@code null} nếu thành công; ngược lại là thông báo lỗi đã sẵn sàng gửi cho sender.
     */
    public String apply(String input) {
        long value;
        try {
            value = type == Type.INT ? Integer.parseInt(input) : Long.parseLong(input);
        } catch (NumberFormatException e) {
            return "<" + key + "> phải là số nguyên.";
        }
        if (value < min) {
            return label + " phải >= " + min + ".";
        }
        if (value > max) {
            return label + " phải <= " + max + ".";
        }
        setter.accept(value);
        return null;
    }

    /**
     * Đọc giá trị từ skills.yml. Key chưa có, không phải số nguyên hoặc ngoài
     * giới hạn thì bỏ qua và giữ nguyên giá trị hiện tại (default của skill).
     */
    public void load(FileConfiguration config, String path) {
        // isInt/isLong: value phải là số nguyên (3.5 hay chuỗi -> bỏ qua)
        if (!config.isInt(path) && !config.isLong(path)) return;
        long value = config.getLong(path);
        if (value < min || value > max) return;
        setter.accept(value);
    }
}
