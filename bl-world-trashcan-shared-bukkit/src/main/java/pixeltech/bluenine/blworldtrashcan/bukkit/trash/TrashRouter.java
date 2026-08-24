package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;

import java.util.UUID;

/** Bukkit 层垃圾桶路由接口。 */
public interface TrashRouter {
    /** 判断世界垃圾桶是否可用。 */
    boolean hasWorldTrash(World world, ItemStack itemStack);

    /** 判断个人垃圾桶是否可用。 */
    boolean hasPersonalTrash(UUID ownerUuid, ItemStack itemStack);

    /** 判断公共垃圾桶是否可用。 */
    boolean hasGlobalTrash(ItemStack itemStack);

    /** 一次完成公共桶准入与容量检查，默认实现兼容现有路由器。 */
    default GlobalTrashCheck checkGlobalTrash(ItemStack itemStack) {
        boolean available = hasGlobalTrash(itemStack);
        RejectedCleanupAction rejected = !available && isGlobalTrashRejectedByWhitelist(itemStack)
                ? getGlobalTrashRejectedCleanupAction()
                : null;
        return new GlobalTrashCheck(available, rejected);
    }

    /** 判断物品是否被公共桶白名单拒绝。 */
    default boolean isGlobalTrashRejectedByWhitelist(ItemStack itemStack) {
        return false;
    }

    /** 返回公共桶白名单拒绝扫地物品后的动作。 */
    default RejectedCleanupAction getGlobalTrashRejectedCleanupAction() {
        return RejectedCleanupAction.KEEP_GROUND;
    }

    /** 按核心决策存放物品并返回实际成功目标。 */
    TrashRoutingResult routeDetailed(World world, UUID ownerUuid, ItemStack itemStack,
                                     TrashRoute route, boolean cleanupSource);

    /** 按独立实际数量路由样品；未覆盖的实现保守退回单个 ItemStack 数量。 */
    default TrashRoutingResult routeDetailedAmount(World world, UUID ownerUuid, ItemStack sample,
                                                    int requestedAmount, TrashRoute route,
                                                    boolean cleanupSource) {
        if (sample == null || requestedAmount <= 0) {
            return TrashRoutingResult.failure();
        }
        ItemStack request = sample.clone();
        request.setAmount(requestedAmount);
        return routeDetailed(world, ownerUuid, request, route, cleanupSource);
    }

    /** 按非扫地来源路由物品并返回是否成功。 */
    default boolean route(World world, UUID ownerUuid, ItemStack itemStack, TrashRoute route) {
        return routeDetailed(world, ownerUuid, itemStack, route, false).isSuccess();
    }

    /** 回滚一次已写入但来源扣减失败的路由结果，返回实际撤回数量。 */
    default int rollbackRouted(TrashRoutingResult result, ItemStack sample, int requestedAmount) {
        return 0;
    }

    /** 重载路由数据。 */
    void reload();

    /** 返回因为未加载区块而跳过的世界垃圾桶容器访问次数。 */
    int getSkippedUnloadedChunkAccesses();
}
