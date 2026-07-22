package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.command.WorldListTrashCanCommandNames;
import pixeltech.worldlisttrashcan.api.command.SubcommandDefinition;
import pixeltech.worldlisttrashcan.api.command.SubcommandRegistration;
import pixeltech.worldlisttrashcan.api.command.WorldListTrashCanSubcommand;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** 验证附属副指令注册器的权限、冲突和注销语义。 */
public final class DefaultWorldListTrashCanCommandRegistryTest {

    /** 验证注册后执行、帮助和补全均可用。 */
    @Test
    public void registersDispatchesAndCompletes() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin owner = plugin("AuditAddon", enabled);
        DefaultWorldListTrashCanCommandRegistry registry = registry();
        AtomicReference<String> executed = new AtomicReference<>("");
        registry.register(owner, new SubcommandDefinition("audit", Collections.singletonList("history"),
                        "WorldListTrashCanAudit.open"),
                command(executed));

        CommandSender allowed = sender(true);
        Assert.assertEquals(Collections.singletonList("audit"), registry.visibleNames(allowed));
        Assert.assertEquals(DefaultWorldListTrashCanCommandRegistry.DispatchResult.HANDLED,
                registry.dispatch(allowed, "history", new String[]{"history", "7"}));
        Assert.assertEquals("7", executed.get());
        Assert.assertEquals(Collections.singletonList("next"),
                registry.tabComplete(allowed, new String[]{"audit", "n"}));
        Assert.assertEquals(1, registry.helpEntries(allowed).size());
    }

    /** 验证无权限发送者不会看到或执行附属命令。 */
    @Test
    public void hidesCommandsWithoutPermission() {
        Plugin owner = plugin("AuditAddon", new AtomicBoolean(true));
        DefaultWorldListTrashCanCommandRegistry registry = registry();
        registry.register(owner, new SubcommandDefinition("audit", Collections.<String>emptyList(),
                        "WorldListTrashCanAudit.open"), command(new AtomicReference<String>()));

        CommandSender denied = sender(false);
        Assert.assertTrue(registry.visibleNames(denied).isEmpty());
        Assert.assertTrue(registry.helpEntries(denied).isEmpty());
        Assert.assertEquals(DefaultWorldListTrashCanCommandRegistry.DispatchResult.NO_PERMISSION,
                registry.dispatch(denied, "audit", new String[]{"audit"}));
    }

    /** 验证保留名和重复别名都会被拒绝。 */
    @Test
    public void rejectsReservedAndConflictingNames() {
        Plugin owner = plugin("AuditAddon", new AtomicBoolean(true));
        DefaultWorldListTrashCanCommandRegistry registry = registry();
        try {
            registry.register(owner, new SubcommandDefinition("clear", Collections.<String>emptyList(), ""),
                    command(new AtomicReference<String>()));
            Assert.fail("reserved name should fail");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Reserved"));
        }

        registry.register(owner, new SubcommandDefinition("audit", Collections.singletonList("history"), ""),
                command(new AtomicReference<String>()));
        try {
            registry.register(owner, new SubcommandDefinition("other", Collections.singletonList("history"), ""),
                    command(new AtomicReference<String>()));
            Assert.fail("conflicting alias should fail");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("already registered"));
        }
    }

    /** 验证关闭句柄和插件禁用都会清理注册。 */
    @Test
    public void unregistersWithoutLeakingHandlers() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin owner = plugin("AuditAddon", enabled);
        DefaultWorldListTrashCanCommandRegistry registry = registry();
        SubcommandRegistration registration = registry.register(owner,
                new SubcommandDefinition("audit", Collections.<String>emptyList(), ""),
                command(new AtomicReference<String>()));

        registration.close();
        registration.close();
        Assert.assertEquals(DefaultWorldListTrashCanCommandRegistry.DispatchResult.NOT_FOUND,
                registry.dispatch(sender(true), "audit", new String[]{"audit"}));

        registry.register(owner, new SubcommandDefinition("audit", Collections.<String>emptyList(), ""),
                command(new AtomicReference<String>()));
        registry.removeOwner(owner);
        Assert.assertTrue(registry.visibleNames(sender(true)).isEmpty());
    }

    /** 创建待测注册器。 */
    private DefaultWorldListTrashCanCommandRegistry registry() {
        return new DefaultWorldListTrashCanCommandRegistry(
                plugin("WorldListTrashCan", new AtomicBoolean(true)), WorldListTrashCanCommandNames.reserved());
    }

    /** 创建记录执行参数的测试副指令。 */
    private WorldListTrashCanSubcommand command(final AtomicReference<String> executed) {
        return new WorldListTrashCanSubcommand() {
            /** 返回测试用法。 */
            @Override
            public String getUsage(CommandSender sender) {
                return "[page]";
            }

            /** 返回测试描述。 */
            @Override
            public String getDescription(CommandSender sender) {
                return "查看记录";
            }

            /** 记录收到的参数。 */
            @Override
            public void execute(CommandSender sender, String[] args) {
                executed.set(args.length == 0 ? "" : args[0]);
            }

            /** 返回固定补全。 */
            @Override
            public List<String> tabComplete(CommandSender sender, String[] args) {
                return Arrays.asList("next");
            }
        };
    }

    /** 创建轻量 Plugin 动态代理。 */
    private Plugin plugin(final String name, final AtomicBoolean enabled) {
        return proxy(Plugin.class, new InvocationHandler() {
            /** 返回测试需要的插件属性。 */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getName".equals(method.getName())) {
                    return name;
                }
                if ("isEnabled".equals(method.getName())) {
                    return enabled.get();
                }
                if ("getLogger".equals(method.getName())) {
                    return Logger.getLogger(name);
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    /** 创建带固定权限结果的 CommandSender 动态代理。 */
    private CommandSender sender(final boolean allowed) {
        return proxy(CommandSender.class, new InvocationHandler() {
            /** 返回测试需要的命令发送者属性。 */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("hasPermission".equals(method.getName())) {
                    return allowed;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    /** 创建接口动态代理。 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** 返回原始类型默认值。 */
    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Character.TYPE) {
            return '\0';
        }
        if (type == Byte.TYPE) {
            return (byte) 0;
        }
        if (type == Short.TYPE) {
            return (short) 0;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Float.TYPE) {
            return 0F;
        }
        if (type == Double.TYPE) {
            return 0D;
        }
        return null;
    }
}
