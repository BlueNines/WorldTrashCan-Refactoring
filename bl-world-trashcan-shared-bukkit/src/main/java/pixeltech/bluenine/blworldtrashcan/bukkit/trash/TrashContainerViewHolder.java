package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** 标识一个垃圾容器的单玩家分页视图，不持有业务物品副本。 */
final class TrashContainerViewHolder implements InventoryHolder {
    private final TrashContainerMenu menu;
    private final Object loreToken = new Object();
    private final UUID playerId;
    private final int pageIndex;
    private final TrashContainerStore store;
    private volatile TrashContainerStore.ViewSnapshot snapshot;
    private Inventory inventory;
    private boolean contentSyncPending;
    private final long[] renderedGroups = new long[54];
    private final int[] renderedAmounts = new int[54];

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

    /** 返回不含库存或玩家引用的发包会话令牌。 */
    Object getLoreToken() {
        return loreToken;
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

    /** 返回本玩家上次操作后使用的视图快照。 */
    TrashContainerStore.ViewSnapshot getSnapshot() {
        return snapshot;
    }

    /** 仅替换本玩家按当前排序重建的快照，不影响其他视图。 */
    void replaceSnapshot(TrashContainerStore.ViewSnapshot nextSnapshot) {
        if (nextSnapshot != null) {
            this.snapshot = nextSnapshot;
        }
    }

    /** 所属玩家线程中合并下一 Tick 的内容校准，不影响其他玩家视图。 */
    synchronized boolean queueContentSync() {
        if (contentSyncPending) {
            return false;
        }
        contentSyncPending = true;
        return true;
    }

    /** 所属玩家线程中释放刷新标记，允许后续操作重新排队。 */
    synchronized void clearContentSyncPending() {
        contentSyncPending = false;
    }

    /** 只在玩家所属线程比较已呈现的组与余量，未变槽位跳过物品构造。 */
    boolean isRendered(int slot, TrashContainerStore.DisplayReference reference, int amount) {
        return renderedGroups[slot] == (reference == null ? 0 : reference.getGroupId())
                && renderedAmounts[slot] == amount;
    }

    /** 记录已实际呈现的单组版本，不持有物品副本。 */
    void markRendered(int slot, TrashContainerStore.DisplayReference reference, int amount) {
        renderedGroups[slot] = reference == null ? 0 : reference.getGroupId();
        renderedAmounts[slot] = amount;
    }

    /** 返回当前 Bukkit 库存。 */
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
