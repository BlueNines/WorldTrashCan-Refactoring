package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemDisplayNameResolver;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SimpleItemDisplayNameResolver;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/** 将当前服务端材质映射为地面悬浮名和垃圾桶提示共用的单语言名称。 */
public final class ItemStackingItemNameResolver implements ItemDisplayNameResolver {
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
    public static ItemStackingItemNameResolver load(Plugin plugin, ItemStackingConfig config) {
        if (plugin == null || config == null) {
            return new ItemStackingItemNameResolver();
        }
        String locale = config.getDisplayNameLocale();
        String resource = RESOURCE_DIRECTORY + locale.toLowerCase(Locale.ROOT) + ".properties";
        Properties translations = new Properties();
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning("[ItemNames] 缺少内置物品名称资源 " + resource
                        + "，已降级为英文材质名。");
                return new ItemStackingItemNameResolver();
            }
            translations.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("[ItemNames] 读取内置物品名称资源失败: "
                    + exception.getMessage() + "，已降级为英文材质名。");
            return new ItemStackingItemNameResolver();
        }

        Material[] materials = Material.values();
        String[] names = new String[materials.length];
        int translated = 0;
        for (Material material : materials) {
            if (material.isLegacy()) {
                continue;
            }
            String name = resolveConfiguredName(material,
                    config.getItemRule(material.name()).getDisplayName(), translations);
            if (name != null && !name.trim().isEmpty()) {
                names[material.ordinal()] = name;
                translated++;
            }
        }
        plugin.getLogger().info("[ItemNames] 已加载 " + locale + " 物品名称 "
                + translated + "/" + materials.length + " 项；运行时仅保留当前语言缓存。");
        return new ItemStackingItemNameResolver(locale, names, translated);
    }

    /** 只按总配置语言加载名称，不读取或生成逐物品配置。 */
    public static ItemStackingItemNameResolver loadBase(Plugin plugin, String locale) {
        return load(plugin, ItemStackingConfig.namesOnly(locale));
    }

    /** 返回物品名称，物品自身名称始终拥有最高优先级。 */
    @Override
    public String resolve(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return "Item";
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return resolve(itemStack.getType());
    }

    /** 返回物品名称，缺少翻译时使用可读英文材质名。 */
    public String resolve(Material material) {
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
    public String getLocale() {
        return locale;
    }

    /** 返回当前缓存命中的材质数量。 */
    public int getTranslatedCount() {
        return translatedCount;
    }

    /** 优先使用逐物品显示名，否则按材质类型选择 block/item 内置翻译键。 */
    static String resolveConfiguredName(Material material, String configuredName,
                                        Properties translations) {
        NamespacedKey key = material.getKey();
        String namespace = key.getNamespace();
        String value = key.getKey();
        String preferred = (material.isBlock() ? "block." : "item.") + namespace + "." + value;
        String alternate = (material.isBlock() ? "item." : "block.") + namespace + "." + value;
        if (configuredName != null && !configuredName.trim().isEmpty()) {
            return configuredName;
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
        return SimpleItemDisplayNameResolver.formatMaterialName(materialName);
    }
}
