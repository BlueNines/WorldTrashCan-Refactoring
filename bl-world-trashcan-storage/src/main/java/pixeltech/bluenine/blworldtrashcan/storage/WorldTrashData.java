package pixeltech.bluenine.blworldtrashcan.storage;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 单世界垃圾桶运行数据。 */
public final class WorldTrashData {
    private final String worldName;
    private final Set<TrashLocation> locations;
    private final Set<String> bannedMaterials;
    private final int maxTrashCanCount;

    /** 创建单世界垃圾桶数据。 */
    public WorldTrashData(String worldName, Set<TrashLocation> locations, Set<String> bannedMaterials, int maxTrashCanCount) {
        this.worldName = worldName == null ? "" : worldName;
        this.locations = locations == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(locations));
        this.bannedMaterials = bannedMaterials == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(bannedMaterials));
        this.maxTrashCanCount = Math.max(0, maxTrashCanCount);
    }

    /** 返回世界名。 */
    public String getWorldName() {
        return worldName;
    }

    /** 返回垃圾桶位置集合。 */
    public Set<TrashLocation> getLocations() {
        return locations;
    }

    /** 返回世界私有黑名单物品类型。 */
    public Set<String> getBannedMaterials() {
        return bannedMaterials;
    }

    /** 返回世界最多可创建垃圾桶数量。 */
    public int getMaxTrashCanCount() {
        return maxTrashCanCount;
    }
}
