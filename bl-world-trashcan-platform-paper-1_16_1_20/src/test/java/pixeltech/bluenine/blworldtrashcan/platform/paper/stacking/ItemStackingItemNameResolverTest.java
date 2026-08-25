package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;

/** 验证 item-stacking 名称映射的键优先级和英文回退。 */
public final class ItemStackingItemNameResolverTest {
    /** 原版方块优先读取 block 键，且独立物品显示名优先于内置值。 */
    @Test
    public void blockTranslationAndOverrideArePreferred() {
        Properties translations = new Properties();
        translations.setProperty("block.minecraft.stone", "石头");
        translations.setProperty("item.minecraft.stone", "Stone Item");

        assertEquals("石头", ItemStackingItemNameResolver.resolveConfiguredName(
                Material.STONE, null, translations));
        assertEquals("自定义石头", ItemStackingItemNameResolver.resolveConfiguredName(
                Material.STONE, "自定义石头", translations));
    }

    /** 缺少翻译时必须保持稳定的英文材质名。 */
    @Test
    public void missingTranslationFallsBackToMaterialName() {
        assertEquals("Crying Obsidian", ItemStackingItemNameResolver.formatMaterialName("CRYING_OBSIDIAN"));
    }

    /** 三份内置 UTF-8 资源必须包含稳定的原版物品翻译。 */
    @Test
    public void bundledLocalesContainExpectedTranslations() throws Exception {
        assertEquals("石头", loadLocale("zh_cn").getProperty("block.minecraft.stone"));
        assertEquals("Stone", loadLocale("en_us").getProperty("block.minecraft.stone"));
        assertEquals("石", loadLocale("ja_jp").getProperty("block.minecraft.stone"));
    }

    /** 关闭堆叠时也能只加载内置语言名称，且不会访问插件数据目录。 */
    @Test
    public void baseResolverLoadsTranslationWithoutItemConfiguration() {
        Plugin plugin = pluginWithBundledResources();

        ItemStackingItemNameResolver resolver = ItemStackingItemNameResolver.loadBase(plugin, "zh-CN");

        assertEquals("石头", resolver.resolve(Material.STONE));
        assertEquals("zh_CN", resolver.getLocale());
    }

    /** 物品自身自定义名必须覆盖内置翻译和逐物品显示名。 */
    @Test
    public void itemCustomNameHasHighestPriority() {
        ItemStack itemStack = itemWithCustomName(Material.STONE, "玩家自定义石头");
        ItemStackingItemNameResolver resolver = ItemStackingItemNameResolver.load(null, null);

        assertEquals("玩家自定义石头", resolver.resolve(itemStack));
    }

    /** 逐物品 display-name 必须覆盖内置翻译并可供通知复用。 */
    @Test
    public void perMaterialNameOverridesBundledTranslation() {
        YamlConfiguration global = new YamlConfiguration();
        global.set("enabled", Boolean.TRUE);
        global.set("display.custom-name.locale", "zh_CN");
        YamlConfiguration items = new YamlConfiguration();
        items.set("STONE.display-name", "配置石头");
        ItemStackingConfig config = ItemStackingConfig.load(
                new BukkitConfigurationSource(global), new BukkitConfigurationSource(items));

        ItemStackingItemNameResolver resolver = ItemStackingItemNameResolver.load(
                pluginWithBundledResources(), config);

        assertEquals("配置石头", resolver.resolve(itemWithoutCustomName(Material.STONE)));
    }

    /** 从测试类路径读取指定内置语言。 */
    private Properties loadLocale(String locale) throws Exception {
        String resource = "/item-names/" + locale + ".properties";
        Properties translations = new Properties();
        try (InputStream stream = ItemStackingItemNameResolverTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("缺少测试资源: " + resource);
            }
            translations.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return translations;
    }

    /** 创建只提供 JAR 内资源的插件代理，访问数据目录时立即失败。 */
    private Plugin pluginWithBundledResources() {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getResource".equals(method.getName())) {
                        return ItemStackingItemNameResolverTest.class.getResourceAsStream("/" + args[0]);
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("ItemStackingItemNameResolverTest");
                    }
                    if ("getDataFolder".equals(method.getName())) {
                        throw new AssertionError("基础名称解析不得访问插件数据目录");
                    }
                    return null;
                });
    }

    /** 创建返回指定自定义 ItemMeta 的测试物品。 */
    private ItemStack itemWithCustomName(Material material, String displayName) {
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> {
                    if ("hasDisplayName".equals(method.getName())) {
                        return Boolean.TRUE;
                    }
                    if ("getDisplayName".equals(method.getName())) {
                        return displayName;
                    }
                    return defaultValue(method.getReturnType());
                });
        return new ItemStack(material) {
            /** 返回测试指定的自定义名称元数据。 */
            @Override
            public ItemMeta getItemMeta() {
                return meta;
            }
        };
    }

    /** 创建无需 Bukkit ItemFactory 的普通物品。 */
    private ItemStack itemWithoutCustomName(Material material) {
        return new ItemStack(material) {
            /** 测试物品明确没有自定义元数据。 */
            @Override
            public ItemMeta getItemMeta() {
                return null;
            }
        };
    }

    /** 返回代理方法所需的基本类型默认值。 */
    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0F);
        }
        if (type == double.class) {
            return Double.valueOf(0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
