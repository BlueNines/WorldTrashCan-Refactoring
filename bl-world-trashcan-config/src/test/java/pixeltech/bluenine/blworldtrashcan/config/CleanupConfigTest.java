package pixeltech.bluenine.blworldtrashcan.config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证扫地世界列表的配置解析与匹配语义。 */
public final class CleanupConfigTest {
    /** 验证旧配置缺少强制直删列表时保持默认关闭。 */
    @Test
    public void missingDirectRemoveWorldsDefaultsToEmpty() {
        CleanupConfig config = load(new MapConfigurationSource()).getCleanupConfig();

        assertFalse(config.isDirectRemoveWorld("world"));
        assertFalse(config.isDirectRemoveWorld(null));
    }

    /** 验证强制直删世界名忽略大小写和首尾空格。 */
    @Test
    public void directRemoveWorldsAreNormalized() {
        MapConfigurationSource cleanup = new MapConfigurationSource();
        cleanup.put("direct-remove-worlds", Arrays.asList(" world_nether ", "MiningWorld"));
        cleanup.put("ignored-worlds", Collections.singletonList("ignored_world"));

        CleanupConfig config = load(cleanup).getCleanupConfig();

        assertTrue(config.isDirectRemoveWorld("WORLD_NETHER"));
        assertTrue(config.isDirectRemoveWorld(" miningworld "));
        assertFalse(config.isDirectRemoveWorld("ignored_world"));
        assertTrue(config.isIgnoredWorld("IGNORED_WORLD"));
        assertFalse(config.isIgnoredWorld("world_nether"));
    }

    /** 验证旧配置缺少移动物品保护项时默认关闭。 */
    @Test
    public void missingMovingItemsConfigDefaultsToDisabled() {
        CleanupConfig.MovingItemConfig movingItems = load(new MapConfigurationSource())
                .getCleanupConfig().getMovingItems();

        assertFalse(movingItems.isEnabled());
        assertEquals(0.01D, movingItems.getMinimumSpeed(), 0D);
        assertFalse(movingItems.isMoving(100D));
    }

    /** 验证开启后按速度平方边界判断移动。 */
    @Test
    public void movingItemsUseConfiguredSpeedThreshold() {
        MapConfigurationSource cleanup = new MapConfigurationSource();
        cleanup.put("moving-items.enabled", true);
        cleanup.put("moving-items.minimum-speed", 0.05D);

        CleanupConfig.MovingItemConfig movingItems = load(cleanup).getCleanupConfig().getMovingItems();

        assertTrue(movingItems.isEnabled());
        assertFalse(movingItems.isMoving(0.00249D));
        assertTrue(movingItems.isMoving(0.00251D));
    }

    /** 验证非正数和非有限阈值不会让浮点抖动全部命中。 */
    @Test
    public void invalidMovingSpeedFallsBackToSafeDefault() {
        CleanupConfig.MovingItemConfig zero = new CleanupConfig.MovingItemConfig(true, 0D);
        CleanupConfig.MovingItemConfig nan = new CleanupConfig.MovingItemConfig(true, Double.NaN);

        assertEquals(0.01D, zero.getMinimumSpeed(), 0D);
        assertEquals(0.01D, nan.getMinimumSpeed(), 0D);
        assertFalse(zero.isMoving(0.000099D));
        assertTrue(zero.isMoving(0.0001D));
    }

    /** 验证旧配置缺少潜影盒保护项时默认关闭。 */
    @Test
    public void missingFilledShulkerBoxesConfigDefaultsToDisabled() {
        CleanupConfig.FilledShulkerBoxConfig filledShulkerBoxes = load(new MapConfigurationSource())
                .getCleanupConfig().getFilledShulkerBoxes();

        assertFalse(filledShulkerBoxes.isEnabled());
    }

    /** 验证潜影盒保护配置可以在 reload 后开启。 */
    @Test
    public void filledShulkerBoxesUseConfiguredToggle() {
        MapConfigurationSource cleanup = new MapConfigurationSource();
        cleanup.put("filled-shulker-boxes.enabled", true);

        assertTrue(load(cleanup).getCleanupConfig().getFilledShulkerBoxes().isEnabled());
    }

    /** 使用空配置补齐其他配置源并加载完整配置。 */
    private ConfigBundle load(ConfigurationSource cleanup) {
        ConfigurationSource empty = new MapConfigurationSource();
        return new ConfigBundleLoader().load(empty, cleanup, empty, empty, empty);
    }

    /** 基于 Map 的最小配置来源。 */
    private static final class MapConfigurationSource implements ConfigurationSource {
        private final Map<String, Object> values = new HashMap<>();

        /** 写入测试配置。 */
        private void put(String path, Object value) {
            values.put(path, value);
        }

        /** 判断路径是否存在。 */
        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
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

        /** 读取字符串列表。 */
        @Override
        public List<String> getStringList(String path) {
            Object value = values.get(path);
            if (!(value instanceof List<?>)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                result.add(String.valueOf(entry));
            }
            return result;
        }

        /** 读取映射列表。 */
        @Override
        public List<Map<?, ?>> getMapList(String path) {
            return Collections.emptyList();
        }
    }
}
