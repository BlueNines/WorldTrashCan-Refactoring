package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 向通用垃圾容器菜单提供公共或个人作用域的少量业务差异。 */
interface TrashContainerMenuPolicy {
    /** 返回后台日志使用的稳定作用域名称。 */
    String getLogName();

    /** 返回菜单标题。 */
    String getTitle(Player player, int pageIndex, int maxPages);

    /** 发送容器未启用提示。 */
    void sendDisabled(Player player);

    /** 判断玩家是否允许从当前容器取物，并自行发送拒绝提示。 */
    boolean canTake(Player player);

    /** 在物品交付前执行冷却或收费检查。 */
    boolean beforeTake(Player player, ItemStack itemStack, int requestedAmount);

    /** 玩家背包没有可接收空间时发送当前作用域提示。 */
    void onTakeInventoryFull(Player player);

    /** 记录实际取出结果。 */
    void afterTake(Player player, ItemStack itemStack, String trackingKey, int removedAmount);

    /** 判断玩家是否允许把该物品手动放入，并自行发送拒绝提示。 */
    boolean canManualPut(Player player, ItemStack itemStack);

    /** 记录玩家手动放入的实际结果。 */
    void afterManualPut(Player player, ItemStack itemStack, String trackingKey, int acceptedAmount);

    /** 返回当前作用域语言键对应的格式化消息。 */
    String message(String suffix, String fallback, String... replacements);
}
