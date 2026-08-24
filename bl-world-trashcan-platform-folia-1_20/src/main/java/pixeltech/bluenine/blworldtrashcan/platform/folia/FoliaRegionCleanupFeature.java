package pixeltech.bluenine.blworldtrashcan.platform.folia;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupConsoleDetailFormatter;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupItemProtection;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemRuleEvaluator;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.stacking.ItemQuantityService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashCheck;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRoutingResult;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntitySnapshotRequirements;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;
import pixeltech.bluenine.blworldtrashcan.storage.TrashLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunContext;
import pixeltech.worldlisttrashcan.api.audit.CleanupTrigger;

/** Folia 专用 region-safe 清理实现。 */
public final class FoliaRegionCleanupFeature implements Feature, Listener {
    private static final String WORLD_TRASH_TRANSFER_METADATA = "worldlisttrashcan_world_trash_transfer";
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final WorldTrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
    private final PersonalTrashService personalTrashService;
    private final DropOwnerTracker dropOwnerTracker;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final ItemRuleEvaluator itemRuleEvaluator;
    private volatile ItemQuantityService itemQuantityService;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private final Set<UUID> pendingWorldTrashItems = ConcurrentHashMap.newKeySet();
    private boolean listenerRegistered;
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
        this(plugin, platform, configSupplier, trashRouter, globalTrashService, personalTrashService,
                dropOwnerTracker, auditBridge, null);
    }

    /** 创建可读取逻辑实际数量的 Folia region-safe 清理功能。 */
    public FoliaRegionCleanupFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                                     WorldTrashRouter trashRouter, GlobalTrashService globalTrashService,
                                     PersonalTrashService personalTrashService, DropOwnerTracker dropOwnerTracker,
                                     DefaultWorldListTrashCanAuditBridge auditBridge,
                                     ItemQuantityService itemQuantityService) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
        this.dropOwnerTracker = dropOwnerTracker;
        this.auditBridge = auditBridge;
        this.itemRuleEvaluator = new ItemRuleEvaluator(platform.itemSnapshotMapper());
        this.itemQuantityService = itemQuantityService;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "folia-cleanup";
    }

    /** 启动 Folia 倒计时清理任务。 */
    @Override
    public void enable() {
        if (!listenerRegistered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
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

    /** 阻止玩家拾取正处于跨 region 世界垃圾桶事务中的物品。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingPlayerPickup(EntityPickupItemEvent event) {
        if (pendingWorldTrashItems.contains(event.getItem().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 阻止漏斗拾取正处于跨 region 世界垃圾桶事务中的物品。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingInventoryPickup(InventoryPickupItemEvent event) {
        if (pendingWorldTrashItems.contains(event.getItem().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 阻止事务占用中的物品参与原版实体合并。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingMerge(ItemMergeEvent event) {
        if (pendingWorldTrashItems.contains(event.getEntity().getUniqueId())
                || pendingWorldTrashItems.contains(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 阻止事务占用中的物品在提交前自然消失。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDespawn(ItemDespawnEvent event) {
        if (pendingWorldTrashItems.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 阻止事务占用中的物品在提交前被火焰、岩浆或仙人掌销毁。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item
                && pendingWorldTrashItems.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
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

    /** 在可选堆叠功能动态启用后更新数量来源。 */
    public void setItemQuantityService(ItemQuantityService itemQuantityService) {
        this.itemQuantityService = itemQuantityService;
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
        CleanupConfig cleanupConfig = configSupplier.get().getCleanupConfig();
        logWorldFilterWarnings(cleanupConfig);
        int interval = cleanupConfig.getIntervalSeconds();
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

    /** 输出会导致世界过滤语义不直观的配置告警。 */
    private void logWorldFilterWarnings(CleanupConfig cleanupConfig) {
        if (!cleanupConfig.hasWorldIncludeRules()) {
            plugin.getLogger().warning("[FoliaCleanup] world-filter.include 没有有效规则，扫地不会扫描任何世界。");
        }
        if (cleanupConfig.isLegacyIgnoredWorldsIgnored()) {
            plugin.getLogger().warning("[FoliaCleanup] 已使用 world-filter，旧 ignored-worlds 节点不会生效。");
        }
        if (cleanupConfig.isLegacyItemProtectionConfigured()) {
            plugin.getLogger().warning("[FoliaCleanup] 已合并旧顶层 ignored-materials/name/lore；建议迁移到 custom-data-items 下。");
        }
        if (cleanupConfig.getSettings().getCustomItemRouting().isEnabled()
                && cleanupConfig.getSettings().getCustomItemRouting().getRules().requiresPdcKeys()
                && !itemRuleEvaluator.isPdcReady()) {
            plugin.getLogger().warning("[FoliaCleanup] custom-data-items.routing 需要 PDC，但当前运行时不可用: "
                    + itemRuleEvaluator.getPdcFailureReason());
        }
        if (cleanupConfig.getSettings().getCustomItemRouting().isEnabled()
                && cleanupConfig.getSettings().getCustomItemRouting().getRules().requiresNbtKeys()
                && !itemRuleEvaluator.isNbtReady()) {
            plugin.getLogger().warning("[FoliaCleanup] custom-data-items.routing 需要 Raw NBT，但当前运行时不可用: "
                    + itemRuleEvaluator.getNbtFailureReason());
        }
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
        if (!tracker.isDispatchOpen()) {
            tracker.initialSchedulingDone();
            return;
        }
        int endIndex = Math.min(chunks.size(), startIndex + foliaConfig.getChunkBatchSize());
        GuardCountBatch batch = new GuardCountBatch(tracker, endIndex);
        for (int index = startIndex; index < endIndex; index++) {
            scheduleGuardCount(chunks.get(index), policy, tracker, batch);
            if (!tracker.isDispatchOpen()) {
                break;
            }
        }
        batch.finishScheduling();
    }

    /** 安排单个 chunk 的门禁目标实体计数任务。 */
    private void scheduleGuardCount(final Chunk chunk, final CleanupPolicy policy, final GuardCountTracker tracker,
                                    final GuardCountBatch batch) {
        if (!tracker.isOpen()) {
            return;
        }
        tracker.taskStarted();
        batch.taskStarted();
        tracker.chunkScheduled();
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
                        tracker.chunkDone();
                        batch.taskDone();
                        tracker.taskDone();
                    }
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派门禁计数失败: "
                    + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                    + " - " + exception.getMessage());
            tracker.chunkDone();
            batch.taskDone();
            tracker.taskDone();
        }
    }

    /** 在当前门禁批次收口后继续派发下一批，避免一次性压入全部区块任务。 */
    private void continueGuardCountBatch(final List<Chunk> chunks, final GuardCountBatch batch,
                                         final CleanupPolicy policy, final CleanupConfig.FoliaCleanupConfig foliaConfig) {
        GuardCountTracker tracker = batch.tracker;
        if (!tracker.isOpen() || !tracker.isDispatchOpen() || batch.nextIndex >= chunks.size()) {
            tracker.initialSchedulingDone();
            return;
        }
        Runnable continuation = new Runnable() {
            /** 继续派发下一批门禁计数任务。 */
            @Override
            public void run() {
                scheduleGuardCountBatch(chunks, batch.nextIndex, policy, tracker, foliaConfig);
            }
        };
        try {
            if (foliaConfig.getChunkBatchDelayTicks() <= 0) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, continuation);
            } else {
                platform.scheduler().runLater(continuation, foliaConfig.getChunkBatchDelayTicks());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分批派发门禁计数失败: " + exception.getMessage());
            tracker.initialSchedulingDone();
        }
    }

    /** 在当前 region 内统计会被扫地处理的实体。 */
    private void countChunkTargets(Chunk chunk, CleanupPolicy policy, GuardCountTracker tracker) {
        Entity[] entities = chunk.getEntities();
        tracker.entitiesChecked.addAndGet(entities.length);
        for (Entity entity : entities) {
            if (!tracker.isAcceptingTargets()) {
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
        plugin.getLogger().info("[FoliaCleanup] guardScan="
                + "chunksScheduled=" + tracker.chunksScheduled.get()
                + ", chunksDone=" + tracker.chunksDone.get()
                + ", entitiesChecked=" + tracker.entitiesChecked.get()
                + ", targetEntities=" + tracker.targetEntities.get()
                + ", stoppedEarly=" + tracker.stopDispatch.get()
                + ", timedOut=" + timedOut);
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
        if (foliaConfig.getChunkBatchDelayTicks() <= 0) {
            int nextIndex = startIndex;
            while (nextIndex < chunks.size() && tracker.isOpen()) {
                int batchEndIndex = Math.min(chunks.size(), nextIndex + foliaConfig.getChunkBatchSize());
                for (int index = nextIndex; index < batchEndIndex; index++) {
                    scheduleChunkScan(chunks.get(index), policy, stats, tracker);
                }
                nextIndex = batchEndIndex;
            }
            tracker.initialSchedulingDone();
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
        EntitySnapshotRequirements requirements = cleanupConfig.getSettings().getEntitySnapshotRequirements();
        EntityCleanupDecision decision = policy.decideEntity(
                platform.entitySnapshotMapper().toSnapshot(entity, requirements));
        return decision.getAction() == EntityCleanupAction.REMOVE;
    }

    /** 判断掉落物是否会被本轮扫地路由或删除。 */
    private boolean isCleanableItemTarget(Item item, CleanupConfig cleanupConfig, CleanupPolicy policy) {
        if (isMovingItemProtected(item, cleanupConfig)) {
            return false;
        }
        if (CleanupItemProtection.isFilledShulkerItem(item.getItemStack(), cleanupConfig)) {
            return false;
        }
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            return false;
        }
        ItemSnapshot snapshot = snapshotWithRoutingMetadata(item,
                snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item)), cleanupConfig);
        RouteState state = initialRouteState(item.getWorld(), snapshot, itemStack, cleanupConfig);
        TrashRoutingDecision decision = policy.decideItem(snapshot, state.worldAvailable,
                state.personalAvailable, state.globalAvailable, state.forceDirectRemove);
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
        int actualAmount = actualAmount(item);
        if (isMovingItemProtected(item, tracker.cleanupConfig)) {
            stats.addItemsSkipped(actualAmount);
            return;
        }
        if (CleanupItemProtection.isFilledShulkerItem(itemStack, tracker.cleanupConfig)) {
            stats.addItemsSkipped(actualAmount);
            return;
        }
        ItemStack routedStack = itemStack.clone();
        ItemSnapshot snapshot = snapshotWithRoutingMetadata(item,
                snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item)
                        .withAmount(actualAmount)), tracker.cleanupConfig);
        RouteState state = initialRouteState(item.getWorld(), snapshot, routedStack, tracker.cleanupConfig);
        routeWithFallback(item, routedStack, actualAmount, snapshot, policy, state, stats, tracker);
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
            return new RouteState(false, false, false, true);
        }
        UUID ownerUuid = snapshot == null ? null : snapshot.getOwnerUuid();
        synchronized (trashRouter) {
            return new RouteState(
                    trashRouter.hasWorldTrash(world, itemStack),
                    trashRouter.hasPersonalTrash(ownerUuid, itemStack),
                    snapshot != null && snapshot.isGlobalTrashAvailabilityEvaluated()
                            ? snapshot.isGlobalTrashAvailable()
                            : trashRouter.hasGlobalTrash(itemStack),
                    false
            );
        }
    }

    /** 按核心策略逐级路由或删除物品。 */
    private void routeWithFallback(Item item, ItemStack itemStack, int actualAmount,
                                   ItemSnapshot snapshot, CleanupPolicy policy,
                                   RouteState state, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        TrashRoutingDecision decision = policy.decideItem(snapshot, state.worldAvailable,
                state.personalAvailable, state.globalAvailable, state.forceDirectRemove);
        while (true) {
            if (!tracker.isOpen()) {
                return;
            }
            TrashRoute route = decision.getRoute();
            if (route == TrashRoute.SKIP) {
                stats.addItemsSkipped(actualAmount);
                return;
            }
            if (route == TrashRoute.REMOVE) {
                forgetTrackedOwner(item);
                item.remove();
                recordItemAmount(tracker, itemStack, actualAmount,
                        CleanupItemDestination.directRemove(), "");
                stats.addItemsRemoved(actualAmount);
                return;
            }
            if (route == TrashRoute.WORLD_TRASH) {
                List<TrashLocation> locations = worldTrashLocations(item.getWorld(), itemStack);
                if (locations.isEmpty()) {
                    state.worldAvailable = false;
                    decision = policy.decideItem(snapshot, state.worldAvailable,
                            state.personalAvailable, state.globalAvailable, state.forceDirectRemove);
                    continue;
                }
                if (!beginWorldTrashTransfer(item)) {
                    stats.addItemsSkipped(actualAmount);
                    return;
                }
                WorldTrashTransfer transfer = new WorldTrashTransfer(item.getLocation().clone());
                tryWorldTrash(item, itemStack, actualAmount, snapshot, policy, state,
                        stats, tracker, locations, 0, transfer);
                return;
            }
            TrashRoutingResult virtualResult = routeVirtual(
                    item, itemStack, actualAmount, snapshot.getOwnerUuid(), route);
            if (virtualResult.isSuccess()) {
                int acceptedAmount = Math.min(actualAmount, virtualResult.getAcceptedAmount());
                if (acceptedAmount <= 0) {
                    stats.addItemsSkipped(actualAmount);
                    return;
                }
                if (!setRemainingAmount(item, actualAmount, actualAmount - acceptedAmount)) {
                    int rolledBack;
                    synchronized (trashRouter) {
                        rolledBack = trashRouter.rollbackRouted(virtualResult, itemStack, acceptedAmount);
                    }
                    plugin.getLogger().severe("[FoliaCleanup] 地面数量提交失败，已回滚虚拟垃圾桶写入: route="
                            + route + ", accepted=" + acceptedAmount + ", rolledBack=" + rolledBack);
                    return;
                }
                recordItemAmount(tracker, itemStack, acceptedAmount,
                        virtualResult.getDestination(), virtualResult.getTrackingKey());
                stats.addItemsRouted(acceptedAmount, route);
                if (route == TrashRoute.PERSONAL_TRASH) {
                    addPersonalTrashAmount(stats, snapshot.getOwnerUuid(), itemStack, acceptedAmount);
                }
                if (acceptedAmount < actualAmount) {
                    plugin.getLogger().info("[FoliaCleanup] 公共垃圾桶达到紧凑模式单条目上限，保留掉落物剩余数量: accepted="
                            + acceptedAmount + ", remaining=" + (actualAmount - acceptedAmount));
                    return;
                }
                forgetTrackedOwner(item);
                return;
            }
            state.markUnavailable(route);
            decision = policy.decideItem(snapshot, state.worldAvailable,
                    state.personalAvailable, state.globalAvailable, state.forceDirectRemove);
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
    private void tryWorldTrash(final Item item, final ItemStack itemStack, final int actualAmount,
                               final ItemSnapshot snapshot,
                               final CleanupPolicy policy, final RouteState state,
                               final CleanupFeature.CleanupStats stats, final CompletionTracker tracker,
                               final List<TrashLocation> locations, final int index,
                               final WorldTrashTransfer transfer) {
        if (!tracker.isOpen()) {
            returnWorldTrashItem(item, transfer, tracker, null);
            return;
        }
        if (index >= locations.size()) {
            returnForWorldTrashFallback(item, itemStack, actualAmount, snapshot,
                    policy, state, stats, tracker, transfer);
            return;
        }
        final TrashLocation location = locations.get(index);
        final World world = Bukkit.getWorld(location.getWorldName());
        if (world == null || !world.isChunkLoaded(location.getX() >> 4, location.getZ() >> 4)) {
            tryWorldTrash(item, itemStack, actualAmount, snapshot, policy, state,
                    stats, tracker, locations, index + 1, transfer);
            return;
        }
        tracker.taskStarted();
        try {
            Location target = new Location(world, location.getX() + 0.5D,
                    location.getY() + 1.0D, location.getZ() + 0.5D);
            item.teleportAsync(target).whenComplete(new BiConsumer<Boolean, Throwable>() {
                /** 传送完成后在物品的新 region 内提交箱子和实体数量。 */
                @Override
                public void accept(Boolean teleported, Throwable error) {
                    if (error != null || !Boolean.TRUE.equals(teleported)) {
                        plugin.getLogger().warning("[FoliaCleanup] 物品转移到世界垃圾桶 region 失败: "
                                + location.getWorldName() + "," + location.getX() + ","
                                + location.getY() + "," + location.getZ()
                                + (error == null ? "" : " - " + error.getMessage()));
                    }
                    scheduleWorldTrashStep(item, tracker, new Runnable() {
                        /** 在物品当前 region 继续提交或尝试下一个世界垃圾桶。 */
                        @Override
                        public void run() {
                            if (error == null && Boolean.TRUE.equals(teleported)) {
                                commitWorldTrashTransfer(item, itemStack, actualAmount, snapshot,
                                        policy, state, stats, tracker, locations, index,
                                        location, transfer);
                            } else {
                                tryWorldTrash(item, itemStack, actualAmount, snapshot, policy, state,
                                        stats, tracker, locations, index + 1, transfer);
                            }
                        }
                    }, "目标 region 提交任务未能执行");
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派世界垃圾桶传送失败: "
                    + location.getWorldName() + "," + location.getX() + "," + location.getY() + "," + location.getZ()
                    + " - " + exception.getMessage());
            tryWorldTrash(item, itemStack, actualAmount, snapshot, policy, state,
                    stats, tracker, locations, index + 1, transfer);
            tracker.taskDone();
        }
    }

    /** 在目标 region 内写入容器并立即提交同 region 的实体数量。 */
    private void commitWorldTrashTransfer(final Item item, final ItemStack itemStack,
                                          final int actualAmount, final ItemSnapshot snapshot,
                                          final CleanupPolicy policy, final RouteState state,
                                          final CleanupFeature.CleanupStats stats,
                                           final CompletionTracker tracker,
                                           final List<TrashLocation> locations, final int index,
                                           final TrashLocation location, final WorldTrashTransfer transfer) {
        if (!tracker.isOpen()) {
            returnWorldTrashItem(item, transfer, tracker, null);
            return;
        }
        int accepted = trashRouter.routeWorldTrashAtAmount(location, itemStack.clone(), actualAmount);
        if (accepted <= 0) {
            tryWorldTrash(item, itemStack, actualAmount, snapshot, policy, state,
                    stats, tracker, locations, index + 1, transfer);
            return;
        }
        if (!setRemainingAmount(item, actualAmount, actualAmount - accepted)) {
            int rolledBack = trashRouter.rollbackWorldTrashAtAmount(location, itemStack, accepted);
            plugin.getLogger().severe("[FoliaCleanup] 世界垃圾桶事务提交失败，已回滚容器: entity="
                    + item.getUniqueId() + ", expected=" + actualAmount + ", accepted=" + accepted
                    + ", rolledBack=" + rolledBack);
            returnWorldTrashItem(item, transfer, tracker, null);
            return;
        }
        if (tracker.isOpen()) {
            stats.addItemsRouted(accepted, TrashRoute.WORLD_TRASH);
            recordItemAmount(tracker, itemStack, accepted, trashRouter.destination(location), "");
        }
        if (accepted >= actualAmount) {
            forgetTrackedOwner(item);
            finishWorldTrashTransfer(item);
            return;
        }
        returnWorldTrashItem(item, transfer, tracker, null);
    }

    /** 世界垃圾桶全部不可用时先返回原位置，再执行个人、公共或删除降级。 */
    private void returnForWorldTrashFallback(final Item item, final ItemStack itemStack,
                                             final int actualAmount, final ItemSnapshot snapshot,
                                             final CleanupPolicy policy, final RouteState state,
                                             final CleanupFeature.CleanupStats stats,
                                             final CompletionTracker tracker,
                                             final WorldTrashTransfer transfer) {
        state.worldAvailable = false;
        returnWorldTrashItem(item, transfer, tracker, new Runnable() {
            /** 回到原 region 后继续原有降级决策。 */
            @Override
            public void run() {
                routeWithFallback(item, itemStack, actualAmount,
                        snapshot, policy, state, stats, tracker);
            }
        });
    }

    /** 把仍存在的物品实体传回扫地前的位置并执行后续动作。 */
    private void returnWorldTrashItem(final Item item, final WorldTrashTransfer transfer,
                                      final CompletionTracker tracker, final Runnable afterReturn) {
        if (!item.isValid() || item.isDead()) {
            finishWorldTrashTransfer(item);
            return;
        }
        tracker.taskStarted();
        try {
            item.teleportAsync(transfer.origin).whenComplete(new BiConsumer<Boolean, Throwable>() {
                /** 返回原位置后在实体 region 释放事务标记。 */
                @Override
                public void accept(final Boolean returned, final Throwable error) {
                    scheduleWorldTrashStep(item, tracker, new Runnable() {
                        /** 释放占用，并仅在成功返回时继续降级路由。 */
                        @Override
                        public void run() {
                            finishWorldTrashTransfer(item);
                            if (error == null && Boolean.TRUE.equals(returned) && afterReturn != null) {
                                afterReturn.run();
                            } else if (error != null || !Boolean.TRUE.equals(returned)) {
                                plugin.getLogger().warning("[FoliaCleanup] 世界垃圾桶事务物品返回原位置失败: entity="
                                        + item.getUniqueId() + (error == null ? "" : " - " + error.getMessage()));
                            }
                        }
                    }, "返回原位置后的实体任务未能执行");
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派物品返回原位置失败: " + exception.getMessage());
            finishWorldTrashTransfer(item);
            tracker.taskDone();
        }
    }

    /** 在物品所属 region 安排事务步骤，并保证完成计数只释放一次。 */
    private void scheduleWorldTrashStep(final Item item, final CompletionTracker tracker,
                                        final Runnable action, final String retiredReason) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable retired = new Runnable() {
            /** 实体已卸载时释放事务和完成计数。 */
            @Override
            public void run() {
                if (finished.compareAndSet(false, true)) {
                    finishWorldTrashTransfer(item);
                    plugin.getLogger().warning("[FoliaCleanup] " + retiredReason + ": entity=" + item.getUniqueId());
                    tracker.taskDone();
                }
            }
        };
        boolean scheduled;
        try {
            scheduled = item.getScheduler().execute(plugin, new Runnable() {
                /** 在实体合法 region 执行事务动作。 */
                @Override
                public void run() {
                    try {
                        action.run();
                    } finally {
                        if (finished.compareAndSet(false, true)) {
                            tracker.taskDone();
                        }
                    }
                }
            }, retired, 1L);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[FoliaCleanup] 分派世界垃圾桶事务实体任务失败: " + exception.getMessage());
            scheduled = false;
        }
        if (!scheduled) {
            retired.run();
        }
    }

    /** 标记物品进入短生命周期的跨 region 世界垃圾桶事务。 */
    private boolean beginWorldTrashTransfer(Item item) {
        if (item == null || !pendingWorldTrashItems.add(item.getUniqueId())) {
            return false;
        }
        item.setMetadata(WORLD_TRASH_TRANSFER_METADATA, new FixedMetadataValue(plugin, Boolean.TRUE));
        return true;
    }

    /** 释放跨 region 世界垃圾桶事务占用。 */
    private void finishWorldTrashTransfer(Item item) {
        if (item == null) {
            return;
        }
        pendingWorldTrashItems.remove(item.getUniqueId());
        if (item.isValid() && !item.isDead()) {
            item.removeMetadata(WORLD_TRASH_TRANSFER_METADATA, plugin);
        }
    }

    /** 路由到个人或公共虚拟垃圾桶。 */
    private TrashRoutingResult routeVirtual(Item item, ItemStack itemStack, int actualAmount,
                                            UUID ownerUuid, TrashRoute route) {
        synchronized (trashRouter) {
            return trashRouter.routeDetailedAmount(
                    item.getWorld(), ownerUuid, itemStack.clone(), actualAmount, route, true);
        }
    }

    /** 返回掉落物实际数量；未启用数量提供者时保持原始路径。 */
    private int actualAmount(Item item) {
        if (item == null || item.getItemStack() == null) {
            return 0;
        }
        return itemQuantityService == null
                ? item.getItemStack().getAmount() : itemQuantityService.getAmount(item);
    }

    /** 按预期数量写入剩余；默认路径直接修改物理堆叠。 */
    private boolean setRemainingAmount(Item item, int expectedAmount, int remainingAmount) {
        if (itemQuantityService != null) {
            return itemQuantityService.setRemaining(item, expectedAmount, remainingAmount);
        }
        if (item == null || item.getItemStack() == null
                || item.getItemStack().getAmount() != expectedAmount || remainingAmount < 0) {
            return false;
        }
        if (remainingAmount == 0) {
            item.remove();
            return true;
        }
        ItemStack remaining = item.getItemStack();
        remaining.setAmount(remainingAmount);
        item.setItemStack(remaining);
        return true;
    }

    /** 按原版堆叠上限拆分并记录审计物品。 */
    private void recordItemAmount(final CompletionTracker tracker, ItemStack sample, int amount,
                                  final CleanupItemDestination destination, final String trackingKey) {
        forEachLegalStack(sample, amount, new ItemQuantityService.ItemStackConsumer() {
            /** 记录一个合法数量的物品快照。 */
            @Override
            public void accept(ItemStack itemStack) {
                tracker.recordItem(itemStack, destination, trackingKey);
            }
        });
    }

    /** 按原版堆叠上限拆分个人垃圾桶通知物品。 */
    private void addPersonalTrashAmount(final CleanupFeature.CleanupStats stats, final UUID ownerUuid,
                                        ItemStack sample, int amount) {
        forEachLegalStack(sample, amount, new ItemQuantityService.ItemStackConsumer() {
            /** 记录一个合法数量的个人通知快照。 */
            @Override
            public void accept(ItemStack itemStack) {
                stats.addPersonalTrashItem(ownerUuid, itemStack);
            }
        });
    }

    /** 使用数量服务或本地循环拆分合法物品堆叠。 */
    private void forEachLegalStack(ItemStack sample, int amount, ItemQuantityService.ItemStackConsumer consumer) {
        if (sample == null || amount <= 0 || consumer == null) {
            return;
        }
        if (itemQuantityService != null) {
            itemQuantityService.forEachStack(sample, amount, consumer);
            return;
        }
        int max = Math.max(1, sample.getMaxStackSize());
        int remaining = amount;
        while (remaining > 0) {
            ItemStack copy = sample.clone();
            int moved = Math.min(max, remaining);
            copy.setAmount(moved);
            consumer.accept(copy);
            remaining -= moved;
        }
    }

    /** 使用短期 owner 记录补齐不支持 PDC 平台上的物品归属。 */
    private ItemSnapshot snapshotWithTrackedOwner(Item item, ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.getOwnerUuid() != null || dropOwnerTracker == null) {
            return snapshot;
        }
        return snapshot.withOwnerUuid(dropOwnerTracker.findOwner(item));
    }

    /** 为物品快照补充新自定义路由和公共桶白名单拒绝结果。 */
    private ItemSnapshot snapshotWithRoutingMetadata(Item item, ItemSnapshot snapshot,
                                                     CleanupConfig cleanupConfig) {
        if (snapshot == null || item == null) {
            return snapshot;
        }
        if (isIgnoredSnapshot(snapshot, cleanupConfig.getSettings())) {
            return snapshot.withRoutingMetadata(false, true, false, null);
        }
        ItemStack itemStack = item.getItemStack();
        boolean directRemoveWorld = cleanupConfig.isDirectRemoveWorld(item.getWorld().getName());
        boolean customMatched = !directRemoveWorld
                && cleanupConfig.getSettings().getCustomItemRouting().isEnabled()
                && itemRuleEvaluator.matches(
                cleanupConfig.getSettings().getCustomItemRouting().getRules(), snapshot, itemStack);
        GlobalTrashCheck globalCheck;
        synchronized (trashRouter) {
            globalCheck = directRemoveWorld || customMatched
                    ? new GlobalTrashCheck(false, null)
                    : trashRouter.checkGlobalTrash(itemStack);
        }
        return snapshot.withRoutingMetadata(customMatched, true, globalCheck.isAvailable(),
                globalCheck.getRejectedCleanupAction());
    }

    /** 判断轻量快照是否已命中最高优先级的绝对保护。 */
    private boolean isIgnoredSnapshot(ItemSnapshot snapshot, CleanupSettings settings) {
        return settings.isIgnoredMaterial(snapshot.getMaterialKey())
                || settings.matchesIgnoredName(snapshot.getDisplayName())
                || settings.matchesIgnoredLore(snapshot.getLore());
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
        private final boolean forceDirectRemove;

        /** 创建路由状态。 */
        private RouteState(boolean worldAvailable, boolean personalAvailable, boolean globalAvailable,
                           boolean forceDirectRemove) {
            this.worldAvailable = worldAvailable;
            this.personalAvailable = personalAvailable;
            this.globalAvailable = globalAvailable;
            this.forceDirectRemove = forceDirectRemove;
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

    /** 单次世界垃圾桶转移需要保留的原始位置。 */
    private static final class WorldTrashTransfer {
        private final Location origin;

        /** 保存扫地开始时的掉落物位置。 */
        private WorldTrashTransfer(Location origin) {
            this.origin = origin;
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

    /** Folia 门禁单批次的任务收口状态。 */
    private final class GuardCountBatch {
        private final GuardCountTracker tracker;
        private final int nextIndex;
        private final AtomicInteger pendingTasks = new AtomicInteger(1);
        private final AtomicBoolean schedulingDone = new AtomicBoolean(false);

        /** 创建一个门禁计数批次。 */
        private GuardCountBatch(GuardCountTracker tracker, int nextIndex) {
            this.tracker = tracker;
            this.nextIndex = nextIndex;
        }

        /** 记录批次内新增的区块任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录批次内一个区块任务已经收口。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0) {
                continueGuardCountBatch(tracker.chunks, this, tracker.policy, tracker.foliaConfig);
            }
        }

        /** 标记批次已经完成派发，并在无任务时立即收口。 */
        private void finishScheduling() {
            if (schedulingDone.compareAndSet(false, true)) {
                taskDone();
            }
        }
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
        private final AtomicBoolean stopDispatch = new AtomicBoolean(false);
        private final AtomicBoolean schedulingDone = new AtomicBoolean(false);
        private final AtomicInteger targetEntities = new AtomicInteger();
        private final AtomicInteger chunksScheduled = new AtomicInteger();
        private final AtomicInteger chunksDone = new AtomicInteger();
        private final AtomicInteger entitiesChecked = new AtomicInteger();
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

        /** 判断门禁是否仍允许派发新的区块任务。 */
        private boolean isDispatchOpen() {
            return isOpen() && !stopDispatch.get();
        }

        /** 判断当前区块是否仍允许继续统计目标实体。 */
        private boolean isAcceptingTargets() {
            return isOpen() && !stopDispatch.get();
        }

        /** 记录新任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录已派发的门禁区块任务。 */
        private void chunkScheduled() {
            chunksScheduled.incrementAndGet();
        }

        /** 记录已收口的门禁区块任务。 */
        private void chunkDone() {
            chunksDone.incrementAndGet();
        }

        /** 记录任务完成。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0) {
                complete(false);
            }
        }

        /** 初始任务分派完成。 */
        private void initialSchedulingDone() {
            if (schedulingDone.compareAndSet(false, true)) {
                taskDone();
            }
        }

        /** 增加一个会被扫地处理的目标实体。 */
        private void targetFound() {
            int target = targetEntities.incrementAndGet();
            if (target >= stats.getGuardMinTotalEntities()) {
                stopDispatch.set(true);
            }
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

        /** 在线程合法的物品处理位置记录审计物品、最终去向和存储条目。 */
        private void recordItem(ItemStack itemStack, CleanupItemDestination destination, String trackingKey) {
            if (isOpen()) {
                auditSession.recordItem(itemStack, destination, trackingKey);
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
