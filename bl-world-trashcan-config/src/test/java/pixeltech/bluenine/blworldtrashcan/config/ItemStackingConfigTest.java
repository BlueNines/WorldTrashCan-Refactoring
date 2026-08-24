package pixeltech.bluenine.blworldtrashcan.config;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        assertTrue(config.getDisplayNameOverrides().isEmpty());
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

    /** 语言配置只属于堆叠悬浮名称，覆盖项应被复制并规范化键名。 */
    @Test
    public void displayNameLocaleAndOverridesAreLoaded() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("display.custom-name.locale", "ja-jp");
        Map<String, String> overrides = new HashMap<>();
        overrides.put(" Minecraft:Stone ", "特製石");
        source.put("display.custom-name.overrides", overrides);

        ItemStackingConfig config = ItemStackingConfig.load(source);

        assertEquals("ja_JP", config.getDisplayNameLocale());
        assertEquals("特製石", config.getDisplayNameOverrides().get("minecraft:stone"));
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

        /** 读取本测试用的字符串映射。 */
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, String> getStringMap(String path) {
            Object value = values.get(path);
            return value instanceof Map ? (Map<String, String>) value : Collections.<String, String>emptyMap();
        }
    }
}
