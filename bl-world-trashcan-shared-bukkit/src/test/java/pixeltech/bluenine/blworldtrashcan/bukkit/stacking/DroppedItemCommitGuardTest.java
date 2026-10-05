package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** 验证扫地路由不会提交已经拾取或发生变化的掉落物。 */
public final class DroppedItemCommitGuardTest {
    /** 实体在路由前已经失效时不得生成可提交状态。 */
    @Test
    public void invalidItemCannotBeCaptured() {
        MutableItem item = new MutableItem(Material.DIAMOND, 1);
        item.invalidate();

        Assert.assertNull(DroppedItemCommitGuard.capture(item.proxy(), null));
    }

    /** 实体在目标写入后被拾取时来源提交必须失败。 */
    @Test
    public void pickedItemCannotCommitAfterDestinationWrite() {
        MutableItem item = new MutableItem(Material.DIAMOND, 1);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);
        item.invalidate();

        Assert.assertFalse(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, null));
    }

    /** 删除调用未真正使实体失效时不得报告来源提交成功。 */
    @Test
    public void ineffectiveRemovalCannotCommit() {
        MutableItem item = new MutableItem(Material.DIAMOND, 1);
        item.setRemovalEffective(false);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);

        Assert.assertFalse(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, null));
        Assert.assertTrue(item.isValid());
    }

    /** 正常完整删除必须提交成功并使实体失效。 */
    @Test
    public void effectiveRemovalCommits() {
        MutableItem item = new MutableItem(Material.DIAMOND, 1);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);

        Assert.assertTrue(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, null));
        Assert.assertFalse(item.isValid());
    }

    /** 路由期间物品类型变化时不得扣除另一件物品。 */
    @Test
    public void changedIdentityCannotCommit() {
        MutableItem item = new MutableItem(Material.DIAMOND, 4);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);
        item.replace(Material.EMERALD, 4);

        Assert.assertFalse(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, null));
        Assert.assertEquals(Material.EMERALD, item.stack().getType());
    }

    /** 路由期间物品数量变化时不得按旧数量扣除。 */
    @Test
    public void changedAmountCannotCommit() {
        MutableItem item = new MutableItem(Material.DIAMOND, 4);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);
        item.replace(Material.DIAMOND, 3);

        Assert.assertFalse(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, null));
        Assert.assertEquals(3, item.stack().getAmount());
    }

    /** 部分接收时必须留下精确数量和原物品身份。 */
    @Test
    public void partialCommitKeepsExactRemainder() {
        MutableItem item = new MutableItem(Material.DIAMOND, 10);
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), null);

        Assert.assertTrue(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 3, null));
        Assert.assertEquals(3, item.stack().getAmount());
        Assert.assertEquals(Material.DIAMOND, item.stack().getType());
    }

    /** 等待原版吸取的来源不能先写入垃圾桶再尝试扣减。 */
    @Test
    public void reservedPickupCannotBeCaptured() {
        MutableItem item = new MutableItem(Material.DIAMOND, 64);
        ReservedQuantityService quantities = new ReservedQuantityService();
        quantities.reserved = true;

        Assert.assertNull(DroppedItemCommitGuard.capture(item.proxy(), quantities));
    }

    /** 捕获后进入拾取事务时，旧扫地状态也必须失效。 */
    @Test
    public void pickupReservationInvalidatesPreviouslyCapturedState() {
        MutableItem item = new MutableItem(Material.DIAMOND, 64);
        ReservedQuantityService quantities = new ReservedQuantityService();
        DroppedItemCommitGuard.State state = DroppedItemCommitGuard.capture(item.proxy(), quantities);
        quantities.reserved = true;

        Assert.assertFalse(DroppedItemCommitGuard.isCurrent(item.proxy(), state, quantities));
        Assert.assertFalse(DroppedItemCommitGuard.commitRemaining(item.proxy(), state, 0, quantities));
        Assert.assertTrue(item.isValid());
    }

    /** 只提供占用状态的数量服务，提交方法必须始终不被测试调用。 */
    private static final class ReservedQuantityService implements ItemQuantityService {
        private boolean reserved;

        /** 返回当前物理数量。 */
        @Override
        public int getAmount(Item item) {
            return item.getItemStack().getAmount();
        }

        /** 返回模拟的原版拾取占用。 */
        @Override
        public boolean isReserved(Item item) {
            return reserved;
        }

        /** 被占用的来源不应到达数量提交。 */
        @Override
        public boolean setRemaining(Item item, int expectedAmount, int remainingAmount) {
            Assert.fail("reserved pickup reached source commit");
            return false;
        }

        /** 此专项不拆分审计样品。 */
        @Override
        public void forEachStack(ItemStack sample, int amount, ItemStackConsumer consumer) {
        }
    }

    /** 保存测试掉落物可变状态。 */
    private static final class MutableItem implements InvocationHandler {
        private final Item proxy;
        private final UUID entityId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        private ItemStack itemStack;
        private boolean valid = true;
        private boolean removalEffective = true;

        /** 创建指定材质和数量的有效掉落物。 */
        private MutableItem(Material material, int amount) {
            this.itemStack = new ItemStack(material, amount);
            this.proxy = Item.class.cast(Proxy.newProxyInstance(
                    Item.class.getClassLoader(), new Class<?>[]{Item.class}, this));
        }

        /** 返回测试代理。 */
        private Item proxy() {
            return proxy;
        }

        /** 返回当前物品堆叠。 */
        private ItemStack stack() {
            return itemStack;
        }

        /** 模拟实体已被玩家拾取。 */
        private void invalidate() {
            valid = false;
        }

        /** 设置 remove 是否真正使实体失效。 */
        private void setRemovalEffective(boolean removalEffective) {
            this.removalEffective = removalEffective;
        }

        /** 替换实体当前承载的物品。 */
        private void replace(Material material, int amount) {
            itemStack = new ItemStack(material, amount);
        }

        /** 返回实体当前是否有效。 */
        private boolean isValid() {
            return valid;
        }

        /** 实现测试需要的 Bukkit Item 方法。 */
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
            if ("getUniqueId".equals(name)) {
                return entityId;
            }
            if ("isValid".equals(name)) {
                return Boolean.valueOf(valid);
            }
            if ("isDead".equals(name)) {
                return Boolean.valueOf(!valid);
            }
            if ("remove".equals(name)) {
                if (removalEffective) {
                    valid = false;
                }
                return null;
            }
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
}
