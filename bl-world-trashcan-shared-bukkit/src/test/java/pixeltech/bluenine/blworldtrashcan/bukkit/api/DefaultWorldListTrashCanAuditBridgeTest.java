package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;
import pixeltech.worldlisttrashcan.api.audit.AuditRegistration;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSink;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunContext;
import pixeltech.worldlisttrashcan.api.audit.CleanupTrigger;
import pixeltech.worldlisttrashcan.api.audit.TrashMutation;
import pixeltech.worldlisttrashcan.api.audit.TrashMutationReason;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** 验证审计桥在注册、注销和空消费者状态下的隔离行为。 */
public final class DefaultWorldListTrashCanAuditBridgeTest {

    /** 验证未注册消费者时不会调用附属代码。 */
    @Test
    public void returnsNoopSessionWithoutConsumer() {
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        CleanupAuditSession session = bridge.beginRun(context());
        session.recordItem(null, CleanupItemDestination.directRemove(), "");
        session.complete(new CleanupRunCompletion(System.currentTimeMillis(), false));
    }

    /** 验证关闭注册会放弃活动会话并阻止后续回调。 */
    @Test
    public void invalidatesActiveSessionsOnUnregister() {
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        Plugin owner = plugin("AuditAddon", ownerEnabled);
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        AtomicInteger recorded = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        AuditRegistration registration = bridge.register(owner, sink(recorded, discarded));

        CleanupAuditSession session = bridge.beginRun(context());
        session.recordItem(null, CleanupItemDestination.globalTrash(), "global:test:1");
        Assert.assertEquals(1, recorded.get());
        registration.close();
        registration.close();
        Assert.assertEquals(1, discarded.get());
        session.recordItem(null, CleanupItemDestination.globalTrash(), "global:test:1");
        Assert.assertEquals(1, recorded.get());
    }

    /** 验证附属插件禁用后新会话直接退化为空实现。 */
    @Test
    public void ignoresDisabledOwner() {
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        Plugin owner = plugin("AuditAddon", ownerEnabled);
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        AtomicInteger recorded = new AtomicInteger();
        bridge.register(owner, sink(recorded, new AtomicInteger()));
        ownerEnabled.set(false);

        bridge.beginRun(context()).recordItem(null, CleanupItemDestination.globalTrash(), "global:test:1");
        Assert.assertEquals(0, recorded.get());
    }

    /** 验证 v3 会话收到主存储提供的追踪键。 */
    @Test
    public void forwardsTrackingKeyToSession() {
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        AtomicInteger recorded = new AtomicInteger();
        AtomicReference<String> trackingKey = new AtomicReference<>();
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        bridge.register(plugin("AuditAddon", ownerEnabled),
                sink(recorded, new AtomicInteger(), trackingKey));

        bridge.beginRun(context()).recordItem(
                null, CleanupItemDestination.globalTrash(), "global:test:42");

        Assert.assertEquals(1, recorded.get());
        Assert.assertEquals("global:test:42", trackingKey.get());
    }

    /** 验证主插件只向活动消费者转发一次垃圾桶变更。 */
    @Test
    public void forwardsTrashMutationToActiveSink() {
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        AtomicInteger mutations = new AtomicInteger();
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        bridge.register(plugin("AuditAddon", ownerEnabled), mutationSink(mutations));

        bridge.recordTrashMutation(TrashMutation.clear(CleanupItemDestination.globalTrash(),
                TrashMutationReason.GLOBAL_REFRESH, 1L));
        ownerEnabled.set(false);
        bridge.recordTrashMutation(TrashMutation.clear(CleanupItemDestination.globalTrash(),
                TrashMutationReason.GLOBAL_REFRESH, 2L));

        Assert.assertEquals(1, mutations.get());
    }

    /** 附属记录异常必须放弃会话并阻止后续调用，避免有界槽位泄漏。 */
    @Test
    public void discardsSessionAfterRecordFailure() {
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        AtomicInteger discarded = new AtomicInteger();
        DefaultWorldListTrashCanAuditBridge bridge = bridge();
        bridge.register(plugin("FailingAuditAddon", ownerEnabled), failingSink(discarded));

        CleanupAuditSession session = bridge.beginRun(context());
        session.recordItem(null, CleanupItemDestination.globalTrash(), "global:test:1");
        session.recordItem(null, CleanupItemDestination.globalTrash(), "global:test:1");

        Assert.assertEquals(1, discarded.get());
    }

    /** 创建记录调用次数的测试消费者。 */
    private CleanupAuditSink sink(final AtomicInteger recorded, final AtomicInteger discarded) {
        return sink(recorded, discarded, new AtomicReference<String>());
    }

    /** 创建同时保存最近追踪键的测试消费者。 */
    private CleanupAuditSink sink(final AtomicInteger recorded, final AtomicInteger discarded,
                                  final AtomicReference<String> trackingKey) {
        return new CleanupAuditSink() {
            /** 创建测试会话。 */
            @Override
            public CleanupAuditSession beginRun(CleanupRunContext context) {
                return new CleanupAuditSession() {
                    /** 记录调用和追踪键。 */
                    @Override
                    public void recordItem(ItemStack itemStack, CleanupItemDestination destination,
                                           String value) {
                        recorded.incrementAndGet();
                        trackingKey.set(value);
                    }

                    /** 忽略完成。 */
                    @Override
                    public void complete(CleanupRunCompletion completion) {
                    }

                    /** 记录放弃。 */
                    @Override
                    public void discard() {
                        discarded.incrementAndGet();
                    }
                };
            }
        };
    }

    /** 创建只统计垃圾桶变更的消费者。 */
    private CleanupAuditSink mutationSink(final AtomicInteger mutations) {
        return new CleanupAuditSink() {
            /** 返回空测试会话。 */
            @Override
            public CleanupAuditSession beginRun(CleanupRunContext context) {
                return sink(new AtomicInteger(), new AtomicInteger()).beginRun(context);
            }

            /** 统计垃圾桶变更。 */
            @Override
            public void onTrashMutation(TrashMutation mutation) {
                mutations.incrementAndGet();
            }
        };
    }

    /** 创建记录时抛错但可正常放弃的测试消费者。 */
    private CleanupAuditSink failingSink(final AtomicInteger discarded) {
        return new CleanupAuditSink() {
            /** 创建故意在记录阶段失败的会话。 */
            @Override
            public CleanupAuditSession beginRun(CleanupRunContext context) {
                return new CleanupAuditSession() {
                    /** 模拟附属插件记录异常。 */
                    @Override
                    public void recordItem(ItemStack itemStack, CleanupItemDestination destination,
                                           String trackingKey) {
                        throw new IllegalStateException("expected test failure");
                    }

                    /** 测试不应完成失败会话。 */
                    @Override
                    public void complete(CleanupRunCompletion completion) {
                    }

                    /** 统计异常后的资源释放。 */
                    @Override
                    public void discard() {
                        discarded.incrementAndGet();
                    }
                };
            }
        };
    }

    /** 创建测试审计桥。 */
    private DefaultWorldListTrashCanAuditBridge bridge() {
        return new DefaultWorldListTrashCanAuditBridge(
                plugin("WorldListTrashCan", new AtomicBoolean(true)), new TestPlatform());
    }

    /** 创建测试清理上下文。 */
    private CleanupRunContext context() {
        return new CleanupRunContext(UUID.randomUUID(), System.currentTimeMillis(), CleanupTrigger.MANUAL, true);
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

    /** 不执行实际 Bukkit 操作的平台桩。 */
    private static final class TestPlatform implements ServerPlatform {
        /** 返回平台 ID。 */
        @Override
        public String id() {
            return "test";
        }

        /** 测试不需要能力报告。 */
        @Override
        public CapabilityReport capabilities() {
            return null;
        }

        /** 测试不需要调度器。 */
        @Override
        public SchedulerAdapter scheduler() {
            return null;
        }

        /** 测试不需要物品映射器。 */
        @Override
        public ItemSnapshotMapper itemSnapshotMapper() {
            return null;
        }

        /** 测试不需要实体映射器。 */
        @Override
        public EntitySnapshotMapper entitySnapshotMapper() {
            return null;
        }

        /** 测试不需要告示牌解析。 */
        @Override
        public Block getAttachedContainerBlock(Block signBlock) {
            return null;
        }

        /** 测试不发送消息。 */
        @Override
        public void sendMessage(UUID playerUuid, String message) {
        }

        /** 测试不执行玩家回调。 */
        @Override
        public boolean executeForPlayer(UUID playerUuid, Consumer<Player> action) {
            return false;
        }
    }
}
