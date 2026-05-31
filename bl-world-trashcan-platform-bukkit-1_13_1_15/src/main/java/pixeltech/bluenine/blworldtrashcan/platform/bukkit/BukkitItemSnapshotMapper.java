package pixeltech.bluenine.blworldtrashcan.platform.bukkit;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.Collections;
import java.util.List;

/** Bukkit 1.13-1.15 物品快照映射器，不依赖 PDC，避免 1.13 运行时缺类。 */
public final class BukkitItemSnapshotMapper implements ItemSnapshotMapper {
    /** 将 Bukkit 物品转换为核心快照。 */
    @Override
    public ItemSnapshot toSnapshot(ItemStack itemStack) {
        if (itemStack == null) {
            return new ItemSnapshot("", 0, "", Collections.<String>emptyList(), null);
        }
        ItemMeta meta = itemStack.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : Collections.<String>emptyList();
        return new ItemSnapshot(itemStack.getType().name(), itemStack.getAmount(), displayName, lore, null);
    }

    /** 1.13 兼容产物不写 PDC 玩家标记。 */
    @Override
    public void markOwner(Item item, Player player) {
    }
}
