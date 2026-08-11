package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.SafeMaterialMatcher;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 公共垃圾桶 GUI 和路由服务，内部存储与 Bukkit Inventory 完全分离。 */
public final class GlobalTrashService {
    private final Plugin plugin;
    private final BukkitMessageService messages;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final ServerPlatform platform;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final GlobalTrashTextResolver textResolver;
    private final GlobalTrashActionExecutor actionExecutor;
    private final GlobalTrashStore store;
    private final ItemRuleEvaluator itemRuleEvaluator;
    private final Map<UUID, Long> lastTakeMillis = new ConcurrentHashMap<>();
    private final Map<Character, Material> layoutMaterials = new HashMap<>();
    private final Map<UUID, GlobalTrashViewHolder> activeViews = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSortPreferences> sortPreferences = new ConcurrentHashMap<>();
    private TrashConfig.GlobalTrashConfig config;
    private TrashConfig.GlobalTrashLayoutConfig layout;

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config, BukkitMessageService messages) {
        this(plugin, config, messages, null, null, null);
    }

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config, BukkitMessageService messages,
                              ItemSnapshotMapper itemSnapshotMapper) {
        this(plugin, config, messages, itemSnapshotMapper, null, null);
    }

    /** 创建带审计变更分发器的公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config, BukkitMessageService messages,
                              ItemSnapshotMapper itemSnapshotMapper,
                              DefaultWorldListTrashCanAuditBridge auditBridge) {
        this(plugin, config, messages, itemSnapshotMapper, null, auditBridge);
    }

    /** 创建带平台调度和审计分发能力的公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config, BukkitMessageService messages,
                              ItemSnapshotMapper itemSnapshotMapper, ServerPlatform platform,
                              DefaultWorldListTrashCanAuditBridge auditBridge) {
        this.plugin = plugin;
        this.messages = messages;
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.platform = platform;
        this.auditBridge = auditBridge;
        this.textResolver = new GlobalTrashTextResolver(plugin);
        this.actionExecutor = new GlobalTrashActionExecutor(plugin, platform, textResolver);
        ItemIdentityProvider identityProvider = new ItemIdentityProviderSelector().select(plugin);
        this.store = new GlobalTrashStore(identityProvider);
        this.itemRuleEvaluator = new ItemRuleEvaluator(itemSnapshotMapper);
        reload(config);
    }

    /** 重载显示配置和布局，但保留模型存量。 */
    public void reload(TrashConfig.GlobalTrashConfig nextConfig) {
        closeCurrentViewers();
        this.config = nextConfig;
        this.layout = nextConfig == null
                ? TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null)
                : nextConfig.getLayout();
        layoutMaterials.clear();
        if (layout.getValidationError() != null) {
            plugin.getLogger().warning("[GlobalTrash] 公共垃圾桶布局无效，已回退默认布局: "
                    + layout.getValidationError());
        }
        store.configure(nextConfig, layout.getContentSlots().size());
        resolveLayoutMaterials();
        validateLayoutActions();
        plugin.getLogger().info("[GlobalTrash] mode=" + modeName()
                + ", identity=" + store.getIdentityProviderId()
                + ", rows=" + layout.getRows().size()
                + ", slots=" + layout.getInventorySize()
                + ", contentSlots=" + layout.getContentSlots().size()
                + ", writablePages=" + configuredMaxPages()
                + ", visiblePages=" + store.getPageCount());
        logAdmissionCapabilities();
    }

    /** 判断公共垃圾桶是否可用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled() && !layout.getContentSlots().isEmpty();
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
            return new GlobalTrashCheck(false, config.getAdmissionWhitelist().getRejectedCleanupAction());
        }
        return new GlobalTrashCheck(store.hasAnySpace(cleanItemStack), null);
    }

    /** 向公共垃圾桶完整放入物品。 */
    public boolean addItem(ItemStack itemStack) {
        return addItem(itemStack, false, TrashMutationReason.NON_CLEANUP_DEPOSIT);
    }

    /** 放入扫地物品并返回实际接收数量和主存储条目追踪键。 */
    public TrashWriteResult addCleanupItem(ItemStack itemStack) {
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack) || config == null
                || config.isBannedMaterial(cleanItemStack.getType().name())
                || !isAdmissionAllowed(cleanItemStack)) {
            return TrashWriteResult.rejected();
        }
        return store.add(cleanItemStack, config.getMode() == TrashConfig.GlobalTrashMode.COMPACT);
    }

    /** 按来源完整放入模型，并维护非审计来源的变更账本。 */
    private boolean addItem(ItemStack itemStack, boolean cleanupSource, TrashMutationReason reason) {
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack) || config == null
                || config.isBannedMaterial(cleanItemStack.getType().name())
                || !isAdmissionAllowed(cleanItemStack)) {
            return false;
        }
        TrashWriteResult result = store.add(cleanItemStack, false);
        if (result.getAcceptedAmount() != cleanItemStack.getAmount()) {
            return false;
        }
        if (!cleanupSource && hasAuditConsumer()) {
            recordMutation(TrashMutation.untrackedDeposit(CleanupItemDestination.globalTrash(),
                    cleanItemStack, result.getTrackingKey(), result.getAcceptedAmount(),
                    reason, System.currentTimeMillis()));
        }
        return true;
    }

    /** 打开公共垃圾桶首页。 */
    public void open(Player player) {
        if (!isEnabled()) {
            player.sendMessage(message("global-trash.disabled", "&c公共垃圾桶未启用。"));
            return;
        }
        TrashConfig.GlobalTrashSortType sortType = sortPreference(player.getUniqueId(), config.getMode());
        openPage(player, 0, store.createViewSnapshot(sortType));
    }

    /** 处理公共垃圾桶点击。 */
    public boolean handleClick(InventoryClickEvent event) {
        GlobalTrashViewHolder holder = viewHolder(event.getInventory());
        if (holder == null) {
            return false;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return true;
        }
        Player player = (Player) event.getWhoClicked();
        int pageIndex = holder.getPageIndex();
        GlobalTrashStore.ViewSnapshot snapshot = holder.getSnapshot();
        if (snapshot == null || pageIndex < 0 || pageIndex >= snapshot.getPageCount()) {
            player.closeInventory();
            return true;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < layout.getInventorySize()) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(rawSlot);
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE && pageIndex > 0) {
                openPage(player, pageIndex - 1, snapshot);
                return true;
            }
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE
                    && pageIndex < snapshot.getPageCount() - 1) {
                openPage(player, pageIndex + 1, snapshot);
                return true;
            }
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.ACTIONS
                    && isNormalActionClick(event.getClick())) {
                actionExecutor.execute(player, item.getActions(), pageIndex, snapshot.getPageCount());
                return true;
            }
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.SORT
                    && isNormalActionClick(event.getClick())) {
                changeSort(player, snapshot.getSortType(), event.getClick());
                return true;
            }
            if (layout.isContentSlot(rawSlot)) {
                int contentIndex = contentIndex(rawSlot);
                if (contentIndex >= 0) {
                    takeItem(player, holder, contentIndex, event.getClick());
                    syncContentSlot(holder, rawSlot);
                }
            }
            return true;
        }
        if (rawSlot >= layout.getInventorySize() && config.isAllowPlayerPut()) {
            putFromPlayer(player, event);
        }
        return true;
    }

    /** 为玩家创建当前模型内容的独立展示视图。 */
    private void openPage(Player player, int requestedPage, GlobalTrashStore.ViewSnapshot snapshot) {
        int pageCount = snapshot == null ? 1 : snapshot.getPageCount();
        int pageIndex = Math.max(0, Math.min(requestedPage, pageCount - 1));
        GlobalTrashViewHolder holder = new GlobalTrashViewHolder(
                this, player.getUniqueId(), pageIndex, snapshot);
        Inventory inventory = createViewPage(player, holder, pageIndex);
        holder.bind(inventory);
        activeViews.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    /** 创建带玩家变量渲染的单页视图，不复制公共模型。 */
    private Inventory createViewPage(Player player, GlobalTrashViewHolder holder, int pageIndex) {
        GlobalTrashStore.ViewSnapshot snapshot = holder.getSnapshot();
        int maxPages = snapshot == null ? 1 : snapshot.getPageCount();
        Inventory inventory = Bukkit.createInventory(holder, layout.getInventorySize(),
                message("global-trash.gui.title", "&8公共垃圾桶 {page}/{max}",
                        "{page}", String.valueOf(pageIndex + 1), "{max}", String.valueOf(maxPages)));
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null) {
                continue;
            }
            if (item.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                int contentIndex = contentIndex(slot);
                GlobalTrashStore.DisplayReference reference = snapshot == null
                        ? null : snapshot.getReference(pageIndex, contentIndex);
                GlobalTrashStore.DisplayItem display = store.getDisplayItem(reference);
                if (display != null) {
                    inventory.setItem(slot, createContentItem(display, player, pageIndex, maxPages));
                }
                continue;
            }
            TrashConfig.GlobalTrashItemConfig displayItem = visibleItem(item, pageIndex, maxPages);
            if (displayItem != null) {
                inventory.setItem(slot, createLayoutItem(displayItem, pageIndex, maxPages, player,
                        snapshot == null ? TrashConfig.GlobalTrashSortType.INSERTION : snapshot.getSortType()));
            }
        }
        return inventory;
    }

    /** 从 Bukkit 库存识别属于本服务的玩家视图。 */
    private GlobalTrashViewHolder viewHolder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof GlobalTrashViewHolder)) {
            return null;
        }
        GlobalTrashViewHolder view = (GlobalTrashViewHolder) holder;
        return view.belongsTo(this) ? view : null;
    }

    /** 只同步当前玩家刚操作的内容槽，不压缩或重排其它槽位。 */
    private void syncContentSlot(GlobalTrashViewHolder holder, int rawSlot) {
        if (holder == null || holder.getInventory() == null || !layout.isContentSlot(rawSlot)) {
            return;
        }
        Player player = Bukkit.getPlayer(holder.getPlayerId());
        GlobalTrashStore.ViewSnapshot snapshot = holder.getSnapshot();
        if (player == null || snapshot == null) {
            return;
        }
        int contentIndex = contentIndex(rawSlot);
        GlobalTrashStore.DisplayReference reference = snapshot.getReference(holder.getPageIndex(), contentIndex);
        GlobalTrashStore.DisplayItem display = store.getDisplayItem(reference);
        holder.getInventory().setItem(rawSlot, display == null ? null
                : createContentItem(display, player, holder.getPageIndex(), snapshot.getPageCount()));
    }

    /** 切换当前模式的玩家排序偏好，并显式创建一个新快照。 */
    private void changeSort(Player player, TrashConfig.GlobalTrashSortType current, ClickType clickType) {
        TrashConfig.GlobalTrashSortType next = clickType == ClickType.RIGHT
                ? current.previous() : current.next();
        preferences(player.getUniqueId()).set(config.getMode(), next);
        openPage(player, 0, store.createViewSnapshot(next));
    }

    /** 返回玩家当前模式排序偏好，没有缓存时使用该模式自己的默认值。 */
    private TrashConfig.GlobalTrashSortType sortPreference(UUID playerId, TrashConfig.GlobalTrashMode mode) {
        PlayerSortPreferences preferences = sortPreferences.get(playerId);
        TrashConfig.GlobalTrashSortType cached = preferences == null ? null : preferences.get(mode);
        if (cached != null) {
            return cached;
        }
        return mode == TrashConfig.GlobalTrashMode.STACKED
                ? config.getStacked().getDefaultSort() : config.getCompact().getDefaultSort();
    }

    /** 返回或创建玩家的两个轻量模式偏好槽。 */
    private PlayerSortPreferences preferences(UUID playerId) {
        PlayerSortPreferences existing = sortPreferences.get(playerId);
        if (existing != null) {
            return existing;
        }
        PlayerSortPreferences created = new PlayerSortPreferences();
        PlayerSortPreferences raced = sortPreferences.putIfAbsent(playerId, created);
        return raced == null ? created : raced;
    }

    /** 只允许普通左键或右键触发动作按钮。 */
    static boolean isNormalActionClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }

    /** 阻止拖拽修改公共垃圾桶 GUI 的内容槽或展示物。 */
    public boolean handleDrag(InventoryDragEvent event) {
        if (viewHolder(event.getInventory()) == null) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /** 菜单关闭时释放玩家视图引用。 */
    public boolean handleClose(InventoryCloseEvent event) {
        GlobalTrashViewHolder holder = viewHolder(event.getInventory());
        if (holder == null) {
            return false;
        }
        activeViews.remove(holder.getPlayerId(), holder);
        return true;
    }

    /** 判断物品是否被已启用的公共桶白名单拒绝。 */
    public boolean isRejectedByAdmissionWhitelist(ItemStack itemStack) {
        return checkCleanupAvailability(itemStack).getRejectedCleanupAction() != null;
    }

    /** 返回公共桶白名单拒绝扫地物品后的动作。 */
    public RejectedCleanupAction getRejectedCleanupAction() {
        return config == null
                ? RejectedCleanupAction.KEEP_GROUND
                : config.getAdmissionWhitelist().getRejectedCleanupAction();
    }

    /** 判断物品是否通过公共垃圾桶准入白名单。 */
    private boolean isAdmissionAllowed(ItemStack itemStack) {
        if (config == null || !config.getAdmissionWhitelist().isEnabled()) {
            return true;
        }
        ItemSnapshot snapshot = itemSnapshot(itemStack);
        return itemRuleEvaluator.matches(config.getAdmissionWhitelist().getRules(), snapshot, itemStack);
    }

    /** 生成公共桶准入匹配所需的轻量物品快照。 */
    private ItemSnapshot itemSnapshot(ItemStack itemStack) {
        if (itemSnapshotMapper != null) {
            return itemSnapshotMapper.toSnapshot(itemStack);
        }
        ItemMeta meta = itemStack == null ? null : itemStack.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : Collections.<String>emptyList();
        return new ItemSnapshot(itemStack == null ? "" : itemStack.getType().name(),
                itemStack == null ? 0 : itemStack.getAmount(), name, lore, null);
    }

    /** 输出公共桶白名单读取能力，配置需要但运行时不可用时给出明确警告。 */
    private void logAdmissionCapabilities() {
        if (config == null || !config.getAdmissionWhitelist().isEnabled()) {
            return;
        }
        if (config.getAdmissionWhitelist().getRules().requiresPdcKeys() && !itemRuleEvaluator.isPdcReady()) {
            plugin.getLogger().warning("[GlobalTrash] admission-whitelist 需要 PDC，但当前运行时不可用: "
                    + itemRuleEvaluator.getPdcFailureReason());
        }
        if (config.getAdmissionWhitelist().getRules().requiresNbtKeys() && !itemRuleEvaluator.isNbtReady()) {
            plugin.getLogger().warning("[GlobalTrash] admission-whitelist 需要 Raw NBT，但当前运行时不可用: "
                    + itemRuleEvaluator.getNbtFailureReason());
        }
    }

    /** 玩家退出时释放排序偏好、打开视图和拿取冷却。 */
    public void handleQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }
        activeViews.remove(playerId);
        sortPreferences.remove(playerId);
        lastTakeMillis.remove(playerId);
    }

    /** 插件关闭时释放公共垃圾桶视图和冷却记录。 */
    public void close() {
        closeCurrentViewers();
        activeViews.clear();
        sortPreferences.clear();
        lastTakeMillis.clear();
    }

    /** 清空所有公共垃圾桶模型存量。 */
    public void clearContent() {
        closeCurrentViewers();
        store.clear();
        recordMutation(TrashMutation.clear(CleanupItemDestination.globalTrash(),
                TrashMutationReason.GLOBAL_REFRESH, System.currentTimeMillis()));
    }

    /** 返回公共垃圾桶页数。 */
    public int getPageCount() {
        return store.getPageCount();
    }

    /** 返回公共垃圾桶内容物品总数量。 */
    public int getStoredItemAmount() {
        return store.getStoredItemAmount();
    }

    /** 返回公共垃圾桶内容堆叠数量。 */
    public int getStoredStackCount() {
        return store.getStoredStackCount();
    }

    /** 返回启动时固定的物品身份实现，供个人虚拟垃圾桶生成同源追踪键。 */
    public ItemIdentityProvider getIdentityProvider() {
        return store.getIdentityProvider();
    }

    /** 玩家从模型公共垃圾桶取出指定展示条目。 */
    private void takeItem(Player player, GlobalTrashViewHolder holder,
                          int contentIndex, ClickType clickType) {
        if (!hasTrashPermission(player, "WorldListTrashCan.GlobalTrashTakeItem")) {
            player.sendMessage(message("global-trash.no-take-permission", "&c你没有权限从公共垃圾桶取出物品。"));
            return;
        }
        if (isCoolingDown(player)) {
            return;
        }
        synchronized (store) {
            GlobalTrashStore.ViewSnapshot snapshot = holder.getSnapshot();
            GlobalTrashStore.DisplayReference reference = snapshot == null ? null
                    : snapshot.getReference(holder.getPageIndex(), contentIndex);
            GlobalTrashStore.DisplayItem display = store.getDisplayItem(reference);
            if (display == null) {
                return;
            }
            int requested = takeAmount(clickType, display);
            if (requested <= 0) {
                return;
            }
            int actualRequested = (int) Math.min((long) requested, display.getLogicalAmount());
            ItemStack itemStack = display.getSample();
            itemStack.setAmount(actualRequested);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
            int moved = actualRequested - leftoverAmount(leftovers);
            if (moved <= 0) {
                return;
            }
            int removed = store.remove(display.getEntryId(), moved);
            if (removed <= 0) {
                return;
            }
            lastTakeMillis.put(player.getUniqueId(), System.currentTimeMillis());
            itemStack.setAmount(removed);
            logGlobalTrash(player, "-global", itemStack, removed);
            recordTake(player, itemStack, display.getTrackingKey(), removed);
        }
    }

    /** 根据当前显示模式和鼠标操作计算取出数量。 */
    private int takeAmount(ClickType clickType, GlobalTrashStore.DisplayItem display) {
        if (config.getMode() == TrashConfig.GlobalTrashMode.STACKED) {
            return display.getDisplayAmount();
        }
        TrashConfig.CompactGlobalTrashConfig compact = config.getCompact();
        if (clickType == ClickType.LEFT) {
            return compact.getLeftClickAmount();
        }
        if (clickType == ClickType.SHIFT_LEFT) {
            return compact.getShiftLeftClickAmount();
        }
        if (!compact.isRightClickEnabled()) {
            return 0;
        }
        if (clickType == ClickType.RIGHT) {
            return compact.getRightClickAmount();
        }
        if (clickType == ClickType.SHIFT_RIGHT) {
            return compact.getShiftRightClickAmount();
        }
        return 0;
    }

    /** 玩家手动把背包物品尽可能放入公共垃圾桶。 */
    private void putFromPlayer(Player player, InventoryClickEvent event) {
        if (!hasTrashPermission(player, "WorldListTrashCan.GlobalTrashPutItem")) {
            return;
        }
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || event.getSlot() < 0 || event.getSlot() >= clickedInventory.getSize()) {
            return;
        }
        ItemStack itemStack = clickedInventory.getItem(event.getSlot());
        if (InventorySlotUtil.isEmpty(itemStack) || config == null
                || config.isBannedMaterial(itemStack.getType().name())) {
            return;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (!isAdmissionAllowed(cleanItemStack)) {
            player.sendMessage(message("global-trash.admission-rejected",
                    "&c该物品不在公共垃圾桶准入白名单中。"));
            return;
        }
        TrashWriteResult result = store.add(cleanItemStack, true);
        int accepted = result.getAcceptedAmount();
        if (!result.isAccepted()) {
            return;
        }
        int remaining = itemStack.getAmount() - accepted;
        if (remaining <= 0) {
            clickedInventory.setItem(event.getSlot(), null);
        } else {
            ItemStack remainder = itemStack.clone();
            remainder.setAmount(remaining);
            clickedInventory.setItem(event.getSlot(), remainder);
        }
        cleanItemStack.setAmount(accepted);
        logGlobalTrash(player, "+global", cleanItemStack, accepted);
        if (hasAuditConsumer()) {
            recordMutation(TrashMutation.untrackedDeposit(CleanupItemDestination.globalTrash(),
                    cleanItemStack, result.getTrackingKey(), accepted,
                    TrashMutationReason.MANUAL_DEPOSIT, System.currentTimeMillis()));
        }
    }

    /** 判断玩家是否拥有公共垃圾桶操作权限，并保留 OP 旁路。 */
    private boolean hasTrashPermission(Player player, String permission) {
        return player != null && (player.isOp() || player.hasPermission(permission));
    }

    /** 判断玩家是否处于取出冷却。 */
    private boolean isCoolingDown(Player player) {
        long delay = config.getTakeDelayMillis();
        if (delay <= 0L) {
            return false;
        }
        long last = lastTakeMillis.containsKey(player.getUniqueId()) ? lastTakeMillis.get(player.getUniqueId()) : 0L;
        long remain = last + delay - System.currentTimeMillis();
        if (remain <= 0L) {
            return false;
        }
        player.sendMessage(message("global-trash.take-cooldown", "&c公共垃圾桶拿取冷却剩余 {time} 秒。",
                "{time}", String.valueOf(Math.max(1L, remain / 100L) / 10D)));
        return true;
    }

    /** 清理插件内部物品标记后用于入库。 */
    private ItemStack sanitize(ItemStack itemStack) {
        return itemSnapshotMapper == null ? itemStack : itemSnapshotMapper.sanitizeForStorage(itemStack);
    }

    /** 返回遗留物品总数量。 */
    private int leftoverAmount(Map<Integer, ItemStack> leftovers) {
        int amount = 0;
        if (leftovers == null) {
            return 0;
        }
        for (ItemStack leftover : leftovers.values()) {
            if (!InventorySlotUtil.isEmpty(leftover)) {
                amount += leftover.getAmount();
            }
        }
        return amount;
    }

    /** 记录玩家从公共垃圾桶实际取出的数量。 */
    private void recordTake(Player player, ItemStack itemStack, String trackingKey, int amount) {
        if (!hasAuditConsumer()) {
            return;
        }
        recordMutation(TrashMutation.take(CleanupItemDestination.globalTrash(), itemStack,
                trackingKey, amount, player.getUniqueId(), player.getName(), System.currentTimeMillis()));
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

    /** 返回当前页面应显示的按钮或不可用替代物。 */
    private TrashConfig.GlobalTrashItemConfig visibleItem(TrashConfig.GlobalTrashItemConfig item,
                                                           int pageIndex, int maxPages) {
        boolean unavailable = item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE && pageIndex <= 0
                || item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE && pageIndex >= maxPages - 1;
        if (!unavailable) {
            return item;
        }
        Character unavailableItem = item.getUnavailableItem();
        return unavailableItem == null ? null : layout.getItem(unavailableItem.charValue());
    }

    /** 解析全部展示物材质，避免每次创建页面重复匹配。 */
    private void resolveLayoutMaterials() {
        Set<Character> resolved = new HashSet<>();
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null || item.getType() == TrashConfig.GlobalTrashItemType.CONTENT
                    || !resolved.add(Character.valueOf(item.getSymbol()))) {
                continue;
            }
            Material material = SafeMaterialMatcher.first(item.getMaterials());
            if (material == null) {
                material = Material.STONE;
                plugin.getLogger().warning("[GlobalTrash] 布局字符 '" + item.getSymbol()
                        + "' 的 material 候选在当前版本全部无效，已降级为 STONE。");
            }
            layoutMaterials.put(Character.valueOf(item.getSymbol()), material);
        }
    }

    /** 校验 actions 按钮，避免未知前缀只能等到玩家点击时才暴露。 */
    private void validateLayoutActions() {
        Set<Character> validated = new HashSet<>();
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null || item.getType() != TrashConfig.GlobalTrashItemType.ACTIONS
                    || !validated.add(Character.valueOf(item.getSymbol()))) {
                continue;
            }
            actionExecutor.validate(item.getSymbol(), item.getActions());
        }
    }

    /** 返回展示物名称，未覆盖时读取对应类型的语言名称。 */
    private String itemName(TrashConfig.GlobalTrashItemConfig item) {
        if (item.getName() != null) {
            return item.getName();
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE) {
            return message("global-trash.gui.back", "&a上一页");
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE) {
            return message("global-trash.gui.next", "&a下一页");
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.SORT) {
            return message("global-trash.gui.sort.name", "&#FFD166排序方式");
        }
        return " ";
    }

    /** 返回布局物品 Lore，排序按钮缺省时使用语言文件说明。 */
    private List<String> itemLore(TrashConfig.GlobalTrashItemConfig item) {
        if (!item.getLore().isEmpty() || item.getType() != TrashConfig.GlobalTrashItemType.SORT) {
            return item.getLore();
        }
        List<String> lore = new ArrayList<>();
        lore.add(message("global-trash.gui.sort.current", "&#C9D4E2当前：&#FFD166{sort}"));
        lore.add(message("global-trash.gui.sort.next", "&#5AC8FA左键 &#C9D4E2切换为 &#FFD166{next-sort}"));
        lore.add(message("global-trash.gui.sort.previous", "&#5AC8FA右键 &#C9D4E2切换为 &#FFD166{previous-sort}"));
        return lore;
    }

    /** 创建紧凑或旧堆叠模式的物品展示。 */
    private ItemStack createContentItem(GlobalTrashStore.DisplayItem display, Player player,
                                        int pageIndex, int maxPages) {
        ItemStack itemStack = display.getSample();
        if (config.getMode() == TrashConfig.GlobalTrashMode.STACKED) {
            itemStack.setAmount(display.getDisplayAmount());
            return itemStack;
        }
        itemStack.setAmount(1);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        TrashConfig.CompactGlobalTrashConfig compact = config.getCompact();
        List<String> originalLore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore()) : Collections.<String>emptyList();
        List<String> lore = new ArrayList<>();
        if (compact.isShowAmountLore()) {
            lore.add(resolveContentText(player, compact.getAmountLore(), pageIndex, maxPages,
                    display, 0));
        }
        int maxOriginal = compact.getMaxOriginalLoreLines();
        int visibleOriginal = maxOriginal < 0 ? originalLore.size() : Math.min(maxOriginal, originalLore.size());
        for (int index = 0; index < visibleOriginal; index++) {
            lore.add(resolveContentText(player, originalLore.get(index), pageIndex, maxPages,
                    display, 0));
        }
        if (maxOriginal >= 0 && originalLore.size() > visibleOriginal) {
            lore.add(resolveContentText(player, compact.getOmittedLore(), pageIndex, maxPages,
                    display, originalLore.size() - visibleOriginal));
        }
        for (String line : compact.getActionLore()) {
            lore.add(resolveContentText(player, line, pageIndex, maxPages, display, 0));
        }
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        if (meta.hasDisplayName()) {
            meta.setDisplayName(resolveContentText(player, meta.getDisplayName(), pageIndex, maxPages,
                    display, 0));
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    /** 替换紧凑模式展示物数量和操作占位符，再执行玩家变量解析。 */
    private String resolveContentText(Player player, String text, int pageIndex, int maxPages,
                                      GlobalTrashStore.DisplayItem display, int omittedCount) {
        String resolved = text == null ? "" : text;
        TrashConfig.CompactGlobalTrashConfig compact = config.getCompact();
        resolved = resolved.replace("{amount}", String.valueOf(display.getLogicalAmount()))
                .replace("{take-amount}", String.valueOf(compact.getLeftClickAmount()))
                .replace("{shift-take-amount}", String.valueOf(compact.getShiftLeftClickAmount()))
                .replace("{right-take-amount}", String.valueOf(compact.getRightClickAmount()))
                .replace("{shift-right-take-amount}", String.valueOf(compact.getShiftRightClickAmount()))
                .replace("{count}", String.valueOf(omittedCount));
        return renderColor(player, textResolver.resolve(player, resolved, pageIndex, maxPages));
    }

    /** 创建布局按钮展示物。 */
    private ItemStack createLayoutItem(TrashConfig.GlobalTrashItemConfig item, int pageIndex,
                                       int maxPages, Player player,
                                       TrashConfig.GlobalTrashSortType sortType) {
        Material material = layoutMaterials.get(Character.valueOf(item.getSymbol()));
        ItemStack itemStack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            String name = itemName(item);
            if (name != null && !name.isEmpty()) {
                meta.setDisplayName(renderColor(player,
                        resolveLayoutText(player, name, pageIndex, maxPages, sortType)));
            }
            List<String> configuredLore = itemLore(item);
            if (!configuredLore.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : configuredLore) {
                    lore.add(renderColor(player,
                            resolveLayoutText(player, line, pageIndex, maxPages, sortType)));
                }
                meta.setLore(lore);
            }
            applyCustomModelData(meta, item.getModelId());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    /** 替换排序占位符后继续执行原有页码和 PAPI 变量解析。 */
    private String resolveLayoutText(Player player, String text, int pageIndex, int maxPages,
                                     TrashConfig.GlobalTrashSortType sortType) {
        TrashConfig.GlobalTrashSortType current = sortType == null
                ? TrashConfig.GlobalTrashSortType.INSERTION : sortType;
        String resolved = (text == null ? "" : text)
                .replace("{sort}", sortName(current))
                .replace("{next-sort}", sortName(current.next()))
                .replace("{previous-sort}", sortName(current.previous()));
        return textResolver.resolve(player, resolved, pageIndex, maxPages);
    }

    /** 返回当前语言下的排序方式名称。 */
    private String sortName(TrashConfig.GlobalTrashSortType sortType) {
        String key = "global-trash.gui.sort.types." + sortType.getConfigValue();
        if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_DESC) {
            return message(key, "数量从多到少");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_ASC) {
            return message(key, "数量从少到多");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.NAME_ASC) {
            return message(key, "名称 A-Z");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.MATERIAL_ASC) {
            return message(key, "材质 A-Z");
        }
        return message(key, "进入顺序");
    }

    /** 按玩家版本渲染颜色。 */
    private String renderColor(Player player, String text) {
        return player == null ? RichTextRenderer.color(text) : RichTextRenderer.color(player, text);
    }

    /** 尝试设置 CustomModelData，旧版本没有该 API 时自动忽略。 */
    private void applyCustomModelData(ItemMeta meta, int modelId) {
        if (meta == null || modelId < 0) {
            return;
        }
        try {
            java.lang.reflect.Method method = meta.getClass().getMethod("setCustomModelData", Integer.class);
            method.invoke(meta, Integer.valueOf(modelId));
        } catch (ReflectiveOperationException ignored) {
            // 1.12 没有 CustomModelData，保持旧版本可加载。
        } catch (RuntimeException ignored) {
            // 反射目标来自服务端实现，异常时只跳过外观字段，不影响 GUI 可用性。
        }
    }

    /** 关闭仍在查看旧分页的玩家，避免 reload 后操作失效页面。 */
    private void closeCurrentViewers() {
        List<UUID> viewerIds = new ArrayList<>(activeViews.keySet());
        activeViews.clear();
        for (UUID playerId : viewerIds) {
            if (platform == null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.closeInventory();
                }
                continue;
            }
            platform.executeForPlayer(playerId, new Consumer<Player>() {
                /** 在玩家合法线程关闭已经失效的旧页面。 */
                @Override
                public void accept(Player player) {
                    InventoryView open = player.getOpenInventory();
                    if (open == null) {
                        return;
                    }
                    InventoryHolder holder = open.getTopInventory().getHolder();
                    if (holder instanceof GlobalTrashViewHolder
                            && ((GlobalTrashViewHolder) holder).belongsTo(GlobalTrashService.this)) {
                        player.closeInventory();
                    }
                }
            });
        }
    }

    /** 返回内容槽在模型页中的连续下标。 */
    private int contentIndex(int rawSlot) {
        List<Integer> slots = layout.getContentSlots();
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).intValue() == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    /** 返回当前公共垃圾桶模式名称。 */
    private String modeName() {
        return config == null || config.getMode() == TrashConfig.GlobalTrashMode.COMPACT
                ? "compact" : "stacked";
    }

    /** 返回当前模式正常可写页数。 */
    private int configuredMaxPages() {
        return config == null ? 1 : config.getMaxPages();
    }

    /** 写入公共垃圾桶操作日志。 */
    private void logGlobalTrash(Player player, String action, ItemStack itemStack, int amount) {
        if (config == null || !config.isLogEnabled() || itemStack == null || amount <= 0) {
            return;
        }
        String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = time + " " + player.getName() + " " + action + " " + itemStack.getType().name() + "x" + amount;
        Path logDir = plugin.getDataFolder().toPath().resolve("logs");
        Path logFile = logDir.resolve("global-trash-" + day + ".log");
        try {
            Files.createDirectories(logDir);
            Files.write(logFile, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            plugin.getLogger().warning("[GlobalTrash] 写入公共垃圾桶日志失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[GlobalTrash] 写入公共垃圾桶日志时出现运行时异常: " + exception.getMessage());
        }
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? RichTextRenderer.color(fallback) : messages.text(key, fallback, replacements);
    }

    /** 单个在线玩家的两个模式排序偏好，不持有物品或视图数据。 */
    private static final class PlayerSortPreferences {
        private volatile TrashConfig.GlobalTrashSortType compact;
        private volatile TrashConfig.GlobalTrashSortType stacked;

        /** 返回指定模式已经选择的排序方式。 */
        private TrashConfig.GlobalTrashSortType get(TrashConfig.GlobalTrashMode mode) {
            return mode == TrashConfig.GlobalTrashMode.STACKED ? stacked : compact;
        }

        /** 只更新指定展示模式的排序方式。 */
        private void set(TrashConfig.GlobalTrashMode mode, TrashConfig.GlobalTrashSortType sortType) {
            if (mode == TrashConfig.GlobalTrashMode.STACKED) {
                stacked = sortType;
            } else {
                compact = sortType;
            }
        }
    }
}
