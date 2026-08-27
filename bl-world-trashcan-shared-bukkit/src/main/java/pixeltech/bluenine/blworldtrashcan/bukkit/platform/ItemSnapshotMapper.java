package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.UUID;

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

    /** 给掉落物写入有明确到期时间的损坏回收归属；不支持的平台可以无操作。 */
    default void markDamageRecoveryOwner(Item item, Player player, long expiresAtMillis) {
    }

    /** 读取尚未过期的损坏回收归属；不支持的平台返回 null。 */
    default UUID findDamageRecoveryOwner(Item item) {
        return null;
    }

    /** 清理掉落物的损坏回收归属；不支持的平台可以无操作。 */
    default void clearDamageRecoveryOwner(Item item) {
    }

    /** 清理插件内部标记后返回适合写入垃圾桶的物品。 */
    default ItemStack sanitizeForStorage(ItemStack itemStack) {
        return itemStack;
    }
}
