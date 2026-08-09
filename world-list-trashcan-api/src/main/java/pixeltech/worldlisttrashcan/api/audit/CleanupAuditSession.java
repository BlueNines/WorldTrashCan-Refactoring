package pixeltech.worldlisttrashcan.api.audit;

import org.bukkit.inventory.ItemStack;

/** 接收一次扫地过程中已经成功处理的物品。 */
public interface CleanupAuditSession {

    /** 记录物品、精确最终去向和主插件提供的不透明存储追踪键。 */
    void recordItem(ItemStack itemStack, CleanupItemDestination destination, String trackingKey);

    /** 正常或部分完成本轮审计；重复调用必须无副作用。 */
    void complete(CleanupRunCompletion completion);

    /** 放弃本轮审计并释放运行期缓存；重复调用必须无副作用。 */
    void discard();
}
