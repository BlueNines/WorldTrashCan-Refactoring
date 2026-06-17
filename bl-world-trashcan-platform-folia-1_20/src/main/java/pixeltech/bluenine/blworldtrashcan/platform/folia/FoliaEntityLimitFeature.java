package pixeltech.bluenine.blworldtrashcan.platform.folia;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.entitylimit.LowOverheadEntityLimitEngine;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Folia 专用低占用实体数量限制，所有 Bukkit 实体访问都落在合法 region 线程。 */
public final class FoliaEntityLimitFeature implements Feature, Listener {
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private final LowOverheadEntityLimitEngine engine = new LowOverheadEntityLimitEngine();
    private ExecutorService worker;
    private ScheduledTask scanTask;
    private ScheduledTask removeTask;
    private ScheduledTask summaryTask;
    private boolean registered;

    /** 创建 Folia 实体限制功能。 */
    public FoliaEntityLimitFeature(Plugin plugin, Supplier<ConfigBundle> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "folia-entity-limits";
    }

    /** 注册监听器并启动低占用扫描任务。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        restartTasks();
    }

    /** 重载配置后重启低占用扫描任务。 */
    @Override
    public void reload() {
        restartTasks();
    }

    /** 释放监听器、任务和索引。 */
    @Override
    public void disable() {
        stopTasks();
        HandlerList.unregisterAll(this);
        registered = false;
        engine.clear();
    }

    /** 实体进入世界时只标记脏 chunk，不维护长期事件实体表。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityAdd(EntityAddToWorldEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            return;
        }
        markDirty(event.getEntity(), config);
    }

    /** 实体离开世界时消费索引中的旧实体并标记 chunk。 */
    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            return;
        }
        markDirty(event.getEntity(), config);
        engine.removeIndexedEntity(event.getEntity().getUniqueId());
    }

    /** 生物生成时只做轻量世界上限拦截并标记脏 chunk。 */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        markDirty(event.getLocation(), config);
        handleWorldLimit(event, config.getWorldLimit());
    }

    /** 使用索引数量处理单世界实体上限。 */
    private boolean handleWorldLimit(CreatureSpawnEvent event, EntityLimitConfig.WorldLimitConfig config) {
        World world = event.getLocation().getWorld();
        EntityType type = event.getEntityType();
        if (!config.isEnabled() || world == null || config.isIgnoredWorld(world.getName())) {
            return false;
        }
        int maxCount = config.getMaxCount(type.name());
        int current = engine.getWorldTypeCount(world.getName(), type.name());
        if (maxCount <= 0 || current < maxCount) {
            return false;
        }
        event.setCancelled(true);
        event.getEntity().remove();
        plugin.getLogger().info("[FoliaEntityLimit] 已按缓存数量拦截实体生成 world=" + world.getName()
                + ", type=" + type.name()
                + ", current=" + current
                + ", max=" + maxCount);
        return true;
    }

    /** 重启 Folia 低占用扫描和删除任务。 */
    private void restartTasks() {
        stopTasks();
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            engine.clear();
            plugin.getLogger().info("[FoliaEntityLimit] 实体限制未启用，跳过低占用扫描任务。");
            return;
        }
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            /** 创建实体限制候选计算线程。 */
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "BLWorldTrashCan-FoliaEntityLimitWorker");
                thread.setDaemon(true);
                return thread;
            }
        });
        EntityLimitConfig.ScanConfig scanConfig = config.getScanConfig();
        scanTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            /** 在 global region 分派少量 chunk 扫描任务。 */
            @Override
            public void accept(ScheduledTask task) {
                runScanBatch();
            }
        }, 1L, scanConfig.getScanIntervalTicks());
        removeTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            /** 在 global region 分派候选删除任务。 */
            @Override
            public void accept(ScheduledTask task) {
                runRemovalBatch();
            }
        }, 1L, scanConfig.getRemoveIntervalTicks());
        if (scanConfig.getLogSummarySeconds() > 0) {
            long period = scanConfig.getLogSummarySeconds() * 20L;
            summaryTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
                /** 周期性输出低占用扫描摘要。 */
                @Override
                public void accept(ScheduledTask task) {
                    logSummary();
                }
            }, period, period);
        }
        plugin.getLogger().info("[FoliaEntityLimit] 已启动低占用实体扫描: scanInterval="
                + scanConfig.getScanIntervalTicks()
                + ", minChunks=" + scanConfig.getMinChunksPerScan()
                + ", maxChunks=" + scanConfig.getMaxChunksPerScan()
                + ", maxRemoves=" + scanConfig.getMaxRemovesPerRun());
    }

    /** 停止 Folia 任务和候选计算线程。 */
    private void stopTasks() {
        cancel(scanTask);
        cancel(removeTask);
        cancel(summaryTask);
        scanTask = null;
        removeTask = null;
        summaryTask = null;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    /** 取消 Folia 任务。 */
    private void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** 执行一轮低占用扫描分派。 */
    private void runScanBatch() {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            return;
        }
        List<LowOverheadEntityLimitEngine.ChunkKey> selected =
                engine.selectChunks(collectLoadedChunkKeys(config), config.getScanConfig());
        long start = System.nanoTime();
        long maxNanos = config.getScanConfig().getMaxScanMillisPerRun() * 1000000L;
        for (LowOverheadEntityLimitEngine.ChunkKey key : selected) {
            if (System.nanoTime() - start > maxNanos) {
                engine.markDirty(key, config.getScanConfig().getMaxDirtyChunks());
                break;
            }
            scheduleSnapshot(key, config);
        }
    }

    /** 收集当前已加载且需要扫描的 chunk。 */
    private List<LowOverheadEntityLimitEngine.ChunkKey> collectLoadedChunkKeys(EntityLimitConfig config) {
        List<LowOverheadEntityLimitEngine.ChunkKey> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!shouldScanWorld(world.getName(), config)) {
                continue;
            }
            try {
                for (Chunk chunk : world.getLoadedChunks()) {
                    result.add(new LowOverheadEntityLimitEngine.ChunkKey(world.getName(), chunk.getX(), chunk.getZ()));
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaEntityLimit] 收集世界已加载 chunk 失败: "
                        + world.getName() + " - " + exception.getMessage());
            }
        }
        return result;
    }

    /** 把单个 chunk 快照采集派发到所在 region。 */
    private void scheduleSnapshot(final LowOverheadEntityLimitEngine.ChunkKey key, final EntityLimitConfig config) {
        World world = Bukkit.getWorld(key.getWorldName());
        if (world == null) {
            submitSnapshot(LowOverheadEntityLimitEngine.ChunkSnapshot.unloaded(key), config);
            return;
        }
        try {
            Bukkit.getRegionScheduler().run(plugin, world, key.getChunkX(), key.getChunkZ(), new Consumer<ScheduledTask>() {
                /** 在 chunk 所在 region 采集实体快照。 */
                @Override
                public void accept(ScheduledTask task) {
                    submitSnapshot(collectSnapshot(key, config), config);
                }
            });
        } catch (RuntimeException exception) {
            engine.markDirty(key, config.getScanConfig().getMaxDirtyChunks());
            plugin.getLogger().warning("[FoliaEntityLimit] 分派 chunk 快照失败: " + key + " - " + exception.getMessage());
        }
    }

    /** 采集单个 chunk 的不可变实体快照。 */
    private LowOverheadEntityLimitEngine.ChunkSnapshot collectSnapshot(LowOverheadEntityLimitEngine.ChunkKey key, EntityLimitConfig config) {
        World world = Bukkit.getWorld(key.getWorldName());
        if (world == null || !world.isChunkLoaded(key.getChunkX(), key.getChunkZ())) {
            return LowOverheadEntityLimitEngine.ChunkSnapshot.unloaded(key);
        }
        Chunk chunk = world.getChunkAt(key.getChunkX(), key.getChunkZ());
        List<LowOverheadEntityLimitEngine.EntityRecord> records = new ArrayList<>();
        int maxRecords = config.getScanConfig().getMaxIndexEntitiesPerChunk();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player || !isRelevantType(config, entity.getType().name())) {
                continue;
            }
            Location location = entity.getLocation();
            records.add(new LowOverheadEntityLimitEngine.EntityRecord(
                    entity.getUniqueId(),
                    world.getName(),
                    entity.getType().name(),
                    key.getChunkX(),
                    key.getChunkZ(),
                    location.getX(),
                    location.getY(),
                    location.getZ()
            ));
            if (records.size() >= maxRecords) {
                break;
            }
        }
        return new LowOverheadEntityLimitEngine.ChunkSnapshot(key, true, records);
    }

    /** 把快照提交给单线程 worker。 */
    private void submitSnapshot(final LowOverheadEntityLimitEngine.ChunkSnapshot snapshot, final EntityLimitConfig config) {
        ExecutorService executor = worker;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(new Runnable() {
                /** 在异步线程处理不可变快照。 */
                @Override
                public void run() {
                    engine.applySnapshot(snapshot, config);
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaEntityLimit] 提交实体快照失败: " + exception.getMessage());
        }
    }

    /** 执行一轮候选删除分派。 */
    private void runRemovalBatch() {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            return;
        }
        List<LowOverheadEntityLimitEngine.RemovalCandidate> batch =
                engine.pollCandidates(config.getScanConfig().getMaxRemovesPerRun(), config.getScanConfig());
        for (LowOverheadEntityLimitEngine.RemovalCandidate candidate : batch) {
            if (!engine.shouldRemove(candidate, config)) {
                engine.finishCandidate(candidate, false, false);
                continue;
            }
            scheduleRemoval(candidate, config);
        }
    }

    /** 把候选删除派发到候选所在 chunk 的 region。 */
    private void scheduleRemoval(final LowOverheadEntityLimitEngine.RemovalCandidate candidate, final EntityLimitConfig config) {
        World world = Bukkit.getWorld(candidate.getWorldName());
        if (world == null) {
            engine.finishCandidate(candidate, true, false);
            return;
        }
        try {
            Bukkit.getRegionScheduler().run(plugin, world, candidate.getChunkX(), candidate.getChunkZ(), new Consumer<ScheduledTask>() {
                /** 在候选所在 region 删除实体。 */
                @Override
                public void accept(ScheduledTask task) {
                    try {
                        removeCandidateInRegion(candidate, config);
                    } catch (RuntimeException exception) {
                        engine.retryCandidate(candidate, config.getScanConfig());
                        plugin.getLogger().warning("[FoliaEntityLimit] 删除候选实体失败，已进入重试: " + exception.getMessage());
                    }
                }
            });
        } catch (RuntimeException exception) {
            engine.retryCandidate(candidate, config.getScanConfig());
            plugin.getLogger().warning("[FoliaEntityLimit] 分派候选删除失败: " + exception.getMessage());
        }
    }

    /** 在 region 线程中校验并删除候选实体。 */
    private void removeCandidateInRegion(LowOverheadEntityLimitEngine.RemovalCandidate candidate, EntityLimitConfig config) {
        World world = Bukkit.getWorld(candidate.getWorldName());
        if (world == null || !world.isChunkLoaded(candidate.getChunkX(), candidate.getChunkZ())) {
            engine.finishCandidate(candidate, true, false);
            return;
        }
        Entity target = null;
        Chunk chunk = world.getChunkAt(candidate.getChunkX(), candidate.getChunkZ());
        for (Entity entity : chunk.getEntities()) {
            if (entity.getUniqueId().equals(candidate.getUniqueId())) {
                target = entity;
                break;
            }
        }
        if (target == null || !target.isValid() || !target.getType().name().equals(candidate.getTypeName())) {
            engine.finishCandidate(candidate, true, false);
            return;
        }
        removeEntity(target, config.getGatherLimit().isDropItems());
        engine.finishCandidate(candidate, true, true);
        engine.markDirty(new LowOverheadEntityLimitEngine.ChunkKey(world.getName(), chunk.getX(), chunk.getZ()),
                config.getScanConfig().getMaxDirtyChunks());
    }

    /** 按配置移除实体。 */
    private void removeEntity(Entity entity, boolean dropItems) {
        if (dropItems && entity instanceof LivingEntity) {
            try {
                ((LivingEntity) entity).setHealth(0D);
            } catch (RuntimeException exception) {
                entity.remove();
            }
            return;
        }
        entity.remove();
    }

    /** 从实体对象标记所在 chunk 为脏。 */
    private void markDirty(Entity entity, EntityLimitConfig config) {
        if (entity == null) {
            return;
        }
        try {
            markDirty(entity.getLocation(), config);
        } catch (RuntimeException ignored) {
            engine.removeIndexedEntity(entity.getUniqueId());
        }
    }

    /** 从位置标记所在 chunk 为脏。 */
    private void markDirty(Location location, EntityLimitConfig config) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        engine.markDirty(new LowOverheadEntityLimitEngine.ChunkKey(
                location.getWorld().getName(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        ), config.getScanConfig().getMaxDirtyChunks());
    }

    /** 判断当前配置是否启用了任一实体限制。 */
    private boolean isAnyLimitEnabled(EntityLimitConfig config) {
        return config.getWorldLimit().isEnabled() || config.getGatherLimit().isEnabled();
    }

    /** 判断世界是否需要扫描。 */
    private boolean shouldScanWorld(String worldName, EntityLimitConfig config) {
        return (config.getWorldLimit().isEnabled() && !config.getWorldLimit().isIgnoredWorld(worldName))
                || (config.getGatherLimit().isEnabled() && !config.getGatherLimit().isIgnoredWorld(worldName));
    }

    /** 判断实体类型是否和当前限制配置相关。 */
    private boolean isRelevantType(EntityLimitConfig config, String typeName) {
        String normalized = typeName == null ? "" : typeName.trim().toUpperCase(Locale.ROOT);
        return (config.getWorldLimit().isEnabled() && config.getWorldLimit().getLimitedTypes().contains(normalized))
                || (config.getGatherLimit().isEnabled() && config.getGatherLimit().getLimitedTypes().contains(normalized));
    }

    /** 周期性输出扫描摘要到后台日志。 */
    private void logSummary() {
        for (String line : debugStats()) {
            plugin.getLogger().info(ChatColor.stripColor(line));
        }
    }

    /** 返回实体密度扫描调试统计。 */
    public List<String> debugStats() {
        if (!isAnyLimitEnabled(configSupplier.get().getEntityLimitConfig())) {
            return Collections.singletonList("§e实体限制未启用。");
        }
        return engine.describe();
    }
}
