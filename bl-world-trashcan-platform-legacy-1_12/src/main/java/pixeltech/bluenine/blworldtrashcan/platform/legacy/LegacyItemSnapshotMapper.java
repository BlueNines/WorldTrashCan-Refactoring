package pixeltech.bluenine.blworldtrashcan.platform.legacy;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.Collections;
import java.util.List;

/** Legacy 1.12 物品快照映射器，不支持 PDC 所属玩家标记。 */
public final class LegacyItemSnapshotMapper implements ItemSnapshotMapper {
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

    /** 1.12 不支持可靠 PDC 标记，保持无操作。 */
    @Override
    public void markOwner(Item item, Player player) {
    }
}

