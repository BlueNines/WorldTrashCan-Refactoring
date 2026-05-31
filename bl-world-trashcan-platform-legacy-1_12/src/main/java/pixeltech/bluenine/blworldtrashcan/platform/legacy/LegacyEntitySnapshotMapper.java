package pixeltech.bluenine.blworldtrashcan.platform.legacy;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;

/** Legacy 1.12 实体快照映射器。 */
public final class LegacyEntitySnapshotMapper implements EntitySnapshotMapper {
    /** 将 Bukkit 实体转换为核心快照。 */
    @Override
    public EntitySnapshot toSnapshot(Entity entity) {
        if (entity == null) {
            return new EntitySnapshot("", "", "", false, false, false, false);
        }
        boolean insideBoat = entity.isInsideVehicle() && entity.getVehicle() instanceof Boat;
        String customName = entity.getCustomName() == null ? "" : entity.getCustomName();
        return new EntitySnapshot(
                entity.getType().name(),
                entity.getName(),
                customName,
                entity instanceof LivingEntity,
                entity instanceof Monster,
                entity instanceof Projectile,
                insideBoat
        );
    }
}

