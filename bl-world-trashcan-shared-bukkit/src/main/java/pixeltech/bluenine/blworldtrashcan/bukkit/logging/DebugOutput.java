package pixeltech.bluenine.blworldtrashcan.bukkit.logging;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 统一输出低频诊断日志；关闭时不会执行延迟消息生成。 */
public final class DebugOutput {
    private final Logger logger;
    private volatile boolean enabled;

    /** 创建调试输出器。 */
    public DebugOutput(Logger logger) {
        this.logger = logger == null ? Logger.getLogger("WorldListTrashCan") : logger;
    }

    /** 创建默认关闭的调试输出器，供兼容旧构造器使用。 */
    public static DebugOutput disabled() {
        return new DebugOutput(null);
    }

    /** 更新运行期调试开关。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 判断当前是否允许输出调试日志。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 延迟生成并输出普通调试日志。 */
    public void debug(Supplier<String> messageSupplier) {
        if (!enabled || messageSupplier == null) {
            return;
        }
        String message = messageSupplier.get();
        if (message != null) {
            logger.info(message);
        }
    }

    /** 延迟生成并输出更细的追踪日志。 */
    public void trace(Supplier<String> messageSupplier) {
        if (!enabled || messageSupplier == null) {
            return;
        }
        String message = messageSupplier.get();
        if (message != null) {
            logger.log(Level.FINE, message);
        }
    }
}
