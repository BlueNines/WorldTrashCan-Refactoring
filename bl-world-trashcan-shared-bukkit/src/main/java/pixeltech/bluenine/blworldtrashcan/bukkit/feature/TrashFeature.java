package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.function.Supplier;
import java.util.UUID;

/** 玩家可见垃圾桶功能，负责告示牌、GUI、主动丢弃标记和损坏回收。 */
public final class TrashFeature implements Feature, Listener {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final WorldTrashRouter trashRouter;
    private final GlobalTrashService globalTrashService;
    private final PersonalTrashService personalTrashService;
    private final BukkitMessageService messages;
    private final DropOwnerTracker dropOwnerTracker;
    private boolean registered;

    /** 创建垃圾桶功能。 */
    public TrashFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                        WorldTrashRouter trashRouter, GlobalTrashService globalTrashService,
                        PersonalTrashService personalTrashService, BukkitMessageService messages,
                        DropOwnerTracker dropOwnerTracker) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
        this.messages = messages;
        this.dropOwnerTracker = dropOwnerTracker;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "trash";
    }

    /** 注册垃圾桶监听器。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    /** 重载运行期服务。 */
    @Override
    public void reload() {
        TrashConfig trashConfig = configSupplier.get().getTrashConfig();
        globalTrashService.reload(trashConfig.getGlobalTrash());
        personalTrashService.reload(trashConfig.getPersonalTrash());
        trashRouter.reload(trashConfig);
    }

    /** 取消注册监听器。 */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (dropOwnerTracker != null) {
            dropOwnerTracker.clear();
        }
        globalTrashService.close();
        personalTrashService.close();
        registered = false;
    }

    /** 处理世界垃圾桶告示牌创建。 */
    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        TrashConfig.WorldTrashConfig worldConfig = configSupplier.get().getTrashConfig().getWorldTrash();
        if (!worldConfig.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("WorldListTrashCan.Main")) {
            return;
        }
        int matchedLine = findLine(event.getLines(), worldConfig.getSignCreateText());
        if (matchedLine < 0) {
            return;
        }
        World world = event.getBlock().getWorld();
        if (!player.isOp() && worldConfig.isBannedWorld(world.getName())) {
            player.sendMessage(message("world-trash.create-banned-world", "&c该世界禁止创建世界垃圾桶。"));
            return;
        }
        Block containerBlock = platform.getAttachedContainerBlock(event.getBlock());
        if (!isContainer(containerBlock)) {
            player.sendMessage(message("world-trash.create-not-container", "&c告示牌必须贴在容器上，或放在容器上方。"));
            return;
        }
        if (!player.isOp() && !canCreateMore(world, worldConfig)) {
            player.sendMessage(message("world-trash.create-reach-limit", "&c该世界的世界垃圾桶数量已达到上限。"));
            return;
        }
        if (trashRouter.addWorldTrash(containerBlock, worldConfig.getDefaultMaxCount(), player.isOp(),
                player.getUniqueId(), player.getName())) {
            event.setLine(matchedLine, color(worldConfig.getSignCreatedText()));
            player.sendMessage(message("world-trash.create-success", "&a已在世界 &f{world} &a创建世界垃圾桶。",
                    "{world}", world.getName()));
        } else {
            player.sendMessage(message("world-trash.create-save-fail", "&c世界垃圾桶保存失败，请查看后台日志。"));
        }
    }

    /** 处理世界垃圾桶告示牌或容器破坏。 */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Sign) {
            Block containerBlock = platform.getAttachedContainerBlock(block);
            removeWorldTrashIfPresent(event.getPlayer(), containerBlock);
            return;
        }
        removeWorldTrashIfPresent(event.getPlayer(), block);
    }

    /** 处理公共和个人垃圾桶点击。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (globalTrashService.handleClick(event)) {
            return;
        }
        personalTrashService.handleClick(event);
    }

    /** 阻止拖拽修改公共或个人垃圾桶 GUI 的内容槽和展示物。 */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (globalTrashService.handleDrag(event)) {
            return;
        }
        personalTrashService.handleDrag(event);
    }

    /** 公共或个人垃圾桶关闭时释放按玩家生成的展示视图。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (globalTrashService.handleClose(event)) {
            return;
        }
        personalTrashService.handleClose(event);
    }

    /** 玩家退出时释放两类虚拟桶的轻量排序偏好和会话引用。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        globalTrashService.handleQuit(playerId);
        personalTrashService.handleQuit(playerId);
    }

    /** 给玩家主动丢弃的物品写入所属玩家标记。 */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        TrashConfig.PersonalTrashConfig personalConfig = configSupplier.get().getTrashConfig().getPersonalTrash();
        if (!personalConfig.isEnabled()) {
            return;
        }
        trackDroppedItem(event.getItemDrop(), event.getPlayer(), personalConfig);
    }

    /** 玩家丢弃物被仙人掌、岩浆等损坏时尝试收回个人或公共垃圾桶。 */
    @EventHandler
    public void onItemDamage(EntityDamageEvent event) {
        if (!isRecoverableDamageCause(event.getCause())) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Item)) {
            return;
        }
        TrashConfig.PersonalTrashConfig personalConfig = configSupplier.get().getTrashConfig().getPersonalTrash();
        TrashConfig.DamageRecoveryMode mode = personalConfig.getDamageRecoveryMode();
        if (!personalConfig.isEnabled() || mode == TrashConfig.DamageRecoveryMode.DISABLED) {
            return;
        }
        Item item = (Item) entity;
        UUID ownerUuid = dropOwnerTracker == null ? null : dropOwnerTracker.removeOwner(item);
        if (ownerUuid == null) {
            return;
        }
        ItemStack itemStack = item.getItemStack();
        TrashRoute route = mode == TrashConfig.DamageRecoveryMode.GLOBAL_TRASH
                ? TrashRoute.GLOBAL_TRASH
                : TrashRoute.PERSONAL_TRASH;
        if (trashRouter.route(item.getWorld(), ownerUuid, itemStack, route)) {
            event.setCancelled(true);
            item.remove();
            if (route == TrashRoute.PERSONAL_TRASH) {
                personalTrashService.notifySingle(ownerUuid, itemStack);
            }
        }
    }

    /** 打开公共垃圾桶。 */
    public void openGlobal(Player player) {
        globalTrashService.open(player);
    }

    /** 打开个人垃圾桶。 */
    public void openPersonal(Player player) {
        personalTrashService.open(player);
    }

    /** 返回公共垃圾桶页数。 */
    public int getGlobalPageCount() {
        return globalTrashService.getPageCount();
    }

    /** 返回已加载个人垃圾桶数量。 */
    public int getPersonalInventoryCount() {
        return personalTrashService.getLoadedInventoryCount();
    }

    /** 返回公共垃圾桶内物品总数量。 */
    public int getGlobalStoredItemAmount() {
        return globalTrashService.getStoredItemAmount();
    }

    /** 返回公共垃圾桶内堆叠数量。 */
    public int getGlobalStoredStackCount() {
        return globalTrashService.getStoredStackCount();
    }

    /** 返回指定玩家个人垃圾桶内物品总数量。 */
    public int getPersonalStoredItemAmount(UUID ownerUuid) {
        return personalTrashService.getStoredItemAmount(ownerUuid);
    }

    /** 返回指定玩家个人垃圾桶内堆叠数量。 */
    public int getPersonalStoredStackCount(UUID ownerUuid) {
        return personalTrashService.getStoredStackCount(ownerUuid);
    }

    /** 后台测试：创建真实掉落物并通过事件总线模拟一次岩浆损坏回收。 */
    public boolean debugDamageRecovery(Player player, Material material, int amount) {
        TrashConfig.PersonalTrashConfig personalConfig = configSupplier.get().getTrashConfig().getPersonalTrash();
        if (!personalConfig.isEnabled() || personalConfig.getDamageRecoveryMode() == TrashConfig.DamageRecoveryMode.DISABLED) {
            return false;
        }
        Item item = player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(material, amount));
        trackDroppedItem(item, player, personalConfig);
        EntityDamageEvent event = new EntityDamageEvent(item, EntityDamageEvent.DamageCause.LAVA, 1D);
        plugin.getServer().getPluginManager().callEvent(event);
        return event.isCancelled() || !item.isValid();
    }

    /** 测试用：让 debug 掉落物走正式掉落归属追踪。 */
    public void trackDebugDrop(Item item, Player player) {
        if (item == null || player == null) {
            return;
        }
        TrashConfig.PersonalTrashConfig personalConfig = configSupplier.get().getTrashConfig().getPersonalTrash();
        if (personalConfig.isEnabled()) {
            trackDroppedItem(item, player, personalConfig);
        }
    }

    /** 判断当前世界是否还能继续创建。 */
    private boolean canCreateMore(World world, TrashConfig.WorldTrashConfig worldConfig) {
        int current = trashRouter.getWorldTrashCount(world);
        int max = trashRouter.getEffectiveMaxTrashCanCount(world, worldConfig.getDefaultMaxCount());
        return max <= 0 || current < max;
    }

    /** 如方块是已登记垃圾桶则删除登记。 */
    private void removeWorldTrashIfPresent(Player player, Block block) {
        if (block != null && trashRouter.isWorldTrashBlock(block) && trashRouter.removeWorldTrash(block)) {
            player.sendMessage(message("world-trash.remove-success", "&a已移除该世界垃圾桶登记。"));
        }
    }

    /** 判断方块是否是可用容器。 */
    private boolean isContainer(Block block) {
        return block != null && block.getType() != Material.AIR && block.getState() instanceof InventoryHolder;
    }

    /** 记录玩家主动丢弃的物品，用于无世界垃圾桶路由和损坏回收。 */
    private void trackDroppedItem(Item item, Player player, TrashConfig.PersonalTrashConfig personalConfig) {
        if (personalConfig.isTrackPlayerDroppedItems()) {
            platform.itemSnapshotMapper().markOwner(item, player);
        }
        if (dropOwnerTracker == null) {
            return;
        }
        int ttlSeconds = ownerTrackingSeconds(personalConfig);
        if (ttlSeconds <= 0) {
            return;
        }
        dropOwnerTracker.track(item, player, ttlSeconds);
    }

    /** 计算短期 owner 记录保留时间，至少覆盖一轮清理间隔。 */
    private int ownerTrackingSeconds(TrashConfig.PersonalTrashConfig personalConfig) {
        int ttlSeconds = 0;
        if (personalConfig.isTrackPlayerDroppedItems()) {
            int cleanupInterval = Math.max(0, configSupplier.get().getCleanupConfig().getIntervalSeconds());
            ttlSeconds = Math.max(ttlSeconds, Math.max(60, cleanupInterval + 5));
        }
        if (personalConfig.getDamageRecoveryMode() != TrashConfig.DamageRecoveryMode.DISABLED) {
            ttlSeconds = Math.max(ttlSeconds, personalConfig.getDamageRecoveryDelaySeconds());
        }
        return Math.max(0, ttlSeconds);
    }

    /** 判断损坏原因是否属于原版物品销毁类场景。 */
    private boolean isRecoverableDamageCause(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.CONTACT
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK;
    }

    /** 查找匹配的告示牌行。 */
    private int findLine(String[] lines, String needle) {
        if (lines == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line != null && line.contains(needle)) {
                return index;
            }
        }
        return -1;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return RichTextRenderer.color(text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }
}
