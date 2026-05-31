package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.TaskHandle;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.TrashRouter;
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

import java.util.function.Supplier;

/** 后台清理功能模块，清理决策交给 core，Bukkit 层只执行结果。 */
public final class CleanupFeature implements Feature {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final TrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
    private TaskHandle taskHandle;
    private CleanupStats lastStats = CleanupStats.empty();
    private long nextRunAtMillis;
    private int cleanupRunsSinceGlobalClear;
    private int countdownSeconds;

    /** 创建后台清理功能。 */
    public CleanupFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                          TrashRouter trashRouter, GlobalTrashService globalTrashService) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
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
        countdownSeconds = 0;
    }

    /** 立即执行一次清理。 */
    public CleanupStats runNow() {
        ConfigBundle bundle = configSupplier.get();
        CleanupPolicy policy = new DefaultCleanupPolicy(bundle.getCleanupSettings());
        CleanupStats stats = new CleanupStats();
        CleanupConfig cleanupConfig = bundle.getCleanupConfig();
        for (World world : Bukkit.getWorlds()) {
            if (cleanupConfig.isIgnoredWorld(world.getName())) {
                continue;
            }
            cleanWorld(world, policy, stats);
        }
        handleGlobalTrashRefresh(bundle, stats);
        lastStats = stats;
        plugin.getLogger().info("[Cleanup] worlds=" + stats.worlds
                + ", itemsRouted=" + stats.itemsRouted
                + ", itemsRemoved=" + stats.itemsRemoved
                + ", itemsSkipped=" + stats.itemsSkipped
                + ", entitiesRemoved=" + stats.entitiesRemoved
                + ", entitiesSkipped=" + stats.entitiesSkipped
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

    /** 按配置启动定时任务。 */
    private void startTask() {
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
        if (interval <= 0) {
            plugin.getLogger().info("[Cleanup] 定时清理已关闭，仅允许手动触发。");
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

    /** 推进一秒倒计时。 */
    private void tickCountdown() {
        int interval = configSupplier.get().getCleanupConfig().getIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        if (countdownSeconds <= 0) {
            CleanupStats stats = runNow();
            sendNotify(0, stats);
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

    /** 清理单个掉落物实体。 */
    private void cleanItem(Item item, CleanupPolicy policy, CleanupStats stats) {
        ItemSnapshot snapshot = platform.itemSnapshotMapper().toSnapshot(item.getItemStack());
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
            if (trashRouter.route(item.getWorld(), snapshot.getOwnerUuid(), item.getItemStack(), decision.getRoute())) {
                item.remove();
                stats.itemsRouted += Math.max(1, snapshot.getAmount());
                countRoute(stats, decision.getRoute());
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

    /** 按清理次数刷新公共垃圾桶。 */
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
                TextComponent component = new TextComponent(color(message));
                component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, notifyConfig.getChatClickCommand()));
                player.spigot().sendMessage(component);
            } else {
                player.sendMessage(color(message));
            }
        }
        if (notifyConfig.isChatConsoleLog()) {
            Bukkit.getConsoleSender().sendMessage(color(message));
        }
    }

    /** 发送 ActionBar 通知。 */
    private void sendActionBarNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isActionBarEnabled() || !notifyConfig.getActionBarMessages().containsKey(count)) {
            return;
        }
        TextComponent component = new TextComponent(color(applyStats(notifyConfig.getActionBarMessages().get(count), stats)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, component);
        }
    }

    /** 发送 Title 通知。 */
    private void sendTitleNotify(NotifyConfig notifyConfig, int count, CleanupStats stats) {
        if (!notifyConfig.isTitleEnabled() || !notifyConfig.getTitleMessages().containsKey(count)) {
            return;
        }
        NotifyConfig.TitleMessage message = notifyConfig.getTitleMessages().get(count);
        String title = color(applyStats(message.getTitle(), stats));
        String subtitle = color(applyStats(message.getSubtitle(), stats));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(title, subtitle, 10, 70, 20);
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
            String finalCommand = ChatColor.stripColor(color(applyStats(command, stats)));
            if (!finalCommand.trim().isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            }
        }
    }

    /** 替换通知中的统计占位符。 */
    private String applyStats(String message, CleanupStats stats) {
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

        /** 创建空统计。 */
        public static CleanupStats empty() {
            return new CleanupStats();
        }

        /** 返回世界数量。 */
        public int getWorlds() {
            return worlds;
        }

        /** 返回移除物品数量。 */
        public int getItemsRemoved() {
            return itemsRemoved;
        }

        /** 返回进入任意垃圾桶的物品数量。 */
        public int getItemsRouted() {
            return itemsRouted;
        }

        /** 返回进入世界垃圾桶的物品数量。 */
        public int getItemsToWorldTrash() {
            return itemsToWorldTrash;
        }

        /** 返回进入个人垃圾桶的物品数量。 */
        public int getItemsToPersonalTrash() {
            return itemsToPersonalTrash;
        }

        /** 返回进入公共垃圾桶的物品数量。 */
        public int getItemsToGlobalTrash() {
            return itemsToGlobalTrash;
        }

        /** 返回跳过物品数量。 */
        public int getItemsSkipped() {
            return itemsSkipped;
        }

        /** 返回移除实体数量。 */
        public int getEntitiesRemoved() {
            return entitiesRemoved;
        }

        /** 返回跳过实体数量。 */
        public int getEntitiesSkipped() {
            return entitiesSkipped;
        }

        /** 判断本轮是否刷新了公共垃圾桶。 */
        public boolean isGlobalTrashRefreshed() {
            return globalTrashRefreshed;
        }
    }
}
