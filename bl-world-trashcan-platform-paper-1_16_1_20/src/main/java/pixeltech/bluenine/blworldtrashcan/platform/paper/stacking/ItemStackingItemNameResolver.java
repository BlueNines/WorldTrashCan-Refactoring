package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** 将当前服务端材质映射为 item-stacking 使用的单语言名称。 */
final class ItemStackingItemNameResolver {
    private static final String RESOURCE_DIRECTORY = "item-names/";
    private final String locale;
    private final String[] names;
    private final int translatedCount;

    /** 创建不含翻译缓存的英文材质名回退解析器。 */
    private ItemStackingItemNameResolver() {
        this.locale = "fallback";
        this.names = new String[0];
        this.translatedCount = 0;
    }

    /** 创建已经按 Material.ordinal 对齐的单语言解析器。 */
    private ItemStackingItemNameResolver(String locale, String[] names, int translatedCount) {
        this.locale = locale;
        this.names = names;
        this.translatedCount = translatedCount;
    }

    /** 按配置加载一个语言；关闭态不读取任何翻译资源。 */
    static ItemStackingItemNameResolver load(Plugin plugin, ItemStackingConfig config) {
        if (plugin == null || config == null || !config.isFeatureEnabled()
                || !config.isDisplayNameEnabled()) {
            return new ItemStackingItemNameResolver();
        }
        String locale = config.getDisplayNameLocale();
        String resource = RESOURCE_DIRECTORY + locale.toLowerCase(Locale.ROOT) + ".properties";
        Properties translations = new Properties();
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning("[ItemStacking] 缺少内置物品名称资源 " + resource
                        + "，已降级为英文材质名。");
                return new ItemStackingItemNameResolver();
            }
            translations.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("[ItemStacking] 读取内置物品名称资源失败: "
                    + exception.getMessage() + "，已降级为英文材质名。");
            return new ItemStackingItemNameResolver();
        }

        Material[] materials = Material.values();
        String[] names = new String[materials.length];
        int translated = 0;
        for (Material material : materials) {
            String name = resolveConfiguredName(material, config.getDisplayNameOverrides(), translations);
            if (name != null && !name.trim().isEmpty()) {
                names[material.ordinal()] = name;
                translated++;
            }
        }
        plugin.getLogger().info("[ItemStacking] 已加载 " + locale + " 物品名称 "
                + translated + "/" + materials.length + " 项；运行时仅保留当前语言缓存。");
        return new ItemStackingItemNameResolver(locale, names, translated);
    }

    /** 返回物品名称，缺少翻译时使用可读英文材质名。 */
    String resolve(Material material) {
        if (material == null || material == Material.AIR) {
            return "Item";
        }
        int ordinal = material.ordinal();
        if (ordinal >= 0 && ordinal < names.length) {
            String translated = names[ordinal];
            if (translated != null && !translated.isEmpty()) {
                return translated;
            }
        }
        return formatMaterialName(material.name());
    }

    /** 返回当前缓存的语言标识。 */
    String getLocale() {
        return locale;
    }

    /** 返回当前缓存命中的材质数量。 */
    int getTranslatedCount() {
        return translatedCount;
    }

    /** 按材质类型选择 block/item 翻译键并应用服主覆盖。 */
    static String resolveConfiguredName(Material material, Map<String, String> overrides,
                                        Properties translations) {
        NamespacedKey key = material.getKey();
        String namespace = key.getNamespace();
        String value = key.getKey();
        String preferred = (material.isBlock() ? "block." : "item.") + namespace + "." + value;
        String alternate = (material.isBlock() ? "item." : "block.") + namespace + "." + value;
        String namespaced = namespace + ":" + value;

        String overridden = firstNonBlank(overrides.get(preferred), overrides.get(alternate),
                overrides.get(namespaced), overrides.get(material.name().toLowerCase(Locale.ROOT)));
        if (overridden != null) {
            return overridden;
        }
        return firstNonBlank(translations.getProperty(preferred), translations.getProperty(alternate));
    }

    /** 返回第一个非空文本。 */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 将枚举材质名格式化为可读英文名称。 */
    static String formatMaterialName(String materialName) {
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(materialName.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            if ("tnt".equals(word)) {
                result.append("TNT");
            } else {
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.length() == 0 ? materialName : result.toString();
    }
}
