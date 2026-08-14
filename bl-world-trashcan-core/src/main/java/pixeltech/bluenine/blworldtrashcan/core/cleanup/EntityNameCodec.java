package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.Locale;

/** 在不依赖 Bukkit 的前提下统一实体名称颜色格式。 */
public final class EntityNameCodec {
    private static final char COLOR = '\u00A7';

    /** 工具类不允许实例化。 */
    private EntityNameCodec() {
    }

    /** 判断文本是否包含受支持的传统或 RGB 颜色代码。 */
    public static boolean containsColorCode(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char marker = value.charAt(index);
            if (!isColorMarker(marker)) {
                continue;
            }
            if (isDirectHex(value, index) || isExpandedHex(value, index)) {
                return true;
            }
            if (index + 1 < value.length() && isLegacyCode(value.charAt(index + 1))) {
                return true;
            }
        }
        return false;
    }

    /** 转成名称匹配使用的颜色标准格式和小写文本。 */
    static String normalizeForMatch(String value) {
        return canonicalizeColors(value).trim().toLowerCase(Locale.ROOT);
    }

    /** 转成服主可直接写入配置的 & 颜色格式。 */
    public static String toConfigText(String value) {
        return canonicalizeColors(value).replace(COLOR, '&');
    }

    /** 移除受支持的颜色代码但保留名称正文。 */
    public static String stripColors(String value) {
        return stripCanonicalColors(canonicalizeColors(value));
    }

    /** 移除已经完成颜色标准化的匹配文本中的颜色代码。 */
    static String stripNormalizedColors(String value) {
        return stripCanonicalColors(value == null ? "" : value);
    }

    /** 将 &、§ 和展开 RGB 统一为 § 颜色格式，同时保留正文大小写。 */
    private static String canonicalizeColors(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!isColorMarker(current)) {
                builder.append(current);
                continue;
            }
            if (isDirectHex(value, index)) {
                builder.append(COLOR).append('#');
                for (int offset = 2; offset < 8; offset++) {
                    builder.append(Character.toLowerCase(value.charAt(index + offset)));
                }
                index += 7;
                continue;
            }
            if (isExpandedHex(value, index)) {
                builder.append(COLOR).append('#');
                for (int pair = 0; pair < 6; pair++) {
                    builder.append(Character.toLowerCase(value.charAt(index + 3 + pair * 2)));
                }
                index += 13;
                continue;
            }
            if (index + 1 < value.length() && isLegacyCode(value.charAt(index + 1))) {
                builder.append(COLOR).append(Character.toLowerCase(value.charAt(index + 1)));
                index++;
                continue;
            }
            builder.append(current);
        }
        return builder.toString();
    }

    /** 从已经标准化的文本中移除颜色代码。 */
    private static String stripCanonicalColors(String value) {
        if (value.isEmpty()) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == COLOR && index + 1 < value.length()) {
                if (value.charAt(index + 1) == '#' && hasSixHexDigits(value, index + 2)) {
                    index += 7;
                    continue;
                }
                if (isLegacyCode(value.charAt(index + 1))) {
                    index++;
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
    }

    /** 判断当前位置是否是 &#RRGGBB 或 §#RRGGBB。 */
    private static boolean isDirectHex(String value, int index) {
        return index + 7 < value.length() && value.charAt(index + 1) == '#'
                && hasSixHexDigits(value, index + 2);
    }

    /** 判断当前位置是否是 &x&R&R&G&G&B&B 或 Bukkit §x 展开格式。 */
    private static boolean isExpandedHex(String value, int index) {
        if (index + 13 >= value.length() || Character.toLowerCase(value.charAt(index + 1)) != 'x') {
            return false;
        }
        for (int pair = 0; pair < 6; pair++) {
            int markerIndex = index + 2 + pair * 2;
            if (!isColorMarker(value.charAt(markerIndex)) || !isHex(value.charAt(markerIndex + 1))) {
                return false;
            }
        }
        return true;
    }

    /** 判断指定位置是否连续包含六个十六进制字符。 */
    private static boolean hasSixHexDigits(String value, int start) {
        if (start + 5 >= value.length()) {
            return false;
        }
        for (int offset = 0; offset < 6; offset++) {
            if (!isHex(value.charAt(start + offset))) {
                return false;
            }
        }
        return true;
    }

    /** 判断字符是否是颜色标记。 */
    private static boolean isColorMarker(char value) {
        return value == '&' || value == COLOR;
    }

    /** 判断字符是否是传统颜色或格式代码。 */
    private static boolean isLegacyCode(char value) {
        char normalized = Character.toLowerCase(value);
        return isHex(normalized) || (normalized >= 'k' && normalized <= 'o') || normalized == 'r';
    }

    /** 判断字符是否是十六进制字符。 */
    private static boolean isHex(char value) {
        char normalized = Character.toLowerCase(value);
        return (normalized >= '0' && normalized <= '9') || (normalized >= 'a' && normalized <= 'f');
    }
}
