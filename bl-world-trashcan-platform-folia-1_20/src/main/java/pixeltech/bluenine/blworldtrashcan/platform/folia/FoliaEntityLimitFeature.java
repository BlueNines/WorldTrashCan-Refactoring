package pixeltech.bluenine.blworldtrashcan.platform.folia;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
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
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Folia 专用实体数量限制，避免同步全世界实体扫描。 */
public final class FoliaEntityLimitFeature implements Feature, Listener {
    private static final long RESYNC_INTERVAL_TICKS = 20L * 60L;
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private final ConcurrentMap<String, AtomicInteger> entityCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> entityKeys = new ConcurrentHashMap<>();
    private final AtomicBoolean resyncRunning = new AtomicBoolean(false);
    private ScheduledTask resyncTask;
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

    /** 注册监听器并启动缓存复算。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        startResyncTask();
    }

    /** 重载配置后重启缓存复算。 */
    @Override
    public void reload() {
        stopResyncTask();
        startResyncTask();
    }

    /** 释放监听器和缓存。 */
    @Override
    public void disable() {
        stopResyncTask();
        HandlerList.unregisterAll(this);
        registered = false;
        entityCounts.clear();
        entityKeys.clear();
    }

    /** 实体进入世界时维护数量缓存。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityAdd(EntityAddToWorldEvent event) {
        if (!isAnyLimitEnabled()) {
            return;
        }
        trackEntity(event.getEntity());
    }

    /** 实体离开世界时维护数量缓存。 */
    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (!isAnyLimitEnabled()) {
            return;
        }
        untrackEntity(event.getEntity());
    }

    /** 生物生成时执行世界上限和密集限制。 */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (handleWorldLimit(event, config.getWorldLimit())) {
            return;
        }
        handleGatherLimit(event, config.getGatherLimit());
    }

    /** 处理单世界实体上限。 */
    private boolean handleWorldLimit(CreatureSpawnEvent event, EntityLimitConfig.WorldLimitConfig config) {
        World world = event.getLocation().getWorld();
        EntityType type = event.getEntityType();
        if (!config.isEnabled() || world == null || config.isIgnoredWorld(world.getName())) {
            return false;
        }
        int maxCount = config.getMaxCount(type.name());
        if (maxCount <= 0) {
            return false;
        }
        int current = getCachedCount(world, type);
        if (isAlreadyTracked(event.getEntity())) {
            current = Math.max(0, current - 1);
        }
        if (current < maxCount) {
            reserveAllowedSpawn(event.getEntity());
            return false;
        }
        event.setCancelled(true);
        event.getEntity().remove();
        plugin.getLogger().info("[FoliaEntityLimit] 已拦截实体生成 world=" + world.getName()
                + ", type=" + type.name()
                + ", current=" + current
                + ", max=" + maxCount);
        return true;
    }

    /** 处理当前 chunk 内的密集实体限制。 */
    private void handleGatherLimit(CreatureSpawnEvent event, EntityLimitConfig.GatherLimitConfig config) {
        World world = event.getLocation().getWorld();
        EntityType type = event.getEntityType();
        if (!config.isEnabled() || world == null || config.isIgnoredWorld(world.getName())) {
            return;
        }
        EntityLimitConfig.GatherRule rule = config.getRule(type.name());
        if (rule == null) {
            return;
        }
        List<Entity> sameType = findSameTypeInCurrentChunk(event.getEntity(), rule.getRadius());
        if (sameType.size() + 1 <= rule.getMaxCount()) {
            return;
        }
        int removed = 0;
        for (Entity entity : sameType) {
            removeEntity(entity, config.isDropItems());
            removed++;
            if (removed >= rule.getRemoveCount()) {
                break;
            }
        }
        plugin.getLogger().info("[FoliaEntityLimit] 已清理密集实体 world=" + world.getName()
                + ", type=" + type.name()
                + ", removed=" + removed
                + ", radius=" + rule.getRadius()
                + ", scope=current-chunk");
    }

    /** 查找当前 chunk 内同类型且在半径内的实体。 */
    private List<Entity> findSameTypeInCurrentChunk(Entity source, int radius) {
        List<Entity> result = new ArrayList<>();
        Location sourceLocation = source.getLocation();
        double radiusSquared = radius * (double) radius;
        for (Entity entity : sourceLocation.getChunk().getEntities()) {
            if (entity.getUniqueId().equals(source.getUniqueId()) || entity.getType() != source.getType()) {
                continue;
            }
            if (entity.getLocation().distanceSquared(sourceLocation) <= radiusSquared) {
                result.add(entity);
            }
        }
        return result;
    }

    /** 按配置移除实体。 */
    private void removeEntity(Entity entity, boolean dropItems) {
        if (dropItems && entity instanceof LivingEntity) {
            ((LivingEntity) entity).setHealth(0D);
            return;
        }
        entity.remove();
    }

    /** 启动实体数量缓存复算任务。 */
    private void startResyncTask() {
        if (!isAnyLimitEnabled()) {
            entityCounts.clear();
            entityKeys.clear();
            plugin.getLogger().info("[FoliaEntityLimit] 实体限制未启用，跳过缓存复算任务。");
            return;
        }
        resyncCounts();
        resyncTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            /** 周期性复算已加载实体数量。 */
            @Override
            public void accept(ScheduledTask task) {
                resyncCounts();
            }
        }, RESYNC_INTERVAL_TICKS, RESYNC_INTERVAL_TICKS);
        plugin.getLogger().info("[FoliaEntityLimit] 已启动实体数量缓存复算任务，间隔 " + (RESYNC_INTERVAL_TICKS / 20L) + " 秒。");
    }

    /** 停止实体数量缓存复算任务。 */
    private void stopResyncTask() {
        if (resyncTask != null) {
            resyncTask.cancel();
            resyncTask = null;
        }
    }

    /** 判断是否有实体限制配置启用。 */
    private boolean isAnyLimitEnabled() {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        return config.getWorldLimit().isEnabled() || config.getGatherLimit().isEnabled();
    }

    /** 触发一次 region-safe 数量缓存复算。 */
    private void resyncCounts() {
        if (!resyncRunning.compareAndSet(false, true)) {
            return;
        }
        final CounterSnapshot snapshot = new CounterSnapshot();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
                /** 在全局区域分派 chunk 计数任务。 */
                @Override
                public void run() {
                    try {
                        scheduleCountTasks(snapshot);
                    } catch (RuntimeException exception) {
                        resyncRunning.set(false);
                        plugin.getLogger().warning("[FoliaEntityLimit] 分派实体数量复算失败: " + exception.getMessage());
                    }
                }
            });
        } catch (RuntimeException exception) {
            resyncRunning.set(false);
            plugin.getLogger().warning("[FoliaEntityLimit] 启动实体数量复算失败: " + exception.getMessage());
        }
    }

    /** 为所有已加载 chunk 分派计数任务。 */
    private void scheduleCountTasks(final CounterSnapshot snapshot) {
        final CompletionTracker tracker = new CompletionTracker(snapshot);
        for (World world : Bukkit.getWorlds()) {
            try {
                snapshot.worlds.incrementAndGet();
                Chunk[] chunks = world.getLoadedChunks();
                for (Chunk chunk : chunks) {
                    tracker.taskStarted();
                    Bukkit.getRegionScheduler().run(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), new Consumer<ScheduledTask>() {
                        /** 在 chunk 所在 region 内计数。 */
                        @Override
                        public void accept(ScheduledTask task) {
                            try {
                                countChunk(chunk, snapshot);
                            } catch (RuntimeException exception) {
                                plugin.getLogger().warning("[FoliaEntityLimit] 统计 chunk 失败: "
                                        + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                                        + " - " + exception.getMessage());
                            } finally {
                                tracker.taskDone();
                            }
                        }
                    });
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaEntityLimit] 收集世界已加载 chunk 失败: "
                        + world.getName() + " - " + exception.getMessage());
            }
        }
        tracker.initialSchedulingDone();
    }

    /** 统计单个 chunk 内的实体。 */
    private void countChunk(Chunk chunk, CounterSnapshot snapshot) {
        snapshot.chunks.incrementAndGet();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            String key = countKey(entity.getWorld(), entity.getType());
            incrementSnapshot(snapshot.counts, key);
            snapshot.entityKeys.put(entity.getUniqueId(), key);
            snapshot.entities.incrementAndGet();
        }
    }

    /** 完成一次缓存复算。 */
    private void finishResync(CounterSnapshot snapshot) {
        entityCounts.clear();
        for (Map.Entry<String, AtomicInteger> entry : snapshot.counts.entrySet()) {
            entityCounts.put(entry.getKey(), new AtomicInteger(Math.max(0, entry.getValue().get())));
        }
        entityKeys.clear();
        entityKeys.putAll(snapshot.entityKeys);
        resyncRunning.set(false);
        plugin.getLogger().info("[FoliaEntityLimit] 已复算实体数量缓存 worlds=" + snapshot.worlds.get()
                + ", chunks=" + snapshot.chunks.get()
                + ", entities=" + snapshot.entities.get());
    }

    /** 将实体写入数量缓存。 */
    private void trackEntity(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return;
        }
        String key = countKey(entity.getWorld(), entity.getType());
        String previous = entityKeys.putIfAbsent(entity.getUniqueId(), key);
        if (previous == null) {
            adjustCount(key, 1);
        }
    }

    /** 对已放行的生成事件预占计数，并在实体未真正存活时释放。 */
    private void reserveAllowedSpawn(final Entity entity) {
        trackEntity(entity);
        if (entity == null) {
            return;
        }
        Runnable retired = new Runnable() {
            /** 实体未进入可调度状态时释放预占计数。 */
            @Override
            public void run() {
                untrackEntity(entity);
            }
        };
        try {
            boolean scheduled = entity.getScheduler().execute(plugin, new Runnable() {
                /** 下一 tick 校验实体是否仍然有效。 */
                @Override
                public void run() {
                    if (!entity.isValid()) {
                        untrackEntity(entity);
                    }
                }
            }, retired, 1L);
            if (!scheduled) {
                retired.run();
            }
        } catch (RuntimeException exception) {
            retired.run();
        }
    }

    /** 将实体从数量缓存移除。 */
    private void untrackEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        String key = entityKeys.remove(entity.getUniqueId());
        if (key != null) {
            adjustCount(key, -1);
        }
    }

    /** 判断实体是否已经在缓存内。 */
    private boolean isAlreadyTracked(Entity entity) {
        return entity != null && entityKeys.containsKey(entity.getUniqueId());
    }

    /** 返回指定世界和类型的缓存数量。 */
    private int getCachedCount(World world, EntityType type) {
        AtomicInteger count = entityCounts.get(countKey(world, type));
        return count == null ? 0 : Math.max(0, count.get());
    }

    /** 调整指定计数键的数量。 */
    private void adjustCount(String key, int delta) {
        AtomicInteger count = entityCounts.get(key);
        if (count == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = entityCounts.putIfAbsent(key, created);
            count = existing == null ? created : existing;
        }
        int next = count.addAndGet(delta);
        if (next <= 0) {
            entityCounts.remove(key, count);
        }
    }

    /** 增加临时快照计数。 */
    private void incrementSnapshot(ConcurrentMap<String, AtomicInteger> counts, String key) {
        AtomicInteger count = counts.get(key);
        if (count == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = counts.putIfAbsent(key, created);
            count = existing == null ? created : existing;
        }
        count.incrementAndGet();
    }

    /** 生成实体计数键。 */
    private String countKey(World world, EntityType type) {
        String worldName = world == null ? "" : world.getName();
        String typeName = type == null ? "" : type.name();
        return worldName + '\u0000' + typeName;
    }

    /** 临时计数快照。 */
    private static final class CounterSnapshot {
        private final ConcurrentMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, String> entityKeys = new ConcurrentHashMap<>();
        private final AtomicInteger worlds = new AtomicInteger();
        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicInteger entities = new AtomicInteger();
    }

    /** 异步计数完成跟踪器。 */
    private final class CompletionTracker {
        private final CounterSnapshot snapshot;
        private final AtomicInteger pendingTasks = new AtomicInteger(1);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        /** 创建完成跟踪器。 */
        private CompletionTracker(CounterSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /** 记录新任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录任务完成。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                finishResync(snapshot);
            }
        }

        /** 初始任务分派完成。 */
        private void initialSchedulingDone() {
            taskDone();
        }
    }
}
