package pixeltech.bluenine.blworldtrashcan.plugin.folia;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityCleanupDecision;
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

/** Folia 专用 region-safe 清理实现。 */
public final class FoliaRegionCleanupFeature implements Feature {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final WorldTrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
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
                                     WorldTrashRouter trashRouter, GlobalTrashService globalTrashService) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
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

    /** 立即启动一次异步 region-safe 清理并返回是否成功提交。 */
    public boolean startNow() {
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
                        scheduleWorldScans(stats);
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
    private void scheduleWorldScans(final CleanupFeature.CleanupStats stats) {
        ConfigBundle bundle = configSupplier.get();
        CleanupPolicy policy = new DefaultCleanupPolicy(bundle.getCleanupSettings());
        CleanupConfig cleanupConfig = bundle.getCleanupConfig();
        final CompletionTracker tracker = new CompletionTracker(stats);
        for (World world : Bukkit.getWorlds()) {
            if (cleanupConfig.isIgnoredWorld(world.getName())) {
                continue;
            }
            try {
                stats.addWorld();
                Chunk[] chunks = world.getLoadedChunks();
                for (Chunk chunk : chunks) {
                    scheduleChunkScan(chunk, policy, stats, tracker);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[FoliaCleanup] 收集世界已加载 chunk 失败: "
                        + world.getName() + " - " + exception.getMessage());
            }
        }
        tracker.initialSchedulingDone();
    }

    /** 安排单个 chunk 的 region 清理任务。 */
    private void scheduleChunkScan(final Chunk chunk, final CleanupPolicy policy,
                                   final CleanupFeature.CleanupStats stats, final CompletionTracker tracker) {
        tracker.taskStarted();
        Bukkit.getRegionScheduler().run(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), new Consumer<ScheduledTask>() {
            /** 在 chunk 所在 region 里处理实体。 */
            @Override
            public void accept(ScheduledTask task) {
                try {
                    cleanChunk(chunk, policy, stats, tracker);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("[FoliaCleanup] 清理 chunk 失败: "
                            + chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ()
                            + " - " + exception.getMessage());
                } finally {
                    tracker.taskDone();
                }
            }
        });
    }

    /** 在当前 region 内清理 chunk 实体。 */
    private void cleanChunk(Chunk chunk, CleanupPolicy policy, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof Item) {
                cleanItem((Item) entity, policy, stats, tracker);
                continue;
            }
            cleanEntity(entity, policy, stats);
        }
    }

    /** 在物品实体所在 region 内处理掉落物。 */
    private void cleanItem(Item item, CleanupPolicy policy, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        ItemStack itemStack = item.getItemStack();
        if (itemStack == null) {
            stats.addItemsSkipped(1);
            return;
        }
        ItemStack routedStack = itemStack.clone();
        ItemSnapshot snapshot = platform.itemSnapshotMapper().toSnapshot(routedStack);
        RouteState state = initialRouteState(item.getWorld(), snapshot, routedStack);
        routeWithFallback(item, routedStack, snapshot, policy, state, stats, tracker);
    }

    /** 生成初始路由可用性。 */
    private RouteState initialRouteState(World world, ItemSnapshot snapshot, ItemStack itemStack) {
        synchronized (trashRouter) {
            return new RouteState(
                    trashRouter.hasWorldTrash(world, itemStack),
                    trashRouter.hasPersonalTrash(snapshot.getOwnerUuid(), itemStack),
                    trashRouter.hasGlobalTrash(itemStack)
            );
        }
    }

    /** 按核心策略逐级路由或删除物品。 */
    private void routeWithFallback(Item item, ItemStack itemStack, ItemSnapshot snapshot, CleanupPolicy policy,
                                   RouteState state, CleanupFeature.CleanupStats stats, CompletionTracker tracker) {
        TrashRoutingDecision decision = policy.decideItem(snapshot, state.worldAvailable, state.personalAvailable, state.globalAvailable);
        while (true) {
            TrashRoute route = decision.getRoute();
            if (route == TrashRoute.SKIP) {
                stats.addItemsSkipped(itemStack.getAmount());
                return;
            }
            if (route == TrashRoute.REMOVE) {
                item.remove();
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
            if (routeVirtual(item, itemStack, snapshot.getOwnerUuid(), route)) {
                item.remove();
                stats.addItemsRouted(itemStack.getAmount(), route);
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
        Bukkit.getRegionScheduler().run(plugin, world, location.getX() >> 4, location.getZ() >> 4, new Consumer<ScheduledTask>() {
            /** 在箱子所在 region 内写入世界垃圾桶。 */
            @Override
            public void accept(ScheduledTask task) {
                try {
                    if (trashRouter.routeWorldTrashAt(location, itemStack.clone())) {
                        stats.addItemsRouted(itemStack.getAmount(), TrashRoute.WORLD_TRASH);
                        scheduleRemoveRoutedItem(item, tracker);
                    } else {
                        tryWorldTrash(item, itemStack, snapshot, policy, state, stats, tracker, locations, index + 1);
                    }
                } finally {
                    tracker.taskDone();
                }
            }
        });
    }

    /** 世界垃圾桶全部失败后回到物品实体 region 继续降级路由。 */
    private void scheduleItemFallback(final Item item, final ItemStack itemStack, final ItemSnapshot snapshot,
                                      final CleanupPolicy policy, final RouteState state,
                                      final CleanupFeature.CleanupStats stats, final CompletionTracker tracker) {
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
        boolean scheduled = item.getScheduler().execute(plugin, new Runnable() {
            /** 在物品实体 region 内继续降级路由。 */
            @Override
            public void run() {
                try {
                    routeWithFallback(item, itemStack, snapshot, policy, state, stats, tracker);
                } finally {
                    if (finished.compareAndSet(false, true)) {
                        tracker.taskDone();
                    }
                }
            }
        }, retired, 1L);
        if (!scheduled) {
            retired.run();
        }
    }

    /** 删除已经成功路由的物品实体。 */
    private void scheduleRemoveRoutedItem(final Item item, final CompletionTracker tracker) {
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
        boolean scheduled = item.getScheduler().execute(plugin, new Runnable() {
            /** 在物品实体 region 内删除实体。 */
            @Override
            public void run() {
                try {
                    item.remove();
                } finally {
                    if (finished.compareAndSet(false, true)) {
                        tracker.taskDone();
                    }
                }
            }
        }, retired, 1L);
        if (!scheduled) {
            retired.run();
        }
    }

    /** 路由到个人或公共虚拟垃圾桶。 */
    private boolean routeVirtual(Item item, ItemStack itemStack, UUID ownerUuid, TrashRoute route) {
        synchronized (trashRouter) {
            return trashRouter.route(item.getWorld(), ownerUuid, itemStack.clone(), route);
        }
    }

    /** 在当前 region 内清理非物品实体。 */
    private void cleanEntity(Entity entity, CleanupPolicy policy, CleanupFeature.CleanupStats stats) {
        EntityCleanupDecision decision = policy.decideEntity(platform.entitySnapshotMapper().toSnapshot(entity));
        if (decision.getAction() == EntityCleanupAction.REMOVE) {
            entity.remove();
            stats.addEntitiesRemoved();
            return;
        }
        stats.addEntitiesSkipped();
    }

    /** 结束清理并在全局区域刷新公共垃圾桶和输出日志。 */
    private void finishCleanup(final CleanupFeature.CleanupStats stats) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, new Runnable() {
            /** 记录最终统计。 */
            @Override
            public void run() {
                handleGlobalTrashRefresh(stats);
                lastStats = stats;
                cleanupRunning.set(false);
                plugin.getLogger().info("[FoliaCleanup] worlds=" + stats.getWorlds()
                        + ", itemsRouted=" + stats.getItemsRouted()
                        + ", itemsRemoved=" + stats.getItemsRemoved()
                        + ", itemsSkipped=" + stats.getItemsSkipped()
                        + ", entitiesRemoved=" + stats.getEntitiesRemoved()
                        + ", entitiesSkipped=" + stats.getEntitiesSkipped()
                        + ", worldTrashSkippedUnloadedChunks=" + trashRouter.getSkippedUnloadedChunkAccesses()
                        + ", globalTrashRefreshed=" + stats.isGlobalTrashRefreshed());
                sendNotify(0, stats);
                sendNotify(stats.isGlobalTrashRefreshed() ? -2 : -1, stats);
            }
        });
    }

    /** 按清理次数刷新公共垃圾桶。 */
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

    /** 按配置发送 Folia 安全通知。 */
    private void sendNotify(int count, CleanupFeature.CleanupStats stats) {
        NotifyConfig notifyConfig = configSupplier.get().getNotifyConfig();
        sendChatNotify(notifyConfig, count, stats);
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
                    TextComponent component = new TextComponent(color(message));
                    component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, clickCommand));
                    player.spigot().sendMessage(component);
                    return;
                }
                player.sendMessage(color(message));
            }
        });
        if (notifyConfig.isChatConsoleLog()) {
            Bukkit.getConsoleSender().sendMessage(color(message));
        }
    }

    /** 发送 ActionBar 通知。 */
    private void sendActionBarNotify(NotifyConfig notifyConfig, int count, CleanupFeature.CleanupStats stats) {
        if (!notifyConfig.isActionBarEnabled() || !notifyConfig.getActionBarMessages().containsKey(count)) {
            return;
        }
        final TextComponent component = new TextComponent(color(applyStats(notifyConfig.getActionBarMessages().get(count), stats)));
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文发送 ActionBar。 */
            @Override
            public void run(Player player) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, component);
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
        current.setTitle(color(applyStats(message.getText(), stats)));
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
        final String title = color(applyStats(message.getTitle(), stats));
        final String subtitle = color(applyStats(message.getSubtitle(), stats));
        forEachOnlinePlayer(new PlayerAction() {
            /** 在玩家实体上下文发送 Title。 */
            @Override
            public void run(Player player) {
                player.sendTitle(title, subtitle, 10, 70, 20);
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
            String finalCommand = ChatColor.stripColor(color(applyStats(command, stats)));
            if (!finalCommand.trim().isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
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
            player.getScheduler().execute(plugin, new Runnable() {
                /** 在玩家实体上下文执行通知动作。 */
                @Override
                public void run() {
                    action.run(player);
                }
            }, retired, 1L);
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
        int clearRemain = clearEvery <= 0 ? 0 : Math.max(0, clearEvery - cleanupRunsSinceGlobalClear);
        return (message == null ? "" : message)
                .replace("%DealItemSum%", String.valueOf(dealItemSum))
                .replace("%GlobalTrashAddSum%", String.valueOf(stats.getItemsToGlobalTrash()))
                .replace("%EntitySum%", String.valueOf(stats.getEntitiesRemoved()))
                .replace("%ClearGlobalCount%", String.valueOf(clearRemain));
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
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

    /** 异步清理完成跟踪器。 */
    private final class CompletionTracker {
        private final CleanupFeature.CleanupStats stats;
        private final AtomicInteger pendingTasks = new AtomicInteger(1);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        /** 创建完成跟踪器。 */
        private CompletionTracker(CleanupFeature.CleanupStats stats) {
            this.stats = stats;
        }

        /** 记录新任务。 */
        private void taskStarted() {
            pendingTasks.incrementAndGet();
        }

        /** 记录任务完成。 */
        private void taskDone() {
            if (pendingTasks.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                finishCleanup(stats);
            }
        }

        /** 初始任务分派完成。 */
        private void initialSchedulingDone() {
            taskDone();
        }
    }
}
