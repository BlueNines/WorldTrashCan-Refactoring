package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

/** 保存一次存储写入实际接受的数量和对应追踪键。 */
final class TrashWriteResult {
    private static final TrashWriteResult REJECTED = new TrashWriteResult(0, "");

    private final int acceptedAmount;
    private final String trackingKey;

    /** 创建不可变写入结果。 */
    private TrashWriteResult(int acceptedAmount, String trackingKey) {
        this.acceptedAmount = Math.max(0, acceptedAmount);
        this.trackingKey = trackingKey == null ? "" : trackingKey;
    }

    /** 返回共享拒绝结果。 */
    static TrashWriteResult rejected() {
        return REJECTED;
    }

    /** 创建成功写入结果。 */
    static TrashWriteResult accepted(int acceptedAmount, String trackingKey) {
        if (acceptedAmount <= 0) {
            throw new IllegalArgumentException("acceptedAmount must be positive");
        }
        return new TrashWriteResult(acceptedAmount, trackingKey);
    }

    /** 返回是否至少写入一个物品。 */
    boolean isAccepted() {
        return acceptedAmount > 0;
    }

    /** 返回实际写入数量。 */
    int getAcceptedAmount() {
        return acceptedAmount;
    }

    /** 返回主存储生成的不透明追踪键。 */
    String getTrackingKey() {
        return trackingKey;
    }
}
