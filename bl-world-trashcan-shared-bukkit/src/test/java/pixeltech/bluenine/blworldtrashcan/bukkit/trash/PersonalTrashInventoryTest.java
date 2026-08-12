package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** 验证个人垃圾桶标题刷新与物品迁移规则。 */
public final class PersonalTrashInventoryTest {
    /** 标题变化且无人查看时应重建菜单。 */
    @Test
    public void recreatesInventoryForChangedTitleWithoutViewers() {
        Assert.assertTrue(PersonalTrashService.shouldRecreateInventory("旧标题", "新标题", true));
    }

    /** 相同标题不应产生无意义的菜单重建。 */
    @Test
    public void keepsInventoryForUnchangedTitle() {
        Assert.assertFalse(PersonalTrashService.shouldRecreateInventory("标题", "标题", true));
    }

    /** 菜单正在被查看时不应重建，避免产生可操作的副本。 */
    @Test
    public void keepsInventoryWhileViewed() {
        Assert.assertFalse(PersonalTrashService.shouldRecreateInventory("旧标题", "新标题", false));
    }

    /** 重建菜单时应保留原槽位、材质和物品数量。 */
    @Test
    public void copiesInventoryContentsWithoutChangingItems() {
        InventoryState sourceState = new InventoryState(54);
        InventoryState targetState = new InventoryState(54);
        ItemStack stone = new ItemStack(Material.STONE, 32);
        ItemStack dirt = new ItemStack(Material.DIRT, 7);
        sourceState.contents[0] = stone;
        sourceState.contents[53] = dirt;

        PersonalTrashService.moveInventoryContents(sourceState.inventory(), targetState.inventory());

        Assert.assertSame(stone, targetState.contents[0]);
        Assert.assertEquals(Material.STONE, targetState.contents[0].getType());
        Assert.assertEquals(32, targetState.contents[0].getAmount());
        Assert.assertSame(dirt, targetState.contents[53]);
        Assert.assertEquals(Material.DIRT, targetState.contents[53].getType());
        Assert.assertEquals(7, targetState.contents[53].getAmount());
        Assert.assertNull(sourceState.contents[0]);
        Assert.assertNull(sourceState.contents[53]);
    }

    /** 为测试提供只实现槽位读写的轻量背包。 */
    private static final class InventoryState implements InvocationHandler {
        private final ItemStack[] contents;

        /** 创建指定容量的测试背包。 */
        private InventoryState(int size) {
            this.contents = new ItemStack[size];
        }

        /** 创建 Bukkit Inventory 代理。 */
        private Inventory inventory() {
            return (Inventory) Proxy.newProxyInstance(
                    Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class}, this);
        }

        /** 处理测试所需的槽位方法。 */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getSize".equals(method.getName())) {
                return Integer.valueOf(contents.length);
            }
            if ("getItem".equals(method.getName())) {
                return contents[((Integer) args[0]).intValue()];
            }
            if ("setItem".equals(method.getName())) {
                contents[((Integer) args[0]).intValue()] = (ItemStack) args[1];
                return null;
            }
            if ("clear".equals(method.getName()) && (args == null || args.length == 0)) {
                for (int slot = 0; slot < contents.length; slot++) {
                    contents[slot] = null;
                }
                return null;
            }
            if ("toString".equals(method.getName())) {
                return "PersonalTrashInventoryTestInventory";
            }
            return null;
        }
    }
}
