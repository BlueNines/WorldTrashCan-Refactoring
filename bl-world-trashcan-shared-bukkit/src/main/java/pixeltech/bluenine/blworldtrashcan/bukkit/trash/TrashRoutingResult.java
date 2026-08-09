package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;

/** 保存一次路由是否成功以及实际成功目标。 */
public final class TrashRoutingResult {
    private static final TrashRoutingResult FAILURE = new TrashRoutingResult(null, 0, "");

    private final CleanupItemDestination destination;
    private final int acceptedAmount;
    private final String trackingKey;

    /** 创建路由结果。 */
    private TrashRoutingResult(CleanupItemDestination destination, int acceptedAmount, String trackingKey) {
        this.destination = destination;
        this.acceptedAmount = Math.max(0, acceptedAmount);
        this.trackingKey = trackingKey == null ? "" : trackingKey;
    }

    /** 返回共享失败结果。 */
    public static TrashRoutingResult failure() {
        return FAILURE;
    }

    /** 创建包含实际目标、接收数量和追踪键的成功结果。 */
    public static TrashRoutingResult success(CleanupItemDestination destination, int acceptedAmount,
                                             String trackingKey) {
        if (destination == null) {
            throw new IllegalArgumentException("destination cannot be null");
        }
        if (acceptedAmount <= 0) {
            throw new IllegalArgumentException("acceptedAmount must be positive");
        }
        return new TrashRoutingResult(destination, acceptedAmount, trackingKey);
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

    /** 返回虚拟垃圾桶存储条目的不透明追踪键。 */
    public String getTrackingKey() {
        return trackingKey;
    }
}
