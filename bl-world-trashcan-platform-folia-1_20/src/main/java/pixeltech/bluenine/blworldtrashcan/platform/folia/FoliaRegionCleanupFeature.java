package pixeltech.bluenine.blworldtrashcan.platform.folia;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupConsoleDetailFormatter;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRoutingResult;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;
import pixeltech.bluenine.blworldtrashcan.storage.TrashLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunContext;
import pixeltech.worldlisttrashcan.api.audit.CleanupTrigger;

/** Folia 专用 region-safe 清理实现。 */
public final class FoliaRegionCleanupFeature implements Feature {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final WorldTrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
    private final PersonalTrashService personalTrashService;
    private final DropOwnerTracker dropOwnerTracker;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private TaskHandle taskHandle;
    private TaskHandle bossBarRemoveTask;
    private BossBar bossBar;
    private volatile CleanupFeature.CleanupStats lastStats = CleanupFeature.CleanupStats.empty();
    private long nextRunAtMillis;
    private int countdownSeconds;
    private int cleanupRunsSinceGlobalClear;

    /** 创建 Folia region-safe 清理功能。 */
    public FoliaRegionCleanupFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                                     WorldTrashRouter trashRouter, GlobalTrashService globalTrashService,
                                     PersonalTrashService personalTrashService, DropOwnerTracker dropOwnerTracker,
                                     DefaultWorldListTrashCanAuditBridge auditBridge) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
        this.dropOwnerTracker = dropOwnerTracker;
        this.auditBridge = auditBridge;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "folia-cleanup";
    }

    /** 启动 Folia 倒计时清理任务。 */
    @Override
    public void enable() {
        startTask();
    }

    /** 重载 Folia 清理任务。 */
    @Override
    public void reload() {
        disable();
        startTask();
    }

    /** 停止 Folia 清理任务。 */
    @Override
    public void disable() {
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
        cancelBossBarRemoval();
        removeBossBar();
        nextRunAtMillis = 0L;
        countdownSeconds = 0;
    }

    /** 立即启动一次异步 region-safe 清理，默认遵守定时扫地门禁。 */
    public boolean startNow() {
        return startNow(false, CleanupTrigger.SCHEDULED);
    }

    /** 立即启动一次异步 region-safe 清理并返回是否成功提交。 */
    public boolean startNow(final boolean ignoreGuards) {
        return startNow(ignoreGuards, CleanupTrigger.MANUAL);
    }

    /** 启动一次带明确触发来源的异步清理。 */
    private boolean startNow(final boolean ignoreGuards, final CleanupTrigger trigger) {
        if (!cleanupRunning.compareAndSet(false, true)) {
            plugin.getLogger().warning("[FoliaCleanup] 上一轮 region-safe 清理仍在运行，本次请求已跳过。");
            return false;
        }
        final CleanupFeature.CleanupStats stats = CleanupFeature.CleanupStats.empty();
        lastStats = stats;
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
                /** 在全局区域收集世界和已加载 chunk。 */
                @Override
                public void run() {
                    try {
                        scheduleWorldScans(stats, ignoreGuards, trigger);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("[FoliaCleanup] 分派 region-safe 清理失败: " + exception.getMessage());
                        finishCleanup(stats);
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            cleanupRunning.set(false);
            plugin.getLogger().warning("[FoliaCleanup] 启动 region-safe 清理失败: " + exception.getMessage());
            return false;
        }
    }

    /** 兼容旧调用方，返回当前正在收集的统计对象。 */
    public CleanupFeature.CleanupStats runNow() {
        startNow();
        return lastStats;
    }

    /** 兼容命令调用方，返回当前正在收集的统计对象。 */
    public CleanupFeature.CleanupStats runNow(boolean ignoreGuards) {
        startNow(ignoreGuards);
        return lastStats;
    }

    /** 判断 Folia 清理扫描是否可用。 */
    public boolean isWorldScanSupported() {
        return true;
    }

    /** 判断当前是否已有清理在运行。 */
    public boolean isRunning() {
        return cleanupRunning.get();
    }

    /** 返回最近一次清理统计。 */
    public CleanupFeature.CleanupStats getLastStats() {
        return lastStats;
    }

    /** 测试用：在 Folia 全局区域按正式通知配置触发指定编号的清理通知。 */
    public boolean debugNotify(final int count) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
                /** 在全局区域复用正式通知链路。 */
                @Override
                public void run() {
                    sendNotify(count, lastStats);
                    if (count == 0 || count == -4) {
                        logConsoleCleanupDetails(lastStats, count == -4);
                    }
                    plugin.getLogger().info("[Debug] debugNotify count=" + count);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Debug] 分派 Folia 清理通知调试失败: " + exception.getMessage());
            return false;
        }
    }

    /** 返回下次自动清理剩余秒数。 */
    public long getRemainingSeconds() {
        if (nextRunAtMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, (nextRunAtMillis - System.currentTimeMillis()) / 1000L);
    }

    /** 按配置启动每秒倒计时任务。 */
    private void startTask() {
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
        if (interval <= 0) {
            plugin.getLogger().info("[FoliaCleanup] 定时清理已关闭，仅允许手动触发。");
            return;
        }
        countdownSeconds = interval;
        nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
        taskHandle = platform.scheduler().runRepeating(new Runnable() {
            /** 推进倒计时。 */
            @Override
            public void run() {
                tickCountdown();
            }
        }, 20L, 20L);
        plugin.getLogger().info("[FoliaCleanup] region-safe 定时清理已启动，间隔 " + interval + " 秒。");
    }

    /** 每秒推进一次倒计时。 */
    private void tickCountdown() {
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        if (countdownSeconds <= 0) {
            runNow();
            countdownSeconds = interval;
            nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
            return;
        }
        sendNotify(countdownSeconds, CleanupFeature.CleanupStats.empty());
        countdownSeconds--;
        nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
    }

    /** 为所有未忽略世界的已加载 chunk 安排 region 任务。 */
    private void scheduleWorldScans(final CleanupFeature.CleanupStats stats, boolean ignoreGuards,
                                    CleanupTrigger trigger) {
        ConfigBundle bundle = configSupplier.get();
        CleanupPolicy policy = new DefaultCleanupPolicy(bundle.getCleanupSettings());
        CleanupConfig cleanupConfig = bundle.getCleanupConfig();
        CleanupConfig.FoliaCleanupConfig foliaConfig = cleanupConfig.getFoliaCleanup();
        CleanupConfig.CleanupGuardConfig guardConfig = cleanupConfig.getGuardConfig();
        stats.recordGuardState(Bukkit.getOnlinePlayers().size(), guardConfig.getMinOnlinePlayers(),
                -1, guardConfig.getMinTotalEntities());
        if (!ignoreGuards && stats.getGuardOnlinePlayers() < stats.getGuardMinOnlinePlayers()) {
            stats.markGuardSkipped(CleanupFeature.GUARD_REASON_ONLINE_PLAYERS);
            finishCleanup(stats);
            return;
        }
        List<Chunk> chunksToScan = new ArrayList<>();
        int chunksSeen = 0;
        int chunksSkippedByLimit = 0;
        for (World world : Bukkit.getWorlds()) {
            if (cleanupConfig.isIgnoredWorld(world.getName())) {
                continue;
            }
            try {
                stats.addWorld();
                Chunk[] chunks = world.getLoadedChunks();
                for (Chunk chunk : chunks) {
                    chunksSeen++;
                    if (isChunkScanLimited(foliaConfig, chunksToScan.size())) {
                        chunksSkippedByLimit++;
                        continue;
                    }
                    chunksToScan.add(chunk);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaCleanup] 收集世界已加载 chunk 失败: "
                        + world.getName() + " - " + exception.getMessage());
            }
        }
        if (!ignoreGuards && guardConfig.getMinTotalEntities() > 0) {
            final GuardCountTracker guardTracker = new GuardCountTracker(
                    stats, cleanupConfig, foliaConfig, chunksToScan, policy, trigger, ignoreGuards);
            guardTracker.startTimeout();
            scheduleGuardCountBatch(chunksToScan, 0, policy, guardTracker, foliaConfig);
            return;
        }
        stats.setGuardTargetEntities(0);
        handleGlobalTrashRefresh(stats);
        final CompletionTracker tracker = new CompletionTracker(
                stats, cleanupConfig, foliaConfig, beginAudit(trigger, ignoreGuards));
        tracker.recordCollectedChunks(chunksSeen, chunksSkippedByLimit);
        tracker.startTimeout();
        scheduleChunkBatch(chunksToScan, 0, policy, stats, tracker, foliaConfig);
    }

    /** 判断本轮 chunk 扫描是否已经达到上限。 */
    private boolean isChunkScanLimited(CleanupConfig.FoliaCleanupConfig foliaConfig, int currentSize) {
        int maxChunks = foliaConfig.getMaxChunksPerCleanup();
        return maxChunks > 0 && currentSize >= maxChunks;
    }

    /** 分批派发扫地门禁目标实体计数任务。 */
    private void scheduleGuardCountBatch(final List<Chunk> chunks, final int startIndex, final CleanupPolicy policy,
                                         final GuardCountTracker tracker,
                                         final CleanupConfig.FoliaCleanupConfig foliaConfig) {
        if (!tracker.isOpen()) {
            return;
        }
        int endIndex = Math.min(chunks.size(), startIndex + foliaConfig.getChunkBatchSize());
        for (int index = startIndex; index < endIndex; index++) {
            scheduleGuardCount(chunks.get(index), policy, tracker);
        }
        if (endIndex >= chunks.size()) {
            tracker.initialSchedulingDone();
            return;
        }
        try {
            platform.scheduler().runLater(new Runnable() {
                /** 继续派发下一批门禁计数任务。 */
                @Override
                public void run() {
                    scheduleGuardCountBatch(chunks, endIndex, policy, tracker, foliaConfig);
                }
            }, foliaConfig.getChunkBatchDelayTicks());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分批派发门禁计数失败: " + exception.getMessage());
            tracker.initialSchedulingDone();
        }
    }

    /** 安排单个 chunk 的门禁目标实体计数任务。 */
    private void scheduleGuardCount(final Chunk chunk, final CleanupPolicy policy, final GuardCountTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        tracker.taskStarted();
        try {
            Bukkit.getRegionScheduler().run(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), new Consumer<ScheduledTask>() {
                /** 在 chunk 所在 region 内统计会被扫地处理的实体。 */
                @Override
                public void accept(ScheduledTask task) {
                    try {
                        if (tracker.isOpen()) {
                            countChunkTargets(chunk, policy, tracker);
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("[FoliaCleanup] 统计门禁目标实体失败: "
                                + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                                + " - " + exception.getMessage());
                    } finally {
                        tracker.taskDone();
                    }
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派门禁计数失败: "
                    + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                    + " - " + exception.getMessage());
            tracker.taskDone();
        }
    }

    /** 在当前 region 内统计会被扫地处理的实体。 */
    private void countChunkTargets(Chunk chunk, CleanupPolicy policy, GuardCountTracker tracker) {
        Entity[] entities = chunk.getEntities();
        for (Entity entity : entities) {
            if (!tracker.isOpen()) {
                return;
            }
            if (!(entity instanceof Player) && isCleanableTarget(entity, tracker.cleanupConfig, policy)) {
                tracker.targetFound();
            }
        }
    }

    /** 完成 Folia 门禁计数后决定是否进入正式清理。 */
    private void finishGuardCount(final GuardCountTracker tracker, final boolean timedOut) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
                /** 在全局区域根据门禁计数继续或跳过。 */
                @Override
                public void run() {
                    finishGuardCountOnGlobalRegion(tracker, timedOut);
                }
            });
        } catch (RuntimeException exception) {
            cleanupRunning.set(false);
            plugin.getLogger().warning("[FoliaCleanup] 分派门禁计数收尾失败，已释放运行状态: " + exception.getMessage());
        }
    }

    /** 在全局区域根据门禁计数继续或跳过。 */
    private void finishGuardCountOnGlobalRegion(GuardCountTracker tracker, boolean timedOut) {
        CleanupFeature.CleanupStats stats = tracker.stats;
        stats.setGuardTargetEntities(tracker.targetEntities.get());
        if (timedOut || stats.getGuardTargetEntities() < stats.getGuardMinTotalEntities()) {
            stats.markGuardSkipped(CleanupFeature.GUARD_REASON_TARGET_ENTITIES);
            finishCleanupOnGlobalRegion(stats, null, timedOut);
            return;
        }
        handleGlobalTrashRefresh(stats);
        CompletionTracker cleanupTracker = new CompletionTracker(
                stats, tracker.cleanupConfig, tracker.foliaConfig,
                beginAudit(tracker.trigger, tracker.guardsIgnored));
        cleanupTracker.recordCollectedChunks(tracker.chunks.size(), 0);
        cleanupTracker.startTimeout();
        scheduleChunkBatch(tracker.chunks, 0, tracker.policy, stats, cleanupTracker, tracker.foliaConfig);
    }

    /** 分批向 Folia region scheduler 派发 chunk 扫描任务。 */
    private void scheduleChunkBatch(final List<Chunk> chunks, final int startIndex, final CleanupPolicy policy,
                                    final CleanupFeature.CleanupStats stats, final CompletionTracker tracker,
                                    final CleanupConfig.FoliaCleanupConfig foliaConfig) {
        if (!tracker.isOpen()) {
            return;
        }
        int endIndex = Math.min(chunks.size(), startIndex + foliaConfig.getChunkBatchSize());
        for (int index = startIndex; index < endIndex; index++) {
            scheduleChunkScan(chunks.get(index), policy, stats, tracker);
        }
        if (endIndex >= chunks.size()) {
            tracker.initialSchedulingDone();
            return;
        }
        try {
            platform.scheduler().runLater(new Runnable() {
                /** 继续派发下一批 chunk 扫描任务。 */
                @Override
                public void run() {
                    scheduleChunkBatch(chunks, endIndex, policy, stats, tracker, foliaConfig);
                }
            }, foliaConfig.getChunkBatchDelayTicks());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分批派发 chunk 扫描失败: " + exception.getMessage());
            tracker.initialSchedulingDone();
        }
    }

    /** 安排单个 chunk 的 region 清理任务。 */
    private void scheduleChunkScan(final Chunk chunk, final CleanupPolicy policy,
                                   final CleanupFeature.CleanupStats stats, final CompletionTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        tracker.taskStarted();
        tracker.chunkScheduled();
        try {
            Bukkit.getRegionScheduler().run(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), new Consumer<ScheduledTask>() {
                /** 在 chunk 所在 region 里处理实体。 */
                @Override
                public void accept(ScheduledTask task) {
                    try {
                        if (tracker.isOpen()) {
                            cleanChunk(chunk, policy, stats, tracker);
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("[FoliaCleanup] 清理 chunk 失败: "
                                + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                                + " - " + exception.getMessage());
                    } finally {
                        tracker.chunkDone();
                        tracker.taskDone();
                    }
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派 chunk 失败: "
                    + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                    + " - " + exception.getMessage());
            tracker.chunkDispatchFailed();
            tracker.taskDone();
        }
    }

    /** 在当前 region 内清理 chunk 实体。 */
    private void cleanChunk(Chunk chunk, CleanupPolicy policy, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        Entity[] entities = chunk.getEntities();
        if (!tracker.isOpen()) {
            return;
        }
        for (Entity entity : entities) {
            if (!tracker.isOpen()) {
                return;
            }
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof Item) {
                cleanItem((Item) entity, policy, stats, tracker);
                continue;
            }
            cleanEntity(entity, policy, stats, tracker);
        }
    }

    /** 判断实体是否会被本轮扫地处理。 */
    private boolean isCleanableTarget(Entity entity, CleanupConfig cleanupConfig, CleanupPolicy policy) {
        if (entity instanceof Item) {
            return isCleanableItemTarget((Item) entity, cleanupConfig, policy);
        }
        EntityCleanupDecision decision = policy.decideEntity(platform.entitySnapshotMapper().toSnapshot(entity));
        return decision.getAction() == EntityCleanupAction.REMOVE;
    }

    /** 判断掉落物是否会被本轮扫地路由或删除。 */
    private boolean isCleanableItemTarget(Item item, CleanupConfig cleanupConfig, CleanupPolicy policy) {
        if (isMovingItemProtected(item, cleanupConfig)) {
            return false;
        }
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            return false;
        }
        ItemSnapshot snapshot = snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item));
        RouteState state = initialRouteState(item.getWorld(), snapshot, itemStack, cleanupConfig);
        TrashRoutingDecision decision = policy.decideItem(snapshot, state.worldAvailable, state.personalAvailable, state.globalAvailable);
        return decision.getRoute() != TrashRoute.SKIP;
    }

    /** 在物品实体所在 region 内处理掉落物。 */
    private void cleanItem(Item item, CleanupPolicy policy, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            stats.addItemsSkipped(1);
            return;
        }
        if (isMovingItemProtected(item, tracker.cleanupConfig)) {
            stats.addItemsSkipped(itemStack.getAmount());
            return;
        }
        ItemStack routedStack = itemStack.clone();
        ItemSnapshot snapshot = snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item));
        RouteState state = initialRouteState(item.getWorld(), snapshot, routedStack, tracker.cleanupConfig);
        routeWithFallback(item, routedStack, snapshot, policy, state, stats, tracker);
    }

    /** 判断掉落物是否因当前速度达到阈值而在本轮扫地中受保护。 */
    private boolean isMovingItemProtected(Item item, CleanupConfig cleanupConfig) {
        CleanupConfig.MovingItemConfig movingItems = cleanupConfig.getMovingItems();
        if (!movingItems.isEnabled()) {
            return false;
        }
        Vector velocity = item.getVelocity();
        return movingItems.isMoving(velocity.lengthSquared());
    }

    /** 生成初始路由可用性。 */
    private RouteState initialRouteState(World world, ItemSnapshot snapshot, ItemStack itemStack,
                                         CleanupConfig cleanupConfig) {
        if (cleanupConfig.isDirectRemoveWorld(world.getName())) {
            return new RouteState(false, false, false);
        }
        UUID ownerUuid = snapshot == null ? null : snapshot.getOwnerUuid();
        synchronized (trashRouter) {
            return new RouteState(
                    trashRouter.hasWorldTrash(world, itemStack),
                    trashRouter.hasPersonalTrash(ownerUuid, itemStack),
                    trashRouter.hasGlobalTrash(itemStack)
            );
        }
    }

    /** 按核心策略逐级路由或删除物品。 */
    private void routeWithFallback(Item item, ItemStack itemStack, ItemSnapshot snapshot, CleanupPolicy policy,
                                   RouteState state, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        TrashRoutingDecision decision = policy.decideItem(snapshot, state.worldAvailable, state.personalAvailable, state.globalAvailable);
        while (true) {
            if (!tracker.isOpen()) {
                return;
            }
            TrashRoute route = decision.getRoute();
            if (route == TrashRoute.SKIP) {
                stats.addItemsSkipped(itemStack.getAmount());
                return;
            }
            if (route == TrashRoute.REMOVE) {
                forgetTrackedOwner(item);
                item.remove();
                tracker.recordItem(itemStack, CleanupItemDestination.directRemove());
                stats.addItemsRemoved(itemStack.getAmount());
                return;
            }
            if (route == TrashRoute.WORLD_TRASH) {
                List<TrashLocation> locations = worldTrashLocations(item.getWorld(), itemStack);
                if (locations.isEmpty()) {
                    state.worldAvailable = false;
                    decision = policy.decideItem(snapshot, state.worldAvailable, state.personalAvailable, state.globalAvailable);
                    continue;
                }
                tryWorldTrash(item, itemStack, snapshot, policy, state, stats, tracker, locations, 0);
                return;
            }
            TrashRoutingResult virtualResult = routeVirtual(item, itemStack, snapshot.getOwnerUuid(), route);
            if (virtualResult.isSuccess()) {
                forgetTrackedOwner(item);
                item.remove();
                tracker.recordItem(itemStack, virtualResult.getDestination());
                stats.addItemsRouted(itemStack.getAmount(), route);
                if (route == TrashRoute.PERSONAL_TRASH) {
                    stats.addPersonalTrashItem(snapshot.getOwnerUuid(), itemStack);
                }
                return;
            }
            state.markUnavailable(route);
            decision = policy.decideItem(snapshot, state.worldAvailable, state.personalAvailable, state.globalAvailable);
        }
    }

    /** 返回世界垃圾桶位置快照。 */
    private List<TrashLocation> worldTrashLocations(World world, ItemStack itemStack) {
        synchronized (trashRouter) {
            Collection<TrashLocation> locations = trashRouter.getWorldTrashLocations(world, itemStack);
            return new ArrayList<>(locations);
        }
    }

    /** 尝试把物品写入世界垃圾桶位置列表。 */
    private void tryWorldTrash(final Item item, final ItemStack itemStack, final ItemSnapshot snapshot,
                               final CleanupPolicy policy, final RouteState state,
                               final CleanupFeature.CleanupStats stats, final CompletionTracker tracker,
                               final List<TrashLocation> locations, final int index) {
        if (!tracker.isOpen()) {
            return;
        }
        if (index >= locations.size()) {
            scheduleItemFallback(item, itemStack, snapshot, policy, state, stats, tracker);
            return;
        }
        final TrashLocation location = locations.get(index);
        final World world = Bukkit.getWorld(location.getWorldName());
        if (world == null) {
            tryWorldTrash(item, itemStack, snapshot, policy, state, stats, tracker, locations, index + 1);
            return;
        }
        tracker.taskStarted();
        try {
            Bukkit.getRegionScheduler().run(plugin, world, location.getX() >> 4, location.getZ() >> 4, new Consumer<ScheduledTask>() {
                /** 在箱子所在 region 内写入世界垃圾桶。 */
                @Override
                public void accept(ScheduledTask task) {
                    try {
                        if (!tracker.isOpen()) {
                            return;
                        }
                        if (trashRouter.routeWorldTrashAt(location, itemStack.clone())) {
                            forgetTrackedOwner(item);
                            stats.addItemsRouted(itemStack.getAmount(), TrashRoute.WORLD_TRASH);
                            scheduleRemoveRoutedItem(item, itemStack, tracker,
                                    trashRouter.destination(location));
                        } else {
                            tryWorldTrash(item, itemStack, snapshot, policy, state, stats, tracker, locations, index + 1);
                        }
                    } finally {
                        tracker.taskDone();
                    }
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派世界垃圾桶写入失败: "
                    + location.getWorldName() + "," + location.getX() + "," + location.getY() + "," + location.getZ()
                    + " - " + exception.getMessage());
            tryWorldTrash(item, itemStack, snapshot, policy, state, stats, tracker, locations, index + 1);
            tracker.taskDone();
        }
    }

    /** 世界垃圾桶全部失败后回到物品实体 region 继续降级路由。 */
    private void scheduleItemFallback(final Item item, final ItemStack itemStack, final ItemSnapshot snapshot,
                                      final CleanupPolicy policy, final RouteState state,
                                      final CleanupFeature.CleanupStats stats, final CompletionTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        state.worldAvailable = false;
        tracker.taskStarted();
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable retired = new Runnable() {
            /** 实体已卸载时结束降级任务。 */
            @Override
            public void run() {
                if (finished.compareAndSet(false, true)) {
                    stats.addItemsSkipped(itemStack.getAmount());
                    tracker.taskDone();
                }
            }
        };
        boolean scheduled;
        try {
            scheduled = item.getScheduler().execute(plugin, new Runnable() {
                /** 在物品实体 region 内继续降级路由。 */
                @Override
                public void run() {
                    try {
                        if (tracker.isOpen()) {
                            routeWithFallback(item, itemStack, snapshot, policy, state, stats, tracker);
                        }
                    } finally {
                        if (finished.compareAndSet(false, true)) {
                            tracker.taskDone();
                        }
                    }
                }
            }, retired, 1L);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派物品降级路由失败: " + exception.getMessage());
            scheduled = false;
        }
        if (!scheduled) {
            retired.run();
        }
    }

    /** 删除已经成功路由的物品实体。 */
    private void scheduleRemoveRoutedItem(final Item item, final ItemStack itemStack,
                                          final CompletionTracker tracker,
                                          final CleanupItemDestination destination) {
        if (!tracker.isOpen()) {
            return;
        }
        tracker.taskStarted();
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable retired = new Runnable() {
            /** 实体已卸载时结束删除任务。 */
            @Override
            public void run() {
                if (finished.compareAndSet(false, true)) {
                    tracker.taskDone();
                }
            }
        };
        boolean scheduled;
        try {
            scheduled = item.getScheduler().execute(plugin, new Runnable() {
                /** 在物品实体 region 内删除实体。 */
                @Override
                public void run() {
                    try {
                        if (tracker.isOpen()) {
                            item.remove();
                            tracker.recordItem(itemStack, destination);
                        }
                    } finally {
                        if (finished.compareAndSet(false, true)) {
                            tracker.taskDone();
                        }
                    }
                }
            }, retired, 1L);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派物品删除失败: " + exception.getMessage());
            scheduled = false;
        }
        if (!scheduled) {
            retired.run();
        }
    }

    /** 路由到个人或公共虚拟垃圾桶。 */
    private TrashRoutingResult routeVirtual(Item item, ItemStack itemStack, UUID ownerUuid, TrashRoute route) {
        synchronized (trashRouter) {
            return trashRouter.routeDetailed(item.getWorld(), ownerUuid, itemStack.clone(), route, true);
        }
    }

    /** 使用短期 owner 记录补齐不支持 PDC 平台上的物品归属。 */
    private ItemSnapshot snapshotWithTrackedOwner(Item item, ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.getOwnerUuid() != null || dropOwnerTracker == null) {
            return snapshot;
        }
        return snapshot.withOwnerUuid(dropOwnerTracker.findOwner(item));
    }

    /** 清理已完成路由或删除的掉落物 owner 记录。 */
    private void forgetTrackedOwner(Item item) {
        if (dropOwnerTracker != null) {
            dropOwnerTracker.removeOwner(item);
        }
    }

    /** 在当前 region 内清理非物品实体。 */
    private void cleanEntity(Entity entity, CleanupPolicy policy, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        if (!tracker.isOpen()) {
            return;
        }
        EntitySnapshot snapshot = platform.entitySnapshotMapper().toSnapshot(entity);
        EntityCleanupDecision decision = policy.decideEntity(snapshot);
        if (!tracker.isOpen()) {
            return;
        }
        if (decision.getAction() == EntityCleanupAction.REMOVE) {
            entity.remove();
            stats.addEntitiesRemoved(snapshot);
            return;
        }
        stats.addEntitiesSkipped();
    }

    /** 结束清理并在全局区域输出日志和通知。 */
    private void finishCleanup(final CleanupFeature.CleanupStats stats) {
        finishCleanup(stats, null, false);
    }

    /** 结束清理并在全局区域输出日志和通知。 */
    private void finishCleanup(final CleanupFeature.CleanupStats stats, final CompletionTracker tracker, final boolean timedOut) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
                /** 记录最终统计。 */
                @Override
                public void run() {
                    finishCleanupOnGlobalRegion(stats, tracker, timedOut);
                }
            });
        } catch (RuntimeException exception) {
            cleanupRunning.set(false);
            if (tracker != null) {
                tracker.discardAudit();
            }
            plugin.getLogger().warning("[FoliaCleanup] 分派清理收尾失败，已释放运行状态: " + exception.getMessage());
        }
    }

    /** 在全局区域完成清理统计、通知和状态释放。 */
    private void finishCleanupOnGlobalRegion(CleanupFeature.CleanupStats stats, CompletionTracker tracker, boolean timedOut) {
        if (stats.isGuardSkipped()) {
            lastStats = stats;
            cleanupRunning.set(false);
            plugin.getLogger().info("[FoliaCleanup] skippedByGuard=true"
                    + ", guardReason=" + stats.getGuardSkipReason()
                    + ", onlinePlayers=" + stats.getGuardOnlinePlayers()
                    + ", minOnlinePlayers=" + stats.getGuardMinOnlinePlayers()
                    + ", targetEntities=" + stats.getGuardTargetEntities()
                    + ", minTotalEntities=" + stats.getGuardMinTotalEntities()
                    + ", worlds=" + stats.getWorlds()
                    + ", timedOut=" + timedOut);
            sendNotify(-5, stats);
            return;
        }
        if (tracker != null) {
            tracker.finishAudit(timedOut);
        }
        sendPersonalTrashBatchNotify(stats);
        lastStats = stats;
        cleanupRunning.set(false);
        long elapsedMs = tracker == null ? -1L : tracker.elapsedMillis();
        plugin.getLogger().info("[FoliaCleanup] worlds=" + stats.getWorlds()
                + ", skippedByGuard=" + stats.isGuardSkipped()
                + ", guardReason=" + stats.getGuardSkipReason()
                + ", onlinePlayers=" + stats.getGuardOnlinePlayers()
                + ", minOnlinePlayers=" + stats.getGuardMinOnlinePlayers()
                + ", targetEntities=" + stats.getGuardTargetEntities()
                + ", minTotalEntities=" + stats.getGuardMinTotalEntities()
                + ", itemsRouted=" + stats.getItemsRouted()
                + ", itemsRemoved=" + stats.getItemsRemoved()
                + ", itemsSkipped=" + stats.getItemsSkipped()
                + ", entitiesRemoved=" + stats.getEntitiesRemoved()
                + ", entitiesSkipped=" + stats.getEntitiesSkipped()
                + ", chunksSeen=" + trackerValue(tracker, TrackerMetric.CHUNKS_SEEN)
                + ", chunksScheduled=" + trackerValue(tracker, TrackerMetric.CHUNKS_SCHEDULED)
                + ", chunksDone=" + trackerValue(tracker, TrackerMetric.CHUNKS_DONE)
                + ", chunksSkippedByLimit=" + trackerValue(tracker, TrackerMetric.CHUNKS_SKIPPED_BY_LIMIT)
                + ", chunksDispatchFailed=" + trackerValue(tracker, TrackerMetric.CHUNKS_DISPATCH_FAILED)
                + ", pendingTasks=" + trackerValue(tracker, TrackerMetric.PENDING_TASKS)
                + ", timeoutSeconds=" + (tracker == null ? -1 : tracker.timeoutSeconds())
                + ", timedOut=" + timedOut
                + ", elapsedMs=" + elapsedMs
                + ", clearEvery=" + currentClearEveryCleanups()
                + ", worldTrashSkippedUnloadedChunks=" + trashRouter.getSkippedUnloadedChunkAccesses()
                + ", globalTrashRefreshed=" + stats.isGlobalTrashRefreshed());
        logConsoleCleanupDetails(stats, timedOut);
        sendNotify(timedOut ? -4 : 0, stats);
        sendNotify(globalTrashStatusNotifyCount(stats), stats);
    }

    /** 返回跟踪器计数，未启用跟踪器时返回 -1。 */
    private int trackerValue(CompletionTracker tracker, TrackerMetric metric) {
        return tracker == null ? -1 : metric.value(tracker);
    }

    /** 创建本轮 Folia 清理使用的审计会话。 */
    private CleanupAuditSession beginAudit(CleanupTrigger trigger, boolean guardsIgnored) {
        return auditBridge.beginRun(new CleanupRunContext(
                UUID.randomUUID(), System.currentTimeMillis(), trigger, guardsIgnored));
    }

    /** 返回当前公共垃圾桶自动刷新间隔配置。 */
    private int currentClearEveryCleanups() {
        return configSupplier.get().getTrashConfig().getGlobalTrash().getClearEveryCleanups();
    }

    /** 返回本轮公共垃圾桶状态对应的通知编号。 */
    private int globalTrashStatusNotifyCount(CleanupFeature.CleanupStats stats) {
        if (stats.isGlobalTrashRefreshed()) {
            return -2;
        }
        if (currentClearEveryCleanups() < 0 || globalTrashService == null || !globalTrashService.isEnabled()) {
            return -3;
        }
        return -1;
    }

    /** 在本轮实际清理前按清理次数刷新公共垃圾桶。 */
    private void handleGlobalTrashRefresh(CleanupFeature.CleanupStats stats) {
        int interval = configSupplier.get().getTrashConfig().getGlobalTrash().getClearEveryCleanups();
        if (interval < 0 || globalTrashService == null || !globalTrashService.isEnabled()) {
            return;
        }
        cleanupRunsSinceGlobalClear++;
        if (interval == 0 || cleanupRunsSinceGlobalClear >= interval) {
            synchronized (globalTrashService) {
                globalTrashService.clearContent();
            }
            cleanupRunsSinceGlobalClear = 0;
            stats.markGlobalTrashRefreshed();
        }
    }

    /** 发送本轮进入个人垃圾桶的批量提示。 */
    private void sendPersonalTrashBatchNotify(CleanupFeature.CleanupStats stats) {
        if (personalTrashService != null) {
            personalTrashService.notifyBatch(stats.snapshotPersonalTrashItemsByOwner());
        }
    }

    /** 按配置发送 Folia 安全通知。 */
    private void sendNotify(int count, CleanupFeature.CleanupStats stats) {
        NotifyConfig notifyConfig = configSupplier.get().getNotifyConfig();
        sendChatNotify(notifyConfig, count, stats);
        sendConsoleNotify(notifyConfig, count, stats);
        sendActionBarNotify(notifyConfig, count, stats);
        sendBossBarNotify(notifyConfig, count, stats);
        sendTitleNotify(notifyConfig, count, stats);
        sendSoundNotify(notifyConfig, count);
        runCommandNotify(notifyConfig, count, stats);
    }

    /** 发送聊天通知。 */
    private void sendChatNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isChatEnabled() || !notifyConfig.getChatMessages().containsKey(count)) {
            return;
        }
        final String message = applyStats(notifyConfig.getChatMessages().get(count), stats);
        final boolean clickable = count == 0 && !notifyConfig.getChatClickCommand().trim().isEmpty();
        final String clickCommand = notifyConfig.getChatClickCommand();
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文发送聊天消息。 */
            @Override
            public void run(Player player) {
                if (clickable) {
                    sendClickableChat(player, message, clickCommand);
                    return;
                }
                player.sendMessage(RichTextRenderer.color(player, message));
            }
        });
    }

    /** 独立向控制台输出对应编号的聊天通知文案。 */
    private void sendConsoleNotify(NotifyConfig notifyConfig, int count,
                                   CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.getConsole().isEnabled()) {
            return;
        }
        String configuredMessage = notifyConfig.getChatMessages().get(count);
        if (configuredMessage != null) {
            Bukkit.getConsoleSender().sendMessage(RichTextRenderer.color(applyStats(configuredMessage, stats)));
        }
    }

    /** 按控制台配置输出本轮清理详细统计。 */
    private void logConsoleCleanupDetails(CleanupFeature.CleanupStats stats, boolean partial) {
        NotifyConfig.ConsoleConfig consoleConfig = configSupplier.get().getNotifyConfig().getConsole();
        if (!consoleConfig.isEnabled() || !consoleConfig.isDetailsEnabled()) {
            return;
        }
        for (String line : CleanupConsoleDetailFormatter.format(consoleConfig, stats, partial)) {
            plugin.getLogger().info("[CleanupDetail] " + line);
        }
    }

    /** 使用 Folia/Paper 原生 Adventure 组件发送可点击聊天。 */
    private void sendClickableChat(Player player, String message, String clickCommand) {
        try {
            Component component = LegacyComponentSerializer.legacySection()
                    .deserialize(RichTextRenderer.color(player, message));
            player.sendMessage(withClickEvent(component, ClickEvent.runCommand(clickCommand)));
        } catch (RuntimeException error) {
            player.spigot().sendMessage(RichTextRenderer.clickable(player, message, clickCommand));
        } catch (LinkageError error) {
            player.spigot().sendMessage(RichTextRenderer.clickable(player, message, clickCommand));
        }
    }

    /** 递归给 Adventure 组件树补点击事件。 */
    private Component withClickEvent(Component component, ClickEvent clickEvent) {
        List<Component> children = component.children();
        if (children.isEmpty()) {
            return component.clickEvent(clickEvent);
        }
        List<Component> updatedChildren = new ArrayList<>();
        for (Component child : children) {
            updatedChildren.add(withClickEvent(child, clickEvent));
        }
        return component.children(updatedChildren).clickEvent(clickEvent);
    }

    /** 发送 ActionBar 通知。 */
    private void sendActionBarNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isActionBarEnabled() || !notifyConfig.getActionBarMessages().containsKey(count)) {
            return;
        }
        final String message = applyStats(notifyConfig.getActionBarMessages().get(count), stats);
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文发送 ActionBar。 */
            @Override
            public void run(Player player) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, RichTextRenderer.components(player, message));
            }
        });
    }

    /** 发送 BossBar 通知。 */
    private void sendBossBarNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isBossBarEnabled()) {
            cancelBossBarRemoval();
            removeBossBar();
            return;
        }
        NotifyConfig.BossBarMessage message = notifyConfig.getBossBarMessages().get(count);
        if (message == null) {
            if (count <= 0) {
                scheduleBossBarRemoval();
            }
            return;
        }
        final BossBar current = bossBar();
        current.setTitle(RichTextRenderer.color(applyStats(message.getText(), stats)));
        current.setStyle(parseBossBarStyle(message.getStyle()));
        current.setColor(parseBossBarColor(message.getColor()));
        current.setProgress(bossBarProgress(count, notifyConfig));
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文加入 BossBar。 */
            @Override
            public void run(Player player) {
                current.addPlayer(player);
            }
        });
        if (count <= 0) {
            scheduleBossBarRemoval();
            return;
        }
        cancelBossBarRemoval();
    }

    /** 发送 Title 通知。 */
    private void sendTitleNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isTitleEnabled() || !notifyConfig.getTitleMessages().containsKey(count)) {
            return;
        }
        NotifyConfig.TitleMessage message = notifyConfig.getTitleMessages().get(count);
        final String title = applyStats(message.getTitle(), stats);
        final String subtitle = applyStats(message.getSubtitle(), stats);
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文发送 Title。 */
            @Override
            public void run(Player player) {
                player.sendTitle(RichTextRenderer.color(player, title), RichTextRenderer.color(player, subtitle), 10, 70, 20);
            }
        });
    }

    /** 发送声音通知。 */
    private void sendSoundNotify(NotifyConfig notifyConfig, int count) {
        if (!notifyConfig.isSoundEnabled() || !notifyConfig.getSoundMessages().containsKey(count)) {
            return;
        }
        final NotifyConfig.SoundMessage message = notifyConfig.getSoundMessages().get(count);
        if (message.getSound().trim().isEmpty()) {
            return;
        }
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文播放声音。 */
            @Override
            public void run(Player player) {
                player.playSound(player.getLocation(), message.getSound(), message.getVolume(), message.getPitch());
            }
        });
    }

    /** 执行倒计时命令。 */
    private void runCommandNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isCommandEnabled() || !notifyConfig.getCommandMessages().containsKey(count)) {
            return;
        }
        for (String command : notifyConfig.getCommandMessages().get(count)) {
            String finalCommand = RichTextRenderer.stripColor(applyStats(command, stats));
            if (!finalCommand.trim().isEmpty()) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("[FoliaCleanup] 执行清理通知命令失败: " + exception.getMessage());
                }
            }
        }
    }

    /** 遍历在线玩家并提交到玩家实体调度器。 */
    private void forEachOnlinePlayer(final PlayerAction action) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            Runnable retired = new Runnable() {
                /** 玩家实体不可用时跳过本次通知。 */
                @Override
                public void run() {
                }
            };
            try {
                player.getScheduler().execute(plugin, new Runnable() {
                    /** 在玩家实体上下文执行通知动作。 */
                    @Override
                    public void run() {
                        action.run(player);
                    }
                }, retired, 1L);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaCleanup] 分派玩家通知失败: "
                        + player.getName() + " - " + exception.getMessage());
            }
        }
    }

    /** 返回可复用 BossBar 实例。 */
    private BossBar bossBar() {
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        }
        return bossBar;
    }

    /** 计算 BossBar 进度。 */
    private double bossBarProgress(int count, NotifyConfig notifyConfig) {
        int max = 0;
        for (Integer key : notifyConfig.getBossBarMessages().keySet()) {
            if (key != null && key > max) {
                max = key;
            }
        }
        if (count <= 0 || max <= 0) {
            return 1D;
        }
        return Math.max(0D, Math.min(1D, count / (double) max));
    }

    /** 解析 BossBar 颜色，配置错误时使用绿色。 */
    private BarColor parseBossBarColor(String value) {
        try {
            return BarColor.valueOf((value == null ? "" : value.trim()).toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return BarColor.GREEN;
        }
    }

    /** 解析 BossBar 样式，配置错误时使用实心样式。 */
    private BarStyle parseBossBarStyle(String value) {
        try {
            return BarStyle.valueOf((value == null ? "" : value.trim()).toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return BarStyle.SOLID;
        }
    }

    /** 延迟移除完成后的 BossBar。 */
    private void scheduleBossBarRemoval() {
        cancelBossBarRemoval();
        try {
            bossBarRemoveTask = platform.scheduler().runLater(new Runnable() {
                /** 执行 BossBar 延迟移除。 */
                @Override
                public void run() {
                    removeBossBar();
                    bossBarRemoveTask = null;
                }
            }, 90L);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派 BossBar 移除失败: " + exception.getMessage());
            bossBarRemoveTask = null;
        }
    }

    /** 取消等待中的 BossBar 移除任务。 */
    private void cancelBossBarRemoval() {
        if (bossBarRemoveTask != null) {
            bossBarRemoveTask.cancel();
            bossBarRemoveTask = null;
        }
    }

    /** 从所有玩家屏幕移除 BossBar。 */
    private void removeBossBar() {
        if (bossBar == null) {
            return;
        }
        final BossBar current = bossBar;
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文移除 BossBar。 */
            @Override
            public void run(Player player) {
                current.removePlayer(player);
            }
        });
        try {
            current.removeAll();
        } catch (RuntimeException ignored) {
            // 在线玩家会通过实体调度器移除；这里兜底处理无玩家场景。
        }
    }

    /** 替换通知中的统计占位符。 */
    private String applyStats(String message, CleanupFeature.CleanupStats stats) {
        int dealItemSum = stats.getItemsRouted() + stats.getItemsRemoved();
        int clearEvery = configSupplier.get().getTrashConfig().getGlobalTrash().getClearEveryCleanups();
        int clearRemain = remainingGlobalClearCount(clearEvery);
        return (message == null ? "" : message)
                .replace("%DealItemSum%", String.valueOf(dealItemSum))
                .replace("%GlobalTrashAddSum%", String.valueOf(stats.getItemsToGlobalTrash()))
                .replace("%EntitySum%", String.valueOf(stats.getEntitiesRemoved()))
                .replace("%CleanupSkipReason%", guardReasonText(stats))
                .replace("%CleanupOnlinePlayers%", String.valueOf(stats.getGuardOnlinePlayers()))
                .replace("%CleanupMinOnlinePlayers%", String.valueOf(stats.getGuardMinOnlinePlayers()))
                .replace("%CleanupTargetEntities%", String.valueOf(stats.getGuardTargetEntities()))
                .replace("%CleanupMinTotalEntities%", String.valueOf(stats.getGuardMinTotalEntities()))
                .replace("%ClearGlobalText%", clearGlobalText(clearEvery, clearRemain))
                .replace("%ClearGlobalCount%", String.valueOf(clearRemain));
    }

    /** 返回扫地门禁原因文案。 */
    private String guardReasonText(CleanupFeature.CleanupStats stats) {
        if (CleanupFeature.GUARD_REASON_ONLINE_PLAYERS.equals(stats.getGuardSkipReason())) {
            return "在线人数不足";
        }
        if (CleanupFeature.GUARD_REASON_TARGET_ENTITIES.equals(stats.getGuardSkipReason())) {
            return "目标实体数量不足";
        }
        return "未跳过";
    }

    /** 返回公共垃圾桶刷新剩余清理次数。 */
    private int remainingGlobalClearCount(int clearEvery) {
        return clearEvery <= 0 ? 0 : Math.max(0, clearEvery - cleanupRunsSinceGlobalClear);
    }

    /** 返回公共垃圾桶刷新状态文案。 */
    private String clearGlobalText(int clearEvery, int clearRemain) {
        if (clearEvery < 0) {
            return "公共垃圾桶不会自动刷新";
        }
        if (clearEvery == 0) {
            return "公共垃圾桶每次清理都会刷新";
        }
        return "还有 " + clearRemain + " 次清理，公共垃圾桶会刷新";
    }

    /** 玩家调度动作。 */
    private interface PlayerAction {
        /** 在玩家实体上下文执行。 */
        void run(Player player);
    }

    /** 路由可用性状态。 */
    private static final class RouteState {
        private boolean worldAvailable;
        private boolean personalAvailable;
        private boolean globalAvailable;

        /** 创建路由状态。 */
        private RouteState(boolean worldAvailable, boolean personalAvailable, boolean globalAvailable) {
            this.worldAvailable = worldAvailable;
            this.personalAvailable = personalAvailable;
            this.globalAvailable = globalAvailable;
        }

        /** 标记指定路由不可用。 */
        private void markUnavailable(TrashRoute route) {
            if (route == TrashRoute.WORLD_TRASH) {
                worldAvailable = false;
            } else if (route == TrashRoute.PERSONAL_TRASH) {
                personalAvailable = false;
            } else if (route == TrashRoute.GLOBAL_TRASH) {
                globalAvailable = false;
            }
        }
    }

    /** 跟踪器日志指标。 */
    private enum TrackerMetric {
        CHUNKS_SEEN {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.chunksSeen.get();
            }
        },
        CHUNKS_SCHEDULED {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.chunksScheduled.get();
            }
        },
        CHUNKS_DONE {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.chunksDone.get();
            }
        },
        CHUNKS_SKIPPED_BY_LIMIT {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.chunksSkippedByLimit.get();
            }
        },
        CHUNKS_DISPATCH_FAILED {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.chunksDispatchFailed.get();
            }
        },
        PENDING_TASKS {
            /** 返回指标值。 */
            @Override
            int value(CompletionTracker tracker) {
                return tracker.pendingTasks.get();
            }
        };

        /** 返回指标值。 */
        abstract int value(CompletionTracker tracker);
    }

    /** Folia 门禁目标实体计数跟踪器。 */
    private final class GuardCountTracker {
        private final CleanupFeature.CleanupStats stats;
        private final CleanupConfig cleanupConfig;
        private final CleanupConfig.FoliaCleanupConfig foliaConfig;
        private final List<Chunk> chunks;
        private final CleanupPolicy policy;
        private final CleanupTrigger trigger;
        private final boolean guardsIgnored;
        private final AtomicInteger pendingTasks = new AtomicInteger(1);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicInteger targetEntities = new AtomicInteger();
        private TaskHandle timeoutTask;

        /** 创建 Folia 门禁目标实体计数跟踪器。 */
        private GuardCountTracker(CleanupFeature.CleanupStats stats, CleanupConfig cleanupConfig,
                                  CleanupConfig.FoliaCleanupConfig foliaConfig, List<Chunk> chunks,
                                  CleanupPolicy policy, CleanupTrigger trigger, boolean guardsIgnored) {
            this.stats = stats;
            this.cleanupConfig = cleanupConfig;
            this.foliaConfig = foliaConfig;
            this.chunks = chunks;
            this.policy = policy;
            this.trigger = trigger;
            this.guardsIgnored = guardsIgnored;
        }

        /** 启动门禁计数超时保护。 */
        private void startTimeout() {
            try {
                timeoutTask = platform.scheduler().runLater(new Runnable() {
                    /** 超时后跳过本轮清理并释放运行状态。 */
                    @Override
                    public void run() {
                        plugin.getLogger().warning("[FoliaCleanup] 门禁目标实体计数超时，"
                                + "timeoutSeconds=" + foliaConfig.getTimeoutSeconds()
                                + ", targetEntities=" + targetEntities.get()
                                + ", pendingTasks=" + pendingTasks.get());
                        complete(true);
                    }
                }, foliaConfig.getTimeoutSeconds() * 20L);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaCleanup] 分派门禁计数超时保护失败: " + exception.getMessage());
            }
        }

        /** 判断门禁计数是否仍接受任务和统计。 */
        private boolean isOpen() {
            return !completed.get();
        }

        /** 记录新任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录任务完成。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0) {
                complete(false);
            }
        }

        /** 初始任务分派完成。 */
        private void initialSchedulingDone() {
            taskDone();
        }

        /** 增加一个会被扫地处理的目标实体。 */
        private void targetFound() {
            targetEntities.incrementAndGet();
        }

        /** 完成门禁计数。 */
        private void complete(boolean timedOut) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (!timedOut && timeoutTask != null) {
                timeoutTask.cancel();
            }
            finishGuardCount(this, timedOut);
        }
    }

    /** 异步清理完成跟踪器。 */
    private final class CompletionTracker {
        private final CleanupFeature.CleanupStats stats;
        private final CleanupConfig cleanupConfig;
        private final CleanupConfig.FoliaCleanupConfig foliaConfig;
        private final CleanupAuditSession auditSession;
        private final long startedAtMillis = System.currentTimeMillis();
        private final AtomicInteger pendingTasks = new AtomicInteger(1);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicInteger chunksSeen = new AtomicInteger();
        private final AtomicInteger chunksScheduled = new AtomicInteger();
        private final AtomicInteger chunksDone = new AtomicInteger();
        private final AtomicInteger chunksSkippedByLimit = new AtomicInteger();
        private final AtomicInteger chunksDispatchFailed = new AtomicInteger();
        private TaskHandle timeoutTask;

        /** 创建完成跟踪器。 */
        private CompletionTracker(CleanupFeature.CleanupStats stats, CleanupConfig cleanupConfig,
                                  CleanupConfig.FoliaCleanupConfig foliaConfig, CleanupAuditSession auditSession) {
            this.stats = stats;
            this.cleanupConfig = cleanupConfig;
            this.foliaConfig = foliaConfig;
            this.auditSession = auditSession;
        }

        /** 启动本轮清理超时保护。 */
        private void startTimeout() {
            try {
                timeoutTask = platform.scheduler().runLater(new Runnable() {
                    /** 超时后释放本轮清理状态。 */
                    @Override
                    public void run() {
                        plugin.getLogger().warning("[FoliaCleanup] 本轮 region-safe 清理超时，"
                                + "timeoutSeconds=" + foliaConfig.getTimeoutSeconds()
                                + ", chunksSeen=" + chunksSeen.get()
                                + ", chunksScheduled=" + chunksScheduled.get()
                                + ", chunksDone=" + chunksDone.get()
                                + ", pendingTasks=" + pendingTasks.get());
                        complete(true);
                    }
                }, foliaConfig.getTimeoutSeconds() * 20L);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaCleanup] 分派清理超时保护失败: " + exception.getMessage());
            }
        }

        /** 判断本轮清理是否仍接受任务和统计。 */
        private boolean isOpen() {
            return !completed.get();
        }

        /** 记录新任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录任务完成。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0) {
                complete(false);
            }
        }

        /** 初始任务分派完成。 */
        private void initialSchedulingDone() {
            taskDone();
        }

        /** 记录发现的已加载 chunk。 */
        private void chunkSeen() {
            chunksSeen.incrementAndGet();
        }

        /** 记录收集阶段已经完成的 chunk 指标。 */
        private void recordCollectedChunks(int seen, int skippedByLimit) {
            chunksSeen.addAndGet(Math.max(0, seen));
            chunksSkippedByLimit.addAndGet(Math.max(0, skippedByLimit));
        }

        /** 记录因单轮上限跳过的 chunk。 */
        private void chunkSkippedByLimit() {
            chunksSkippedByLimit.incrementAndGet();
        }

        /** 记录已派发的 chunk 扫描任务。 */
        private void chunkScheduled() {
            chunksScheduled.incrementAndGet();
        }

        /** 记录已完成的 chunk 扫描任务。 */
        private void chunkDone() {
            chunksDone.incrementAndGet();
        }

        /** 记录派发失败的 chunk 扫描任务。 */
        private void chunkDispatchFailed() {
            chunksDispatchFailed.incrementAndGet();
        }

        /** 返回本轮清理已耗时毫秒。 */
        private long elapsedMillis() {
            return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        }

        /** 返回本轮配置的超时时间。 */
        private int timeoutSeconds() {
            return foliaConfig.getTimeoutSeconds();
        }

        /** 在线程合法的物品处理位置记录审计物品。 */
        private void recordItem(ItemStack itemStack) {
            if (isOpen()) {
                auditSession.recordItem(itemStack);
            }
        }

        /** 在线程合法的物品处理位置记录审计物品和最终去向。 */
        private void recordItem(ItemStack itemStack, CleanupItemDestination destination) {
            if (isOpen()) {
                auditSession.recordItem(itemStack, destination);
            }
        }

        /** 完成本轮审计；空记录直接丢弃。 */
        private void finishAudit(boolean timedOut) {
            if (stats.getItemsHandled() <= 0) {
                auditSession.discard();
                return;
            }
            boolean partial = timedOut || chunksSkippedByLimit.get() > 0 || chunksDispatchFailed.get() > 0;
            auditSession.complete(new CleanupRunCompletion(System.currentTimeMillis(), partial));
        }

        /** 放弃本轮审计。 */
        private void discardAudit() {
            auditSession.discard();
        }

        /** 结束本轮清理。 */
        private void complete(boolean timedOut) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (!timedOut && timeoutTask != null) {
                timeoutTask.cancel();
            }
            finishCleanup(stats, this, timedOut);
        }
    }
}
