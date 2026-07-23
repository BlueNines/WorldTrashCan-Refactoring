package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.UUID;

/** Bukkit 层垃圾桶路由接口。 */
public interface TrashRouter {
    /** 判断世界垃圾桶是否可用。 */
    boolean hasWorldTrash(World world, ItemStack itemStack);

    /** 判断个人垃圾桶是否可用。 */
    boolean hasPersonalTrash(UUID ownerUuid, ItemStack itemStack);

    /** 判断公共垃圾桶是否可用。 */
    boolean hasGlobalTrash(ItemStack itemStack);

    /** 按核心决策存放物品并返回实际成功目标。 */
    TrashRoutingResult routeDetailed(World world, UUID ownerUuid, ItemStack itemStack,
                                     TrashRoute route, boolean cleanupSource);

    /** 按非扫地来源路由物品并返回是否成功。 */
    default boolean route(World world, UUID ownerUuid, ItemStack itemStack, TrashRoute route) {
        return routeDetailed(world, ownerUuid, itemStack, route, false).isSuccess();
    }

    /** 重载路由数据。 */
    void reload();

    /** 返回因为未加载区块而跳过的世界垃圾桶容器访问次数。 */
    int getSkippedUnloadedChunkAccesses();
}
