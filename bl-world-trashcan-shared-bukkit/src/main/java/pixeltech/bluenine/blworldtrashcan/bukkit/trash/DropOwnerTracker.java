package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 低版本无 PDC 时使用的短期掉落物归属追踪器。 */
public final class DropOwnerTracker {
    private final ServerPlatform platform;
    private final Map<UUID, OwnerEntry> owners = new ConcurrentHashMap<>();

    /** 创建掉落物归属追踪器。 */
    public DropOwnerTracker(ServerPlatform platform) {
        this.platform = platform;
    }

    /** 记录掉落实体所属玩家，并在过期后自动释放。 */
    public void track(Item item, Player player, int ttlSeconds) {
        if (item == null || player == null || ttlSeconds <= 0) {
            return;
        }
        final UUID itemUuid = item.getUniqueId();
        final long expiresAtMillis = System.currentTimeMillis() + ttlSeconds * 1000L;
        owners.put(itemUuid, new OwnerEntry(player.getUniqueId(), expiresAtMillis));
        if (platform != null) {
            platform.scheduler().runLater(new Runnable() {
                /** 清理已经过期且未被新记录覆盖的归属。 */
                @Override
                public void run() {
                    removeIfExpired(itemUuid, expiresAtMillis);
                }
            }, Math.max(1L, ttlSeconds * 20L));
        }
    }

    /** 查找掉落实体所属玩家，过期记录会被同步清理。 */
    public UUID findOwner(Item item) {
        if (item == null) {
            return null;
        }
        OwnerEntry entry = owners.get(item.getUniqueId());
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            owners.remove(item.getUniqueId(), entry);
            return null;
        }
        return entry.ownerUuid;
    }

    /** 移除并返回掉落实体所属玩家，过期记录不会继续生效。 */
    public UUID removeOwner(Item item) {
        if (item == null) {
            return null;
        }
        OwnerEntry entry = owners.remove(item.getUniqueId());
        if (entry == null || entry.isExpired()) {
            return null;
        }
        return entry.ownerUuid;
    }

    /** 清空全部短期归属记录。 */
    public void clear() {
        owners.clear();
    }

    /** 仅当记录仍是同一过期时间时移除，避免误删新记录。 */
    private void removeIfExpired(UUID itemUuid, long expiresAtMillis) {
        OwnerEntry entry = owners.get(itemUuid);
        if (entry != null && entry.expiresAtMillis == expiresAtMillis && entry.isExpired()) {
            owners.remove(itemUuid, entry);
        }
    }

    /** 单个掉落实体的 owner 与过期时间。 */
    private static final class OwnerEntry {
        private final UUID ownerUuid;
        private final long expiresAtMillis;

        /** 创建 owner 记录。 */
        private OwnerEntry(UUID ownerUuid, long expiresAtMillis) {
            this.ownerUuid = ownerUuid;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** 判断记录是否已经过期。 */
        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }
}
