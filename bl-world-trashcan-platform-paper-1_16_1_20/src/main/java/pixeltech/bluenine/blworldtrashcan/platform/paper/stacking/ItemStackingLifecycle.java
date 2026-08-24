package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

/** 约束聚集、排空和冲突阻断之间的合法状态。 */
final class ItemStackingLifecycle {
    private volatile boolean draining;
    private volatile boolean mergeBlocked;
    private volatile boolean conflictBlocked;

    /** 从持久化的排空状态恢复生命周期。 */
    ItemStackingLifecycle(boolean persistedDraining) {
        this.draining = persistedDraining;
        this.mergeBlocked = persistedDraining;
    }

    /** 阻止新聚集并进入排空。 */
    void requestDrain() {
        mergeBlocked = true;
        draining = true;
    }

    /** 在没有冲突时恢复新聚集。 */
    boolean resumeMerging() {
        if (conflictBlocked) {
            return false;
        }
        mergeBlocked = false;
        draining = false;
        return true;
    }

    /** 永久阻断当前实例的新聚集并进入排空。 */
    void blockForConflict() {
        conflictBlocked = true;
        mergeBlocked = true;
        draining = true;
    }

    /** 完成排空，但保持新聚集关闭。 */
    void completeDrain() {
        draining = false;
        mergeBlocked = true;
    }

    /** 判断当前是否正在排空。 */
    boolean isDraining() {
        return draining;
    }

    /** 判断是否禁止产生新逻辑堆叠。 */
    boolean isMergeBlocked() {
        return mergeBlocked;
    }

    /** 判断是否因第三方同类插件而阻断。 */
    boolean isConflictBlocked() {
        return conflictBlocked;
    }
}
