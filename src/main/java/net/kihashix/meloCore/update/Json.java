package net.kihashix.meloCore.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trình phân tích JSON tối giản — chỉ đủ dùng cho phản hồi của GitHub Releases API.
 * <p>
 * Chủ ý giữ plugin KHÔNG có dependency ngoài: chỉ cần JDK của server.
 * Phản hồi GitHub có cấu trúc ổn định nên parser nhỏ này đủ an toàn;
 * nếu gặp JSON lạ sẽ ném {@link IllegalArgumentException} thay vì đoán mò.
 */
final class Json {

    private Json() {
    }

    /** Đọc chuỗi và ép là object JSON ở gốc. */
    static Map<String, Object> asObject(String text) {
        Object parsed = parse(text);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Phản hồi không phải JSON object.");
        }
        return castMap(map);
    }

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON không hợp lệ ở vị trí " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) throw error("Thiếu giá trị.");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) throw error("JSON không hợp lệ.");
            pos += literal.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw error("Thiếu tên key (phải là chuỗi).");
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') throw error("Thiếu dấu ':'.");
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                char next = peek();
                if (next == '}') {
                    pos++;
                    return map;
                }
                if (next != ',') throw error("Thiếu dấu ','.");
                pos++;
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ']') {
                    pos++;
                    return list;
                }
                if (next != ',') throw error("Thiếu dấu ','.");
                pos++;
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) throw error("Chuỗi chưa kết thúc.");
                char c = text.charAt(pos++);
                if (c == '"') return builder.toString();
                if (c == '\\') {
                    if (atEnd()) throw error("Chuỗi chưa kết thúc.");
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(parseUnicode());
                        default -> throw error("Escape không hợp lệ: \\" + escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
        }

        private char parseUnicode() {
            if (pos + 4 > text.length()) throw error("\\u thiếu 4 chữ số hex.");
            String hex = text.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("\\u không hợp lệ: " + hex);
            }
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || Character.isDigit(c)) {
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            if (number.isEmpty()) throw error("JSON không hợp lệ.");
            try {
                if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("Số không hợp lệ: " + number);
            }
        }

        private char peek() {
            if (atEnd()) throw error("JSON kết thúc bất ngờ.");
            return text.charAt(pos);
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " (vị trí " + pos + ")");
        }
    }
}
