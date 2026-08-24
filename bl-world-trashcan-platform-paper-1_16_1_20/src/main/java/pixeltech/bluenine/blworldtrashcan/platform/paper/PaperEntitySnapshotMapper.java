package pixeltech.bluenine.blworldtrashcan.platform.paper;

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
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntitySnapshotRequirements;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;

/** Paper 实体快照映射器。 */
public final class PaperEntitySnapshotMapper implements EntitySnapshotMapper {
    /** 将 Bukkit 实体转成核心层快照。 */
    @Override
    public EntitySnapshot toSnapshot(Entity entity) {
        return toSnapshot(entity, EntitySnapshotRequirements.all());
    }

    /** 按当前清理配置读取 Paper 实体快照。 */
    @Override
    public EntitySnapshot toSnapshot(Entity entity, EntitySnapshotRequirements requirements) {
        if (entity == null) {
            return new EntitySnapshot("", "", "", false, false, false, false, false, false);
        }
        EntitySnapshotRequirements actual = requirements == null
                ? EntitySnapshotRequirements.all() : requirements;
        boolean insideBoat = actual.readInsideBoat()
                && entity.isInsideVehicle() && entity.getVehicle() instanceof Boat;
        String entityName = actual.readEntityName() ? entity.getName() : "";
        String rawCustomName = actual.readCustomName() ? entity.getCustomName() : null;
        String customName = rawCustomName == null ? "" : rawCustomName;
        return new EntitySnapshot(
                entity.getType().name(),
                entityName,
                customName,
                entity instanceof LivingEntity,
                entity instanceof Monster,
                entity instanceof Projectile,
                insideBoat,
                actual.readSaddle() && hasSaddle(entity),
                actual.readOwner() && hasTameableOwner(entity)
        );
    }

    /** 判断现代 Paper 实体是否实际装备了鞍。 */
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
