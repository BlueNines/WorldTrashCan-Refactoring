package pixeltech.bluenine.blworldtrashcan.platform.folia;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;

import java.util.function.Consumer;

/** Folia 全局区域调度适配器。 */
public final class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final Plugin plugin;

    /** 创建 Folia 调度适配器。 */
    public FoliaSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 启动 Folia 全局区域重复任务。 */
    @Override
    public TaskHandle runRepeating(final Runnable runnable, long delayTicks, long periodTicks) {
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            /** 执行业务任务。 */
            @Override
            public void accept(ScheduledTask task) {
                runnable.run();
            }
        }, delayTicks, periodTicks);
        return new FoliaTaskHandle(task);
    }

    /** 启动 Folia 全局区域延迟任务。 */
    @Override
    public TaskHandle runLater(final Runnable runnable, long delayTicks) {
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, new Consumer<ScheduledTask>() {
            /** 执行业务任务。 */
            @Override
            public void accept(ScheduledTask task) {
                runnable.run();
            }
        }, delayTicks);
        return new FoliaTaskHandle(task);
    }

    /** Folia ScheduledTask 的取消句柄。 */
    private static final class FoliaTaskHandle implements TaskHandle {
        private final ScheduledTask task;

        /** 创建任务句柄。 */
        private FoliaTaskHandle(ScheduledTask task) {
            this.task = task;
        }

        /** 取消 Folia 任务。 */
        @Override
        public void cancel() {
            task.cancel();
        }
    }
}
