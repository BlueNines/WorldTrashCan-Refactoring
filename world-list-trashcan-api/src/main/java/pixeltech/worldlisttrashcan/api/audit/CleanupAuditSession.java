package pixeltech.worldlisttrashcan.api.audit;

import org.bukkit.inventory.ItemStack;

/** 接收一次扫地过程中已经成功处理的物品。 */
public interface CleanupAuditSession {

    /** 记录一个已经被主插件成功处理的物品。 */
    void recordItem(ItemStack itemStack);

    /** 记录物品和精确最终去向；旧实现默认退化为只记录物品。 */
    default void recordItem(ItemStack itemStack, CleanupItemDestination destination) {
        recordItem(itemStack);
    }

    /** 正常或部分完成本轮审计；重复调用必须无副作用。 */
    void complete(CleanupRunCompletion completion);

    /** 放弃本轮审计并释放运行期缓存；重复调用必须无副作用。 */
    void discard();
}
