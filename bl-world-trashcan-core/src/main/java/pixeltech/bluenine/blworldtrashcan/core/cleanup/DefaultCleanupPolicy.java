package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;
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
        return decideItem(item, worldTrashAvailable, personalTrashAvailable, globalTrashAvailable, false);
    }

    /** 按绝对保护、强制直删、自定义路由和普通路由的固定优先级决定物品去向。 */
    @Override
    public TrashRoutingDecision decideItem(ItemSnapshot item, boolean worldTrashAvailable,
                                           boolean personalTrashAvailable, boolean globalTrashAvailable,
                                           boolean forceDirectRemove) {
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
        if (forceDirectRemove) {
            return new TrashRoutingDecision(TrashRoute.REMOVE, "direct-remove-world");
        }
        TrashRoutingDecision customDecision = decideCustomItem(item, personalTrashAvailable);
        if (customDecision != null) {
            return customDecision;
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
        RejectedCleanupAction rejectedAction = item.getGlobalWhitelistRejectedAction();
        if (rejectedAction == RejectedCleanupAction.KEEP_GROUND) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "global-whitelist-rejected-keep-ground");
        }
        if (rejectedAction == RejectedCleanupAction.DIRECT_REMOVE) {
            return new TrashRoutingDecision(TrashRoute.REMOVE, "global-whitelist-rejected-remove");
        }
        return new TrashRoutingDecision(TrashRoute.REMOVE, "no-trash-available");
    }

    /** 返回命中新自定义识别规则后的路由决策；未命中或未启用时返回 null。 */
    private TrashRoutingDecision decideCustomItem(ItemSnapshot item, boolean personalTrashAvailable) {
        CustomItemRoutingSettings routing = settings.getCustomItemRouting();
        if (!routing.isEnabled() || !item.isCustomRoutingMatched()) {
            return null;
        }
        if (routing.getMode() == CustomItemRoutingSettings.Mode.KEEP_GROUND) {
            return new TrashRoutingDecision(TrashRoute.SKIP, "custom-item-keep-ground");
        }
        if (routing.getMode() == CustomItemRoutingSettings.Mode.DIRECT_REMOVE) {
            return new TrashRoutingDecision(TrashRoute.REMOVE, "custom-item-direct-remove");
        }
        if (personalTrashAvailable && item.getOwnerUuid() != null) {
            return new TrashRoutingDecision(TrashRoute.PERSONAL_TRASH, "custom-item-personal-only");
        }
        if (routing.getPersonalUnavailable() == CustomItemRoutingSettings.UnavailableAction.DIRECT_REMOVE) {
            return new TrashRoutingDecision(TrashRoute.REMOVE, "custom-item-personal-unavailable-remove");
        }
        return new TrashRoutingDecision(TrashRoute.SKIP, "custom-item-personal-unavailable-keep-ground");
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
        if (settings.isIgnoreEntitiesWithSaddle() && entity.hasSaddle()) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "entity-with-saddle");
        }
        if (settings.isIgnoreEntitiesWithOwner() && entity.hasOwner()) {
            return new EntityCleanupDecision(EntityCleanupAction.SKIP, "entity-with-owner");
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
