package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.inventory.ItemStack;

/** 为不同业务入口统一解析玩家可见的物品名称。 */
public interface ItemDisplayNameResolver {
    /** 返回物品最终显示名。 */
    String resolve(ItemStack itemStack);
}
