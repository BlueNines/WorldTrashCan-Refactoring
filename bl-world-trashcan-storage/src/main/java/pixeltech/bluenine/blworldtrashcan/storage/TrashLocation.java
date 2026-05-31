package pixeltech.bluenine.blworldtrashcan.storage;

import java.util.Objects;

/** 世界垃圾桶箱子位置，存储层不依赖 Bukkit Location。 */
public final class TrashLocation {
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    /** 创建垃圾桶位置。 */
    public TrashLocation(String worldName, int x, int y, int z) {
        this.worldName = worldName == null ? "" : worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 返回世界名。 */
    public String getWorldName() {
        return worldName;
    }

    /** 返回方块 X 坐标。 */
    public int getX() {
        return x;
    }

    /** 返回方块 Y 坐标。 */
    public int getY() {
        return y;
    }

    /** 返回方块 Z 坐标。 */
    public int getZ() {
        return z;
    }

    /** 判断两个位置是否相同。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrashLocation)) {
            return false;
        }
        TrashLocation that = (TrashLocation) other;
        return x == that.x && y == that.y && z == that.z && worldName.equals(that.worldName);
    }

    /** 返回位置哈希值。 */
    @Override
    public int hashCode() {
        return Objects.hash(worldName, x, y, z);
    }

    /** 返回便于日志阅读的位置文本。 */
    @Override
    public String toString() {
        return worldName + ":" + x + "," + y + "," + z;
    }
}
