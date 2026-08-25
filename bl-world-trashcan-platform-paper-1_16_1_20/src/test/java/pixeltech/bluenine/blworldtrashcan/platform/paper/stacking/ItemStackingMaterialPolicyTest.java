package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.config.ConfigurationSource;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证热路径 ordinal 策略快照的逐材质语义。 */
public final class ItemStackingMaterialPolicyTest {
    /** 数组快照应保留禁用、独立上限、继承上限和显示名。 */
    @Test
    public void policyCompilesIndependentRulesToOrdinalArrays() {
        MapSource config = new MapSource();
        config.put("stack.max-logical-amount", 5000);
        config.put("items.STONE.enabled", false);
        config.put("items.STONE.max-stack-size", 88);
        config.put("items.STONE.display-name", "独立石头");
        config.put("items.DIRT.max-stack-size", -1);

        ItemStackingMaterialPolicy policy = ItemStackingMaterialPolicy.from(
                ItemStackingConfig.load(config));

        assertFalse(policy.isEnabled(Material.STONE));
        assertEquals(88, policy.maximumAmount(Material.STONE));
        assertEquals("独立石头", policy.displayName(Material.STONE));
        assertTrue(policy.isEnabled(Material.DIRT));
        assertEquals(5000, policy.maximumAmount(Material.DIRT));
        assertEquals(null, policy.displayName(Material.DIRT));
    }

    /** 仅供策略测试使用的轻量配置来源。 */
    private static final class MapSource implements ConfigurationSource {
        private final Map<String, Object> values = new HashMap<>();

        /** 写入测试值。 */
        private void put(String path, Object value) {
            values.put(path, value);
        }

        /** 判断路径是否存在。 */
        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }

        /** 测试来源不提供列表类型。 */
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

        /** 返回指定节点下的第一层测试键。 */
        @Override
        public Set<String> getKeys(String path) {
            String prefix = path == null || path.isEmpty() ? "" : path + ".";
            Set<String> keys = new LinkedHashSet<>();
            for (String key : values.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String remainder = key.substring(prefix.length());
                int separator = remainder.indexOf('.');
                keys.add(separator < 0 ? remainder : remainder.substring(0, separator));
            }
            return keys;
        }
    }
}
