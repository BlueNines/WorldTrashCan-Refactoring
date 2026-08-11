package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;

/** 一次公共桶准入与容量检查的不可变结果。 */
public final class GlobalTrashCheck {
    private final boolean available;
    private final RejectedCleanupAction rejectedCleanupAction;

    /** 创建公共桶检查结果。 */
    public GlobalTrashCheck(boolean available, RejectedCleanupAction rejectedCleanupAction) {
        this.available = available;
        this.rejectedCleanupAction = rejectedCleanupAction;
    }

    /** 返回公共桶是否允许并能接收当前物品。 */
    public boolean isAvailable() {
        return available;
    }

    /** 返回白名单拒绝动作；未被白名单拒绝时为 null。 */
    public RejectedCleanupAction getRejectedCleanupAction() {
        return rejectedCleanupAction;
    }
}
