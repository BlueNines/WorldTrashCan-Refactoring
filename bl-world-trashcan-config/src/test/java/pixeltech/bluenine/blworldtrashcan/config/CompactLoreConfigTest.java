package pixeltech.bluenine.blworldtrashcan.config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 验证紧凑模式完整 Lore 模板及旧配置兼容。 */
public final class CompactLoreConfigTest {
    /** 验证新 item-lore 列表完整覆盖旧数量和操作节点。 */
    @Test
    public void explicitItemLoreControlsCompleteOrder() {
        MapConfigurationSource trash = new MapConfigurationSource();
        List<String> template = Arrays.asList("操作顶部", "{content}", "数量 {amount}");
        trash.put("global-trash.compact.item-lore", template);
        trash.put("global-trash.compact.amount-lore", "旧数量");
        trash.put("global-trash.compact.action-lore", Collections.singletonList("旧操作"));

        TrashConfig.CompactGlobalTrashConfig compact = load(trash)
                .getTrashConfig().getGlobalTrash().getCompact();

        assertEquals(template, compact.getItemLore());
    }

    /** 验证显式空列表表示不显示任何 Lore。 */
    @Test
    public void explicitEmptyItemLoreRemainsEmpty() {
        MapConfigurationSource trash = new MapConfigurationSource();
        trash.put("personal-trash.compact.item-lore", Collections.emptyList());

        TrashConfig.CompactGlobalTrashConfig compact = load(trash)
                .getTrashConfig().getPersonalTrash().getCompact();

        assertEquals(Collections.emptyList(), compact.getItemLore());
    }

    /** 验证单行字符串也会被兼容为完整模板的一行。 */
    @Test
    public void scalarItemLoreBecomesSingleLineTemplate() {
        MapConfigurationSource trash = new MapConfigurationSource();
        trash.put("global-trash.compact.item-lore", "只有 {amount}");

        TrashConfig.CompactGlobalTrashConfig compact = load(trash)
                .getTrashConfig().getGlobalTrash().getCompact();

        assertEquals(Collections.singletonList("只有 {amount}"), compact.getItemLore());
    }

    /** 验证缺少新节点时按旧开关、数量模板和操作模板合成原布局。 */
    @Test
    public void missingItemLoreComposesLegacySettings() {
        MapConfigurationSource trash = new MapConfigurationSource();
        trash.put("global-trash.compact.show-amount-lore", false);
        trash.put("global-trash.compact.amount-lore", "不应出现");
        trash.put("global-trash.compact.action-lore", Arrays.asList("操作一", "操作二"));

        TrashConfig.CompactGlobalTrashConfig compact = load(trash)
                .getTrashConfig().getGlobalTrash().getCompact();

        assertEquals(Arrays.asList("{content}", "操作一", "操作二"), compact.getItemLore());
    }

    /** 使用指定 trash.yml 配置加载完整配置集合。 */
    private ConfigBundle load(ConfigurationSource trash) {
        ConfigurationSource empty = new MapConfigurationSource();
        return new ConfigBundleLoader().load(empty, empty, trash, empty, empty);
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

        /** 判断路径值是否为列表。 */
        @Override
        public boolean isList(String path) {
            return values.get(path) instanceof List<?>;
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
