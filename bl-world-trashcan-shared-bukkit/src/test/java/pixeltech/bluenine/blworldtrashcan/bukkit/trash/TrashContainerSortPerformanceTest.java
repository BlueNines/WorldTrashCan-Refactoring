package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.*;

/** 本地容量基准：测量条目排序而非 NBT、GUI 渲染或网络耗时。 */
public final class TrashContainerSortPerformanceTest {
    /** 分别覆盖中等容量与扩容场景，计时只做记录，避免按机器速度写脆弱断言。 */
    @Test
    public void measureChangedStockSortAndCachedReads() {
        for (int count : new int[]{1350, 10000}) {
            TrashContainerStore store = new TrashContainerStore(new ItemIdentityProvider() {
                /** 区分基准使用的轻量物品。 */
                @Override public String id() { return "benchmark"; }
                /** 用耐久字段模拟不同身份，不让序列化影响排序计时。 */
                @Override public String key(ItemStack item) { return String.valueOf(item.getDurability()); }
            });
            store.configure(new TrashConfig.PersonalTrashConfig(true, 0, true,
                    TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null),
                    TrashConfig.GlobalTrashMode.COMPACT,
                    new TrashConfig.CompactGlobalTrashConfig(1000, 9999L, 1, 64, false, 1, 64,
                            true, 5, "{amount}", "...", java.util.Collections.<String>emptyList()),
                    new TrashConfig.StackedGlobalTrashConfig(1000), true, false, -1D,
                    TrashConfig.DamageRecoveryMode.DISABLED, 6, true, 3), 45);
            for (int index = 0; index < count; index++) {
                ItemStack item = new Sample();
                item.setDurability((short) index);
                store.add(item, 1 + (index * 7919 % 5000), true);
            }
            ItemStack changed = new Sample();
            for (TrashConfig.GlobalTrashSortType type : TrashConfig.GlobalTrashSortType.values()) {
                long[] nanos = new long[100];
                for (int index = -30; index < nanos.length; index++) {
                    assertEquals(1, store.add(changed, 1, true).getAcceptedAmount());
                    long start = System.nanoTime();
                    TrashContainerStore.ViewSnapshot snapshot = store.createViewSnapshot(type);
                    long elapsed = System.nanoTime() - start;
                    assertEquals(count, snapshot.getReferenceCount());
                    if (index >= 0) nanos[index] = elapsed;
                }
                TrashContainerStore.ViewSnapshot cached = store.createViewSnapshot(type);
                long start = System.nanoTime();
                for (int index = 0; index < 10000; index++) assertSame(cached, store.createViewSnapshot(type));
                double cachedMicros = (System.nanoTime() - start) / 10000D / 1000D;
                Arrays.sort(nanos);
                System.out.printf(Locale.ROOT,
                        "SORT_BENCH entries=%d sort=%s medianMs=%.4f p95Ms=%.4f maxMs=%.4f cacheMicros=%.4f%n",
                        count, type, nanos[50] / 1e6, nanos[94] / 1e6, nanos[99] / 1e6, cachedMicros);
            }
        }
    }

    /** 不读取 Bukkit 全局 ItemFactory 的基准样品。 */
    private static final class Sample extends ItemStack {
        /** 创建可独立计数的普通样品。 */
        private Sample() { super(Material.STONE, 1); }
        /** 基准不包含物品元数据解析。 */
        @Override public ItemMeta getItemMeta() { return null; }
    }
}
