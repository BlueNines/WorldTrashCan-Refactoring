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

    /** 尝试按核心路由决策存放物品。 */
    boolean route(World world, UUID ownerUuid, ItemStack itemStack, TrashRoute route);

    /** 重载路由数据。 */
    void reload();
}
