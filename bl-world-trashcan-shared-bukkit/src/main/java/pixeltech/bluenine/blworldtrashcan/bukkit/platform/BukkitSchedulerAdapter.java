package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit 主线程调度实现。 */
public final class BukkitSchedulerAdapter implements SchedulerAdapter {
    private final Plugin plugin;

    /** 创建 Bukkit 调度适配器。 */
    public BukkitSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 启动 Bukkit 重复任务。 */
    @Override
    public TaskHandle runRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        return new BukkitTaskHandle(task);
    }

    /** 启动 Bukkit 延迟任务。 */
    @Override
    public TaskHandle runLater(Runnable runnable, long delayTicks) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return new BukkitTaskHandle(task);
    }

    /** BukkitTask 的取消句柄。 */
    private static final class BukkitTaskHandle implements TaskHandle {
        private final BukkitTask task;

        /** 创建 Bukkit 任务句柄。 */
        private BukkitTaskHandle(BukkitTask task) {
            this.task = task;
        }

        /** 取消 Bukkit 任务。 */
        @Override
        public void cancel() {
            task.cancel();
        }
    }
}
