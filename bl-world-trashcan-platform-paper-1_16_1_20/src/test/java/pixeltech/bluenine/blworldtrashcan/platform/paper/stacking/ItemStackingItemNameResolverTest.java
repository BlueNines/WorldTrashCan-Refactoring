package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

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
}
