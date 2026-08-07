package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.inventory.ItemStack;

/** 为公共垃圾桶生成当前运行时稳定的物品身份键。 */
public interface ItemIdentityProvider {
    /** 返回身份实现名称，用于启动日志和诊断。 */
    String id();

    /** 生成不包含数量字段的物品身份键；无法生成时返回 null。 */
    String key(ItemStack itemStack);
}
