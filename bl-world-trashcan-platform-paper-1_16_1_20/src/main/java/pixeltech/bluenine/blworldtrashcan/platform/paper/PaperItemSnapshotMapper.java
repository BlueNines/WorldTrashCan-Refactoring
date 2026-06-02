package pixeltech.bluenine.blworldtrashcan.platform.paper;

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

/** Paper 现代物品快照映射器，使用掉落实体 PDC 保存玩家标记。 */
public final class PaperItemSnapshotMapper implements ItemSnapshotMapper {
    private final NamespacedKey ownerKey;

    /** 创建 Paper 物品映射器。 */
    public PaperItemSnapshotMapper(Plugin plugin) {
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

    /** 将掉落实体转成核心快照，优先读取实体 PDC，兼容旧 ItemStack PDC。 */
    @Override
    public ItemSnapshot toSnapshot(Item item) {
        if (item == null) {
            return toSnapshot((ItemStack) null);
        }
        ItemSnapshot snapshot = toSnapshot(item.getItemStack());
        UUID ownerUuid = readOwnerUuid(item);
        return ownerUuid == null ? snapshot : snapshot.withOwnerUuid(ownerUuid);
    }

    /** 给玩家主动丢弃的实体写入 PDC 所属玩家标记，不污染 ItemStack 叠加。 */
    @Override
    public void markOwner(Item item, Player player) {
        if (item == null || player == null) {
            return;
        }
        item.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
    }

    /** 清理旧版本写入 ItemStack 的 owner PDC，避免入库后影响物品叠加。 */
    @Override
    public ItemStack sanitizeForStorage(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            return itemStack;
        }
        ItemStack clean = itemStack.clone();
        ItemMeta cleanMeta = clean.getItemMeta();
        if (cleanMeta != null) {
            cleanMeta.getPersistentDataContainer().remove(ownerKey);
            clean.setItemMeta(cleanMeta);
        }
        return clean;
    }

    /** 从掉落实体 PDC 读取玩家 UUID。 */
    private UUID readOwnerUuid(Item item) {
        if (item == null) {
            return null;
        }
        return parseOwnerUuid(item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    /** 从旧 ItemStack PDC 读取玩家 UUID。 */
    private UUID readOwnerUuid(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        return parseOwnerUuid(meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    /** 解析玩家 UUID 字符串。 */
    private UUID parseOwnerUuid(String raw) {
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
