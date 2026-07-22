package pixeltech.worldlisttrashcan.api.audit;

/** 描述一次扫地审计的完成时间和完整性。 */
public final class CleanupRunCompletion {
    private final long finishedAtMillis;
    private final boolean partial;

    /** 创建不可变完成信息。 */
    public CleanupRunCompletion(long finishedAtMillis, boolean partial) {
        this.finishedAtMillis = finishedAtMillis;
        this.partial = partial;
    }

    /** 返回完成时的 Unix 毫秒时间戳。 */
    public long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    /** 返回本次记录是否只包含部分区域结果。 */
    public boolean isPartial() {
        return partial;
    }
}
