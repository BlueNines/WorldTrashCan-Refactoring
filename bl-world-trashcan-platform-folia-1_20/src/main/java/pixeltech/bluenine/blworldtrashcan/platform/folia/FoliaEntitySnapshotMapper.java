package pixeltech.bluenine.blworldtrashcan.platform.folia;

import org.bukkit.Material;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;

/** Folia 实体快照映射器。 */
public final class FoliaEntitySnapshotMapper implements EntitySnapshotMapper {
    /** 将 Bukkit 实体转成核心层快照。 */
    @Override
    public EntitySnapshot toSnapshot(Entity entity) {
        if (entity == null) {
            return new EntitySnapshot("", "", "", false, false, false, false, false, false);
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
                insideBoat,
                hasSaddle(entity),
                hasTameableOwner(entity)
        );
    }

    /** 判断 Folia 实体是否实际装备了鞍。 */
    private boolean hasSaddle(Entity entity) {
        if (entity instanceof Steerable && ((Steerable) entity).hasSaddle()) {
            return true;
        }
        if (!(entity instanceof AbstractHorse)) {
            return false;
        }
        ItemStack saddle = ((AbstractHorse) entity).getInventory().getSaddle();
        return saddle != null && saddle.getType() != Material.AIR;
    }

    /** 判断实体是否拥有 Bukkit Tameable 主人。 */
    private boolean hasTameableOwner(Entity entity) {
        return entity instanceof Tameable && ((Tameable) entity).getOwner() != null;
    }
}
