package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.Collections;

/** 验证公共垃圾桶模型的紧凑合并、上限和旧堆叠显示语义。 */
public final class GlobalTrashStoreTest {
    /** 验证 9980 加 20 时只接受到 9999，而不是拆成多条或全部拒绝。 */
    @Test
    public void compactModeAcceptsOnlyRemainingCapacity() {
        GlobalTrashStore store = new GlobalTrashStore(new TestIdentityProvider());
        store.configure(config(TrashConfig.GlobalTrashMode.COMPACT, 9999L, 1), 45);

        Assert.assertEquals(9980, store.add(new ItemStack(Material.STONE, 9980), true));
        Assert.assertFalse(store.hasSpace(new ItemStack(Material.STONE, 20)));
        Assert.assertTrue(store.hasAnySpace(new ItemStack(Material.STONE, 20)));
        Assert.assertEquals(19, store.add(new ItemStack(Material.STONE, 20), true));
        Assert.assertEquals(9999, store.getStoredItemAmount());
        Assert.assertEquals(1, store.getStoredStackCount());
        Assert.assertEquals(9999L, store.getDisplayItem(0, 0).getLogicalAmount());
        Assert.assertEquals(1, store.getDisplayItem(0, 0).getDisplayAmount());
    }

    /** 验证不同原始 Lore 不会被身份算法错误合并。 */
    @Test
    public void compactModeKeepsDifferentMetadataSeparate() {
        GlobalTrashStore store = new GlobalTrashStore(new TestIdentityProvider());
        store.configure(config(TrashConfig.GlobalTrashMode.COMPACT, 9999L, 1), 45);

        ItemStack first = new ItemStack(Material.STONE, 2);
        ItemStack second = new ItemStack(Material.DIRT, 2);

        Assert.assertEquals(2, store.add(first, false));
        Assert.assertEquals(2, store.add(second, false));
        Assert.assertEquals(4, store.getStoredItemAmount());
        Assert.assertEquals(2, store.getStoredStackCount());
    }

    /** 验证 stacked 模式继续按原本最大堆叠数显示。 */
    @Test
    public void stackedModePreservesVanillaStackDisplay() {
        GlobalTrashStore store = new GlobalTrashStore(new TestIdentityProvider());
        store.configure(config(TrashConfig.GlobalTrashMode.STACKED, 9999L, 1), 45);

        Assert.assertEquals(65, store.add(new ItemStack(Material.STONE, 65), false));
        Assert.assertEquals(2, store.getStoredStackCount());
        Assert.assertEquals(64, store.getDisplayItem(0, 0).getDisplayAmount());
        Assert.assertEquals(1, store.getDisplayItem(0, 1).getDisplayAmount());
        Assert.assertEquals(65, store.getStoredItemAmount());
    }

    /** 创建只用于模型测试的独立模式配置。 */
    private TrashConfig.GlobalTrashConfig config(TrashConfig.GlobalTrashMode mode,
                                                 long maxAmount, int maxPages) {
        TrashConfig.CompactGlobalTrashConfig compact = new TrashConfig.CompactGlobalTrashConfig(
                maxPages, maxAmount, 1, 64, false, 1, 64, true, 5,
                "数量：{amount}", "省略 {count} 行", Collections.<String>emptyList());
        TrashConfig.StackedGlobalTrashConfig stacked = new TrashConfig.StackedGlobalTrashConfig(maxPages);
        return new TrashConfig.GlobalTrashConfig(true, 0, -1, true, false,
                TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null),
                Collections.<String>emptySet(), mode, compact, stacked);
    }

    /** 只按 Material 区分物品，避免纯 JUnit 环境依赖 Bukkit 全局服务。 */
    private static final class TestIdentityProvider implements ItemIdentityProvider {
        /** 返回测试身份名称。 */
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
