package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** 标识一个垃圾容器的单玩家分页视图，不持有业务物品副本。 */
final class TrashContainerViewHolder implements InventoryHolder {
    private final TrashContainerMenu menu;
    private final UUID playerId;
    private final int pageIndex;
    private final TrashContainerStore store;
    private final TrashContainerStore.ViewSnapshot snapshot;
    private Inventory inventory;

    /** 创建绑定具体 Store 和稳定快照的菜单视图。 */
    TrashContainerViewHolder(TrashContainerMenu menu, UUID playerId, int pageIndex,
                             TrashContainerStore store, TrashContainerStore.ViewSnapshot snapshot) {
        this.menu = menu;
        this.playerId = playerId;
        this.pageIndex = pageIndex;
        this.store = store;
        this.snapshot = snapshot;
    }

    /** 绑定 Bukkit 创建完成后的库存。 */
    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    /** 判断视图是否属于指定菜单实例。 */
    boolean belongsTo(TrashContainerMenu target) {
        return menu == target;
    }

    /** 返回查看玩家 UUID。 */
    UUID getPlayerId() {
        return playerId;
    }

    /** 返回当前页下标。 */
    int getPageIndex() {
        return pageIndex;
    }

    /** 返回本次视图绑定的容器状态。 */
    TrashContainerStore getStore() {
        return store;
    }

    /** 返回打开时冻结的视图快照。 */
    TrashContainerStore.ViewSnapshot getSnapshot() {
        return snapshot;
    }

    /** 返回当前 Bukkit 库存。 */
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
