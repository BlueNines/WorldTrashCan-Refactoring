package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** 标识公共库存的单玩家视图，不保存或复制公共垃圾桶业务状态。 */
final class GlobalTrashViewHolder implements InventoryHolder {
    private final GlobalTrashService service;
    private final UUID playerId;
    private final int pageIndex;
    private final GlobalTrashStore.ViewSnapshot snapshot;
    private Inventory inventory;

    /** 创建指定玩家和页码的视图标识。 */
    GlobalTrashViewHolder(GlobalTrashService service, UUID playerId, int pageIndex,
                          GlobalTrashStore.ViewSnapshot snapshot) {
        this.service = service;
        this.playerId = playerId;
        this.pageIndex = pageIndex;
        this.snapshot = snapshot;
    }

    /** 绑定 Bukkit 创建完成后的视图库存。 */
    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    /** 判断该视图是否属于指定公共垃圾桶服务。 */
    boolean belongsTo(GlobalTrashService target) {
        return service == target;
    }

    /** 返回视图所属玩家 UUID。 */
    UUID getPlayerId() {
        return playerId;
    }

    /** 返回视图页码下标。 */
    int getPageIndex() {
        return pageIndex;
    }

    /** 返回本次打开冻结的排序和分页引用快照。 */
    GlobalTrashStore.ViewSnapshot getSnapshot() {
        return snapshot;
    }

    /** 返回绑定的 Bukkit 库存。 */
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
