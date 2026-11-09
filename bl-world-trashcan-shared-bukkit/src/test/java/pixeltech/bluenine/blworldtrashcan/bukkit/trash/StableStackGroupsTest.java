package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 覆盖独立组余量、容量、旧引用及真实并发扣减的数量不变量。 */
public final class StableStackGroupsTest {
    /** 从中间组部分取出不改变前后组，补入优先填未满组。 */
    @Test public void partialGroupRemainsIndependentAndRefills() {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 130), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        assertEquals(10, store.remove(view.getReference(0, 1), 10));
        assertAmounts(store, view, 64, 54, 2);
        assertSame(view, store.refreshStackedSnapshot(view));
        assertTrue(store.add(new ItemStack(Material.STONE, 10), false).isAccepted());
        assertAmounts(store, view, 64, 64, 2);
        assertEquals(130, store.getStoredItemAmount());
    }

    /** 取空留下洞，新组只填洞，旧引用不会错误领取后来加入的同类组。 */
    @Test public void emptiedGroupCannotClaimReplacement() {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 130), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        TrashContainerStore.DisplayReference old = view.getReference(0, 0);
        assertEquals(64, store.remove(old, 64));
        assertAmounts(store, view, 0, 64, 2);
        store.add(new ItemStack(Material.DIRT, 7), false);
        TrashContainerStore.ViewSnapshot next = store.refreshStackedSnapshot(view);
        assertEquals(Material.DIRT, store.getDisplayItem(next.getReference(0, 0)).getSample().getType());
        assertSame(view.getReference(0, 1), next.getReference(0, 1));
        assertEquals(0, store.remove(old, 64));
        assertEquals(73, store.getStoredItemAmount());
    }

    /** 不能因总量可压缩而虚增容量，两个部分组仍占两个位置。 */
    @Test public void fragmentedGroupsConsumeRealCapacity() {
        TrashContainerStore store = store(2);
        store.add(new ItemStack(Material.STONE, 128), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        store.remove(view.getReference(0, 0), 32);
        store.remove(view.getReference(0, 1), 32);
        assertEquals(2, store.getStoredStackCount());
        assertFalse(store.add(new ItemStack(Material.DIRT, 1), false).isAccepted());
        assertEquals(64, store.add(new ItemStack(Material.STONE, 65), true).getAcceptedAmount());
        assertAmounts(store, view, 64, 64);
    }

    /** 同桶不同排序引用相同组，更新数量不强行同步两人的槽位。 */
    @Test public void differentSortsShareGroupButKeepTheirPositions() {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 65), false);
        store.add(new ItemStack(Material.DIRT, 7), false);
        TrashContainerStore.ViewSnapshot a = store.createViewSnapshot(TrashConfig.GlobalTrashSortType.AMOUNT_DESC);
        TrashContainerStore.ViewSnapshot b = store.createViewSnapshot(TrashConfig.GlobalTrashSortType.AMOUNT_ASC);
        assertEquals(a.getReference(0, 0).getGroupId(), b.getReference(0, 1).getGroupId());
        store.remove(a.getReference(0, 0), 64);
        assertAmounts(store, a, 0, 1, 7);
        assertAmounts(store, b, 7, 0, 1);
        assertSame(a, store.refreshStackedSnapshot(a));
        assertSame(b, store.refreshStackedSnapshot(b));
    }

    /** 清空后序号不复用，现有会话能发现新组而不误取旧组。 */
    @Test public void clearAndReAddDoesNotReuseGroupIdentity() {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 64), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        store.clear();
        store.add(new ItemStack(Material.STONE, 9), false);
        assertEquals(0, store.remove(view.getReference(0, 0), 64));
        assertAmounts(store, store.refreshStackedSnapshot(view), 9);
    }

    /** 配置重载保持同模式分组，紧凑切换回来才按总量重建。 */
    @Test public void reloadPreservesGroupsAndModeChangeRebuildsThem() {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 128), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        store.remove(view.getReference(0, 0), 32);
        store.configure(config(TrashConfig.GlobalTrashMode.STACKED), 3);
        assertAmounts(store, view, 32, 64);
        store.configure(config(TrashConfig.GlobalTrashMode.COMPACT), 3);
        assertEquals(96, store.getStoredItemAmount());
        store.configure(config(TrashConfig.GlobalTrashMode.STACKED), 3);
        assertAmounts(store, store.createViewSnapshot(null), 64, 32);
        assertEquals(0, store.remove(view.getReference(0, 0), 64));
    }

    /** 并发取同一组总领取不超过该组；其它同类组完全不受影响。 */
    @Test public void concurrentReadersCannotDrainAnotherGroup() throws Exception {
        TrashContainerStore store = store(3);
        store.add(new ItemStack(Material.STONE, 128), false);
        TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
        AtomicInteger removed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    removed.addAndGet(store.remove(view.getReference(0, 0), 64));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(5000);
        for (Thread thread : threads) assertFalse(thread.isAlive());
        assertEquals(64, removed.get());
        assertAmounts(store, view, 0, 64);
    }

    /** 大量随机增减始终满足组数量之和等于真实总量，且从不突破容量。 */
    @Test public void randomizedGroupAccountingRemainsConservative() {
        TrashContainerStore store = store(12);
        Random random = new Random(711);
        long expected = 0;
        for (int iteration = 0; iteration < 5000; iteration++) {
            if (random.nextBoolean()) {
                Material material = random.nextBoolean() ? Material.STONE : Material.DIRT;
                expected += store.add(new ItemStack(material, 1 + random.nextInt(130)), true).getAcceptedAmount();
            } else {
                TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
                expected -= store.remove(view.getReference(0, random.nextInt(12)), random.nextInt(90));
            }
            TrashContainerStore.ViewSnapshot view = store.createViewSnapshot(null);
            long sum = 0;
            for (int i = 0; i < view.getReferenceCount(); i++) {
                int amount = store.getDisplayAmount(view.getReference(0, i));
                assertTrue(amount > 0 && amount <= 64);
                sum += amount;
            }
            assertEquals(expected, sum);
            assertEquals(expected, store.getStoredItemAmount());
            assertTrue(store.getStoredStackCount() <= 12);
        }
    }

    /** 无成功变更不通知，取消订阅后不再保留或调用回调。 */
    @Test public void mutationNotificationsAreScopedAndDetachable() {
        TrashContainerStore store = store(1);
        AtomicInteger changes = new AtomicInteger();
        store.setChangeListener(ignored -> changes.incrementAndGet());
        store.add(new ItemStack(Material.STONE, 64), false);
        store.add(new ItemStack(Material.DIRT, 1), false);
        store.remove("missing", 10);
        assertEquals(1, changes.get());
        store.remove(store.createViewSnapshot(null).getReference(0, 0), 1);
        assertEquals(2, changes.get());
        store.setChangeListener(null);
        store.clear();
        assertEquals(2, changes.get());
    }

    /** 创建单页独立容量的轻量模型。 */
    private TrashContainerStore store(int slots) {
        TrashContainerStore store = new TrashContainerStore(new ItemIdentityProvider() {
            /** 返回测试身份策略名称。 */
            @Override public String id() { return "material"; }
            /** 测试只按材质区分身份。 */
            @Override public String key(ItemStack item) { return item.getType().name(); }
        });
        store.configure(config(TrashConfig.GlobalTrashMode.STACKED), slots);
        return store;
    }

    /** 创建兼容公共与个人桶共用模型的配置。 */
    private TrashConfig.PersonalTrashConfig config(TrashConfig.GlobalTrashMode mode) {
        return new TrashConfig.PersonalTrashConfig(true, 0, true,
                TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null), mode,
                TrashConfig.CompactGlobalTrashConfig.defaults(), new TrashConfig.StackedGlobalTrashConfig(1),
                true, false, -1D, TrashConfig.DamageRecoveryMode.DISABLED, 6, true, 3);
    }

    /** 校验每个引用的独立余量，零表示原位置已空。 */
    private void assertAmounts(TrashContainerStore store, TrashContainerStore.ViewSnapshot view, int... amounts) {
        for (int i = 0; i < amounts.length; i++) {
            assertEquals("slot " + i, amounts[i], store.getDisplayAmount(view.getReference(0, i)));
        }
    }
}
