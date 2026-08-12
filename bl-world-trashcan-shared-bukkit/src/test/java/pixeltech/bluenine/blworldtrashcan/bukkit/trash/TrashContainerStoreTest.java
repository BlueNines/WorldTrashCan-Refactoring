package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.Collections;

/** 验证公共与个人垃圾桶共用存储的容量、隔离和重试语义。 */
public final class TrashContainerStoreTest {
    /** 两个玩家 Store 中的同类物品必须完全隔离。 */
    @Test
    public void independentStoresDoNotShareAmountsOrTrackingKeys() {
        TrashContainerStore first = store("personal:first", compactConfig(10L, 1), 1);
        TrashContainerStore second = store("personal:second", compactConfig(10L, 1), 1);

        TrashWriteResult firstWrite = first.add(new ItemStack(Material.STONE, 7), false);
        TrashWriteResult secondWrite = second.add(new ItemStack(Material.STONE, 2), false);

        Assert.assertEquals(7, first.getStoredItemAmount());
        Assert.assertEquals(2, second.getStoredItemAmount());
        Assert.assertNotEquals(firstWrite.getTrackingKey(), secondWrite.getTrackingKey());
    }

    /** 紧凑模式部分接收后必须保留旧存量且不能触发清空。 */
    @Test
    public void compactPartialWriteNeverClearsExistingAmount() {
        TrashContainerStore store = store("personal:partial", compactConfig(10L, 1), 1);
        store.add(new ItemStack(Material.STONE, 8), false);

        TrashWriteResult result = store.addWithClearRetry(new ItemStack(Material.STONE, 5), true);

        Assert.assertEquals(TrashWriteResult.Status.ACCEPTED_PARTIAL, result.getStatus());
        Assert.assertEquals(2, result.getAcceptedAmount());
        Assert.assertFalse(result.isClearedBeforeWrite());
        Assert.assertEquals(10, store.getStoredItemAmount());
    }

    /** 单条目达到上限属于条目拒绝，不能清空整个个人桶。 */
    @Test
    public void entryLimitDoesNotTriggerClearRetry() {
        TrashContainerStore store = store("personal:entry-limit", compactConfig(10L, 2), 1);
        store.add(new ItemStack(Material.STONE, 10), false);
        store.add(new ItemStack(Material.DIRT, 3), false);

        TrashWriteResult result = store.addWithClearRetry(new ItemStack(Material.STONE, 1), true);

        Assert.assertEquals(TrashWriteResult.Status.REJECTED_ENTRY_LIMIT, result.getStatus());
        Assert.assertFalse(result.isClearedBeforeWrite());
        Assert.assertEquals(13, store.getStoredItemAmount());
        Assert.assertEquals(2, store.getStoredStackCount());
    }

    /** 整个容器满且新请求可完整放入空桶时，只允许清空并重试一次。 */
    @Test
    public void containerCapacityCanClearAndRetryOnce() {
        TrashContainerStore store = store("personal:clear-retry", compactConfig(10L, 1), 1);
        store.add(new ItemStack(Material.STONE, 10), false);

        Assert.assertTrue(store.canAccept(new ItemStack(Material.DIRT, 4), true, true));
        TrashWriteResult result = store.addWithClearRetry(new ItemStack(Material.DIRT, 4), true);

        Assert.assertEquals(TrashWriteResult.Status.ACCEPTED_FULL, result.getStatus());
        Assert.assertTrue(result.isClearedBeforeWrite());
        Assert.assertEquals(4, store.getStoredItemAmount());
        Assert.assertEquals(Material.DIRT, store.getDisplayItem(0, 0).getSample().getType());
    }

    /** 清空后仍不能完整接收的请求不能破坏旧存量。 */
    @Test
    public void clearRetryRequiresFullAcceptanceInEmptyContainer() {
        TrashContainerStore store = store("personal:oversized", compactConfig(10L, 1), 1);
        store.add(new ItemStack(Material.DIRT, 10), false);

        Assert.assertFalse(store.canAccept(new ItemStack(Material.STONE, 11), true, true));
        TrashWriteResult result = store.addWithClearRetry(new ItemStack(Material.STONE, 11), true);

        Assert.assertEquals(TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY, result.getStatus());
        Assert.assertFalse(result.isClearedBeforeWrite());
        Assert.assertEquals(10, store.getStoredItemAmount());
        Assert.assertEquals(Material.DIRT, store.getDisplayItem(0, 0).getSample().getType());
    }

    /** 未开启清空重试时，满桶预判和正式写入都必须拒绝且保留内容。 */
    @Test
    public void fullContainerRejectsWithoutClearPermission() {
        TrashContainerStore store = store("personal:reject", compactConfig(10L, 1), 1);
        store.add(new ItemStack(Material.STONE, 10), false);

        Assert.assertFalse(store.canAccept(new ItemStack(Material.DIRT, 1), true, false));
        TrashWriteResult result = store.add(new ItemStack(Material.DIRT, 1), true);

        Assert.assertEquals(TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY, result.getStatus());
        Assert.assertEquals(10, store.getStoredItemAmount());
        Assert.assertEquals(Material.STONE, store.getDisplayItem(0, 0).getSample().getType());
    }

    /** stacked 模式必须按原版最大堆叠数生成分页引用。 */
    @Test
    public void stackedModeUsesVanillaStackSizes() {
        TrashContainerStore store = store("personal:stacked", stackedConfig(1), 2);

        TrashWriteResult result = store.add(new ItemStack(Material.STONE, 65), false);

        Assert.assertEquals(TrashWriteResult.Status.ACCEPTED_FULL, result.getStatus());
        Assert.assertEquals(2, store.getStoredStackCount());
        Assert.assertEquals(64, store.getDisplayItem(0, 0).getDisplayAmount());
        Assert.assertEquals(1, store.getDisplayItem(0, 1).getDisplayAmount());
    }

    /** reload 改变模式和容量时必须保留运行期存量。 */
    @Test
    public void reconfigurePreservesRuntimeContents() {
        TrashContainerStore store = store("personal:reload", compactConfig(9999L, 2), 2);
        store.add(new ItemStack(Material.STONE, 65), false);

        store.configure(stackedConfig(2), 2);

        Assert.assertEquals(65, store.getStoredItemAmount());
        Assert.assertEquals(2, store.getStoredStackCount());
    }

    /** 创建已经配置好的测试 Store。 */
    private TrashContainerStore store(String prefix, TrashConfig.TrashContainerConfig config,
                                      int contentSlots) {
        TrashContainerStore store = new TrashContainerStore(new TestIdentityProvider(), prefix);
        store.configure(config, contentSlots);
        return store;
    }

    /** 创建个人桶语义的紧凑配置。 */
    private TrashConfig.PersonalTrashConfig compactConfig(long maxAmount, int maxPages) {
        TrashConfig.CompactGlobalTrashConfig compact = new TrashConfig.CompactGlobalTrashConfig(
                maxPages, maxAmount, 1, 64, false, 1, 64, true, 5,
                "数量：{amount}", "省略 {count} 行", Collections.<String>emptyList());
        return personalConfig(TrashConfig.GlobalTrashMode.COMPACT, compact,
                new TrashConfig.StackedGlobalTrashConfig(maxPages));
    }

    /** 创建个人桶语义的堆叠配置。 */
    private TrashConfig.PersonalTrashConfig stackedConfig(int maxPages) {
        return personalConfig(TrashConfig.GlobalTrashMode.STACKED,
                TrashConfig.CompactGlobalTrashConfig.defaults(),
                new TrashConfig.StackedGlobalTrashConfig(maxPages));
    }

    /** 创建完整个人桶配置。 */
    private TrashConfig.PersonalTrashConfig personalConfig(
            TrashConfig.GlobalTrashMode mode,
            TrashConfig.CompactGlobalTrashConfig compact,
            TrashConfig.StackedGlobalTrashConfig stacked) {
        return new TrashConfig.PersonalTrashConfig(true, 0, true,
                TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null),
                mode, compact, stacked, true, false, -1D,
                TrashConfig.DamageRecoveryMode.DISABLED, 6, true, 3);
    }

    /** 只按 Material 区分物品，避免纯 JUnit 依赖 Bukkit 全局服务。 */
    private static final class TestIdentityProvider implements ItemIdentityProvider {
        /** 返回测试身份实现名称。 */
        @Override
        public String id() {
            return "test";
        }

        /** 生成忽略数量的测试身份键。 */
        @Override
        public String key(ItemStack itemStack) {
            return itemStack == null ? null : itemStack.getType().name();
        }
    }
}
