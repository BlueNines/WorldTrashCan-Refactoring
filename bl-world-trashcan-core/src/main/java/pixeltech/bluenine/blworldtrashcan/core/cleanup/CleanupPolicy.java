package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

/** 清理策略入口，负责把快照转成明确决策。 */
public interface CleanupPolicy {
    /** 决定物品应该进入哪个目标。 */
    TrashRoutingDecision decideItem(ItemSnapshot item, boolean worldTrashAvailable,
                                    boolean personalTrashAvailable, boolean globalTrashAvailable);

    /** 决定物品路由；forceDirectRemove 用于绝对清理世界且仍保留 ignored-* 保护。 */
    default TrashRoutingDecision decideItem(ItemSnapshot item, boolean worldTrashAvailable,
                                            boolean personalTrashAvailable, boolean globalTrashAvailable,
                                            boolean forceDirectRemove) {
        return decideItem(item, worldTrashAvailable, personalTrashAvailable, globalTrashAvailable);
    }

    /** 决定实体是否应该被清理。 */
    EntityCleanupDecision decideEntity(EntitySnapshot entity);
}
