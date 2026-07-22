package pixeltech.worldlisttrashcan.api.audit;

import java.util.Objects;
import java.util.UUID;

/** 描述一次扫地审计的稳定运行期身份。 */
public final class CleanupRunContext {
    private final UUID runId;
    private final long startedAtMillis;
    private final CleanupTrigger trigger;
    private final boolean guardsIgnored;

    /** 创建不可变清理上下文。 */
    public CleanupRunContext(UUID runId, long startedAtMillis, CleanupTrigger trigger, boolean guardsIgnored) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.startedAtMillis = startedAtMillis;
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.guardsIgnored = guardsIgnored;
    }

    /** 返回本轮运行期 UUID。 */
    public UUID getRunId() {
        return runId;
    }

    /** 返回开始时的 Unix 毫秒时间戳。 */
    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    /** 返回清理触发方式。 */
    public CleanupTrigger getTrigger() {
        return trigger;
    }

    /** 返回本轮是否忽略了扫地 guards。 */
    public boolean isGuardsIgnored() {
        return guardsIgnored;
    }
}
