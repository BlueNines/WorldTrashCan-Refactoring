package pixeltech.worldlisttrashcan.api.audit;

/** 表示一个可注销的清理审计消费者注册。 */
public interface AuditRegistration extends AutoCloseable {

    /** 注销消费者；重复调用必须无副作用。 */
    @Override
    void close();
}
