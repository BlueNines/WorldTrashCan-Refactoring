package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

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
import org.bukkit.scheduler.BukkitTask;
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
import java.util.function.Supplier;

/** 世界实体数量限制和密集实体清理功能。 */
public final class EntityLimitFeature implements Feature, Listener {
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private final LowOverheadEntityLimitEngine engine = new LowOverheadEntityLimitEngine();
    private boolean registered;
    private ExecutorService worker;
    private BukkitTask scanTask;
    private BukkitTask removeTask;
    private BukkitTask summaryTask;

    /** 创建实体限制功能。 */
    public EntityLimitFeature(Plugin plugin, Supplier<ConfigBundle> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "entity-limits";
    }

    /** 注册监听器。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        restartTasks();
    }

    /** 重载实体限制后台扫描任务。 */
    @Override
    public void reload() {
        restartTasks();
    }

    /** 取消注册监听器。 */
    @Override
    public void disable() {
        stopTasks();
        HandlerList.unregisterAll(this);
        registered = false;
        engine.clear();
    }

    /** 在实体生成时标记脏 chunk，并用缓存数量做轻量拦截。 */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        markDirty(event.getLocation(), config);
        handleWorldLimit(event, config.getWorldLimit());
    }

    /** 处理单世界实体上限，避免再从世界里同步扫全部实体。 */
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
        plugin.getLogger().info("[EntityLimit] 已按缓存数量拦截实体生成 world=" + world.getName()
                + ", type=" + type.name()
                + ", current=" + current
                + ", max=" + maxCount);
        return true;
    }

    /** 重启后台扫描、候选计算和预算清理任务。 */
    private void restartTasks() {
        stopTasks();
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            engine.clear();
            return;
        }
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            /** 创建实体限制候选计算线程。 */
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "BLWorldTrashCan-EntityLimitWorker");
                thread.setDaemon(true);
                return thread;
            }
        });
        EntityLimitConfig.ScanConfig scanConfig = config.getScanConfig();
        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            /** 按预算扫描少量已加载 chunk。 */
            @Override
            public void run() {
                runScanBatch();
            }
        }, 1L, scanConfig.getScanIntervalTicks());
        removeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            /** 按预算消费删除候选。 */
            @Override
            public void run() {
                runRemovalBatch();
            }
        }, 1L, scanConfig.getRemoveIntervalTicks());
        if (scanConfig.getLogSummarySeconds() > 0) {
            long period = scanConfig.getLogSummarySeconds() * 20L;
            summaryTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
                /** 周期性输出低占用扫描摘要。 */
                @Override
                public void run() {
                    logSummary();
                }
            }, period, period);
        }
        plugin.getLogger().info("[EntityLimit] 已启动低占用实体扫描: scanInterval="
                + scanConfig.getScanIntervalTicks()
                + ", minChunks=" + scanConfig.getMinChunksPerScan()
                + ", maxChunks=" + scanConfig.getMaxChunksPerScan()
                + ", maxRemoves=" + scanConfig.getMaxRemovesPerRun());
    }

    /** 停止后台任务和候选计算线程。 */
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

    /** 安全取消 Bukkit 任务。 */
    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** 执行一轮主线程 chunk 快照采集。 */
    private void runScanBatch() {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (!isAnyLimitEnabled(config)) {
            return;
        }
        List<LowOverheadEntityLimitEngine.ChunkKey> loaded = collectLoadedChunkKeys(config);
        List<LowOverheadEntityLimitEngine.ChunkKey> selected = engine.selectChunks(loaded, config.getScanConfig());
        long start = System.nanoTime();
        long maxNanos = config.getScanConfig().getMaxScanMillisPerRun() * 1000000L;
        for (LowOverheadEntityLimitEngine.ChunkKey key : selected) {
            if (System.nanoTime() - start > maxNanos) {
                engine.markDirty(key, config.getScanConfig().getMaxDirtyChunks());
                break;
            }
            LowOverheadEntityLimitEngine.ChunkSnapshot snapshot = collectSnapshot(key, config);
            submitSnapshot(snapshot, config);
        }
    }

    /** 收集需要参与扫描的已加载 chunk 键。 */
    private List<LowOverheadEntityLimitEngine.ChunkKey> collectLoadedChunkKeys(EntityLimitConfig config) {
        List<LowOverheadEntityLimitEngine.ChunkKey> result = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!shouldScanWorld(world.getName(), config)) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                result.add(new LowOverheadEntityLimitEngine.ChunkKey(world.getName(), chunk.getX(), chunk.getZ()));
            }
        }
        return result;
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

    /** 把快照交给单线程 worker 做索引和候选计算。 */
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
            plugin.getLogger().warning("[EntityLimit] 提交实体快照失败: " + exception.getMessage());
        }
    }

    /** 按预算消费实体删除候选。 */
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
            try {
                removeCandidate(candidate, config);
            } catch (RuntimeException exception) {
                engine.retryCandidate(candidate, config.getScanConfig());
                plugin.getLogger().warning("[EntityLimit] 删除候选实体失败，已进入重试: " + exception.getMessage());
            }
        }
    }

    /** 删除一个候选实体，找不到或校验失败都会消费候选并清理索引。 */
    private void removeCandidate(LowOverheadEntityLimitEngine.RemovalCandidate candidate, EntityLimitConfig config) {
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

    /** 标记指定位置所在 chunk 需要重扫。 */
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

    /** 判断世界是否需要被扫描。 */
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
