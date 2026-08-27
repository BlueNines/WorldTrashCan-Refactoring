package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.util.function.Supplier;

/** 普通 Bukkit/Paper 主线程掉落物堆叠实现。 */
public final class PaperItemStackingFeature extends AbstractModernItemStackingFeature {
    private BukkitTask processorTask;

    /** 创建普通平台实现。 */
    public PaperItemStackingFeature(Plugin plugin, Supplier<ItemStackingConfig> configSupplier) {
        super(plugin, configSupplier);
    }

    /** 启动主线程预算任务。 */
    @Override
    protected void startProcessor() {
        processorTask = Bukkit.getScheduler().runTaskTimer(plugin(), new Runnable() {
            /** 推进 dirty chunk 队列。 */
            @Override
            public void run() {
                processDirtyQueue();
            }
        }, config().getProcessIntervalTicks(), config().getProcessIntervalTicks());
    }

    /** 停止主线程任务。 */
    @Override
    protected void stopProcessor() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }
    }

    /** 在当前主线程处理中心和已加载相邻区块。 */
    @Override
    protected boolean dispatchChunk(StackingChunkKey key, long deadlineNanos) {
        World world = Bukkit.getWorld(key.getWorldUuid());
        if (world == null || !world.isChunkLoaded(key.getChunkX(), key.getChunkZ())) {
            return false;
        }
        // 每个掉落物生成和区块加载都会标记自己的 chunk；这里只扫描中心 chunk，避免邻居扩扫耗尽合并预算。
        processChunk(world, key.getChunkX(), key.getChunkZ(), false, deadlineNanos);
        return true;
    }

    /** 在 Paper 主线程下一 tick 执行非玩家拾取收尾。 */
    @Override
    protected boolean schedulePickupReconciliation(Item item, Runnable runnable, Runnable retired) {
        try {
            Bukkit.getScheduler().runTaskLater(plugin(), runnable, 1L);
            return true;
        } catch (RuntimeException error) {
            plugin().getLogger().warning("[ItemStacking] Paper 拾取收尾任务提交失败: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    /** 使用 Bukkit 异步调度器保存小型状态快照。 */
    @Override
    protected void saveStateAsync(Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin(), runnable);
    }
}
