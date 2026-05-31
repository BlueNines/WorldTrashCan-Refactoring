package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.ChatColor;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
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
    private boolean registered;

    /** 创建垃圾桶功能。 */
    public TrashFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                        WorldTrashRouter trashRouter, GlobalTrashService globalTrashService,
                        PersonalTrashService personalTrashService) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
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
        if (!player.hasPermission("blworldtrashcan.world.create") && !player.hasPermission("WorldListTrashCan.Main")) {
            return;
        }
        int matchedLine = findLine(event.getLines(), worldConfig.getSignCreateText());
        if (matchedLine < 0) {
            return;
        }
        World world = event.getBlock().getWorld();
        if (!player.isOp() && worldConfig.isBannedWorld(world.getName())) {
            player.sendMessage(color("&c该世界禁止创建世界垃圾桶。"));
            return;
        }
        Block containerBlock = platform.getAttachedContainerBlock(event.getBlock());
        if (!isContainer(containerBlock)) {
            player.sendMessage(color("&c告示牌必须贴在容器上，或放在容器上方。"));
            return;
        }
        if (!player.isOp() && !canCreateMore(world, worldConfig)) {
            player.sendMessage(color("&c该世界的世界垃圾桶数量已达到上限。"));
            return;
        }
        if (trashRouter.addWorldTrash(containerBlock, worldConfig.getDefaultMaxCount())) {
            event.setLine(matchedLine, color(worldConfig.getSignCreatedText()));
            player.sendMessage(color("&a已在世界 &f" + world.getName() + " &a创建世界垃圾桶。"));
        } else {
            player.sendMessage(color("&c世界垃圾桶保存失败，请查看后台日志。"));
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

    /** 给玩家主动丢弃的物品写入所属玩家标记。 */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        TrashConfig.PersonalTrashConfig personalConfig = configSupplier.get().getTrashConfig().getPersonalTrash();
        if (personalConfig.isEnabled() && personalConfig.isTrackPlayerDroppedItems()) {
            platform.itemSnapshotMapper().markOwner(event.getItemDrop(), event.getPlayer());
        }
    }

    /** 玩家丢弃物被仙人掌、岩浆等损坏时尝试收回个人或公共垃圾桶。 */
    @EventHandler
    public void onItemDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Item)) {
            return;
        }
        Item item = (Item) entity;
        ItemSnapshot snapshot = platform.itemSnapshotMapper().toSnapshot(item.getItemStack());
        if (snapshot.getOwnerUuid() == null) {
            return;
        }
        ItemStack itemStack = item.getItemStack();
        if (trashRouter.route(item.getWorld(), snapshot.getOwnerUuid(), itemStack, TrashRoute.PERSONAL_TRASH)
                || trashRouter.route(item.getWorld(), snapshot.getOwnerUuid(), itemStack, TrashRoute.GLOBAL_TRASH)) {
            item.remove();
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

    /** 判断当前世界是否还能继续创建。 */
    private boolean canCreateMore(World world, TrashConfig.WorldTrashConfig worldConfig) {
        int current = trashRouter.getWorldTrashCount(world);
        int max = worldConfig.getDefaultMaxCount();
        return max <= 0 || current < max;
    }

    /** 如方块是已登记垃圾桶则删除登记。 */
    private void removeWorldTrashIfPresent(Player player, Block block) {
        if (block != null && trashRouter.isWorldTrashBlock(block) && trashRouter.removeWorldTrash(block)) {
            player.sendMessage(color("&a已移除该世界垃圾桶登记。"));
        }
    }

    /** 判断方块是否是可用容器。 */
    private boolean isContainer(Block block) {
        return block != null && block.getType() != Material.AIR && block.getState() instanceof InventoryHolder;
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
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
