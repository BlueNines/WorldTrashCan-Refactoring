package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import java.util.UUID;

/** 不持有 World 或 Chunk 强引用的区块坐标键。 */
public final class StackingChunkKey {
    private final UUID worldUuid;
    private final int chunkX;
    private final int chunkZ;

    /** 创建区块坐标键。 */
    public StackingChunkKey(UUID worldUuid, int chunkX, int chunkZ) {
        this.worldUuid = worldUuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /** 返回世界 UUID。 */
    public UUID getWorldUuid() {
        return worldUuid;
    }

    /** 返回 chunk X。 */
    public int getChunkX() {
        return chunkX;
    }

    /** 返回 chunk Z。 */
    public int getChunkZ() {
        return chunkZ;
    }

    /** 序列化为状态文件中的紧凑文本。 */
    public String serialize() {
        return worldUuid + ":" + chunkX + ":" + chunkZ;
    }

    /** 从状态文件文本解析区块键。 */
    public static StackingChunkKey parse(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new StackingChunkKey(UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** 按世界和坐标判断相等。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StackingChunkKey)) {
            return false;
        }
        StackingChunkKey key = (StackingChunkKey) other;
        return chunkX == key.chunkX && chunkZ == key.chunkZ && worldUuid.equals(key.worldUuid);
    }

    /** 返回稳定哈希。 */
    @Override
    public int hashCode() {
        int result = worldUuid.hashCode();
        result = 31 * result + chunkX;
        return 31 * result + chunkZ;
    }
}
