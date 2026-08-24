package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import org.bukkit.plugin.Plugin;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证旧 API 运行时会被能力门禁明确拒绝。 */
public final class ItemStackingCapabilityProbeLegacyTest {
    /** 1.12 API 不具备 PDC，不能启用逻辑堆叠。 */
    @Test
    public void legacyApiIsRejectedWithoutPersistentDataCapabilities() {
        ItemStackingCapabilityProbe.Result result = new ItemStackingCapabilityProbe()
                .probe(pluginProxy(), false);

        assertFalse(result.isSupported());
        assertTrue(result.getMissing().contains("class:org.bukkit.persistence.PersistentDataHolder"));
    }

    /** 创建只用于提供测试类加载器的最小插件代理。 */
    private Plugin pluginProxy() {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    /** 返回基本类型方法的合法默认值。 */
    private Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE || type == Short.TYPE || type == Byte.TYPE || type == Long.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Float.TYPE || type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
