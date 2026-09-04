package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRouter;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRoutingResult;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** 验证扫地路由部分接收后会在同一轮继续处理剩余数量。 */
public final class CleanupPartialRoutingTest {
    /** 同一路由分两次接收时，本轮必须处理完整数量并移除地面实体。 */
    @Test
    public void partialWritesContinueUntilRemainderIsEmpty() throws Exception {
        SequencedTrashRouter router = new SequencedTrashRouter(7, 3);
        MutableItem item = new MutableItem(10);
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        RecordingAuditSession audit = new RecordingAuditSession();

        TrashRoutingDecision result = invokeRoute(item.proxy(), router, stats, audit);

        Assert.assertEquals(TrashRoute.GLOBAL_TRASH, result.getRoute());
        Assert.assertEquals(Arrays.asList(Integer.valueOf(10), Integer.valueOf(3)), router.getRequests());
        Assert.assertEquals(10, stats.getItemsRouted());
        Assert.assertEquals(1, stats.getItemEntitiesHandled());
        Assert.assertEquals(1, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(10, audit.getRecordedAmount());
        Assert.assertTrue(item.isRemoved());
    }

    /** 当前路由吃满后拒绝剩余数量时，必须把精确剩余量交给删除降级。 */
    @Test
    public void partialWriteFallsBackWithExactRemainder() throws Exception {
        SequencedTrashRouter router = new SequencedTrashRouter(15, 0);
        MutableItem item = new MutableItem(20);
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        RecordingAuditSession audit = new RecordingAuditSession();

        TrashRoutingDecision result = invokeRoute(item.proxy(), router, stats, audit);

        Assert.assertEquals(TrashRoute.REMOVE, result.getRoute());
        Assert.assertEquals(Arrays.asList(Integer.valueOf(20), Integer.valueOf(5)), router.getRequests());
        Assert.assertEquals(15, stats.getItemsRouted());
        Assert.assertEquals(1, stats.getItemEntitiesHandled());
        Assert.assertEquals(1, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(15, audit.getRecordedAmount());
        Assert.assertEquals(5, item.getAmount());
        Assert.assertFalse(item.isRemoved());
    }

    /** 目标桶写入后来源删除未生效时，必须撤销写入且不得增加清理统计。 */
    @Test
    public void failedSourceRemovalRollsBackDestinationWrite() throws Exception {
        SequencedTrashRouter router = new SequencedTrashRouter(1);
        MutableItem item = new MutableItem(1);
        item.setRemovalEffective(false);
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        RecordingAuditSession audit = new RecordingAuditSession();

        TrashRoutingDecision result = invokeRoute(item.proxy(), router, stats, audit);

        Assert.assertEquals(TrashRoute.SKIP, result.getRoute());
        Assert.assertEquals(1, router.getRolledBackAmount());
        Assert.assertEquals(0, stats.getItemsRouted());
        Assert.assertEquals(0, stats.getItemEntitiesHandled());
        Assert.assertEquals(0, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(0, audit.getRecordedAmount());
        Assert.assertFalse(item.isRemoved());
    }

    /** 目标桶写入完成后来源被玩家拾取时，必须回滚且不能生成第二份物品。 */
    @Test
    public void pickupAfterDestinationWriteRollsBackWithoutDuplication() throws Exception {
        SequencedTrashRouter router = new SequencedTrashRouter(1);
        MutableItem item = new MutableItem(1);
        router.setAfterWrite(new Runnable() {
            /** 模拟目标桶完成写入后，原版拾取立即使地面实体失效。 */
            @Override
            public void run() {
                item.invalidate();
            }
        });
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        RecordingAuditSession audit = new RecordingAuditSession();

        TrashRoutingDecision result = invokeRoute(item.proxy(), router, stats, audit);

        Assert.assertEquals(TrashRoute.SKIP, result.getRoute());
        Assert.assertEquals(1, router.getRolledBackAmount());
        Assert.assertEquals(0, stats.getItemsRouted());
        Assert.assertEquals(0, stats.getItemEntitiesHandled());
        Assert.assertEquals(0, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(0, audit.getRecordedAmount());
        Assert.assertTrue(item.isRemoved());
    }

    /** 调用正式私有路由方法，避免为测试扩大生产代码可见范围。 */
    private TrashRoutingDecision invokeRoute(Item item, TrashRouter router,
                                             CleanupFeature.CleanupStats stats,
                                             CleanupAuditSession audit) throws Exception {
        ServerPlatform platform = proxy(ServerPlatform.class, new NullInvocationHandler());
        Plugin plugin = proxy(Plugin.class, new TestPluginInvocationHandler());
        CleanupFeature feature = new CleanupFeature(plugin, platform, null, router,
                null, null, null, null);
        Method method = CleanupFeature.class.getDeclaredMethod("routeWithFallback",
                Item.class, ItemSnapshot.class, CleanupPolicy.class, TrashRoutingDecision.class,
                CleanupFeature.CleanupStats.class, CleanupAuditSession.class,
                CleanupFeature.CleanupStats.ItemEntityCounter.class);
        method.setAccessible(true);
        ItemSnapshot snapshot = new ItemSnapshot("STONE", item.getItemStack().getAmount(), "",
                null, null);
        return (TrashRoutingDecision) method.invoke(feature, item, snapshot, new GlobalThenRemovePolicy(),
                new TrashRoutingDecision(TrashRoute.GLOBAL_TRASH, "test"), stats, audit,
                stats.beginItemEntity());
    }

    /** 为测试插件提供日志对象，其余方法保持默认值。 */
    private static final class TestPluginInvocationHandler implements InvocationHandler {
        /** 处理测试插件调用。 */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getLogger".equals(method.getName())) {
                return Logger.getLogger(CleanupPartialRoutingTest.class.getName());
            }
            return new NullInvocationHandler().invoke(proxy, method, args);
        }
    }

    /** 创建只实现本测试所需方法的接口代理。 */
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /** 返回基本类型默认值，其余方法返回 null。 */
    private static final class NullInvocationHandler implements InvocationHandler {
        /** 处理未参与断言的平台接口调用。 */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class) {
                return Integer.valueOf(0);
            }
            if (returnType == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    /** 保存测试掉落物的数量和移除状态。 */
    private static final class MutableItem implements InvocationHandler {
        private final Item proxy;
        private ItemStack itemStack;
        private boolean removed;
        private boolean removalEffective = true;

        /** 创建指定数量的掉落物代理。 */
        private MutableItem(int amount) {
            this.itemStack = new ItemStack(Material.STONE, amount);
            this.proxy = CleanupPartialRoutingTest.proxy(Item.class, this);
        }

        /** 返回掉落物接口代理。 */
        private Item proxy() {
            return proxy;
        }

        /** 返回当前物理数量。 */
        private int getAmount() {
            return itemStack.getAmount();
        }

        /** 返回实体是否已经被移除。 */
        private boolean isRemoved() {
            return removed;
        }

        /** 设置 remove 是否真正移除测试实体。 */
        private void setRemovalEffective(boolean removalEffective) {
            this.removalEffective = removalEffective;
        }

        /** 模拟掉落物已被拾取并从世界中失效。 */
        private void invalidate() {
            removed = true;
        }

        /** 实现数量读取、数量提交、世界读取和实体移除。 */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getItemStack".equals(name)) {
                return itemStack;
            }
            if ("setItemStack".equals(name)) {
                itemStack = ((ItemStack) args[0]).clone();
                return null;
            }
            if ("getWorld".equals(name)) {
                return CleanupPartialRoutingTest.proxy(World.class, new NullInvocationHandler());
            }
            if ("remove".equals(name)) {
                if (removalEffective) {
                    removed = true;
                }
                return null;
            }
            if ("getUniqueId".equals(name)) {
                return UUID.fromString("00000000-0000-0000-0000-000000000001");
            }
            if ("isValid".equals(name)) {
                return Boolean.valueOf(!removed);
            }
            if ("isDead".equals(name)) {
                return Boolean.valueOf(removed);
            }
            return new NullInvocationHandler().invoke(proxy, method, args);
        }
    }

    /** 按预设序列返回部分接收或拒绝结果。 */
    private static final class SequencedTrashRouter implements TrashRouter {
        private final int[] acceptedAmounts;
        private final List<Integer> requests = new ArrayList<>();
        private int callIndex;
        private int rolledBackAmount;
        private Runnable afterWrite;

        /** 创建按顺序返回接收量的路由器。 */
        private SequencedTrashRouter(int... acceptedAmounts) {
            this.acceptedAmounts = acceptedAmounts;
        }

        /** 返回收到的每次请求数量。 */
        private List<Integer> getRequests() {
            return requests;
        }

        /** 返回测试路由器累计回滚数量。 */
        private int getRolledBackAmount() {
            return rolledBackAmount;
        }

        /** 设置目标写入完成、来源提交开始前的测试动作。 */
        private void setAfterWrite(Runnable afterWrite) {
            this.afterWrite = afterWrite;
        }

        /** 测试不提供世界垃圾桶。 */
        @Override
        public boolean hasWorldTrash(World world, ItemStack itemStack) {
            return false;
        }

        /** 测试不提供个人垃圾桶。 */
        @Override
        public boolean hasPersonalTrash(UUID ownerUuid, ItemStack itemStack) {
            return false;
        }

        /** 测试初始状态提供公共垃圾桶。 */
        @Override
        public boolean hasGlobalTrash(ItemStack itemStack) {
            return true;
        }

        /** 兼容未使用的单堆叠路由入口。 */
        @Override
        public TrashRoutingResult routeDetailed(World world, UUID ownerUuid, ItemStack itemStack,
                                                 TrashRoute route, boolean cleanupSource) {
            return routeDetailedAmount(world, ownerUuid, itemStack,
                    itemStack == null ? 0 : itemStack.getAmount(), route, cleanupSource);
        }

        /** 记录请求并按序列返回接收量。 */
        @Override
        public TrashRoutingResult routeDetailedAmount(World world, UUID ownerUuid, ItemStack sample,
                                                       int requestedAmount, TrashRoute route,
                                                       boolean cleanupSource) {
            requests.add(Integer.valueOf(requestedAmount));
            int accepted = callIndex < acceptedAmounts.length ? acceptedAmounts[callIndex++] : 0;
            if (accepted <= 0) {
                return TrashRoutingResult.failure();
            }
            if (afterWrite != null) {
                afterWrite.run();
            }
            return TrashRoutingResult.success(CleanupItemDestination.globalTrash(),
                    Math.min(accepted, requestedAmount), "test");
        }

        /** 记录来源提交失败后的目标桶回滚数量。 */
        @Override
        public int rollbackRouted(TrashRoutingResult result, ItemStack sample, int requestedAmount) {
            rolledBackAmount += requestedAmount;
            return requestedAmount;
        }

        /** 测试路由器没有外部数据需要重载。 */
        @Override
        public void reload() {
        }

        /** 测试路由器不会访问未加载区块。 */
        @Override
        public int getSkippedUnloadedChunkAccesses() {
            return 0;
        }
    }

    /** 公共桶可用时进入公共桶，否则直接删除。 */
    private static final class GlobalThenRemovePolicy implements CleanupPolicy {
        /** 根据公共桶可用性返回路由决策。 */
        @Override
        public TrashRoutingDecision decideItem(ItemSnapshot item, boolean worldTrashAvailable,
                                               boolean personalTrashAvailable,
                                               boolean globalTrashAvailable) {
            return globalTrashAvailable
                    ? new TrashRoutingDecision(TrashRoute.GLOBAL_TRASH, "test-global")
                    : new TrashRoutingDecision(TrashRoute.REMOVE, "test-remove");
        }

        /** 本测试不处理普通实体。 */
        @Override
        public EntityCleanupDecision decideEntity(EntitySnapshot entity) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "test");
        }
    }

    /** 累计审计记录中的实际物品数量。 */
    private static final class RecordingAuditSession implements CleanupAuditSession {
        private int recordedAmount;

        /** 累计一条审计物品数量。 */
        @Override
        public void recordItem(ItemStack itemStack, CleanupItemDestination destination, String trackingKey) {
            recordedAmount += itemStack.getAmount();
        }

        /** 测试不需要处理审计完成回调。 */
        @Override
        public void complete(CleanupRunCompletion completion) {
        }

        /** 测试不需要处理审计放弃回调。 */
        @Override
        public void discard() {
        }

        /** 返回累计审计数量。 */
        private int getRecordedAmount() {
            return recordedAmount;
        }
    }
}
