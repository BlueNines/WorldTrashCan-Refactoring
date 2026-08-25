package pixeltech.bluenine.blworldtrashcan.config;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证掉落物逻辑堆叠独立配置的默认值和边界。 */
public final class ItemStackingConfigTest {
    /** 缺少独立配置文件时应使用低占用默认值。 */
    @Test
    public void missingValuesUseDocumentedDefaults() {
        ItemStackingConfig config = ItemStackingConfig.load(new MapConfigurationSource());

        assertEquals(10000, config.getMaxLogicalAmount());
        assertEquals(3.0D, config.getHorizontalRadius(), 0.0001D);
        assertEquals(1.5D, config.getVerticalRadius(), 0.0001D);
        assertEquals(5, config.getProcessIntervalTicks());
        assertEquals(10, config.getMergeDelayTicks());
        assertEquals(8, config.getMaxChunksPerRun());
        assertEquals(256, config.getMaxItemsPerChunk());
        assertEquals(1500, config.getTimeBudgetMicros());
        assertEquals(4096, config.getMaxQueuedChunks());
        assertEquals(30, config.getQueueTtlSeconds());
        assertTrue(config.isDisplayNameEnabled());
        assertEquals("zh_CN", config.getDisplayNameLocale());
        assertTrue(config.getItemRules().isEmpty());
    }

    /** 极端配置必须被收敛，避免无限队列或单轮无界扫描。 */
    @Test
    public void extremeValuesAreClamped() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("stack.max-logical-amount", -20);
        source.put("merge.horizontal-radius", 100.0D);
        source.put("merge.vertical-radius", 0.0D);
        source.put("scheduler.process-interval-ticks", 0);
        source.put("scheduler.merge-delay-ticks", 5000);
        source.put("scheduler.max-chunks-per-run", 5000);
        source.put("scheduler.max-items-per-chunk", 50000);
        source.put("scheduler.time-budget-micros", 1);
        source.put("scheduler.max-queued-chunks", 1);
        source.put("scheduler.queue-ttl-seconds", 5000);

        ItemStackingConfig config = ItemStackingConfig.load(source);

        assertEquals(2, config.getMaxLogicalAmount());
        assertEquals(16.0D, config.getHorizontalRadius(), 0.0001D);
        assertEquals(0.1D, config.getVerticalRadius(), 0.0001D);
        assertEquals(1, config.getProcessIntervalTicks());
        assertEquals(1200, config.getMergeDelayTicks());
        assertEquals(1024, config.getMaxChunksPerRun());
        assertEquals(10000, config.getMaxItemsPerChunk());
        assertEquals(100, config.getTimeBudgetMicros());
        assertEquals(16, config.getMaxQueuedChunks());
        assertEquals(3600, config.getQueueTtlSeconds());
    }

    /** 语言配置只属于堆叠悬浮名称，旧 overrides 节点必须彻底忽略。 */
    @Test
    public void displayNameLocaleLoadsWithoutLegacyOverrides() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("display.custom-name.locale", "ja-jp");
        Map<String, String> overrides = new HashMap<>();
        overrides.put(" Minecraft:Stone ", "特製石");
        source.put("display.custom-name.overrides", overrides);

        ItemStackingConfig config = ItemStackingConfig.load(source);

        assertEquals("ja_JP", config.getDisplayNameLocale());
        assertTrue(config.getItemRules().isEmpty());
    }

    /** 逐 Material 配置应支持禁用、继承上限、独立上限和独立显示名。 */
    @Test
    public void independentItemRulesLoadAllSupportedFields() {
        MapConfigurationSource global = new MapConfigurationSource();
        global.put("stack.max-logical-amount", 10000);
        MapConfigurationSource items = new MapConfigurationSource();
        items.put("STONE.enabled", false);
        items.put("STONE.max-stack-size", -1);
        items.put("STONE.display-name", "自定义石头");
        items.put("DIRT.enabled", true);
        items.put("DIRT.max-stack-size", 320);
        items.put("DIRT.display-name", "default");

        ItemStackingConfig config = ItemStackingConfig.load(global, items);

        ItemStackingConfig.ItemRule stone = config.getItemRule("stone");
        assertFalse(stone.isEnabled());
        assertEquals(10000, stone.getMaxStackSize());
        assertEquals("自定义石头", stone.getDisplayName());
        ItemStackingConfig.ItemRule dirt = config.getItemRule("DIRT");
        assertTrue(dirt.isEnabled());
        assertEquals(320, dirt.getMaxStackSize());
        assertEquals(null, dirt.getDisplayName());
    }

    /** 缺少逐物品条目时应继承全局启用状态和数量上限。 */
    @Test
    public void missingItemRuleInheritsGlobalDefaults() {
        MapConfigurationSource global = new MapConfigurationSource();
        global.put("stack.max-logical-amount", 4567);

        ItemStackingConfig.ItemRule rule = ItemStackingConfig.load(global,
                new MapConfigurationSource()).getItemRule("NEW_VERSION_ITEM");

        assertTrue(rule.isEnabled());
        assertEquals(4567, rule.getMaxStackSize());
        assertEquals(null, rule.getDisplayName());
    }

    /** 总开关只读取独立配置根节点，不再兼容 config.yml 旧路径。 */
    @Test
    public void enableSwitchComesOnlyFromIndependentConfiguration() {
        MapConfigurationSource main = new MapConfigurationSource();
        MapConfigurationSource empty = new MapConfigurationSource();
        MapConfigurationSource itemStacking = new MapConfigurationSource();
        main.put("features.item-stacking.enabled", Boolean.TRUE);

        ConfigBundle disabled = new ConfigBundleLoader().load(
                main, empty, empty, empty, empty, itemStacking, true);
        assertFalse(disabled.isItemStackingEnabled());

        itemStacking.put("enabled", Boolean.TRUE);
        ConfigBundle enabled = new ConfigBundleLoader().load(
                main, empty, empty, empty, empty, itemStacking, true);
        assertTrue(enabled.isItemStackingEnabled());
    }

    /** 仅供本测试使用的轻量配置来源。 */
    private static final class MapConfigurationSource implements ConfigurationSource {
        private final Map<String, Object> values = new HashMap<>();

        /** 写入测试配置值。 */
        private void put(String path, Object value) {
            values.put(path, value);
        }

        /** 判断路径是否存在。 */
        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }

        /** 测试来源不提供列表。 */
        @Override
        public boolean isList(String path) {
            return false;
        }

        /** 读取字符串。 */
        @Override
        public String getString(String path, String fallback) {
            Object value = values.get(path);
            return value == null ? fallback : String.valueOf(value);
        }

        /** 读取布尔值。 */
        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object value = values.get(path);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
        }

        /** 读取整数。 */
        @Override
        public int getInt(String path, int fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        /** 读取小数。 */
        @Override
        public double getDouble(String path, double fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        /** 返回空字符串列表。 */
        @Override
        public List<String> getStringList(String path) {
            return Collections.emptyList();
        }

        /** 返回空映射列表。 */
        @Override
        public List<Map<?, ?>> getMapList(String path) {
            return Collections.emptyList();
        }

        /** 返回测试值中指定节点的第一层键。 */
        @Override
        public Set<String> getKeys(String path) {
            String prefix = path == null || path.isEmpty() ? "" : path + ".";
            Set<String> keys = new LinkedHashSet<>();
            for (String key : values.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String remaining = key.substring(prefix.length());
                int separator = remaining.indexOf('.');
                keys.add(separator < 0 ? remaining : remaining.substring(0, separator));
            }
            return keys;
        }
    }
}
