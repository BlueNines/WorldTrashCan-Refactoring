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

/** 验证个人垃圾桶通用容器配置的默认值和独立布局解析。 */
public final class PersonalTrashConfigTest {
    /** 验证旧配置缺少新节点时使用紧凑模式，并默认拒绝满桶自动清空。 */
    @Test
    public void missingContainerSettingsUseSafePersonalDefaults() {
        TrashConfig.PersonalTrashConfig config = load(new MapConfigurationSource())
                .getTrashConfig().getPersonalTrash();

        assertTrue(config.isEnabled());
        assertEquals(TrashConfig.GlobalTrashMode.COMPACT, config.getMode());
        assertEquals(2, config.getCompact().getMaxPages());
        assertEquals(9999L, config.getCompact().getMaxAmountPerEntry());
        assertEquals(TrashConfig.GlobalTrashSortType.INSERTION,
                config.getCompact().getDefaultSort());
        assertEquals(2, config.getStacked().getMaxPages());
        assertEquals(0, config.getTakeDelayMillis());
        assertTrue(config.isAllowPlayerPut());
        assertFalse(config.isAutoClearWhenFull());
        assertFalse(config.getLayout().getContentSlots().isEmpty());
    }

    /** 验证个人桶读取自己的模式、容量、排序、布局和专属策略，不复用公共桶节点。 */
    @Test
    public void explicitPersonalContainerSettingsLoadIndependently() {
        MapConfigurationSource trash = new MapConfigurationSource();
        trash.put("global-trash.mode", "compact");
        trash.put("global-trash.compact.max-pages", 6);
        trash.put("personal-trash.mode", "stacked");
        trash.put("personal-trash.take-delay-millis", 250);
        trash.put("personal-trash.allow-player-put", false);
        trash.put("personal-trash.auto-clear-when-full", true);
        trash.put("personal-trash.compact.max-pages", 3);
        trash.put("personal-trash.compact.max-amount-per-entry", 1234);
        trash.put("personal-trash.compact.default-sort", "amount-desc");
        trash.put("personal-trash.stacked.max-pages", 4);
        trash.put("personal-trash.stacked.default-sort", "material-asc");
        trash.put("personal-trash.gui.layout.position",
                Arrays.asList("xxxxxxxxx", "pxxxsxxxc"));
        trash.put("personal-trash.gui.layout.items.x.type", "content");
        trash.put("personal-trash.gui.layout.items.p.type", "previous-page");
        trash.put("personal-trash.gui.layout.items.s.type", "sort");
        trash.put("personal-trash.gui.layout.items.s.glow", true);
        trash.put("personal-trash.gui.layout.items.c.type", "close");
        trash.put("personal-trash.gui.layout.items.c.name", "关闭个人桶");
        trash.put("personal-trash.gui.layout.items.c.lore",
                Collections.singletonList("点击关闭"));

        TrashConfig.PersonalTrashConfig config = load(trash)
                .getTrashConfig().getPersonalTrash();

        assertEquals(TrashConfig.GlobalTrashMode.STACKED, config.getMode());
        assertEquals(4, config.getMaxPages());
        assertEquals(3, config.getCompact().getMaxPages());
        assertEquals(1234L, config.getCompact().getMaxAmountPerEntry());
        assertEquals(TrashConfig.GlobalTrashSortType.AMOUNT_DESC,
                config.getCompact().getDefaultSort());
        assertEquals(TrashConfig.GlobalTrashSortType.MATERIAL_ASC,
                config.getStacked().getDefaultSort());
        assertEquals(250, config.getTakeDelayMillis());
        assertFalse(config.isAllowPlayerPut());
        assertTrue(config.isAutoClearWhenFull());
        assertEquals(2, config.getLayout().getRows().size());
        assertEquals(15, config.getLayout().getContentSlots().size());
        assertEquals(TrashConfig.GlobalTrashItemType.SORT,
                config.getLayout().getItem('s').getType());
        assertTrue(config.getLayout().getItem('s').isGlow());
        assertEquals(TrashConfig.GlobalTrashItemType.CLOSE,
                config.getLayout().getItem('c').getType());
        assertEquals("关闭个人桶", config.getLayout().getItem('c').getName());
        assertEquals(Collections.singletonList("点击关闭"),
                config.getLayout().getItem('c').getLore());
    }

    /** 使用指定 trash.yml 配置和其它空配置加载完整配置集合。 */
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
