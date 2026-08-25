package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证逐物品配置只在开启时生成，并且补齐时不覆盖服主设置。 */
public final class ItemStackingConfigurationLoaderTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 总开关关闭时不得读取或创建逐物品文件。 */
    @Test
    public void disabledFeatureDoesNotCreateItemFile() throws Exception {
        File folder = temporaryFolder.newFolder();
        write(new File(folder, "item-stacking.yml"), "enabled: false\n");

        ItemStackingConfig config = ItemStackingConfigurationLoader.load(plugin(folder));

        assertFalse(config.isFeatureEnabled());
        assertFalse(new File(folder, "item-stacking-items.yml").exists());
    }

    /** 首次开启应按当前服务端 Material 生成完整字段和中文说明。 */
    @Test
    public void enabledFeatureCreatesCompleteItemRules() throws Exception {
        File folder = temporaryFolder.newFolder();
        write(new File(folder, "item-stacking.yml"), "enabled: true\n");

        ItemStackingConfig config = ItemStackingConfigurationLoader.load(plugin(folder));
        String text = normalized(read(new File(folder, "item-stacking-items.yml")));

        assertTrue(config.isFeatureEnabled());
        assertTrue(text.contains("# enabled: 是否由本插件接管该物品"));
        assertTrue(text.contains(Material.STONE.name() + ":\n  enabled: true\n"
                + "  max-stack-size: -1\n  display-name: \"default\""));
    }

    /** 已有条目必须保持原值，只追加当前服务端仍缺少的 Material。 */
    @Test
    public void existingRulesArePreservedWhileMissingRulesAreAppended() throws Exception {
        File folder = temporaryFolder.newFolder();
        write(new File(folder, "item-stacking.yml"),
                "enabled: true\nstack:\n  max-logical-amount: 9000\n");
        write(new File(folder, "item-stacking-items.yml"),
                "STONE:\n  enabled: false\n  max-stack-size: 77\n  display-name: '测试石头'\n");

        ItemStackingConfig config = ItemStackingConfigurationLoader.load(plugin(folder));
        String text = normalized(read(new File(folder, "item-stacking-items.yml")));

        assertFalse(config.getItemRule("STONE").isEnabled());
        assertEquals(77, config.getItemRule("STONE").getMaxStackSize());
        assertEquals("测试石头", config.getItemRule("STONE").getDisplayName());
        assertEquals(1, occurrences("\n" + text, "\nSTONE:\n"));
        assertTrue(text.contains("DIRT:"));
    }

    /** Material 根键大小写不应导致补全器追加重复默认规则。 */
    @Test
    public void lowercaseMaterialRuleIsPreservedWithoutUppercaseDuplicate() throws Exception {
        File folder = temporaryFolder.newFolder();
        write(new File(folder, "item-stacking.yml"), "enabled: true\n");
        write(new File(folder, "item-stacking-items.yml"),
                "stone:\n  enabled: false\n  max-stack-size: 45\n  display-name: '小写石头'\n");

        ItemStackingConfig config = ItemStackingConfigurationLoader.load(plugin(folder));
        String text = normalized(read(new File(folder, "item-stacking-items.yml")));

        assertFalse(config.getItemRule("STONE").isEnabled());
        assertEquals(45, config.getItemRule("STONE").getMaxStackSize());
        assertEquals("小写石头", config.getItemRule("STONE").getDisplayName());
        assertEquals(0, occurrences("\n" + text, "\nSTONE:\n"));
    }

    /** 格式损坏时必须保留原文件，不得在后面继续追加大量条目。 */
    @Test
    public void malformedItemFileIsNeverModified() throws Exception {
        File folder = temporaryFolder.newFolder();
        write(new File(folder, "item-stacking.yml"), "enabled: true\n");
        File items = new File(folder, "item-stacking-items.yml");
        String malformed = "STONE: [broken\n";
        write(items, malformed);

        ItemStackingConfig config = ItemStackingConfigurationLoader.load(plugin(folder));

        assertTrue(config.isFeatureEnabled());
        assertEquals(malformed, read(items));
    }

    /** 创建只提供数据目录和日志器的轻量 Plugin 代理。 */
    private Plugin plugin(final File folder) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> {
                    if ("getDataFolder".equals(method.getName())) {
                        return folder;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("ItemStackingConfigurationLoaderTest");
                    }
                    if ("getName".equals(method.getName())) {
                        return "WorldListTrashCan";
                    }
                    if ("toString".equals(method.getName())) {
                        return "ItemStackingConfigurationLoaderTestPlugin";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 返回代理方法所需的基本类型默认值。 */
    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class || type == double.class) {
            return Double.valueOf(0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    /** 以 UTF-8 写入测试配置。 */
    private void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** 以 UTF-8 读取完整测试配置。 */
    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 把平台换行统一为 LF，避免 Windows 和 Linux 测试结果不同。 */
    private String normalized(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 统计完整文本中某个片段的出现次数。 */
    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
