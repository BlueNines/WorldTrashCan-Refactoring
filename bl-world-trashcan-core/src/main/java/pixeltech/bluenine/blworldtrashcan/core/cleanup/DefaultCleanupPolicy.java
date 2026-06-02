package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

/** 默认清理策略，只处理纯决策，不触碰 Bukkit 或 Folia 对象。 */
public final class DefaultCleanupPolicy implements CleanupPolicy {
    private final CleanupSettings settings;

    /** 创建默认清理策略。 */
    public DefaultCleanupPolicy(CleanupSettings settings) {
        this.settings = settings;
    }

    /** 决定物品应该进入哪个垃圾桶或跳过。 */
    @Override
    public TrashRoutingDecision decideItem(ItemSnapshot item, boolean worldTrashAvailable,
                                           boolean personalTrashAvailable, boolean globalTrashAvailable) {
        if (item == null) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "item-null");
        }
        if (settings.isIgnoredMaterial(item.getMaterialKey())) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "ignored-material");
        }
        if (settings.matchesIgnoredName(item.getDisplayName())) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "ignored-name");
        }
        if (settings.matchesIgnoredLore(item.getLore())) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "ignored-lore");
        }
        if (worldTrashAvailable) {
            return new TrashRoutingDecision(TrashRoute.WORLD_TRASH, "world-trash-available");
        }
        if (personalTrashAvailable && item.getOwnerUuid() != null) {
            return new TrashRoutingDecision(TrashRoute.PERSONAL_TRASH, "personal-trash-owner");
        }
        if (globalTrashAvailable) {
            return new TrashRoutingDecision(TrashRoute.GLOBAL_TRASH, "global-trash-available");
        }
        return new TrashRoutingDecision(TrashRoute.REMOVE, "no-trash-available");
    }

    /** 决定实体是否应该清理。 */
    @Override
    public EntityCleanupDecision decideEntity(EntitySnapshot entity) {
        if (entity == null) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "entity-null");
        }
        if (!settings.isEntityCleanupEnabled()) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "entity-cleanup-disabled");
        }
        if (settings.isIgnoreEntitiesInBoat() && entity.isInsideBoat()) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "inside-boat");
        }
        if (settings.matchesEntityWhitelist(entity.getTypeKey(), entity.getName())) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "entity-whitelist");
        }
        if (settings.matchesEntityBlacklist(entity.getTypeKey(), entity.getName())) {
            return new EntityCleanupDecision(EntityCleanupAction.REMOVE, "entity-blacklist");
        }
        if (isExperienceOrb(entity.getTypeKey()) && settings.isClearExperienceOrb()) {
            return new EntityCleanupDecision(EntityCleanupAction.REMOVE, "experience-orb");
        }
        if (!settings.isClearNamedEntity() && !entity.getCustomName().isEmpty()) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "named-entity");
        }
        if (entity.isProjectile() && settings.isClearProjectile()) {
            return new EntityCleanupDecision(EntityCleanupAction.REMOVE, "projectile");
        }
        if (entity.isMonsterLike() && settings.isClearMonster()) {
            return new EntityCleanupDecision(EntityCleanupAction.REMOVE, "monster");
        }
        if (entity.isLiving() && settings.isClearAnimals()) {
            return new EntityCleanupDecision(EntityCleanupAction.REMOVE, "living");
        }
        return new EntityCleanupDecision(EntityCleanupAction.SKIP, "not-matched");
    }

    /** 判断实体类型是否是经验球。 */
    private boolean isExperienceOrb(String typeKey) {
        String normalized = typeKey == null ? "" : typeKey.trim().toUpperCase();
        return "EXPERIENCE_ORB".equals(normalized) || "XP_ORB".equals(normalized);
    }
}
