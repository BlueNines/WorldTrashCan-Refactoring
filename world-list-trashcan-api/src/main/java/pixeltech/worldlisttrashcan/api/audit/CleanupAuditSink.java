package pixeltech.worldlisttrashcan.api.audit;

/** 创建每轮扫地使用的审计会话。 */
public interface CleanupAuditSink {

    /** 为一次清理创建审计会话；容量不足时应返回轻量空会话。 */
    CleanupAuditSession beginRun(CleanupRunContext context);
}
