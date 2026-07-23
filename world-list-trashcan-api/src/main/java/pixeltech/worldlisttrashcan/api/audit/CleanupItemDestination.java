package pixeltech.worldlisttrashcan.api.audit;

import java.util.Objects;
import java.util.UUID;

/** 保存清理物品最终去向，避免附属插件依赖主插件内部路由类。 */
public final class CleanupItemDestination {
    private final CleanupItemDestinationType type;
    private final UUID ownerUuid;
    private final String ownerName;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    /** 创建经过校验的不可变去向。 */
    private CleanupItemDestination(CleanupItemDestinationType type, UUID ownerUuid,
                                   String ownerName, String worldName, int x, int y, int z) {
        this.type = Objects.requireNonNull(type, "type");
        this.ownerUuid = ownerUuid;
        this.ownerName = clean(ownerName);
        this.worldName = clean(worldName);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 创建世界垃圾桶去向；owner 可以为空以兼容旧位置。 */
    public static CleanupItemDestination worldTrash(String worldName, int x, int y, int z,
                                                     UUID ownerUuid, String ownerName) {
        if (clean(worldName).isEmpty()) {
            throw new IllegalArgumentException("worldName cannot be empty");
        }
        return new CleanupItemDestination(CleanupItemDestinationType.WORLD_TRASH,
                ownerUuid, ownerName, worldName, x, y, z);
    }

    /** 创建个人垃圾桶去向。 */
    public static CleanupItemDestination personalTrash(UUID ownerUuid, String ownerName) {
        return new CleanupItemDestination(CleanupItemDestinationType.PERSONAL_TRASH,
                Objects.requireNonNull(ownerUuid, "ownerUuid"), ownerName, "", 0, 0, 0);
    }

    /** 创建公共垃圾桶去向。 */
    public static CleanupItemDestination globalTrash() {
        return new CleanupItemDestination(CleanupItemDestinationType.GLOBAL_TRASH,
                null, "", "", 0, 0, 0);
    }

    /** 创建直接删除去向。 */
    public static CleanupItemDestination directRemove() {
        return new CleanupItemDestination(CleanupItemDestinationType.DIRECT_REMOVE,
                null, "", "", 0, 0, 0);
    }

    /** 创建无法从旧记录还原的未知去向。 */
    public static CleanupItemDestination legacyUnknown() {
        return new CleanupItemDestination(CleanupItemDestinationType.LEGACY_UNKNOWN,
                null, "", "", 0, 0, 0);
    }

    /** 返回去向类型。 */
    public CleanupItemDestinationType getType() {
        return type;
    }

    /** 返回个人垃圾桶 owner 或世界垃圾桶创建者 UUID。 */
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** 返回记录当时的 owner 名字。 */
    public String getOwnerName() {
        return ownerName;
    }

    /** 返回世界垃圾桶所在世界。 */
    public String getWorldName() {
        return worldName;
    }

    /** 返回世界垃圾桶方块 X。 */
    public int getX() {
        return x;
    }

    /** 返回世界垃圾桶方块 Y。 */
    public int getY() {
        return y;
    }

    /** 返回世界垃圾桶方块 Z。 */
    public int getZ() {
        return z;
    }

    /** 返回不含可变玩家名的稳定账本键。 */
    public String trackingKey() {
        if (type == CleanupItemDestinationType.PERSONAL_TRASH) {
            return type.name() + ":" + ownerUuid;
        }
        if (type == CleanupItemDestinationType.WORLD_TRASH) {
            return type.name() + ":" + worldName + ":" + x + ":" + y + ":" + z;
        }
        return type.name();
    }

    /** 清理可选字符串字段。 */
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
