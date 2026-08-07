package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;

/** 保存一次路由是否成功以及实际成功目标。 */
public final class TrashRoutingResult {
    private static final TrashRoutingResult FAILURE = new TrashRoutingResult(null, 0);

    private final CleanupItemDestination destination;
    private final int acceptedAmount;

    /** 创建路由结果。 */
    private TrashRoutingResult(CleanupItemDestination destination, int acceptedAmount) {
        this.destination = destination;
        this.acceptedAmount = Math.max(0, acceptedAmount);
    }

    /** 返回共享失败结果。 */
    public static TrashRoutingResult failure() {
        return FAILURE;
    }

    /** 创建包含实际目标的成功结果。 */
    public static TrashRoutingResult success(CleanupItemDestination destination) {
        return success(destination, Integer.MAX_VALUE);
    }

    /** 创建包含实际目标和实际接收数量的成功结果。 */
    public static TrashRoutingResult success(CleanupItemDestination destination, int acceptedAmount) {
        if (destination == null) {
            throw new IllegalArgumentException("destination cannot be null");
        }
        if (acceptedAmount <= 0) {
            throw new IllegalArgumentException("acceptedAmount must be positive");
        }
        return new TrashRoutingResult(destination, acceptedAmount);
    }

    /** 返回路由是否成功。 */
    public boolean isSuccess() {
        return destination != null;
    }

    /** 返回实际成功目标；失败时为 null。 */
    public CleanupItemDestination getDestination() {
        return destination;
    }

    /** 返回本次路由实际接收的数量。 */
    public int getAcceptedAmount() {
        return acceptedAmount;
    }
}
