package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

/** 统一读取和修改掉落物实际数量，避免扫地绑定某一种堆叠实现。 */
public interface ItemQuantityService {
    /** 返回掉落物代表的实际数量。 */
    int getAmount(Item item);

    /** 判断物品是否正在等待原版拾取收尾，期间禁止其它业务提交数量。 */
    default boolean isReserved(Item item) {
        return false;
    }

    /** 仅在当前数量仍等于预期值时写入剩余数量。 */
    boolean setRemaining(Item item, int expectedAmount, int remainingAmount);

    /** 把样品按原版堆叠上限拆成合法快照并逐个交给消费者。 */
    void forEachStack(ItemStack sample, int amount, ItemStackConsumer consumer);

    /** 接收一个合法原版数量的物品快照。 */
    interface ItemStackConsumer {
        /** 处理单个快照。 */
        void accept(ItemStack itemStack);
    }
}
