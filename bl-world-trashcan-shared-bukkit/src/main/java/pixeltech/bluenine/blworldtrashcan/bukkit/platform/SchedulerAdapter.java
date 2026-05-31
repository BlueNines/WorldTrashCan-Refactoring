package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

/** 平台调度适配器，业务任务只依赖这里。 */
public interface SchedulerAdapter {
    /** 启动同步重复任务。 */
    TaskHandle runRepeating(Runnable runnable, long delayTicks, long periodTicks);

    /** 启动同步延迟任务。 */
    TaskHandle runLater(Runnable runnable, long delayTicks);
}
