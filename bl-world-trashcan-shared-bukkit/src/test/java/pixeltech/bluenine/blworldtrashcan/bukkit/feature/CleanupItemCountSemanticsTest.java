package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 验证旧版掉落实体计数与新增实际物品计数保持独立。 */
public final class CleanupItemCountSemanticsTest {
    /** 三个各含六十四件物品的掉落实体应得到实体数三、实际件数一百九十二。 */
    @Test
    public void physicalStacksKeepLegacyEntityCountAndPreciseAmount() {
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();

        recordGlobalItem(stats, 64);
        recordGlobalItem(stats, 64);
        recordGlobalItem(stats, 64);

        Assert.assertEquals(3, stats.getItemEntitiesHandled());
        Assert.assertEquals(3, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(192, stats.getItemsHandled());
        Assert.assertEquals(192, stats.getItemsToGlobalTrash());
        Assert.assertEquals("3|3|192|192", CleanupFeature.applyItemStats(
                "%DealItemSum%|%GlobalTrashAddSum%|%DealItemAmount%|%GlobalTrashAddAmount%", stats));
    }

    /** 同一来源实体分批进入公共垃圾桶时，实体数只增加一次。 */
    @Test
    public void partialWritesCountOneSourceEntity() {
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        CleanupFeature.CleanupStats.ItemEntityCounter counter = stats.beginItemEntity();

        counter.recordRouted(40, TrashRoute.GLOBAL_TRASH);
        counter.recordRouted(24, TrashRoute.GLOBAL_TRASH);

        Assert.assertEquals(1, stats.getItemEntitiesHandled());
        Assert.assertEquals(1, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(64, stats.getItemsHandled());
        Assert.assertEquals(64, stats.getItemsToGlobalTrash());
    }

    /** 同一来源实体部分入公共桶、剩余直接删除时，总实体数仍只增加一次。 */
    @Test
    public void mixedRouteAndRemovalCountOneSourceEntity() {
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        CleanupFeature.CleanupStats.ItemEntityCounter counter = stats.beginItemEntity();

        counter.recordRouted(20, TrashRoute.GLOBAL_TRASH);
        counter.recordRemoved(44);

        Assert.assertEquals(1, stats.getItemEntitiesHandled());
        Assert.assertEquals(1, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(64, stats.getItemsHandled());
        Assert.assertEquals(20, stats.getItemsToGlobalTrash());
    }

    /** 直接删除一个普通物理堆叠时，旧统计为一个实体，新统计保留完整件数。 */
    @Test
    public void directRemovalKeepsEntityAndAmountSemanticsSeparate() {
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();

        stats.beginItemEntity().recordRemoved(64);

        Assert.assertEquals(1, stats.getItemEntitiesHandled());
        Assert.assertEquals(0, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(64, stats.getItemsHandled());
        Assert.assertEquals(64, stats.getItemsRemoved());
    }

    /** Folia 并行处理不同来源实体时，实体数和实际件数都不能丢失。 */
    @Test
    public void concurrentSourceEntitiesKeepBothCountersExact() throws Exception {
        final CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 1000; index++) {
                futures.add(executor.submit(new Callable<Void>() {
                    /** 记录一个包含六十四件物品的独立来源实体。 */
                    @Override
                    public Void call() {
                        stats.beginItemEntity().recordRouted(64, TrashRoute.GLOBAL_TRASH);
                        return null;
                    }
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        Assert.assertEquals(1000, stats.getItemEntitiesHandled());
        Assert.assertEquals(1000, stats.getItemEntitiesToGlobalTrash());
        Assert.assertEquals(64000, stats.getItemsHandled());
        Assert.assertEquals(64000, stats.getItemsToGlobalTrash());
    }

    /** 记录一个完整进入公共垃圾桶的来源实体。 */
    private void recordGlobalItem(CleanupFeature.CleanupStats stats, int amount) {
        CleanupFeature.CleanupStats.ItemEntityCounter counter = stats.beginItemEntity();
        counter.recordRouted(amount, TrashRoute.GLOBAL_TRASH);
    }
}
