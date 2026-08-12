package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProviderSelector;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemRuleEvaluator;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.TrashMutation;
import pixeltech.worldlisttrashcan.api.audit.TrashMutationReason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 公共垃圾桶服务，通用容器负责存储和菜单，本类只保留公共桶策略。 */
public final class GlobalTrashService {
    private final Plugin plugin;
    private final BukkitMessageService messages;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final GlobalTrashStore store;
    private final TrashContainerMenu containerMenu;
    private final ItemRuleEvaluator itemRuleEvaluator;
    private final ConcurrentHashMap<UUID, Long> lastTakeMillis = new ConcurrentHashMap<>();
    private TrashConfig.GlobalTrashConfig config;

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config,
                              BukkitMessageService messages) {
        this(plugin, config, messages, null, null, null,
                CustomModelDataSupport.unsupported(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config,
                              BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper) {
        this(plugin, config, messages, itemSnapshotMapper, null, null,
                CustomModelDataSupport.unsupported(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建带审计变更分发器的公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config,
                              BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper,
                              DefaultWorldListTrashCanAuditBridge auditBridge) {
        this(plugin, config, messages, itemSnapshotMapper, null, auditBridge,
                CustomModelDataSupport.unsupported(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建带平台调度和审计分发能力的公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config,
                              BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper,
                              ServerPlatform platform,
                              DefaultWorldListTrashCanAuditBridge auditBridge) {
        this(plugin, config, messages, itemSnapshotMapper, platform, auditBridge,
                CustomModelDataSupport.unsupported(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建带完整平台外观能力的公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config,
                              BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper,
                              ServerPlatform platform,
                              DefaultWorldListTrashCanAuditBridge auditBridge,
                              CustomModelDataSupport customModelDataSupport) {
        this.plugin = plugin;
        this.messages = messages;
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.auditBridge = auditBridge;
        ItemIdentityProvider identityProvider = new ItemIdentityProviderSelector().select(plugin);
        this.store = new GlobalTrashStore(identityProvider);
        CustomModelDataSupport modelDataSupport = customModelDataSupport == null
                ? CustomModelDataSupport.unsupported(plugin.getLogger()) : customModelDataSupport;
        this.containerMenu = new TrashContainerMenu(plugin, platform, itemSnapshotMapper,
                modelDataSupport, new GlobalMenuPolicy());
        this.itemRuleEvaluator = new ItemRuleEvaluator(itemSnapshotMapper);
        reload(config);
    }

    /** 重载显示、布局和容量配置，同时保留运行期存量。 */
    public void reload(TrashConfig.GlobalTrashConfig nextConfig) {
        this.config = nextConfig;
        containerMenu.reload(nextConfig);
        int contentSlots = containerMenu.getContentSlotsPerPage();
        store.configure(nextConfig, contentSlots);
        TrashConfig.GlobalTrashLayoutConfig layout = nextConfig == null ? null : nextConfig.getLayout();
        plugin.getLogger().info("[GlobalTrash] mode=" + modeName()
                + ", identity=" + store.getIdentityProviderId()
                + ", rows=" + (layout == null ? 0 : layout.getRows().size())
                + ", contentSlots=" + contentSlots
                + ", writablePages=" + configuredMaxPages()
                + ", visiblePages=" + store.getPageCount());
        logAdmissionCapabilities();
    }

    /** 判断公共垃圾桶是否可用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled()
                && !config.getLayout().getContentSlots().isEmpty();
    }

    /** 判断是否有容量完整放入指定物品。 */
    public boolean hasSpace(ItemStack itemStack) {
        if (!isEnabled()) {
            return false;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack)
                || config.isBannedMaterial(cleanItemStack.getType().name())
                || !isAdmissionAllowed(cleanItemStack)) {
            return false;
        }
        return store.hasSpace(cleanItemStack);
    }

    /** 判断指定物品至少有一个数量可以进入公共垃圾桶。 */
    public boolean hasAnySpace(ItemStack itemStack) {
        return checkCleanupAvailability(itemStack).isAvailable();
    }

    /** 一次完成扫地路由所需的公共桶准入和容量检查。 */
    public GlobalTrashCheck checkCleanupAvailability(ItemStack itemStack) {
        if (!isEnabled()) {
            return new GlobalTrashCheck(false, null);
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack)
                || config.isBannedMaterial(cleanItemStack.getType().name())) {
            return new GlobalTrashCheck(false, null);
        }
        if (!isAdmissionAllowed(cleanItemStack)) {
            return new GlobalTrashCheck(false,
                    config.getAdmissionWhitelist().getRejectedCleanupAction());
        }
        return new GlobalTrashCheck(store.hasAnySpace(cleanItemStack), null);
    }

    /** 向公共垃圾桶完整放入一个非扫地来源物品。 */
    public boolean addItem(ItemStack itemStack) {
        return addItem(itemStack, TrashMutationReason.NON_CLEANUP_DEPOSIT);
    }

    /** 放入扫地物品并返回实际接收数量和主存储条目追踪键。 */
    public TrashWriteResult addCleanupItem(ItemStack itemStack) {
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack) || config == null
                || config.isBannedMaterial(cleanItemStack.getType().name())
                || !isAdmissionAllowed(cleanItemStack)) {
            return TrashWriteResult.rejected();
        }
        return store.add(cleanItemStack,
                config.getMode() == TrashConfig.GlobalTrashMode.COMPACT);
    }

    /** 按来源完整放入公共模型并维护非清理审计账本。 */
    private boolean addItem(ItemStack itemStack, TrashMutationReason reason) {
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack) || config == null
                || config.isBannedMaterial(cleanItemStack.getType().name())
                || !isAdmissionAllowed(cleanItemStack)) {
            return false;
        }
        TrashWriteResult result = store.add(cleanItemStack, false);
        if (result.getStatus() != TrashWriteResult.Status.ACCEPTED_FULL) {
            return false;
        }
        if (hasAuditConsumer()) {
            recordMutation(TrashMutation.untrackedDeposit(
                    CleanupItemDestination.globalTrash(), cleanItemStack,
                    result.getTrackingKey(), result.getAcceptedAmount(), reason,
                    System.currentTimeMillis()));
        }
        return true;
    }

    /** 打开公共垃圾桶首页。 */
    public void open(Player player) {
        containerMenu.open(player, store);
    }

    /** 处理公共垃圾桶点击。 */
    public boolean handleClick(InventoryClickEvent event) {
        return containerMenu.handleClick(event);
    }

    /** 只允许普通左键或右键触发 actions 按钮。 */
    static boolean isNormalActionClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }

    /** 阻止拖拽修改公共垃圾桶菜单。 */
    public boolean handleDrag(InventoryDragEvent event) {
        return containerMenu.handleDrag(event);
    }

    /** 菜单关闭时释放公共桶视图。 */
    public boolean handleClose(InventoryCloseEvent event) {
        return containerMenu.handleClose(event);
    }

    /** 判断物品是否被已启用的公共桶白名单拒绝。 */
    public boolean isRejectedByAdmissionWhitelist(ItemStack itemStack) {
        return checkCleanupAvailability(itemStack).getRejectedCleanupAction() != null;
    }

    /** 返回公共桶白名单拒绝扫地物品后的动作。 */
    public RejectedCleanupAction getRejectedCleanupAction() {
        return config == null ? RejectedCleanupAction.KEEP_GROUND
                : config.getAdmissionWhitelist().getRejectedCleanupAction();
    }

    /** 玩家退出时释放公共桶排序偏好、视图和拿取冷却。 */
    public void handleQuit(UUID playerId) {
        containerMenu.handleQuit(playerId);
        if (playerId != null) {
            lastTakeMillis.remove(playerId);
        }
    }

    /** 插件关闭时释放公共桶视图和冷却记录。 */
    public void close() {
        containerMenu.close();
        lastTakeMillis.clear();
    }

    /** 清空所有公共垃圾桶模型存量。 */
    public void clearContent() {
        containerMenu.close();
        store.clear();
        recordMutation(TrashMutation.clear(CleanupItemDestination.globalTrash(),
                TrashMutationReason.GLOBAL_REFRESH, System.currentTimeMillis()));
    }

    /** 返回公共垃圾桶页数。 */
    public int getPageCount() {
        return store.getPageCount();
    }

    /** 返回公共垃圾桶物品总数量。 */
    public int getStoredItemAmount() {
        return store.getStoredItemAmount();
    }

    /** 返回公共垃圾桶当前模式的展示堆叠数量。 */
    public int getStoredStackCount() {
        return store.getStoredStackCount();
    }

    /** 返回启动时固定的物品身份实现，供个人桶复用。 */
    public ItemIdentityProvider getIdentityProvider() {
        return store.getIdentityProvider();
    }

    /** 判断物品是否通过公共垃圾桶准入白名单。 */
    private boolean isAdmissionAllowed(ItemStack itemStack) {
        if (config == null || !config.getAdmissionWhitelist().isEnabled()) {
            return true;
        }
        ItemSnapshot snapshot = itemSnapshot(itemStack);
        return itemRuleEvaluator.matches(
                config.getAdmissionWhitelist().getRules(), snapshot, itemStack);
    }

    /** 生成公共桶准入匹配所需的轻量物品快照。 */
    private ItemSnapshot itemSnapshot(ItemStack itemStack) {
        if (itemSnapshotMapper != null) {
            return itemSnapshotMapper.toSnapshot(itemStack);
        }
        ItemMeta meta = itemStack == null ? null : itemStack.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
        List<String> lore = meta != null && meta.hasLore()
                ? meta.getLore() : Collections.<String>emptyList();
        return new ItemSnapshot(itemStack == null ? "" : itemStack.getType().name(),
                itemStack == null ? 0 : itemStack.getAmount(), name, lore, null);
    }

    /** 输出公共桶白名单读取能力警告。 */
    private void logAdmissionCapabilities() {
        if (config == null || !config.getAdmissionWhitelist().isEnabled()) {
            return;
        }
        if (config.getAdmissionWhitelist().getRules().requiresPdcKeys()
                && !itemRuleEvaluator.isPdcReady()) {
            plugin.getLogger().warning("[GlobalTrash] admission-whitelist 需要 PDC，但当前运行时不可用: "
                    + itemRuleEvaluator.getPdcFailureReason());
        }
        if (config.getAdmissionWhitelist().getRules().requiresNbtKeys()
                && !itemRuleEvaluator.isNbtReady()) {
            plugin.getLogger().warning("[GlobalTrash] admission-whitelist 需要 Raw NBT，但当前运行时不可用: "
                    + itemRuleEvaluator.getNbtFailureReason());
        }
    }

    /** 判断玩家是否拥有公共垃圾桶操作权限，并保留 OP 旁路。 */
    private boolean hasTrashPermission(Player player, String permission) {
        return player != null && (player.isOp() || player.hasPermission(permission));
    }

    /** 判断玩家是否处于公共桶拿取冷却。 */
    private boolean isCoolingDown(Player player) {
        long delay = config == null ? 0L : config.getTakeDelayMillis();
        if (delay <= 0L) {
            return false;
        }
        Long lastValue = lastTakeMillis.get(player.getUniqueId());
        long remain = (lastValue == null ? 0L : lastValue.longValue()) + delay
                - System.currentTimeMillis();
        if (remain <= 0L) {
            return false;
        }
        player.sendMessage(message("global-trash.take-cooldown",
                "&c公共垃圾桶拿取冷却剩余 {time} 秒。",
                "{time}", String.valueOf(Math.max(1L, remain / 100L) / 10D)));
        return true;
    }

    /** 清理插件内部物品标记后用于入库。 */
    private ItemStack sanitize(ItemStack itemStack) {
        return itemSnapshotMapper == null ? itemStack
                : itemSnapshotMapper.sanitizeForStorage(itemStack);
    }

    /** 记录玩家从公共垃圾桶实际取出的数量。 */
    private void recordTake(Player player, ItemStack itemStack,
                            String trackingKey, int amount) {
        if (!hasAuditConsumer()) {
            return;
        }
        recordMutation(TrashMutation.take(CleanupItemDestination.globalTrash(), itemStack,
                trackingKey, amount, player.getUniqueId(), player.getName(),
                System.currentTimeMillis()));
    }

    /** 在附属存在时转发变更；没有附属时保持空操作。 */
    private void recordMutation(TrashMutation mutation) {
        if (hasAuditConsumer()) {
            auditBridge.recordTrashMutation(mutation);
        }
    }

    /** 返回当前是否存在活动审计消费者。 */
    private boolean hasAuditConsumer() {
        return auditBridge != null && auditBridge.hasActiveConsumer();
    }

    /** 返回当前公共垃圾桶模式名称。 */
    private String modeName() {
        return config == null || config.getMode() == null
                ? "disabled" : config.getMode().name().toLowerCase();
    }

    /** 返回当前模式正常可写页数。 */
    private int configuredMaxPages() {
        return config == null ? 0 : config.getMaxPages();
    }

    /** 写入公共垃圾桶玩家操作日志。 */
    private void logGlobalTrash(Player player, String action,
                                ItemStack itemStack, int amount) {
        if (config == null || !config.isLogEnabled()
                || itemStack == null || amount <= 0) {
            return;
        }
        Date now = new Date();
        String day = new SimpleDateFormat("yyyy-MM-dd").format(now);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now);
        String line = time + " " + player.getName() + " " + action + " "
                + itemStack.getType().name() + "x" + amount;
        Path logDir = plugin.getDataFolder().toPath().resolve("logs");
        Path logFile = logDir.resolve("global-trash-" + day + ".log");
        try {
            Files.createDirectories(logDir);
            Files.write(logFile, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("[GlobalTrash] 写入公共垃圾桶日志失败: "
                    + exception.getMessage());
        }
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? RichTextRenderer.color(fallback)
                : messages.text(key, fallback, replacements);
    }

    /** 公共垃圾桶专属菜单策略。 */
    private final class GlobalMenuPolicy implements TrashContainerMenuPolicy {
        /** 返回公共桶后台日志作用域。 */
        @Override
        public String getLogName() {
            return "GlobalTrash";
        }

        /** 返回公共桶分页标题。 */
        @Override
        public String getTitle(Player player, int pageIndex, int maxPages) {
            return GlobalTrashService.this.message(
                    "global-trash.gui.title", "&8公共垃圾桶 {page}/{max}",
                    "{page}", String.valueOf(pageIndex + 1),
                    "{max}", String.valueOf(maxPages));
        }

        /** 发送公共桶关闭提示。 */
        @Override
        public void sendDisabled(Player player) {
            player.sendMessage(GlobalTrashService.this.message(
                    "global-trash.disabled", "&c公共垃圾桶未启用。"));
        }

        /** 判断公共桶取物权限。 */
        @Override
        public boolean canTake(Player player) {
            if (hasTrashPermission(player, "WorldListTrashCan.GlobalTrashTakeItem")) {
                return true;
            }
            player.sendMessage(GlobalTrashService.this.message(
                    "global-trash.no-take-permission",
                    "&c你没有权限从公共垃圾桶取出物品。"));
            return false;
        }

        /** 执行公共桶取物冷却。 */
        @Override
        public boolean beforeTake(Player player, ItemStack itemStack, int requestedAmount) {
            return !isCoolingDown(player);
        }

        /** 发送公共桶背包空间不足提示。 */
        @Override
        public void onTakeInventoryFull(Player player) {
            player.sendMessage(GlobalTrashService.this.message(
                    "global-trash.inventory-full",
                    "&c背包空间不足，无法取出该物品。"));
        }

        /** 记录公共桶取物日志和审计，并更新冷却时间。 */
        @Override
        public void afterTake(Player player, ItemStack itemStack,
                              String trackingKey, int removedAmount) {
            lastTakeMillis.put(player.getUniqueId(), System.currentTimeMillis());
            logGlobalTrash(player, "-global", itemStack, removedAmount);
            recordTake(player, itemStack, trackingKey, removedAmount);
        }

        /** 判断公共桶手动放入权限和准入规则。 */
        @Override
        public boolean canManualPut(Player player, ItemStack itemStack) {
            if (!hasTrashPermission(player, "WorldListTrashCan.GlobalTrashPutItem")) {
                return false;
            }
            if (config == null || config.isBannedMaterial(itemStack.getType().name())) {
                return false;
            }
            if (isAdmissionAllowed(sanitize(itemStack))) {
                return true;
            }
            player.sendMessage(GlobalTrashService.this.message(
                    "global-trash.admission-rejected",
                    "&c该物品不在公共垃圾桶准入白名单中。"));
            return false;
        }

        /** 记录公共桶手动存入日志和审计。 */
        @Override
        public void afterManualPut(Player player, ItemStack itemStack,
                                   String trackingKey, int acceptedAmount) {
            logGlobalTrash(player, "+global", itemStack, acceptedAmount);
            if (hasAuditConsumer()) {
                recordMutation(TrashMutation.untrackedDeposit(
                        CleanupItemDestination.globalTrash(), itemStack,
                        trackingKey, acceptedAmount,
                        TrashMutationReason.MANUAL_DEPOSIT,
                        System.currentTimeMillis()));
            }
        }

        /** 读取公共桶语言键。 */
        @Override
        public String message(String suffix, String fallback, String... replacements) {
            return GlobalTrashService.this.message(
                    "global-trash." + suffix, fallback, replacements);
        }
    }
}
