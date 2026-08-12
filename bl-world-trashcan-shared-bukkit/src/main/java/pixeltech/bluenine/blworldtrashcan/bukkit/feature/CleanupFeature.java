package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
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
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemRuleEvaluator;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashCheck;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRouter;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRoutingResult;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunContext;
import pixeltech.worldlisttrashcan.api.audit.CleanupTrigger;

/** 后台清理功能模块，清理决策交给 core，Bukkit 层只执行结果。 */
public final class CleanupFeature implements Feature {
    public static final String GUARD_REASON_ONLINE_PLAYERS = "online-players";
    public static final String GUARD_REASON_TARGET_ENTITIES = "target-entities";
    private static final int MAX_DEFERRED_GUARD_TARGETS = 4096;

    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final TrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
    private final PersonalTrashService personalTrashService;
    private final DropOwnerTracker dropOwnerTracker;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final ItemRuleEvaluator itemRuleEvaluator;
    private TaskHandle taskHandle;
    private TaskHandle bossBarRemoveTask;
    private BossBar bossBar;
    private CleanupStats lastStats = CleanupStats.empty();
    private long nextRunAtMillis;
    private int cleanupRunsSinceGlobalClear;
    private int countdownSeconds;

    /** 创建后台清理功能。 */
    public CleanupFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                          TrashRouter trashRouter, GlobalTrashService globalTrashService,
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
        this.itemRuleEvaluator = new ItemRuleEvaluator(platform.itemSnapshotMapper());
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "cleanup";
    }

    /** 启用后台清理任务。 */
    @Override
    public void enable() {
        startTask();
    }

    /** 重载后台清理任务。 */
    @Override
    public void reload() {
        disable();
        startTask();
    }

    /** 停止后台清理任务。 */
    @Override
    public void disable() {
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
        cancelBossBarRemoval();
        removeBossBar();
        countdownSeconds = 0;
    }

    /** 立即执行一次清理，默认遵守定时扫地门禁。 */
    public CleanupStats runNow() {
        return runNow(false, CleanupTrigger.SCHEDULED);
    }

    /** 立即执行一次清理，可由命令入口决定是否忽略 guards。 */
    public CleanupStats runNow(boolean ignoreGuards) {
        return runNow(ignoreGuards, CleanupTrigger.MANUAL);
    }

    /** 执行一次带明确触发来源的清理。 */
    private CleanupStats runNow(boolean ignoreGuards, CleanupTrigger trigger) {
        ConfigBundle bundle = configSupplier.get();
        if (!isWorldScanSupported()) {
            CleanupStats stats = new CleanupStats();
            handleGlobalTrashRefresh(bundle, stats);
            lastStats = stats;
            plugin.getLogger().warning("[Cleanup] 当前平台未启用 region-safe 清理，已跳过世界实体扫描。");
            return stats;
        }
        CleanupPolicy policy = new DefaultCleanupPolicy(bundle.getCleanupSettings());
        CleanupStats stats = new CleanupStats();
        CleanupConfig cleanupConfig = bundle.getCleanupConfig();
        CleanupConfig.CleanupGuardConfig guardConfig = cleanupConfig.getGuardConfig();
        stats.recordGuardState(Bukkit.getOnlinePlayers().size(), guardConfig.getMinOnlinePlayers(),
                -1, guardConfig.getMinTotalEntities());
        if (!ignoreGuards && stats.getGuardOnlinePlayers() < stats.getGuardMinOnlinePlayers()) {
            stats.markGuardSkipped(GUARD_REASON_ONLINE_PLAYERS);
            lastStats = stats;
            logCleanupGuardSkipped(stats);
            return stats;
        }
        CleanupAuditSession auditSession = auditBridge.beginRun(new CleanupRunContext(
                UUID.randomUUID(), System.currentTimeMillis(), trigger, ignoreGuards));
        boolean auditFinalized = false;
        try {
        if (!ignoreGuards && guardConfig.getMinTotalEntities() > 0) {
            cleanWithEntityGuard(bundle, cleanupConfig, policy, stats, auditSession);
            if (stats.isGuardSkipped()) {
                lastStats = stats;
                logCleanupGuardSkipped(stats);
                auditSession.discard();
                auditFinalized = true;
                return stats;
            }
        } else {
            stats.setGuardTargetEntities(0);
            handleGlobalTrashRefresh(bundle, stats);
            for (World world : Bukkit.getWorlds()) {
                if (cleanupConfig.isIgnoredWorld(world.getName())) {
                    continue;
                }
                cleanWorld(world, cleanupConfig, policy, stats, auditSession);
            }
        }
        sendPersonalTrashBatchNotify(stats);
        lastStats = stats;
        plugin.getLogger().info("[Cleanup] worlds=" + stats.worlds
                + ", skippedByGuard=" + stats.isGuardSkipped()
                + ", guardReason=" + stats.getGuardSkipReason()
                + ", onlinePlayers=" + stats.getGuardOnlinePlayers()
                + ", minOnlinePlayers=" + stats.getGuardMinOnlinePlayers()
                + ", targetEntities=" + stats.getGuardTargetEntities()
                + ", minTotalEntities=" + stats.getGuardMinTotalEntities()
                + ", itemsRouted=" + stats.itemsRouted
                + ", itemsRemoved=" + stats.itemsRemoved
                + ", itemsSkipped=" + stats.itemsSkipped
                + ", entitiesRemoved=" + stats.entitiesRemoved
                + ", entitiesSkipped=" + stats.entitiesSkipped
                + ", worldTrashSkippedUnloadedChunks=" + trashRouter.getSkippedUnloadedChunkAccesses()
                + ", globalTrashRefreshed=" + stats.globalTrashRefreshed);
        logConsoleCleanupDetails(stats, false);
        finishAudit(auditSession, stats, false);
        auditFinalized = true;
        return stats;
        } finally {
            if (!auditFinalized) {
                auditSession.discard();
            }
        }
    }

    /** 返回最近一次清理统计。 */
    public CleanupStats getLastStats() {
        return lastStats;
    }

    /** 测试用：按正式通知配置直接触发指定编号的清理通知。 */
    public boolean debugNotify(int count) {
        sendNotify(count, lastStats);
        if (count == 0 || count == -4) {
            logConsoleCleanupDetails(lastStats, count == -4);
        }
        plugin.getLogger().info("[Debug] debugNotify count=" + count);
        return true;
    }

    /** 返回距离下次自动清理的秒数。 */
    public long getRemainingSeconds() {
        if (nextRunAtMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, (nextRunAtMillis - System.currentTimeMillis()) / 1000L);
    }

    /** 判断当前平台是否允许同步扫描世界实体。 */
    public boolean isWorldScanSupported() {
        return !isFoliaWithoutRegionSafe();
    }

    /** 按配置启动定时任务。 */
    private void startTask() {
        CleanupConfig cleanupConfig = configSupplier.get().getCleanupConfig();
        logWorldFilterWarnings(cleanupConfig);
        int interval = cleanupConfig.getIntervalSeconds();
        if (interval <= 0) {
            plugin.getLogger().info("[Cleanup] 定时清理已关闭，仅允许手动触发。");
            return;
        }
        if (!isWorldScanSupported()) {
            nextRunAtMillis = 0L;
            plugin.getLogger().warning("[Cleanup] 当前 Folia 产物尚未启用 region-safe 清理，定时世界扫描已关闭。");
            return;
        }
        long ticks = Math.max(20L, interval * 20L);
        countdownSeconds = interval;
        nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
        taskHandle = platform.scheduler().runRepeating(new Runnable() {
            /** 执行倒计时或定时清理。 */
            @Override
            public void run() {
                tickCountdown();
            }
        }, 20L, 20L);
        plugin.getLogger().info("[Cleanup] 定时清理已启动，间隔 " + interval + " 秒。");
    }

    /** 输出世界过滤、旧物品保护路径和数据读取能力告警。 */
    private void logWorldFilterWarnings(CleanupConfig cleanupConfig) {
        if (!cleanupConfig.hasWorldIncludeRules()) {
            plugin.getLogger().warning("[Cleanup] world-filter.include 没有有效规则，扫地不会扫描任何世界。");
        }
        if (cleanupConfig.isLegacyIgnoredWorldsIgnored()) {
            plugin.getLogger().warning("[Cleanup] 已使用 world-filter，旧 ignored-worlds 节点不会生效。");
        }
        if (cleanupConfig.isLegacyItemProtectionConfigured()) {
            plugin.getLogger().warning("[Cleanup] 已合并旧顶层 ignored-materials/name/lore；建议迁移到 custom-data-items 下。");
        }
        if (cleanupConfig.getSettings().getCustomItemRouting().isEnabled()
                && cleanupConfig.getSettings().getCustomItemRouting().getRules().requiresPdcKeys()
                && !itemRuleEvaluator.isPdcReady()) {
            plugin.getLogger().warning("[Cleanup] custom-data-items.routing 需要 PDC，但当前运行时不可用: "
                    + itemRuleEvaluator.getPdcFailureReason());
        }
        if (cleanupConfig.getSettings().getCustomItemRouting().isEnabled()
                && cleanupConfig.getSettings().getCustomItemRouting().getRules().requiresNbtKeys()
                && !itemRuleEvaluator.isNbtReady()) {
            plugin.getLogger().warning("[Cleanup] custom-data-items.routing 需要 Raw NBT，但当前运行时不可用: "
                    + itemRuleEvaluator.getNbtFailureReason());
        }
    }

    /** 判断是否是尚未声明 region-safe 的 Folia 产物。 */
    private boolean isFoliaWithoutRegionSafe() {
        return platform.id().toLowerCase(Locale.ROOT).startsWith("folia")
                && !platform.capabilities().has(Capability.FOLIA_REGION_SAFE);
    }

    /** 推进一秒倒计时。 */
    private void tickCountdown() {
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        if (countdownSeconds <= 0) {
            CleanupStats stats = runNow();
            if (!stats.isGuardSkipped()) {
                sendNotify(0, stats);
                sendNotify(globalTrashStatusNotifyCount(stats), stats);
            }
            countdownSeconds = interval;
            nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
            return;
        }
        sendNotify(countdownSeconds, CleanupStats.empty());
        countdownSeconds--;
        nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
    }

    /** 清理单个世界。 */
    private void cleanWorld(World world, CleanupConfig cleanupConfig, CleanupPolicy policy, CleanupStats stats,
                            CleanupAuditSession auditSession) {
        stats.worlds++;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof Item) {
                cleanItem((Item) entity, cleanupConfig, policy, stats, auditSession);
                continue;
            }
            cleanEntity(entity, policy, stats);
        }
    }

    /** 按扫地门禁统计目标实体，达到阈值后再实际清理。 */
    private void cleanWithEntityGuard(ConfigBundle bundle, CleanupConfig cleanupConfig, CleanupPolicy policy,
                                      CleanupStats stats, CleanupAuditSession auditSession) {
        int minTotalEntities = cleanupConfig.getGuardConfig().getMinTotalEntities();
        if (minTotalEntities > MAX_DEFERRED_GUARD_TARGETS) {
            CountResult result = countCleanableTargets(cleanupConfig, policy);
            stats.setGuardTargetEntities(result.targetEntities);
            if (result.targetEntities < minTotalEntities) {
                stats.worlds = result.worlds;
                stats.markGuardSkipped(GUARD_REASON_TARGET_ENTITIES);
                return;
            }
            handleGlobalTrashRefresh(bundle, stats);
            for (World world : Bukkit.getWorlds()) {
                if (cleanupConfig.isIgnoredWorld(world.getName())) {
                    continue;
                }
                cleanWorld(world, cleanupConfig, policy, stats, auditSession);
            }
            return;
        }
        stats.setGuardTargetEntities(0);
        List<Entity> deferredTargets = new ArrayList<>(Math.max(1, minTotalEntities));
        boolean thresholdReached = false;
        for (World world : Bukkit.getWorlds()) {
            if (cleanupConfig.isIgnoredWorld(world.getName())) {
                continue;
            }
            stats.worlds++;
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (!thresholdReached) {
                    if (isCleanableTarget(entity, cleanupConfig, policy)) {
                        deferredTargets.add(entity);
                        stats.setGuardTargetEntities(stats.getGuardTargetEntities() + 1);
                        if (stats.getGuardTargetEntities() >= minTotalEntities) {
                            thresholdReached = true;
                            handleGlobalTrashRefresh(bundle, stats);
                            cleanDeferredTargets(deferredTargets, cleanupConfig, policy, stats, auditSession);
                            deferredTargets.clear();
                        }
                    }
                    continue;
                }
                cleanNonPlayerEntity(entity, cleanupConfig, policy, stats, auditSession);
            }
        }
        if (!thresholdReached) {
            stats.markGuardSkipped(GUARD_REASON_TARGET_ENTITIES);
        }
    }

    /** 统计当前世界里会被扫地处理的目标实体数量。 */
    private CountResult countCleanableTargets(CleanupConfig cleanupConfig, CleanupPolicy policy) {
        CountResult result = new CountResult();
        for (World world : Bukkit.getWorlds()) {
            if (cleanupConfig.isIgnoredWorld(world.getName())) {
                continue;
            }
            result.worlds++;
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Player) && isCleanableTarget(entity, cleanupConfig, policy)) {
                    result.targetEntities++;
                }
            }
        }
        return result;
    }

    /** 清理门禁通过前暂存的候选实体。 */
    private void cleanDeferredTargets(List<Entity> deferredTargets, CleanupConfig cleanupConfig,
                                      CleanupPolicy policy, CleanupStats stats,
                                      CleanupAuditSession auditSession) {
        for (Entity entity : deferredTargets) {
            if (entity == null || entity.isDead()) {
                continue;
            }
            cleanNonPlayerEntity(entity, cleanupConfig, policy, stats, auditSession);
        }
    }

    /** 清理一个非玩家实体。 */
    private void cleanNonPlayerEntity(Entity entity, CleanupConfig cleanupConfig, CleanupPolicy policy,
                                      CleanupStats stats,
                                      CleanupAuditSession auditSession) {
        if (entity instanceof Item) {
            cleanItem((Item) entity, cleanupConfig, policy, stats, auditSession);
            return;
        }
        cleanEntity(entity, policy, stats);
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
        if (CleanupItemProtection.isFilledShulkerItem(item.getItemStack(), cleanupConfig)) {
            return false;
        }
        ItemSnapshot snapshot = snapshotWithRoutingMetadata(item,
                snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item)), cleanupConfig);
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            return false;
        }
        TrashRoutingDecision decision = decideItemRoute(item, snapshot, itemStack, cleanupConfig, policy);
        return decision.getRoute() != TrashRoute.SKIP;
    }

    /** 输出扫地门禁跳过日志。 */
    private void logCleanupGuardSkipped(CleanupStats stats) {
        plugin.getLogger().info("[Cleanup] skippedByGuard=true"
                + ", guardReason=" + stats.getGuardSkipReason()
                + ", onlinePlayers=" + stats.getGuardOnlinePlayers()
                + ", minOnlinePlayers=" + stats.getGuardMinOnlinePlayers()
                + ", targetEntities=" + stats.getGuardTargetEntities()
                + ", minTotalEntities=" + stats.getGuardMinTotalEntities()
                + ", worlds=" + stats.getWorlds());
        sendNotify(-5, stats);
    }

    /** 清理单个掉落物实体。 */
    private void cleanItem(Item item, CleanupConfig cleanupConfig, CleanupPolicy policy, CleanupStats stats,
                           CleanupAuditSession auditSession) {
        if (isMovingItemProtected(item, cleanupConfig)) {
            stats.itemsSkipped++;
            return;
        }
        if (CleanupItemProtection.isFilledShulkerItem(item.getItemStack(), cleanupConfig)) {
            stats.itemsSkipped++;
            return;
        }
        ItemSnapshot snapshot = snapshotWithRoutingMetadata(item,
                snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item)), cleanupConfig);
        if (snapshot == null) {
            stats.itemsSkipped++;
            return;
        }
        TrashRoutingDecision decision = decideItemRoute(
                item, snapshot, item.getItemStack(), cleanupConfig, policy);
        if (decision.getRoute() == TrashRoute.SKIP) {
            stats.itemsSkipped++;
            return;
        }
        TrashRoutingDecision finalDecision = routeWithFallback(item, snapshot, policy, decision, stats, auditSession);
        if (finalDecision.getRoute() == TrashRoute.REMOVE) {
            ItemStack removedItemStack = item.getItemStack() == null ? null : item.getItemStack().clone();
            forgetTrackedOwner(item);
            item.remove();
            auditSession.recordItem(removedItemStack, CleanupItemDestination.directRemove(), "");
            stats.itemsRemoved += Math.max(1, snapshot.getAmount());
        }
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

    /** 生成扫地物品的首个路由决策，强制直删世界不会查询任何垃圾桶。 */
    private TrashRoutingDecision decideItemRoute(Item item, ItemSnapshot snapshot, ItemStack itemStack,
                                                 CleanupConfig cleanupConfig, CleanupPolicy policy) {
        if (cleanupConfig.isDirectRemoveWorld(item.getWorld().getName())) {
            return policy.decideItem(snapshot, false, false, false, true);
        }
        boolean worldTrash = trashRouter.hasWorldTrash(item.getWorld(), itemStack);
        UUID ownerUuid = snapshot == null ? null : snapshot.getOwnerUuid();
        boolean personalTrash = trashRouter.hasPersonalTrash(ownerUuid, itemStack);
        boolean globalTrash = snapshot != null && snapshot.isGlobalTrashAvailabilityEvaluated()
                ? snapshot.isGlobalTrashAvailable()
                : trashRouter.hasGlobalTrash(itemStack);
        return policy.decideItem(snapshot, worldTrash, personalTrash, globalTrash);
    }

    /** 按核心决策尝试路由，失败后逐级降级到删除。 */
    private TrashRoutingDecision routeWithFallback(Item item, ItemSnapshot snapshot, CleanupPolicy policy,
                                                   TrashRoutingDecision firstDecision, CleanupStats stats,
                                                   CleanupAuditSession auditSession) {
        TrashRoutingDecision decision = firstDecision;
        if (decision.getRoute() == TrashRoute.REMOVE || decision.getRoute() == TrashRoute.SKIP) {
            return decision;
        }
        boolean worldAvailable = trashRouter.hasWorldTrash(item.getWorld(), item.getItemStack());
        boolean personalAvailable = trashRouter.hasPersonalTrash(snapshot.getOwnerUuid(), item.getItemStack());
        boolean globalAvailable = snapshot.isGlobalTrashAvailabilityEvaluated()
                ? snapshot.isGlobalTrashAvailable()
                : trashRouter.hasGlobalTrash(item.getItemStack());
        while (decision.getRoute() != TrashRoute.REMOVE && decision.getRoute() != TrashRoute.SKIP) {
            ItemStack routedItemStack = item.getItemStack() == null ? null : item.getItemStack().clone();
            TrashRoutingResult routed = trashRouter.routeDetailed(item.getWorld(), snapshot.getOwnerUuid(),
                    item.getItemStack(), decision.getRoute(), true);
            if (routed.isSuccess()) {
                int currentAmount = item.getItemStack() == null ? snapshot.getAmount() : item.getItemStack().getAmount();
                int acceptedAmount = Math.min(currentAmount, routed.getAcceptedAmount());
                if (acceptedAmount <= 0) {
                    return new TrashRoutingDecision(TrashRoute.SKIP, "route-accepted-zero");
                }
                routedItemStack.setAmount(acceptedAmount);
                auditSession.recordItem(routedItemStack, routed.getDestination(), routed.getTrackingKey());
                stats.addItemsRouted(acceptedAmount, decision.getRoute());
                if (decision.getRoute() == TrashRoute.PERSONAL_TRASH) {
                    stats.addPersonalTrashItem(snapshot.getOwnerUuid(), routedItemStack);
                }
                if (acceptedAmount < currentAmount) {
                    ItemStack remaining = item.getItemStack();
                    if (remaining != null) {
                        remaining.setAmount(currentAmount - acceptedAmount);
                        item.setItemStack(remaining);
                    }
                    plugin.getLogger().info("[Cleanup] 目标垃圾桶只接收了部分物品，已保留掉落物剩余数量: route="
                            + decision.getRoute() + ", accepted="
                            + acceptedAmount + ", remaining=" + (currentAmount - acceptedAmount));
                    return decision;
                }
                forgetTrackedOwner(item);
                item.remove();
                return decision;
            }
            if (decision.getRoute() == TrashRoute.WORLD_TRASH) {
                worldAvailable = false;
            } else if (decision.getRoute() == TrashRoute.PERSONAL_TRASH) {
                personalAvailable = false;
            } else if (decision.getRoute() == TrashRoute.GLOBAL_TRASH) {
                globalAvailable = false;
            }
            decision = policy.decideItem(snapshot, worldAvailable, personalAvailable, globalAvailable);
        }
        if (decision.getRoute() == TrashRoute.SKIP) {
            stats.itemsSkipped++;
        }
        return decision;
    }

    /** 完成有内容的审计批次；空批次直接丢弃。 */
    private void finishAudit(CleanupAuditSession auditSession, CleanupStats stats, boolean partial) {
        if (stats.getItemsHandled() <= 0) {
            auditSession.discard();
            return;
        }
        auditSession.complete(new CleanupRunCompletion(System.currentTimeMillis(), partial));
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
        GlobalTrashCheck globalCheck = directRemoveWorld || customMatched
                ? new GlobalTrashCheck(false, null)
                : trashRouter.checkGlobalTrash(itemStack);
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

    /** 在本轮实际清理前按清理次数刷新公共垃圾桶。 */
    private void handleGlobalTrashRefresh(ConfigBundle bundle, CleanupStats stats) {
        int interval = bundle.getTrashConfig().getGlobalTrash().getClearEveryCleanups();
        if (interval < 0 || globalTrashService == null || !globalTrashService.isEnabled()) {
            return;
        }
        cleanupRunsSinceGlobalClear++;
        if (interval == 0 || cleanupRunsSinceGlobalClear >= interval) {
            globalTrashService.clearContent();
            cleanupRunsSinceGlobalClear = 0;
            stats.globalTrashRefreshed = true;
        }
    }

    /** 返回本轮公共垃圾桶状态对应的通知编号。 */
    private int globalTrashStatusNotifyCount(CleanupStats stats) {
        if (stats.isGlobalTrashRefreshed()) {
            return -2;
        }
        if (configSupplier.get().getTrashConfig().getGlobalTrash().getClearEveryCleanups() < 0
                || globalTrashService == null || !globalTrashService.isEnabled()) {
            return -3;
        }
        return -1;
    }

    /** 发送本轮清理进入个人垃圾桶的批量提示。 */
    private void sendPersonalTrashBatchNotify(CleanupStats stats) {
        if (personalTrashService != null) {
            personalTrashService.notifyBatch(stats.snapshotPersonalTrashItemsByOwner());
        }
    }

    /** 清理单个非物品实体。 */
    private void cleanEntity(Entity entity, CleanupPolicy policy, CleanupStats stats) {
        EntitySnapshot snapshot = platform.entitySnapshotMapper().toSnapshot(entity);
        EntityCleanupDecision decision = policy.decideEntity(snapshot);
        if (decision.getAction() == EntityCleanupAction.REMOVE) {
            entity.remove();
            stats.addEntitiesRemoved(snapshot);
            return;
        }
        stats.entitiesSkipped++;
    }

    /** 按配置发送倒计时通知。 */
    private void sendNotify(int count, CleanupStats stats) {
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
    private void sendChatNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isChatEnabled() || !notifyConfig.getChatMessages().containsKey(count)) {
            return;
        }
        String message = applyStats(notifyConfig.getChatMessages().get(count), stats);
        boolean clickable = count == 0 && !notifyConfig.getChatClickCommand().trim().isEmpty();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (clickable) {
                player.spigot().sendMessage(RichTextRenderer.clickable(player, message, notifyConfig.getChatClickCommand()));
            } else {
                player.sendMessage(RichTextRenderer.color(player, message));
            }
        }
    }

    /** 独立向控制台输出对应编号的聊天通知文案。 */
    private void sendConsoleNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.getConsole().isEnabled()) {
            return;
        }
        String configuredMessage = notifyConfig.getChatMessages().get(count);
        if (configuredMessage != null) {
            Bukkit.getConsoleSender().sendMessage(RichTextRenderer.color(applyStats(configuredMessage, stats)));
        }
    }

    /** 按控制台配置输出本轮清理详细统计。 */
    private void logConsoleCleanupDetails(CleanupStats stats, boolean partial) {
        NotifyConfig.ConsoleConfig consoleConfig = configSupplier.get().getNotifyConfig().getConsole();
        if (!consoleConfig.isEnabled() || !consoleConfig.isDetailsEnabled()) {
            return;
        }
        for (String line : CleanupConsoleDetailFormatter.format(consoleConfig, stats, partial)) {
            plugin.getLogger().info("[CleanupDetail] " + line);
        }
    }

    /** 发送 ActionBar 通知。 */
    private void sendActionBarNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isActionBarEnabled() || !notifyConfig.getActionBarMessages().containsKey(count)) {
            return;
        }
        String message = applyStats(notifyConfig.getActionBarMessages().get(count), stats);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, RichTextRenderer.components(player, message));
        }
    }

    /** 发送 BossBar 通知。 */
    private void sendBossBarNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
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
        BossBar current = bossBar();
        current.setTitle(RichTextRenderer.color(applyStats(message.getText(), stats)));
        current.setStyle(parseBossBarStyle(message.getStyle()));
        current.setColor(parseBossBarColor(message.getColor()));
        current.setProgress(bossBarProgress(count, notifyConfig));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!current.getPlayers().contains(player)) {
                current.addPlayer(player);
            }
        }
        if (count <= 0) {
            scheduleBossBarRemoval();
        } else {
            cancelBossBarRemoval();
        }
    }

    /** 发送 Title 通知。 */
    private void sendTitleNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isTitleEnabled() || !notifyConfig.getTitleMessages().containsKey(count)) {
            return;
        }
        NotifyConfig.TitleMessage message = notifyConfig.getTitleMessages().get(count);
        String title = applyStats(message.getTitle(), stats);
        String subtitle = applyStats(message.getSubtitle(), stats);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(RichTextRenderer.color(player, title), RichTextRenderer.color(player, subtitle), 10, 70, 20);
        }
    }

    /** 发送声音通知。 */
    private void sendSoundNotify(NotifyConfig notifyConfig, int count) {
        if (!notifyConfig.isSoundEnabled() || !notifyConfig.getSoundMessages().containsKey(count)) {
            return;
        }
        NotifyConfig.SoundMessage message = notifyConfig.getSoundMessages().get(count);
        if (message.getSound().trim().isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), message.getSound(), message.getVolume(), message.getPitch());
        }
    }

    /** 执行倒计时命令。 */
    private void runCommandNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isCommandEnabled() || !notifyConfig.getCommandMessages().containsKey(count)) {
            return;
        }
        for (String command : notifyConfig.getCommandMessages().get(count)) {
            String finalCommand = RichTextRenderer.stripColor(applyStats(command, stats));
            if (!finalCommand.trim().isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
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
        bossBarRemoveTask = platform.scheduler().runLater(new Runnable() {
            /** 执行 BossBar 延迟移除。 */
            @Override
            public void run() {
                removeBossBar();
                bossBarRemoveTask = null;
            }
        }, 90L);
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
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    /** 替换通知中的统计占位符。 */
    private String applyStats(String message, CleanupStats stats) {
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
    private String guardReasonText(CleanupStats stats) {
        if (GUARD_REASON_ONLINE_PLAYERS.equals(stats.getGuardSkipReason())) {
            return "在线人数不足";
        }
        if (GUARD_REASON_TARGET_ENTITIES.equals(stats.getGuardSkipReason())) {
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

    /** 清理统计。 */
    public static final class CleanupStats {
        private static final int MAX_TRACKED_ENTITY_GROUPS = 4096;
        private static final int MAX_ENTITY_LABEL_LENGTH = 128;
        private int worlds;
        private int itemsRouted;
        private int itemsToWorldTrash;
        private int itemsToPersonalTrash;
        private int itemsToGlobalTrash;
        private int itemsRemoved;
        private int itemsSkipped;
        private int entitiesRemoved;
        private int entitiesSkipped;
        private boolean globalTrashRefreshed;
        private boolean guardSkipped;
        private String guardSkipReason = "";
        private int guardOnlinePlayers;
        private int guardMinOnlinePlayers;
        private int guardTargetEntities = -1;
        private int guardMinTotalEntities;
        private final Map<UUID, List<ItemStack>> personalTrashItemsByOwner = new HashMap<>();
        private final Map<String, MutableEntityRemovalEntry> entityRemovals = new HashMap<>();
        private int untrackedEntitiesRemoved;

        /** 创建空统计。 */
        public static CleanupStats empty() {
            return new CleanupStats();
        }

        /** 返回世界数量。 */
        public synchronized int getWorlds() {
            return worlds;
        }

        /** 返回移除物品数量。 */
        public synchronized int getItemsRemoved() {
            return itemsRemoved;
        }

        /** 返回进入任意垃圾桶的物品数量。 */
        public synchronized int getItemsRouted() {
            return itemsRouted;
        }

        /** 返回本轮成功处理的物品实际数量。 */
        public synchronized int getItemsHandled() {
            return itemsRouted + itemsRemoved;
        }

        /** 返回进入世界垃圾桶的物品数量。 */
        public synchronized int getItemsToWorldTrash() {
            return itemsToWorldTrash;
        }

        /** 返回进入个人垃圾桶的物品数量。 */
        public synchronized int getItemsToPersonalTrash() {
            return itemsToPersonalTrash;
        }

        /** 返回进入公共垃圾桶的物品数量。 */
        public synchronized int getItemsToGlobalTrash() {
            return itemsToGlobalTrash;
        }

        /** 返回跳过物品数量。 */
        public synchronized int getItemsSkipped() {
            return itemsSkipped;
        }

        /** 返回移除实体数量。 */
        public synchronized int getEntitiesRemoved() {
            return entitiesRemoved;
        }

        /** 返回跳过实体数量。 */
        public synchronized int getEntitiesSkipped() {
            return entitiesSkipped;
        }

        /** 判断本轮是否刷新了公共垃圾桶。 */
        public synchronized boolean isGlobalTrashRefreshed() {
            return globalTrashRefreshed;
        }

        /** 判断本轮是否被扫地门禁跳过。 */
        public synchronized boolean isGuardSkipped() {
            return guardSkipped;
        }

        /** 返回扫地门禁跳过原因。 */
        public synchronized String getGuardSkipReason() {
            return guardSkipReason;
        }

        /** 返回本轮检查到的在线玩家数。 */
        public synchronized int getGuardOnlinePlayers() {
            return guardOnlinePlayers;
        }

        /** 返回配置的最少在线玩家数。 */
        public synchronized int getGuardMinOnlinePlayers() {
            return guardMinOnlinePlayers;
        }

        /** 返回本轮扫地门禁统计到的目标实体数量。 */
        public synchronized int getGuardTargetEntities() {
            return guardTargetEntities;
        }

        /** 返回配置的最少目标实体数量。 */
        public synchronized int getGuardMinTotalEntities() {
            return guardMinTotalEntities;
        }

        /** 返回本轮进入个人垃圾桶的物品快照。 */
        public synchronized Map<UUID, List<ItemStack>> snapshotPersonalTrashItemsByOwner() {
            Map<UUID, List<ItemStack>> snapshot = new HashMap<>();
            for (Map.Entry<UUID, List<ItemStack>> entry : personalTrashItemsByOwner.entrySet()) {
                snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return snapshot;
        }

        /** 增加扫描世界数量。 */
        public synchronized void addWorld() {
            worlds++;
        }

        /** 增加跳过物品数量。 */
        public synchronized void addItemsSkipped(int amount) {
            itemsSkipped += Math.max(1, amount);
        }

        /** 增加移除物品数量。 */
        public synchronized void addItemsRemoved(int amount) {
            itemsRemoved += Math.max(1, amount);
        }

        /** 增加路由成功物品数量。 */
        public synchronized void addItemsRouted(int amount, TrashRoute route) {
            int safeAmount = Math.max(1, amount);
            itemsRouted += safeAmount;
            if (route == TrashRoute.WORLD_TRASH) {
                itemsToWorldTrash += safeAmount;
            } else if (route == TrashRoute.PERSONAL_TRASH) {
                itemsToPersonalTrash += safeAmount;
            } else if (route == TrashRoute.GLOBAL_TRASH) {
                itemsToGlobalTrash += safeAmount;
            }
        }

        /** 记录本轮进入个人垃圾桶的物品。 */
        public synchronized void addPersonalTrashItem(UUID ownerUuid, ItemStack itemStack) {
            if (ownerUuid == null || itemStack == null) {
                return;
            }
            List<ItemStack> items = personalTrashItemsByOwner.get(ownerUuid);
            if (items == null) {
                items = new ArrayList<>();
                personalTrashItemsByOwner.put(ownerUuid, items);
            }
            items.add(itemStack.clone());
        }

        /** 增加移除实体数量并记录名称与类型分组。 */
        public synchronized void addEntitiesRemoved(EntitySnapshot snapshot) {
            entitiesRemoved++;
            String type = resolveEntityType(snapshot);
            String name = resolveEntityName(snapshot, type);
            String key = name + '\u0000' + type;
            MutableEntityRemovalEntry entry = entityRemovals.get(key);
            if (entry != null) {
                entry.count++;
                return;
            }
            if (entityRemovals.size() >= MAX_TRACKED_ENTITY_GROUPS) {
                untrackedEntitiesRemoved++;
                return;
            }
            entityRemovals.put(key, new MutableEntityRemovalEntry(name, type));
        }

        /** 返回按清理数量排序后的实体明细快照。 */
        public synchronized EntityRemovalSummary snapshotEntityRemovalSummary(int maxEntries) {
            List<EntityRemovalEntry> entries = new ArrayList<>();
            for (MutableEntityRemovalEntry entry : entityRemovals.values()) {
                entries.add(new EntityRemovalEntry(entry.name, entry.type, entry.count));
            }
            Collections.sort(entries, new Comparator<EntityRemovalEntry>() {
                /** 按数量降序、名称和类型升序稳定排序。 */
                @Override
                public int compare(EntityRemovalEntry left, EntityRemovalEntry right) {
                    int countCompare = Integer.compare(right.getCount(), left.getCount());
                    if (countCompare != 0) {
                        return countCompare;
                    }
                    int nameCompare = left.getName().compareToIgnoreCase(right.getName());
                    return nameCompare != 0 ? nameCompare : left.getType().compareToIgnoreCase(right.getType());
                }
            });
            int safeMaxEntries = Math.max(1, Math.min(100, maxEntries));
            int shown = Math.min(safeMaxEntries, entries.size());
            int others = untrackedEntitiesRemoved;
            for (int index = shown; index < entries.size(); index++) {
                others += entries.get(index).getCount();
            }
            return new EntityRemovalSummary(new ArrayList<>(entries.subList(0, shown)),
                    entitiesRemoved, getItemsHandled(), entityRemovals.size(), others);
        }

        /** 按自定义名、实体名、类型的顺序解析日志名称。 */
        private static String resolveEntityName(EntitySnapshot snapshot, String type) {
            if (snapshot != null) {
                String customName = sanitizeEntityLabel(snapshot.getCustomName());
                if (!customName.isEmpty()) {
                    return customName;
                }
                String name = sanitizeEntityLabel(snapshot.getName());
                if (!name.isEmpty()) {
                    return name;
                }
            }
            return type;
        }

        /** 返回小写实体类型，缺失时使用 unknown。 */
        private static String resolveEntityType(EntitySnapshot snapshot) {
            String type = sanitizeEntityLabel(snapshot == null ? "" : snapshot.getTypeKey());
            return type.isEmpty() ? "unknown" : type.toLowerCase(Locale.ROOT);
        }

        /** 去除颜色、控制字符和多余空白，并限制日志名称长度。 */
        private static String sanitizeEntityLabel(String value) {
            String stripped = RichTextRenderer.stripColor(value == null ? "" : value);
            StringBuilder result = new StringBuilder(Math.min(MAX_ENTITY_LABEL_LENGTH, stripped.length()));
            boolean pendingSpace = false;
            for (int index = 0; index < stripped.length() && result.length() < MAX_ENTITY_LABEL_LENGTH; index++) {
                char character = stripped.charAt(index);
                if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                    pendingSpace = result.length() > 0;
                    continue;
                }
                if (pendingSpace && result.length() < MAX_ENTITY_LABEL_LENGTH) {
                    result.append(' ');
                }
                pendingSpace = false;
                result.append(character);
            }
            return result.toString();
        }

        /** 增加跳过实体数量。 */
        public synchronized void addEntitiesSkipped() {
            entitiesSkipped++;
        }

        /** 标记公共垃圾桶已刷新。 */
        public synchronized void markGlobalTrashRefreshed() {
            globalTrashRefreshed = true;
        }

        /** 记录扫地门禁检查上下文。 */
        public synchronized void recordGuardState(int onlinePlayers, int minOnlinePlayers,
                                                  int targetEntities, int minTotalEntities) {
            this.guardOnlinePlayers = Math.max(0, onlinePlayers);
            this.guardMinOnlinePlayers = Math.max(0, minOnlinePlayers);
            this.guardTargetEntities = targetEntities;
            this.guardMinTotalEntities = Math.max(0, minTotalEntities);
        }

        /** 更新扫地门禁统计到的目标实体数量。 */
        public synchronized void setGuardTargetEntities(int targetEntities) {
            this.guardTargetEntities = Math.max(0, targetEntities);
        }

        /** 标记本轮扫地因门禁被跳过。 */
        public synchronized void markGuardSkipped(String reason) {
            this.guardSkipped = true;
            this.guardSkipReason = reason == null ? "" : reason;
        }

        /** 可变的内部实体统计项。 */
        private static final class MutableEntityRemovalEntry {
            private final String name;
            private final String type;
            private int count = 1;

            /** 创建内部实体统计项。 */
            private MutableEntityRemovalEntry(String name, String type) {
                this.name = name;
                this.type = type;
            }
        }

        /** 对外只读的实体清理统计项。 */
        public static final class EntityRemovalEntry {
            private final String name;
            private final String type;
            private final int count;

            /** 创建只读实体清理统计项。 */
            private EntityRemovalEntry(String name, String type, int count) {
                this.name = name;
                this.type = type;
                this.count = count;
            }

            /** 返回实体显示名。 */
            public String getName() {
                return name;
            }

            /** 返回小写实体类型。 */
            public String getType() {
                return type;
            }

            /** 返回清理数量。 */
            public int getCount() {
                return count;
            }
        }

        /** 一次清理的实体明细只读快照。 */
        public static final class EntityRemovalSummary {
            private final List<EntityRemovalEntry> entries;
            private final int totalEntities;
            private final int totalItems;
            private final int trackedGroups;
            private final int others;

            /** 创建实体明细只读快照。 */
            private EntityRemovalSummary(List<EntityRemovalEntry> entries, int totalEntities,
                                         int totalItems, int trackedGroups, int others) {
                this.entries = Collections.unmodifiableList(entries);
                this.totalEntities = totalEntities;
                this.totalItems = totalItems;
                this.trackedGroups = trackedGroups;
                this.others = others;
            }

            /** 返回显示的实体分组。 */
            public List<EntityRemovalEntry> getEntries() {
                return entries;
            }

            /** 返回清理实体总数。 */
            public int getTotalEntities() {
                return totalEntities;
            }

            /** 返回处理物品实际总数。 */
            public int getTotalItems() {
                return totalItems;
            }

            /** 返回实际跟踪到的实体分组数量。 */
            public int getTrackedGroups() {
                return trackedGroups;
            }

            /** 返回未逐项显示的实体总数。 */
            public int getOthers() {
                return others;
            }
        }
    }

    /** 可清理目标实体计数结果。 */
    private static final class CountResult {
        private int worlds;
        private int targetEntities;
    }
}
