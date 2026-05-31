package pixeltech.bluenine.blworldtrashcan.platform.folia;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Folia 物品快照映射器，支持 PDC 玩家标记。 */
public final class FoliaItemSnapshotMapper implements ItemSnapshotMapper {
    private final NamespacedKey ownerKey;

    /** 创建 Folia 物品映射器。 */
    public FoliaItemSnapshotMapper(Plugin plugin) {
        this.ownerKey = new NamespacedKey(plugin, "player_uuid");
    }

    /** 将 Bukkit ItemStack 转成核心层快照。 */
    @Override
    public ItemSnapshot toSnapshot(ItemStack itemStack) {
        if (itemStack == null) {
            return new ItemSnapshot("", 0, "", Collections.<String>emptyList(), null);
        }
        ItemMeta meta = itemStack.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : Collections.<String>emptyList();
        UUID ownerUuid = readOwnerUuid(meta);
        return new ItemSnapshot(itemStack.getType().name(), itemStack.getAmount(), displayName, lore, ownerUuid);
    }

    /** 给玩家主动丢弃的物品写入 PDC 所属玩家标记。 */
    @Override
    public void markOwner(Item item, Player player) {
        if (item == null || player == null) {
            return;
        }
        ItemStack itemStack = item.getItemStack();
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        itemStack.setItemMeta(meta);
        item.setItemStack(itemStack);
    }

    /** 从 PDC 读取玩家 UUID。 */
    private UUID readOwnerUuid(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
