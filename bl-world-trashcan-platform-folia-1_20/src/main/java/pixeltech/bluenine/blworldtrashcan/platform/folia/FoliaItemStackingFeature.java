package pixeltech.bluenine.blworldtrashcan.platform.folia;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;
import pixeltech.bluenine.blworldtrashcan.platform.paper.stacking.AbstractModernItemStackingFeature;
import pixeltech.bluenine.blworldtrashcan.platform.paper.stacking.StackingChunkKey;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Folia region-safe 掉落物逻辑堆叠实现。 */
public final class FoliaItemStackingFeature extends AbstractModernItemStackingFeature {
    private ScheduledTask processorTask;

    /** 创建 Folia 实现。 */
    public FoliaItemStackingFeature(Plugin plugin, Supplier<ItemStackingConfig> configSupplier) {
        super(plugin, configSupplier);
    }

    /** 在 global region 启动有界队列调度。 */
    @Override
    protected void startProcessor() {
        processorTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin(),
                new Consumer<ScheduledTask>() {
                    /** 从全局队列选择少量 chunk 并派发到对应 region。 */
                    @Override
                    public void accept(ScheduledTask task) {
                        processDirtyQueue();
                    }
                }, config().getProcessIntervalTicks(), config().getProcessIntervalTicks());
    }

    /** 停止 global region 队列任务。 */
    @Override
    protected void stopProcessor() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }
    }

    /** 把 chunk 提交到其 region，并只读取同一 region 拥有的相邻区块。 */
    @Override
    protected boolean dispatchChunk(final StackingChunkKey key, long ignoredDeadlineNanos) {
        final World world = Bukkit.getWorld(key.getWorldUuid());
        if (world == null || !world.isChunkLoaded(key.getChunkX(), key.getChunkZ())) {
            return false;
        }
        try {
            Bukkit.getRegionScheduler().run(plugin(), world, key.getChunkX(), key.getChunkZ(),
                    new Consumer<ScheduledTask>() {
                        /** 在 chunk 所属 region 内读取和修改掉落物。 */
                        @Override
                        public void accept(ScheduledTask task) {
                            long deadline = System.nanoTime() + config().getTimeBudgetMicros() * 1000L;
                            processChunk(world, key.getChunkX(), key.getChunkZ(), true, deadline);
                        }
                    });
            return true;
        } catch (RuntimeException error) {
            plugin().getLogger().warning("[ItemStacking] Folia region 任务提交失败: "
                    + world.getName() + "," + key.getChunkX() + "," + key.getChunkZ()
                    + " - " + error.getMessage());
            return false;
        }
    }

    /** 只允许读取当前 Folia region 实际拥有的相邻区块。 */
    @Override
    protected boolean canReadChunk(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    /** 使用 Folia AsyncScheduler 保存小型状态快照。 */
    @Override
    protected void saveStateAsync(final Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin(), new Consumer<ScheduledTask>() {
            /** 在异步调度器执行文件写入。 */
            @Override
            public void accept(ScheduledTask task) {
                runnable.run();
            }
        });
    }
}
