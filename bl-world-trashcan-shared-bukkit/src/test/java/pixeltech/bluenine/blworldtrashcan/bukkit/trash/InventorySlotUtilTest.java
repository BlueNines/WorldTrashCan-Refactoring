package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;

/** 验证玩家背包的部分容量判断不会错误拒绝部分取出。 */
public final class InventorySlotUtilTest {

    /** 同类堆叠只剩三个容量时仍应允许进入实际部分取出流程。 */
    @Test
    public void acceptsPartiallyAvailableSimilarStack() {
        ItemStack[] contents = fullStorage();
        contents[0] = stack(Material.STONE, 61);

        Assert.assertTrue(InventorySlotUtil.hasAnyStorageSpace(
                inventory(contents), stack(Material.STONE, 8)));
    }

    /** 所有槽位和同类堆叠均已满时必须拒绝取出。 */
    @Test
    public void rejectsCompletelyFullStorage() {
        ItemStack[] contents = fullStorage();
        contents[0] = stack(Material.STONE, 64);

        Assert.assertFalse(InventorySlotUtil.hasAnyStorageSpace(
                inventory(contents), stack(Material.STONE, 8)));
    }

    /** 任意空槽都应允许继续执行取出。 */
    @Test
    public void acceptsEmptyStorageSlot() {
        ItemStack[] contents = fullStorage();
        contents[10] = null;

        Assert.assertTrue(InventorySlotUtil.hasAnyStorageSpace(
                inventory(contents), stack(Material.STONE, 8)));
    }

    /** 批量写入后反向回滚应恢复原有同类堆叠和空槽。 */
    @Test
    public void partialAddCanBeRolledBackWithoutChangingTotal() {
        ItemStack[] contents = fullStorage();
        contents[0] = stack(Material.STONE, 60);
        contents[1] = null;
        Inventory inventory = inventory(contents);

        int accepted = InventorySlotUtil.addPartial(inventory, stack(Material.STONE, 1),
                20, 0, contents.length);
        int rolledBack = InventorySlotUtil.removePartialReverse(inventory, stack(Material.STONE, 1),
                accepted, 0, contents.length);

        Assert.assertEquals(20, accepted);
        Assert.assertEquals(accepted, rolledBack);
        Assert.assertEquals(60, inventory.getItem(0).getAmount());
        Assert.assertNull(inventory.getItem(1));
    }

    /** 创建全满的 36 槽玩家存储区。 */
    private ItemStack[] fullStorage() {
        ItemStack[] contents = new ItemStack[36];
        for (int index = 0; index < contents.length; index++) {
            contents[index] = stack(Material.DIRT, 64);
        }
        return contents;
    }

    /** 创建不依赖 Bukkit 全局 ItemFactory 的测试物品。 */
    private ItemStack stack(Material material, int amount) {
        return new TestItemStack(material, amount);
    }

    /** 创建只实现 getStorageContents 的轻量 Inventory 代理。 */
    private Inventory inventory(final ItemStack[] contents) {
        return (Inventory) Proxy.newProxyInstance(
                Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
                (proxy, method, args) -> {
                    if ("getStorageContents".equals(method.getName())) {
                        return contents;
                    }
                    if ("getSize".equals(method.getName())) {
                        return contents.length;
                    }
                    if ("getItem".equals(method.getName())) {
                        return contents[((Integer) args[0]).intValue()];
                    }
                    if ("setItem".equals(method.getName())) {
                        contents[((Integer) args[0]).intValue()] = (ItemStack) args[1];
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "InventorySlotUtilTestInventory";
                    }
                    return null;
                });
    }

    /** 仅在纯 JUnit 中按材质实现相似性，避免启动完整 Bukkit Server。 */
    private static final class TestItemStack extends ItemStack {
        /** 创建测试物品。 */
        private TestItemStack(Material material, int amount) {
            super(material, amount);
        }

        /** 测试容量只需要比较材质。 */
        @Override
        public boolean isSimilar(ItemStack itemStack) {
            return itemStack != null && getType() == itemStack.getType();
        }
    }
}
