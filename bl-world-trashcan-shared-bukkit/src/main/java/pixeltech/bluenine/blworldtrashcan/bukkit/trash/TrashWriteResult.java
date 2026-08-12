package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

/** 保存一次存储写入实际接受的数量和对应追踪键。 */
final class TrashWriteResult {
    /** 写入结果状态，决定后续路由及是否允许破坏性清空。 */
    enum Status {
        ACCEPTED_FULL,
        ACCEPTED_PARTIAL,
        REJECTED_CONTAINER_CAPACITY,
        REJECTED_ENTRY_LIMIT,
        REJECTED_RULE
    }

    private final int acceptedAmount;
    private final String trackingKey;
    private final Status status;
    private final boolean clearedBeforeWrite;

    /** 创建不可变写入结果。 */
    private TrashWriteResult(int acceptedAmount, String trackingKey, Status status,
                             boolean clearedBeforeWrite) {
        this.acceptedAmount = Math.max(0, acceptedAmount);
        this.trackingKey = trackingKey == null ? "" : trackingKey;
        this.status = status == null ? Status.REJECTED_RULE : status;
        this.clearedBeforeWrite = clearedBeforeWrite;
    }

    /** 返回规则拒绝结果。 */
    static TrashWriteResult rejected() {
        return rejected(Status.REJECTED_RULE);
    }

    /** 创建指定原因的拒绝结果。 */
    static TrashWriteResult rejected(Status status) {
        if (status == Status.ACCEPTED_FULL || status == Status.ACCEPTED_PARTIAL) {
            throw new IllegalArgumentException("rejected result cannot use accepted status");
        }
        return new TrashWriteResult(0, "", status, false);
    }

    /** 创建成功写入结果。 */
    static TrashWriteResult accepted(int acceptedAmount, String trackingKey) {
        return accepted(acceptedAmount, acceptedAmount, trackingKey, false);
    }

    /** 创建包含请求数量和清空标记的成功写入结果。 */
    static TrashWriteResult accepted(int acceptedAmount, int requestedAmount, String trackingKey,
                                     boolean clearedBeforeWrite) {
        if (acceptedAmount <= 0) {
            throw new IllegalArgumentException("acceptedAmount must be positive");
        }
        Status status = acceptedAmount >= requestedAmount
                ? Status.ACCEPTED_FULL : Status.ACCEPTED_PARTIAL;
        return new TrashWriteResult(acceptedAmount, trackingKey, status, clearedBeforeWrite);
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

    /** 返回写入状态。 */
    Status getStatus() {
        return status;
    }

    /** 判断本次成功写入前是否清空了旧存量。 */
    boolean isClearedBeforeWrite() {
        return clearedBeforeWrite;
    }
}
