package pixeltech.bluenine.blworldtrashcan.bukkit;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/** 跨服务端安全解析 Bukkit Material 名称。 */
public final class SafeMaterialMatcher {
    /** 工具类不允许创建实例。 */
    private SafeMaterialMatcher() {
    }

    /** 解析 Material 名称，Arclight 等端遇到旧材质名异常时返回 null。 */
    public static Material match(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(value);
        Material direct = Material.getMaterial(normalized);
        if (direct != null) {
            return direct;
        }
        Material mapped = Material.getMaterial(toModernName(normalized));
        if (mapped != null) {
            return mapped;
        }
        try {
            return Material.matchMaterial(normalized);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 按顺序解析多个候选名。 */
    public static Material first(String... values) {
        for (String value : values) {
            Material material = match(value);
            if (material != null && material != Material.AIR) {
                return material;
            }
        }
        return null;
    }

    /** 按列表顺序解析多个候选名。 */
    public static Material first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String value : values) {
            Material material = match(value);
            if (material != null && material != Material.AIR) {
                return material;
            }
        }
        return null;
    }

    /** 统一配置输入的大小写和命名空间。 */
    private static String normalize(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        int namespaceIndex = normalized.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < normalized.length()) {
            normalized = normalized.substring(namespaceIndex + 1);
        }
        return normalized;
    }

    /** 将常见旧材质名映射为现代 Bukkit 名称。 */
    private static String toModernName(String normalized) {
        if ("LEGACY_STAINED_GLASS_PANE".equals(normalized) || "STAINED_GLASS_PANE".equals(normalized)) {
            return "BLACK_STAINED_GLASS_PANE";
        }
        if ("THIN_GLASS".equals(normalized) || "LEGACY_THIN_GLASS".equals(normalized)) {
            return "GLASS_PANE";
        }
        return normalized;
    }
}
