package pixeltech.bluenine.blworldtrashcan.core.cleanup;

/** 描述一次实体清理决策。 */
public final class EntityCleanupDecision {
    private final EntityCleanupAction action;
    private final String reason;

    /** 创建实体清理决策。 */
    public EntityCleanupDecision(EntityCleanupAction action, String reason) {
        this.action = action == null ? EntityCleanupAction.SKIP : action;
        this.reason = reason == null ? "" : reason;
    }

    /** 返回清理动作。 */
    public EntityCleanupAction getAction() {
        return action;
    }

    /** 返回决策原因。 */
    public String getReason() {
        return reason;
    }
}
