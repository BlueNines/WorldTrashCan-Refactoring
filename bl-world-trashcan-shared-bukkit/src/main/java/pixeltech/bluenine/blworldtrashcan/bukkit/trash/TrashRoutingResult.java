package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;

/** 保存一次路由是否成功以及实际成功目标。 */
public final class TrashRoutingResult {
    private static final TrashRoutingResult FAILURE = new TrashRoutingResult(null);

    private final CleanupItemDestination destination;

    /** 创建路由结果。 */
    private TrashRoutingResult(CleanupItemDestination destination) {
        this.destination = destination;
    }

    /** 返回共享失败结果。 */
    public static TrashRoutingResult failure() {
        return FAILURE;
    }

    /** 创建包含实际目标的成功结果。 */
    public static TrashRoutingResult success(CleanupItemDestination destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination cannot be null");
        }
        return new TrashRoutingResult(destination);
    }

    /** 返回路由是否成功。 */
    public boolean isSuccess() {
        return destination != null;
    }

    /** 返回实际成功目标；失败时为 null。 */
    public CleanupItemDestination getDestination() {
        return destination;
    }
}
