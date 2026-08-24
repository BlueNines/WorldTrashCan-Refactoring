package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.plugin.Plugin;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.stacking.ItemStackingCapabilityProbe;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertTrue;

/** 验证现代 API 能力组合可以通过探测。 */
public final class ItemStackingCapabilityProbeModernTest {
    /** 现代 Paper API 具备普通端逻辑堆叠所需能力。 */
    @Test
    public void modernApiSupportsNormalRuntimeCapabilities() {
        ItemStackingCapabilityProbe.Result result = new ItemStackingCapabilityProbe()
                .probe(pluginProxy(), false);

        assertTrue(result.getMissing().toString(), result.isSupported());
    }

    /** 现代 Paper API 暴露 Folia 调度契约所需类型。 */
    @Test
    public void modernApiExposesFoliaSchedulerContracts() {
        ItemStackingCapabilityProbe.Result result = new ItemStackingCapabilityProbe()
                .probe(pluginProxy(), true);

        assertTrue(result.getMissing().toString(), result.isSupported());
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
