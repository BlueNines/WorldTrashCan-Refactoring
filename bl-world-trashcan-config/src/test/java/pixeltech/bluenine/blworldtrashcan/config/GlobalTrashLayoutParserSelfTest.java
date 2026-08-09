package pixeltech.bluenine.blworldtrashcan.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 不依赖 Bukkit 和 JUnit 的公共垃圾桶布局解析自测。 */
public final class GlobalTrashLayoutParserSelfTest {
    /** 执行布局解析自测。 */
    public static void main(String[] args) {
        verifyLegacyFallback();
        verifyCustomLayout();
        verifyInvalidRowsFallback();
        verifyUnavailableCycleFallback();
        System.out.println("GlobalTrashLayoutParserSelfTest passed");
    }

    /** 验证缺少新布局时保留旧固定布局和 ModelId。 */
    private static void verifyLegacyFallback() {
        TrashConfig.GlobalTrashLayoutConfig layout = new GlobalTrashLayoutParser().parse(
                new MapConfigurationSource(), 101, 102, 103);
        assertEquals("legacy size", 54, layout.getInventorySize());
        assertEquals("legacy content slots", 45, layout.getContentSlots().size());
        assertEquals("legacy back model", 101, layout.getItem('a').getModelId());
        assertEquals("legacy next model", 102, layout.getItem('c').getModelId());
        assertEquals("legacy background model", 103, layout.getItem('b').getModelId());
        assertNull("legacy validation", layout.getValidationError());
    }

    /** 验证自定义行数、内容槽、名称、Lore 和材质候选。 */
    private static void verifyCustomLayout() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("global-trash.gui.layout.position", Arrays.asList(
                "xxxxxxxxx", "xxxxxxxxx", "abbsdbbbc"));
        source.put("global-trash.gui.layout.items.x.type", "content");
        source.put("global-trash.gui.layout.items.a.type", "previous-page");
        source.put("global-trash.gui.layout.items.a.model-id", Integer.valueOf(77));
        source.put("global-trash.gui.layout.items.a.material", Arrays.asList("NEW_ARROW", "ARROW"));
        source.put("global-trash.gui.layout.items.a.name", "&#5AC8FA上一页 {page}");
        source.put("global-trash.gui.layout.items.a.lore", Arrays.asList("第 {page} 页", "共 {max-page} 页"));
        source.put("global-trash.gui.layout.items.a.unavailable-item", "b");
        source.put("global-trash.gui.layout.items.b.type", "background");
        source.put("global-trash.gui.layout.items.b.material", Collections.singletonList("STONE"));
        source.put("global-trash.gui.layout.items.b.name", "");
        source.put("global-trash.gui.layout.items.c.type", "next-page");
        source.put("global-trash.gui.layout.items.c.material", Collections.singletonList("ARROW"));
        source.put("global-trash.gui.layout.items.c.unavailable-item", "b");
        source.put("global-trash.gui.layout.items.d.type", "actions");
        source.put("global-trash.gui.layout.items.d.material", Collections.singletonList("BOOK"));
        source.put("global-trash.gui.layout.items.d.name", "&e统计 {player}");
        source.put("global-trash.gui.layout.items.d.actions", Arrays.asList(
                "[message] &a第 {page} 页", "[command] wtc stats"));
        source.put("global-trash.gui.layout.items.s.type", "sort");
        source.put("global-trash.gui.layout.items.s.material", Arrays.asList(
                "COMPARATOR", "REDSTONE_COMPARATOR"));

        TrashConfig.GlobalTrashLayoutConfig layout = new GlobalTrashLayoutParser().parse(source, -1, -1, -1);
        assertEquals("custom size", 27, layout.getInventorySize());
        assertEquals("custom content slots", 18, layout.getContentSlots().size());
        assertTrue("custom first content slot", layout.isContentSlot(0));
        assertFalse("custom previous page slot", layout.isContentSlot(18));
        assertFalse("custom out of range slot", layout.isContentSlot(27));
        assertEquals("custom compiled slot item", "previous-page",
                layout.getItemAt(18).getType().name().toLowerCase().replace('_', '-'));
        assertEquals("custom model", 77, layout.getItem('a').getModelId());
        assertEquals("custom material candidates", 2, layout.getItem('a').getMaterials().size());
        assertEquals("custom lore", 2, layout.getItem('a').getLore().size());
        assertEquals("custom actions type", "actions",
                layout.getItem('d').getType().name().toLowerCase());
        assertEquals("custom actions", 2, layout.getItem('d').getActions().size());
        assertEquals("custom sort type", "sort", layout.getItem('s').getType().name().toLowerCase());
        assertEquals("custom sort materials", 2, layout.getItem('s').getMaterials().size());
        assertEquals("empty name override", "", layout.getItem('b').getName());
        assertNull("custom validation", layout.getValidationError());
    }

    /** 验证七行布局不会生成超过原版箱子上限的 GUI。 */
    private static void verifyInvalidRowsFallback() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("global-trash.gui.layout.position", Arrays.asList(
                "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx",
                "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx"));
        source.put("global-trash.gui.layout.items.x.type", "content");
        TrashConfig.GlobalTrashLayoutConfig layout = new GlobalTrashLayoutParser().parse(source, -1, -1, -1);
        assertEquals("invalid rows fallback size", 54, layout.getInventorySize());
        assertContains("invalid rows message", layout.getValidationError(), "只能配置 1-6 行");
    }

    /** 验证不可用展示物循环引用会回退默认布局。 */
    private static void verifyUnavailableCycleFallback() {
        MapConfigurationSource source = new MapConfigurationSource();
        source.put("global-trash.gui.layout.position", Collections.singletonList("xxxxxxxac"));
        source.put("global-trash.gui.layout.items.x.type", "content");
        source.put("global-trash.gui.layout.items.a.type", "previous-page");
        source.put("global-trash.gui.layout.items.a.unavailable-item", "c");
        source.put("global-trash.gui.layout.items.c.type", "next-page");
        source.put("global-trash.gui.layout.items.c.unavailable-item", "a");
        TrashConfig.GlobalTrashLayoutConfig layout = new GlobalTrashLayoutParser().parse(source, -1, -1, -1);
        assertContains("cycle message", layout.getValidationError(), "形成循环");
    }

    /** 断言整数相等。 */
    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException(label + " expected " + expected + " but got " + actual);
        }
    }

    /** 断言字符串相等。 */
    private static void assertEquals(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(label + " expected " + expected + " but got " + actual);
        }
    }

    /** 断言值为空。 */
    private static void assertNull(String label, Object value) {
        if (value != null) {
            throw new IllegalStateException(label + " expected null but got " + value);
        }
    }

    /** 断言文本包含指定片段。 */
    private static void assertContains(String label, String value, String expected) {
        if (value == null || !value.contains(expected)) {
            throw new IllegalStateException(label + " missing " + expected + " in " + value);
        }
    }

    /** 断言条件成立。 */
    private static void assertTrue(String label, boolean value) {
        if (!value) {
            throw new IllegalStateException(label + " expected true");
        }
    }

    /** 断言条件不成立。 */
    private static void assertFalse(String label, boolean value) {
        if (value) {
            throw new IllegalStateException(label + " expected false");
        }
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

    /** 阻止实例化测试类。 */
    private GlobalTrashLayoutParserSelfTest() {
    }
}
