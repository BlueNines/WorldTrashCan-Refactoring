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
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
                          PersonalTrashService personalTrashService, DropOwnerTracker dropOwnerTracker) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
        this.dropOwnerTracker = dropOwnerTracker;
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
        return runNow(false);
    }

    /** 立即执行一次清理，可由命令入口决定是否忽略 guards。 */
    public CleanupStats runNow(boolean ignoreGuards) {
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
        if (!ignoreGuards && guardConfig.getMinTotalEntities() > 0) {
            cleanWithEntityGuard(bundle, cleanupConfig, policy, stats);
            if (stats.isGuardSkipped()) {
                lastStats = stats;
                logCleanupGuardSkipped(stats);
                return stats;
            }
        } else {
            stats.setGuardTargetEntities(0);
            handleGlobalTrashRefresh(bundle, stats);
            for (World world : Bukkit.getWorlds()) {
                if (cleanupConfig.isIgnoredWorld(world.getName())) {
                    continue;
                }
                cleanWorld(world, policy, stats);
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
        return stats;
    }

    /** 返回最近一次清理统计。 */
    public CleanupStats getLastStats() {
        return lastStats;
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
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
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
            if (stats.isGuardSkipped()) {
                sendNotify(-5, stats);
                countdownSeconds = interval;
                nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
                return;
            }
            sendNotify(0, stats);
            sendNotify(globalTrashStatusNotifyCount(stats), stats);
            countdownSeconds = interval;
            nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
            return;
        }
        sendNotify(countdownSeconds, CleanupStats.empty());
        countdownSeconds--;
        nextRunAtMillis = System.currentTimeMillis() + countdownSeconds * 1000L;
    }

    /** 清理单个世界。 */
    private void cleanWorld(World world, CleanupPolicy policy, CleanupStats stats) {
        stats.worlds++;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof Item) {
                cleanItem((Item) entity, policy, stats);
                continue;
            }
            cleanEntity(entity, policy, stats);
        }
    }

    /** 按扫地门禁统计目标实体，达到阈值后再实际清理。 */
    private void cleanWithEntityGuard(ConfigBundle bundle, CleanupConfig cleanupConfig, CleanupPolicy policy, CleanupStats stats) {
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
                cleanWorld(world, policy, stats);
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
                    if (isCleanableTarget(entity, policy)) {
                        deferredTargets.add(entity);
                        stats.setGuardTargetEntities(stats.getGuardTargetEntities() + 1);
                        if (stats.getGuardTargetEntities() >= minTotalEntities) {
                            thresholdReached = true;
                            handleGlobalTrashRefresh(bundle, stats);
                            cleanDeferredTargets(deferredTargets, policy, stats);
                            deferredTargets.clear();
                        }
                    }
                    continue;
                }
                cleanNonPlayerEntity(entity, policy, stats);
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
                if (!(entity instanceof Player) && isCleanableTarget(entity, policy)) {
                    result.targetEntities++;
                }
            }
        }
        return result;
    }

    /** 清理门禁通过前暂存的候选实体。 */
    private void cleanDeferredTargets(List<Entity> deferredTargets, CleanupPolicy policy, CleanupStats stats) {
        for (Entity entity : deferredTargets) {
            if (entity == null || entity.isDead()) {
                continue;
            }
            cleanNonPlayerEntity(entity, policy, stats);
        }
    }

    /** 清理一个非玩家实体。 */
    private void cleanNonPlayerEntity(Entity entity, CleanupPolicy policy, CleanupStats stats) {
        if (entity instanceof Item) {
            cleanItem((Item) entity, policy, stats);
            return;
        }
        cleanEntity(entity, policy, stats);
    }

    /** 判断实体是否会被本轮扫地处理。 */
    private boolean isCleanableTarget(Entity entity, CleanupPolicy policy) {
        if (entity instanceof Item) {
            return isCleanableItemTarget((Item) entity, policy);
        }
        EntityCleanupDecision decision = policy.decideEntity(platform.entitySnapshotMapper().toSnapshot(entity));
        return decision.getAction() == EntityCleanupAction.REMOVE;
    }

    /** 判断掉落物是否会被本轮扫地路由或删除。 */
    private boolean isCleanableItemTarget(Item item, CleanupPolicy policy) {
        ItemSnapshot snapshot = snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item));
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            return false;
        }
        boolean worldTrash = trashRouter.hasWorldTrash(item.getWorld(), itemStack);
        UUID ownerUuid = snapshot == null ? null : snapshot.getOwnerUuid();
        boolean personalTrash = trashRouter.hasPersonalTrash(ownerUuid, itemStack);
        boolean globalTrash = trashRouter.hasGlobalTrash(itemStack);
        TrashRoutingDecision decision = policy.decideItem(snapshot, worldTrash, personalTrash, globalTrash);
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
    private void cleanItem(Item item, CleanupPolicy policy, CleanupStats stats) {
        ItemSnapshot snapshot = snapshotWithTrackedOwner(item, platform.itemSnapshotMapper().toSnapshot(item));
        boolean worldTrash = trashRouter.hasWorldTrash(item.getWorld(), item.getItemStack());
        boolean personalTrash = trashRouter.hasPersonalTrash(snapshot.getOwnerUuid(), item.getItemStack());
        boolean globalTrash = trashRouter.hasGlobalTrash(item.getItemStack());
        TrashRoutingDecision decision = policy.decideItem(snapshot, worldTrash, personalTrash, globalTrash);
        if (decision.getRoute() == TrashRoute.SKIP) {
            stats.itemsSkipped++;
            return;
        }
        TrashRoutingDecision finalDecision = routeWithFallback(item, snapshot, policy, decision, stats);
        if (finalDecision.getRoute() == TrashRoute.REMOVE) {
            forgetTrackedOwner(item);
            item.remove();
            stats.itemsRemoved += Math.max(1, snapshot.getAmount());
        }
    }

    /** 按核心决策尝试路由，失败后逐级降级到删除。 */
    private TrashRoutingDecision routeWithFallback(Item item, ItemSnapshot snapshot, CleanupPolicy policy,
                                                   TrashRoutingDecision firstDecision, CleanupStats stats) {
        TrashRoutingDecision decision = firstDecision;
        boolean worldAvailable = trashRouter.hasWorldTrash(item.getWorld(), item.getItemStack());
        boolean personalAvailable = trashRouter.hasPersonalTrash(snapshot.getOwnerUuid(), item.getItemStack());
        boolean globalAvailable = trashRouter.hasGlobalTrash(item.getItemStack());
        while (decision.getRoute() != TrashRoute.REMOVE && decision.getRoute() != TrashRoute.SKIP) {
            ItemStack routedItemStack = item.getItemStack() == null ? null : item.getItemStack().clone();
            if (trashRouter.route(item.getWorld(), snapshot.getOwnerUuid(), item.getItemStack(), decision.getRoute())) {
                forgetTrackedOwner(item);
                item.remove();
                stats.itemsRouted += Math.max(1, snapshot.getAmount());
                countRoute(stats, decision.getRoute());
                if (decision.getRoute() == TrashRoute.PERSONAL_TRASH) {
                    stats.addPersonalTrashItem(snapshot.getOwnerUuid(), routedItemStack);
                }
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

    /** 统计物品进入的垃圾桶类型。 */
    private void countRoute(CleanupStats stats, TrashRoute route) {
        if (route == TrashRoute.WORLD_TRASH) {
            stats.itemsToWorldTrash++;
        } else if (route == TrashRoute.PERSONAL_TRASH) {
            stats.itemsToPersonalTrash++;
        } else if (route == TrashRoute.GLOBAL_TRASH) {
            stats.itemsToGlobalTrash++;
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
        EntityCleanupDecision decision = policy.decideEntity(platform.entitySnapshotMapper().toSnapshot(entity));
        if (decision.getAction() == EntityCleanupAction.REMOVE) {
            entity.remove();
            stats.entitiesRemoved++;
            return;
        }
        stats.entitiesSkipped++;
    }

    /** 按配置发送倒计时通知。 */
    private void sendNotify(int count, CleanupStats stats) {
        NotifyConfig notifyConfig = configSupplier.get().getNotifyConfig();
        sendChatNotify(notifyConfig, count, stats);
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
        if (notifyConfig.isChatConsoleLog()) {
            Bukkit.getConsoleSender().sendMessage(RichTextRenderer.color(message));
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

        /** 增加移除实体数量。 */
        public synchronized void addEntitiesRemoved() {
            entitiesRemoved++;
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
    }

    /** 可清理目标实体计数结果。 */
    private static final class CountResult {
        private int worlds;
        private int targetEntities;
    }
}
