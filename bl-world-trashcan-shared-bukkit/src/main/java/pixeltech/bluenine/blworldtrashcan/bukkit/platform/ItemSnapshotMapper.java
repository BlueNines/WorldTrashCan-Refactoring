package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

/** 把 Bukkit 物品转换为核心层快照。 */
public interface ItemSnapshotMapper {
    /** 转换物品快照。 */
    ItemSnapshot toSnapshot(ItemStack itemStack);

    /** 转换掉落实体快照，支持的平台可读取实体自身的归属标记。 */
    default ItemSnapshot toSnapshot(Item item) {
        return toSnapshot(item == null ? null : item.getItemStack());
    }

    /** 给掉落物标记所属玩家；不支持的平台可以无操作。 */
    void markOwner(Item item, Player player);

    /** 清理插件内部标记后返回适合写入垃圾桶的物品。 */
    default ItemStack sanitizeForStorage(ItemStack itemStack) {
        return itemStack;
    }
}
