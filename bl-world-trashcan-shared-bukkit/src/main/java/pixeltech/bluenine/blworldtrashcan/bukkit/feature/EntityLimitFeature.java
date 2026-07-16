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
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/** 世界实体数量限制和密集实体清理功能。 */
public final class EntityLimitFeature implements Feature, Listener {
    private static final int NOTICE_FLUSH_INTERVAL_TICKS = 10;
    private static final int MAX_PENDING_NOTICE_KEYS = 1024;
    private static final String DENSITY_NOTIFY_KEY = "entity-limit.gather-cleared";
    private static final String DENSITY_NOTIFY_FALLBACK = "{prefix}&#FFD166密集实体清理 &#64748B| &#C9D4E2你的附近 &#FFD166{range} &#C9D4E2格内有 &#FFD166{entity} x {size} &#C9D4E2只，超过上限 &#FFD166{max}&#C9D4E2，本次已清理 &#5AC8FA{removed} &#C9D4E2只。";
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private final BukkitMessageService messages;
    private final LowOverheadEntityLimitEngine engine = new LowOverheadEntityLimitEngine();
    private final Map<DensityNoticeKey, DensityRemovalNotice> pendingDensityNotices = new HashMap<>();
    private boolean registered;
    private ExecutorService worker;
    private BukkitTask scanTask;
    private BukkitTask removeTask;
    private BukkitTask summaryTask;
    private BukkitTask noticeTask;

    /** 创建实体限制功能。 */
    public EntityLimitFeature(Plugin plugin, Supplier<ConfigBundle> configSupplier, BukkitMessageService messages) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.messages = messages;
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
                Thread thread = new Thread(runnable, "BlWorldTrashCan-EntityLimitWorker");
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
        noticeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            /** 聚合并发送密集实体清理提示。 */
            @Override
            public void run() {
                flushDensityNotices();
            }
        }, NOTICE_FLUSH_INTERVAL_TICKS, NOTICE_FLUSH_INTERVAL_TICKS);
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
        cancel(noticeTask);
        scanTask = null;
        removeTask = null;
        summaryTask = null;
        noticeTask = null;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        synchronized (pendingDensityNotices) {
            pendingDensityNotices.clear();
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
        flushDensityNotices();
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
        EntityLimitConfig.GatherRule rule = config.getGatherLimit().getRule(candidate.getTypeName());
        int totalBefore = rule == null ? 0 : engine.countNearbySameType(candidate, rule.getRadius());
        Location location = target.getLocation();
        String typeName = target.getType().name();
        removeEntity(target, config.getGatherLimit().isDropItems());
        engine.finishCandidate(candidate, true, true);
        queueDensityNotice(location, typeName, rule, totalBefore, 1);
        engine.markDirty(new LowOverheadEntityLimitEngine.ChunkKey(world.getName(), chunk.getX(), chunk.getZ()),
                config.getScanConfig().getMaxDirtyChunks());
    }

    /** 记录一次密集实体删除提示，稍后按玩家聚合发送。 */
    private void queueDensityNotice(Location location, String typeName, EntityLimitConfig.GatherRule rule,
                                    int totalBefore, int removed) {
        if (location == null || location.getWorld() == null || rule == null || removed <= 0) {
            return;
        }
        DensityNoticeKey key = new DensityNoticeKey(location.getWorld().getName(), typeName,
                location.getBlockX() >> 4, location.getBlockZ() >> 4, rule.getRadius(), rule.getMaxCount());
        synchronized (pendingDensityNotices) {
            DensityRemovalNotice notice = pendingDensityNotices.get(key);
            if (notice == null) {
                if (pendingDensityNotices.size() >= MAX_PENDING_NOTICE_KEYS) {
                    return;
                }
                notice = new DensityRemovalNotice(key, location.getX(), location.getY(), location.getZ());
                pendingDensityNotices.put(key, notice);
            }
            notice.add(totalBefore, removed);
        }
    }

    /** 发送聚合后的密集实体清理提示。 */
    private void flushDensityNotices() {
        List<DensityRemovalNotice> notices = drainDensityNotices();
        if (notices.isEmpty() || messages == null) {
            return;
        }
        Map<DensityPlayerNoticeKey, DensityPlayerNotice> playerNotices = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location playerLocation = player.getLocation();
            for (DensityRemovalNotice notice : notices) {
                if (!notice.isNear(playerLocation)) {
                    continue;
                }
                DensityPlayerNoticeKey key = new DensityPlayerNoticeKey(player.getUniqueId(), notice.getTypeName(),
                        notice.getRadius(), notice.getMaxCount());
                DensityPlayerNotice playerNotice = playerNotices.get(key);
                if (playerNotice == null) {
                    playerNotice = new DensityPlayerNotice(player, notice.getTypeName(), notice.getRadius(),
                            notice.getMaxCount());
                    playerNotices.put(key, playerNotice);
                }
                playerNotice.add(notice.getTotalBefore(), notice.getRemoved());
            }
        }
        for (DensityPlayerNotice notice : playerNotices.values()) {
            notice.send();
        }
    }

    /** 取出并清空待发送的密集实体提示。 */
    private List<DensityRemovalNotice> drainDensityNotices() {
        synchronized (pendingDensityNotices) {
            if (pendingDensityNotices.isEmpty()) {
                return Collections.emptyList();
            }
            List<DensityRemovalNotice> result = new ArrayList<>(pendingDensityNotices.values());
            pendingDensityNotices.clear();
            return result;
        }
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

    /** 密集实体提示聚合键。 */
    private static final class DensityNoticeKey {
        private final String worldName;
        private final String typeName;
        private final int chunkX;
        private final int chunkZ;
        private final int radius;
        private final int maxCount;

        /** 创建密集实体提示聚合键。 */
        private DensityNoticeKey(String worldName, String typeName, int chunkX, int chunkZ, int radius, int maxCount) {
            this.worldName = worldName == null ? "" : worldName;
            this.typeName = typeName == null ? "" : typeName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.radius = radius;
            this.maxCount = maxCount;
        }

        /** 判断两个键是否相等。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DensityNoticeKey)) {
                return false;
            }
            DensityNoticeKey that = (DensityNoticeKey) other;
            return chunkX == that.chunkX
                    && chunkZ == that.chunkZ
                    && radius == that.radius
                    && maxCount == that.maxCount
                    && worldName.equals(that.worldName)
                    && typeName.equals(that.typeName);
        }

        /** 返回哈希值。 */
        @Override
        public int hashCode() {
            int result = worldName.hashCode();
            result = 31 * result + typeName.hashCode();
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            result = 31 * result + radius;
            result = 31 * result + maxCount;
            return result;
        }
    }

    /** 一组同区域密集实体删除提示。 */
    private static final class DensityRemovalNotice {
        private final DensityNoticeKey key;
        private final double x;
        private final double y;
        private final double z;
        private int totalBefore;
        private int removed;

        /** 创建密集实体删除提示。 */
        private DensityRemovalNotice(DensityNoticeKey key, double x, double y, double z) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 合并一次删除结果。 */
        private void add(int totalBefore, int removed) {
            this.totalBefore = Math.max(this.totalBefore, totalBefore);
            this.removed += Math.max(0, removed);
        }

        /** 判断玩家位置是否在提示范围内。 */
        private boolean isNear(Location location) {
            if (location == null || location.getWorld() == null || !key.worldName.equals(location.getWorld().getName())) {
                return false;
            }
            double dx = location.getX() - x;
            double dy = location.getY() - y;
            double dz = location.getZ() - z;
            double radiusSquared = key.radius * (double) key.radius;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        }

        /** 返回实体类型。 */
        private String getTypeName() {
            return key.typeName;
        }

        /** 返回检测半径。 */
        private int getRadius() {
            return key.radius;
        }

        /** 返回上限数量。 */
        private int getMaxCount() {
            return key.maxCount;
        }

        /** 返回清理前数量。 */
        private int getTotalBefore() {
            return Math.max(totalBefore, key.maxCount + removed);
        }

        /** 返回已删除数量。 */
        private int getRemoved() {
            return removed;
        }
    }

    /** 玩家维度的密集实体提示聚合键。 */
    private static final class DensityPlayerNoticeKey {
        private final UUID playerUuid;
        private final String typeName;
        private final int radius;
        private final int maxCount;

        /** 创建玩家提示聚合键。 */
        private DensityPlayerNoticeKey(UUID playerUuid, String typeName, int radius, int maxCount) {
            this.playerUuid = playerUuid;
            this.typeName = typeName == null ? "" : typeName;
            this.radius = radius;
            this.maxCount = maxCount;
        }

        /** 判断两个键是否相等。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DensityPlayerNoticeKey)) {
                return false;
            }
            DensityPlayerNoticeKey that = (DensityPlayerNoticeKey) other;
            return radius == that.radius
                    && maxCount == that.maxCount
                    && playerUuid.equals(that.playerUuid)
                    && typeName.equals(that.typeName);
        }

        /** 返回哈希值。 */
        @Override
        public int hashCode() {
            int result = playerUuid.hashCode();
            result = 31 * result + typeName.hashCode();
            result = 31 * result + radius;
            result = 31 * result + maxCount;
            return result;
        }
    }

    /** 单个玩家最终收到的密集实体提示。 */
    private final class DensityPlayerNotice {
        private final Player player;
        private final String typeName;
        private final int radius;
        private final int maxCount;
        private int totalBefore;
        private int removed;

        /** 创建玩家密集实体提示。 */
        private DensityPlayerNotice(Player player, String typeName, int radius, int maxCount) {
            this.player = player;
            this.typeName = typeName;
            this.radius = radius;
            this.maxCount = maxCount;
        }

        /** 合并一组删除统计。 */
        private void add(int totalBefore, int removed) {
            this.totalBefore = Math.max(this.totalBefore, totalBefore);
            this.removed += Math.max(0, removed);
        }

        /** 向玩家发送提示。 */
        private void send() {
            if (removed <= 0) {
                return;
            }
            player.sendMessage(messages.text(player, DENSITY_NOTIFY_KEY, DENSITY_NOTIFY_FALLBACK,
                    "{range}", String.valueOf(radius),
                    "{entity}", typeName,
                    "{entityType}", typeName,
                    "{size}", String.valueOf(Math.max(totalBefore, maxCount + removed)),
                    "{max}", String.valueOf(maxCount),
                    "{removed}", String.valueOf(removed)));
        }
    }
}
