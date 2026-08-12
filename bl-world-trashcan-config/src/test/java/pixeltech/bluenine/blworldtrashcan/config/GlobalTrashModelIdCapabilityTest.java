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

/** 验证 model-id 只在运行时支持 CustomModelData 时解析。 */
public final class GlobalTrashModelIdCapabilityTest {
    /** 验证不支持能力时完全不读取布局和旧版 model-id 路径。 */
    @Test
    public void unsupportedRuntimeDoesNotReadAnyModelIdPath() {
        TrackingConfigurationSource trash = customLayoutSource();
        ConfigBundle bundle = new ConfigBundleLoader().load(
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), trash,
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), false);

        TrashConfig.GlobalTrashLayoutConfig layout = bundle.getTrashConfig().getGlobalTrash().getLayout();
        assertEquals(-1, layout.getItem('c').getModelId());
        assertFalse(trash.hasReadModelId());
    }

    /** 验证支持能力时读取布局物品的 model-id。 */
    @Test
    public void supportedRuntimeReadsLayoutModelId() {
        TrackingConfigurationSource trash = customLayoutSource();
        ConfigBundle bundle = new ConfigBundleLoader().load(
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), trash,
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), true);

        TrashConfig.GlobalTrashLayoutConfig layout = bundle.getTrashConfig().getGlobalTrash().getLayout();
        assertEquals(30538, layout.getItem('c').getModelId());
        assertTrue(trash.hasReadPath("global-trash.gui.layout.items.c.model-id"));
    }

    /** 验证不支持能力时缺少新布局也不会读取旧模型字段。 */
    @Test
    public void unsupportedRuntimeSkipsLegacyModelIdFallbacks() {
        TrackingConfigurationSource trash = new TrackingConfigurationSource();
        trash.put("global-trash.gui.back-model-id", Integer.valueOf(101));
        trash.put("global-trash.gui.next-model-id", Integer.valueOf(102));
        trash.put("global-trash.gui.background-model-id", Integer.valueOf(103));

        ConfigBundle bundle = new ConfigBundleLoader().load(
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), trash,
                new TrackingConfigurationSource(), new TrackingConfigurationSource(), false);

        TrashConfig.GlobalTrashLayoutConfig layout = bundle.getTrashConfig().getGlobalTrash().getLayout();
        assertEquals(-1, layout.getItem('a').getModelId());
        assertEquals(-1, layout.getItem('b').getModelId());
        assertEquals(-1, layout.getItem('c').getModelId());
        assertFalse(trash.hasReadModelId());
    }

    /** 创建带下一页模型编号的最小有效布局。 */
    private TrackingConfigurationSource customLayoutSource() {
        TrackingConfigurationSource source = new TrackingConfigurationSource();
        source.put("global-trash.gui.layout.position", Collections.singletonList("xxxxxxxbc"));
        source.put("global-trash.gui.layout.items.x.type", "content");
        source.put("global-trash.gui.layout.items.b.type", "background");
        source.put("global-trash.gui.layout.items.c.type", "next-page");
        source.put("global-trash.gui.layout.items.c.model-id", Integer.valueOf(30538));
        source.put("global-trash.gui.layout.items.c.material", Arrays.asList("PAPER", "ARROW"));
        source.put("global-trash.gui.layout.items.c.unavailable-item", "b");
        return source;
    }

    /** 记录读取路径的最小配置来源。 */
    private static final class TrackingConfigurationSource implements ConfigurationSource {
        private final Map<String, Object> values = new HashMap<>();
        private final List<String> readPaths = new ArrayList<>();

        /** 写入测试值。 */
        private void put(String path, Object value) {
            values.put(path, value);
        }

        /** 返回是否读取过任意模型编号路径。 */
        private boolean hasReadModelId() {
            for (String path : readPaths) {
                if (path.endsWith("model-id")) {
                    return true;
                }
            }
            return false;
        }

        /** 返回是否读取过指定路径。 */
        private boolean hasReadPath(String expected) {
            return readPaths.contains(expected);
        }

        /** 判断路径是否存在。 */
        @Override
        public boolean contains(String path) {
            readPaths.add(path);
            return values.containsKey(path);
        }

        /** 读取字符串。 */
        @Override
        public String getString(String path, String fallback) {
            readPaths.add(path);
            Object value = values.get(path);
            return value == null ? fallback : String.valueOf(value);
        }

        /** 读取布尔值。 */
        @Override
        public boolean getBoolean(String path, boolean fallback) {
            readPaths.add(path);
            Object value = values.get(path);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
        }

        /** 读取整数。 */
        @Override
        public int getInt(String path, int fallback) {
            readPaths.add(path);
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        /** 读取小数。 */
        @Override
        public double getDouble(String path, double fallback) {
            readPaths.add(path);
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        /** 读取字符串列表。 */
        @Override
        public List<String> getStringList(String path) {
            readPaths.add(path);
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
            readPaths.add(path);
            return Collections.emptyList();
        }
    }
}
