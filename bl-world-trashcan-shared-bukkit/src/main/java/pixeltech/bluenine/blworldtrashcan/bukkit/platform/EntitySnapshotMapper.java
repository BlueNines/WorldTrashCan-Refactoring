package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.entity.Entity;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;

/** 把 Bukkit 实体转换为核心层快照。 */
public interface EntitySnapshotMapper {
    /** 转换实体快照。 */
    EntitySnapshot toSnapshot(Entity entity);
}
