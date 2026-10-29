package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 验证提示块移动、无前置类加载隔离和安全回退。 */
public final class TrashMenuLoreRouterTest {
    /** 动态描述追加在模板后时只移动模板尾部，数量和原文位置不变。 */
    @Test
    public void movesOnlyTemplateTailAfterDynamicLore() {
        List<String> source = Arrays.asList("数量 12", "原始", "左键取 1", "Shift 取 64", "槽位 1", "槽位 2");
        List<String> result = TrashMenuLoreRouter.moveTail(source,
                Arrays.asList("左键取 1", "Shift 取 64"), Function.identity());
        assertEquals(Arrays.asList("数量 12", "原始", "槽位 1", "槽位 2", "左键取 1", "Shift 取 64"), result);
        assertEquals("左键取 1", source.get(2));
        assertNotSame(source, result);
    }

    /** 第三方在头尾添加内容时仍保留原顺序、空行和全部动态描述。 */
    @Test
    public void preservesPrependedAndAppendedLines() {
        List<String> source = Arrays.asList("动态头", "数量", "原始", "", "操作", "动态尾");
        assertEquals(Arrays.asList("动态头", "数量", "原始", "动态尾", "", "操作"),
                TrashMenuLoreRouter.moveTail(source, Arrays.asList("", "操作"), Function.identity()));
    }

    /** 同一个物品包再次经过路由时不复制、不重复追加。 */
    @Test
    public void alreadyOrderedAndRepeatedCallsAreNoOps() {
        List<String> tail = Collections.singletonList("操作");
        List<String> result = TrashMenuLoreRouter.moveTail(Arrays.asList("数量", "操作", "动态"), tail, Function.identity());
        assertSame(result, TrashMenuLoreRouter.moveTail(result, tail, Function.identity()));
    }

    /** 第三方改写或移除提示时不猜测、不重造缺失内容。 */
    @Test
    public void missingPartialAndEmptyTailsDoNotRewrite() {
        List<String> source = Arrays.asList("原始", "操作一", "动态");
        assertSame(source, TrashMenuLoreRouter.moveTail(source,
                Arrays.asList("操作一", "操作二"), Function.identity()));
        assertSame(source, TrashMenuLoreRouter.moveTail(source, Collections.emptyList(), Function.identity()));
        assertSame(source, TrashMenuLoreRouter.moveTail(source,
                Collections.singletonList("其它"), Function.identity()));
    }

    /** 原始描述恰有同文行时优先移动最后一个完整提示块，不删除其它同文行。 */
    @Test
    public void equalOriginalLineIsNotDeleted() {
        List<String> source = Arrays.asList("操作", "原始", "操作", "动态");
        assertEquals(Arrays.asList("操作", "原始", "动态", "操作"),
                TrashMenuLoreRouter.moveTail(source, Collections.singletonList("操作"), Function.identity()));
    }

    /** 整理使用原行对象，不能破坏富文本的字体、悬停或其它元数据。 */
    @Test
    public void preservesOriginalLineObjects() {
        StringBuilder original = new StringBuilder("原始");
        StringBuilder control = new StringBuilder("操作");
        StringBuilder dynamic = new StringBuilder("动态");
        List<StringBuilder> result = TrashMenuLoreRouter.moveTail(Arrays.asList(original, control, dynamic),
                Collections.singletonList("操作"), StringBuilder::toString);
        assertSame(original, result.get(0));
        assertSame(dynamic, result.get(1));
        assertSame(control, result.get(2));
    }

    /** 提示块来自首个内容占位符之后；没有占位符时保持现有模板语义。 */
    @Test
    public void countsOnlyTemplateTail() {
        assertEquals(2, TrashMenuLoreRouter.tailSize(Arrays.asList("数量", "{content}", "操作一", "操作二")));
        assertEquals(2, TrashMenuLoreRouter.tailSize(Arrays.asList("{content}", "操作一", " {content} ", "操作二")));
        assertEquals(0, TrashMenuLoreRouter.tailSize(Arrays.asList("数量", "操作")));
        assertEquals(0, TrashMenuLoreRouter.tailSize(Collections.singletonList("{content}")));
        assertEquals(0, TrashMenuLoreRouter.tailSize(Collections.emptyList()));
    }

    /** 服务器没有前置时，选择路由与其整个生命周期均不得链接 PacketEvents 类。 */
    @Test
    public void absentDependencyDoesNotLoadPacketClasses() throws Exception {
        assertOptionalFallback(null, 0);
    }

    /** 前置存在但未启用时同样不能尝试链接其 API。 */
    @Test
    public void disabledDependencyDoesNotLoadPacketClasses() throws Exception {
        assertOptionalFallback(dependency(false), 0);
    }

    /** 前置声明已启用但实际 API 缺失时，主体仍选择 Bukkit 安全回退。 */
    @Test
    public void incompatibleDependencyFallsBack() throws Exception {
        assertOptionalFallback(dependency(true), -1);
    }

    /** 在拒绝加载全部 PacketEvents 类的隔离类加载器中执行真实选择分支。 */
    private void assertOptionalFallback(Plugin dependency, int expectedLinks) throws Exception {
        AtomicInteger links = new AtomicInteger();
        URL classes = TrashMenuLoreRouter.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, getClass().getClassLoader()) {
            /** 仅重载路由类，并使外部前置在本次测试中真正不可见。 */
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.github.retrooper.packetevents.")
                        || name.startsWith("io.github.retrooper.packetevents.")) {
                    links.incrementAndGet();
                    throw new ClassNotFoundException(name);
                }
                if (name.equals(TrashMenuLoreRouter.class.getName())
                        || name.startsWith("pixeltech.bluenine.blworldtrashcan.bukkit.trash.PacketEventsTrashMenuLoreRouter")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> routeType = Class.forName(TrashMenuLoreRouter.class.getName(), true, loader);
            Method select = accessible(routeType, "select", Plugin.class, String.class);
            Object route = select.invoke(null, owner(dependency), "LoreTest");
            assertFalse((Boolean) accessible(routeType, "isPacketRoute").invoke(route));
            UUID player = UUID.randomUUID();
            Object token = new Object();
            accessible(routeType, "beginView", UUID.class, Object.class, int.class).invoke(route, player, token, 54);
            accessible(routeType, "updateSlot", UUID.class, Object.class, int.class, List.class)
                    .invoke(route, player, token, 0, Collections.singletonList("操作"));
            accessible(routeType, "closeView", UUID.class, Object.class).invoke(route, player, token);
            accessible(routeType, "clearViews").invoke(route);
            accessible(routeType, "close").invoke(route);
            if (expectedLinks == 0) {
                assertEquals(0, links.get());
            } else {
                assertTrue(links.get() > 0);
            }
        }
    }

    /** 获取包级路由方法以测试独立类加载器中的真实实现。 */
    private Method accessible(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    /** 创建仅提供启用状态的外部依赖。 */
    private Plugin dependency(boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> "isEnabled".equals(method.getName()) ? enabled : null);
    }

    /** 创建提供插件管理器和日志的最小宿主，不启动 Bukkit 服务端。 */
    private Plugin owner(Plugin dependency) {
        ClassLoader loader = getClass().getClassLoader();
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(loader, new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> "getPlugin".equals(method.getName()) ? dependency : null);
        Server server = (Server) Proxy.newProxyInstance(loader, new Class<?>[]{Server.class},
                (proxy, method, args) -> "getPluginManager".equals(method.getName()) ? manager : null);
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if ("getServer".equals(method.getName())) {
                return server;
            }
            return "getLogger".equals(method.getName()) ? Logger.getLogger("LoreTest") : null;
        });
    }
}
