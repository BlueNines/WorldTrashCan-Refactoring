package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** 缓存公开 ItemMeta API 的 CustomModelData 能力。 */
public final class CustomModelDataSupport {
    private final Logger logger;
    private final Method setter;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    /** 创建固定能力实例。 */
    private CustomModelDataSupport(Logger logger, Method setter) {
        this.logger = logger;
        this.setter = setter;
    }

    /** 创建明确不支持的实例，不执行任何反射探测。 */
    public static CustomModelDataSupport unsupported(Logger logger) {
        return new CustomModelDataSupport(logger, null);
    }

    /** 从公开 ItemMeta 接口探测并缓存 CustomModelData 方法。 */
    public static CustomModelDataSupport detect(Logger logger) {
        try {
            Method method = ItemMeta.class.getMethod("setCustomModelData", Integer.class);
            return new CustomModelDataSupport(logger, method);
        } catch (NoSuchMethodException | SecurityException | LinkageError ignored) {
            return unsupported(logger);
        }
    }

    /** 返回当前运行时是否支持 CustomModelData。 */
    public boolean isSupported() {
        return setter != null;
    }

    /** 写入模型编号；运行时故障只告警一次且不影响菜单打开。 */
    public void apply(ItemMeta meta, int modelId) {
        if (setter == null || meta == null || modelId < 0) {
            return;
        }
        try {
            setter.invoke(meta, Integer.valueOf(modelId));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            warnOnce(exception);
        }
    }

    /** 输出一次应用失败警告，避免菜单热路径刷屏。 */
    private void warnOnce(Throwable throwable) {
        if (logger == null || !failureLogged.compareAndSet(false, true)) {
            return;
        }
        Throwable cause = throwable instanceof InvocationTargetException
                && ((InvocationTargetException) throwable).getTargetException() != null
                ? ((InvocationTargetException) throwable).getTargetException() : throwable;
        logger.warning("[GlobalTrash] CustomModelData 写入失败，已跳过该外观字段: "
                + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
    }
}
