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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.SafeMaterialMatcher;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.TrashMutation;
import pixeltech.worldlisttrashcan.api.audit.TrashMutationReason;

import java.io.IOException;
import java.lang.reflect.Method;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 公共垃圾桶 GUI 和路由服务。 */
public final class GlobalTrashService {
    private final Plugin plugin;
    private final BukkitMessageService messages;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final ServerPlatform platform;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final GlobalTrashTextResolver textResolver;
    private final GlobalTrashActionExecutor actionExecutor;
    private final List<Inventory> pages = new ArrayList<>();
    private final Map<UUID, Long> lastTakeMillis = new HashMap<>();
    private final Map<Character, Material> layoutMaterials = new HashMap<>();
    private final Map<UUID, GlobalTrashViewHolder> activeViews = new ConcurrentHashMap<>();
    private final AtomicBoolean viewRefreshScheduled = new AtomicBoolean();
    private TrashConfig.GlobalTrashConfig config;
    private TrashConfig.GlobalTrashLayoutConfig layout;
    private int writablePageCount;

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
        reload(config);
    }

    /** 重载配置并重建分页。 */
    public void reload(TrashConfig.GlobalTrashConfig nextConfig) {
        List<ItemStack> oldItems = snapshotContent();
        closeCurrentViewers();
        this.config = nextConfig;
        this.layout = nextConfig == null
                ? TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null)
                : nextConfig.getLayout();
        this.writablePageCount = nextConfig == null ? 1 : nextConfig.getMaxPages();
        this.layoutMaterials.clear();
        if (layout.getValidationError() != null) {
            plugin.getLogger().warning("[GlobalTrash] 公共垃圾桶布局无效，已回退默认布局: "
                    + layout.getValidationError());
        }
        resolveLayoutMaterials();
        validateLayoutActions();
        rebuildPages(oldItems);
        plugin.getLogger().info("[GlobalTrash] GUI layout rows=" + layout.getRows().size()
                + ", slots=" + layout.getInventorySize()
                + ", contentSlots=" + layout.getContentSlots().size()
                + ", writablePages=" + writablePageCount
                + ", visiblePages=" + pages.size());
    }

    /** 判断公共垃圾桶是否可用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled() && !pages.isEmpty();
    }

    /** 判断是否有容量放入指定物品。 */
    public boolean hasSpace(ItemStack itemStack) {
        if (!isEnabled()) {
            return false;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack)
                || config.isBannedMaterial(cleanItemStack.getType().name())) {
            return false;
        }
        for (int index = 0; index < Math.min(writablePageCount, pages.size()); index++) {
            if (InventorySlotUtil.hasSpace(pages.get(index), cleanItemStack, layout.getContentSlots())) {
                return true;
            }
        }
        return false;
    }

    /** 向公共垃圾桶放入物品。 */
    public boolean addItem(ItemStack itemStack) {
        return addItem(itemStack, false, TrashMutationReason.NON_CLEANUP_DEPOSIT);
    }

    /** 放入正式扫地物品，不把它重复登记为非审计存量。 */
    public boolean addCleanupItem(ItemStack itemStack) {
        return addItem(itemStack, true, TrashMutationReason.NON_CLEANUP_DEPOSIT);
    }

    /** 按来源放入物品并维护非审计数量账本。 */
    private boolean addItem(ItemStack itemStack, boolean cleanupSource, TrashMutationReason reason) {
        ItemStack cleanItemStack = sanitize(itemStack);
        if (!hasSpace(cleanItemStack)) {
            return false;
        }
        for (int index = 0; index < Math.min(writablePageCount, pages.size()); index++) {
            if (InventorySlotUtil.add(pages.get(index), cleanItemStack, layout.getContentSlots())) {
                if (!cleanupSource) {
                    recordMutation(TrashMutation.untrackedDeposit(CleanupItemDestination.globalTrash(),
                            cleanItemStack, cleanItemStack.getAmount(), reason, System.currentTimeMillis()));
                }
                scheduleOpenViewRefresh();
                return true;
            }
        }
        return false;
    }

    /** 打开公共垃圾桶首页。 */
    public void open(Player player) {
        if (!isEnabled()) {
            player.sendMessage(message("global-trash.disabled", "&c公共垃圾桶未启用。"));
            return;
        }
        openPage(player, 0);
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
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            player.closeInventory();
            return true;
        }
        syncViewContent(holder);
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < layout.getInventorySize()) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(rawSlot);
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE && pageIndex > 0) {
                openPage(player, pageIndex - 1);
                return true;
            }
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE
                    && pageIndex < pages.size() - 1) {
                openPage(player, pageIndex + 1);
                return true;
            }
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.ACTIONS
                    && isNormalActionClick(event.getClick())) {
                actionExecutor.execute(player, item.getActions(), pageIndex, pages.size());
                return true;
            }
            if (layout.isContentSlot(rawSlot)) {
                takeItem(player, pages.get(pageIndex), rawSlot);
                syncViewContent(holder);
                scheduleOpenViewRefresh();
            }
            return true;
        }
        if (rawSlot >= layout.getInventorySize() && config.isAllowPlayerPut()) {
            putFromPlayer(player, event);
            syncViewContent(holder);
        }
        return true;
    }

    /** 为玩家创建当前公共库存的独立展示视图。 */
    private void openPage(Player player, int requestedPage) {
        int pageIndex = Math.max(0, Math.min(requestedPage, pages.size() - 1));
        GlobalTrashViewHolder holder = new GlobalTrashViewHolder(this, player.getUniqueId(), pageIndex);
        Inventory inventory = createViewPage(player, holder, pageIndex);
        holder.bind(inventory);
        activeViews.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    /** 创建带玩家 PAPI 展示文本、但不复制业务状态的单页视图。 */
    private Inventory createViewPage(Player player, GlobalTrashViewHolder holder, int pageIndex) {
        int maxPages = pages.size();
        Inventory inventory = Bukkit.createInventory(holder, layout.getInventorySize(),
                message("global-trash.gui.title", "&8公共垃圾桶 {page}/{max}",
                        "{page}", String.valueOf(pageIndex + 1), "{max}", String.valueOf(maxPages)));
        Inventory source = pages.get(pageIndex);
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null) {
                continue;
            }
            if (item.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                ItemStack content = source.getItem(slot);
                inventory.setItem(slot, InventorySlotUtil.isEmpty(content) ? null : content.clone());
                continue;
            }
            TrashConfig.GlobalTrashItemConfig displayItem = visibleItem(item, pageIndex, maxPages);
            if (displayItem != null) {
                inventory.setItem(slot, createLayoutItem(displayItem, pageIndex, maxPages, player));
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

    /** 把唯一公共库存中的内容槽同步到一个玩家视图。 */
    private void syncViewContent(GlobalTrashViewHolder holder) {
        if (holder == null || holder.getInventory() == null) {
            return;
        }
        int pageIndex = holder.getPageIndex();
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return;
        }
        Inventory source = pages.get(pageIndex);
        Inventory target = holder.getInventory();
        for (Integer slotValue : layout.getContentSlots()) {
            int slot = slotValue.intValue();
            ItemStack itemStack = source.getItem(slot);
            target.setItem(slot, InventorySlotUtil.isEmpty(itemStack) ? null : itemStack.clone());
        }
    }

    /** 把大量连续入库合并为下一 Tick 的一次玩家视图刷新。 */
    private void scheduleOpenViewRefresh() {
        if (activeViews.isEmpty() || !viewRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        Runnable refresh = new Runnable() {
            /** 分派一次有界的在线玩家视图刷新。 */
            @Override
            public void run() {
                viewRefreshScheduled.set(false);
                for (UUID playerId : new ArrayList<>(activeViews.keySet())) {
                    refreshViewForPlayer(playerId);
                }
            }
        };
        try {
            if (platform != null) {
                platform.scheduler().runLater(refresh, 1L);
            } else {
                Bukkit.getScheduler().runTask(plugin, refresh);
            }
        } catch (RuntimeException exception) {
            viewRefreshScheduled.set(false);
            plugin.getLogger().warning("[GlobalTrash] 调度公共垃圾桶视图刷新失败: " + exception.getMessage());
        }
    }

    /** 在玩家所属合法线程刷新其当前公共垃圾桶视图。 */
    private void refreshViewForPlayer(final UUID playerId) {
        if (platform == null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                activeViews.remove(playerId);
                return;
            }
            refreshCurrentView(player);
            return;
        }
        boolean scheduled = platform.executeForPlayer(playerId, new Consumer<Player>() {
            /** 刷新仍然处于打开状态的当前视图。 */
            @Override
            public void accept(Player player) {
                refreshCurrentView(player);
            }
        });
        if (!scheduled) {
            activeViews.remove(playerId);
        }
    }

    /** 确认玩家仍在查看登记页面后同步内容槽。 */
    private void refreshCurrentView(Player player) {
        GlobalTrashViewHolder holder = activeViews.get(player.getUniqueId());
        if (holder == null) {
            return;
        }
        if (player.getOpenInventory() == null
                || player.getOpenInventory().getTopInventory() != holder.getInventory()) {
            activeViews.remove(player.getUniqueId(), holder);
            return;
        }
        syncViewContent(holder);
    }

    /** 只允许普通左键或右键触发动作按钮。 */
    static boolean isNormalActionClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }

    /** 阻止拖拽修改公共垃圾桶的内容槽或展示物。 */
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

    /** 插件关闭时释放公共垃圾桶视图和冷却记录。 */
    public void close() {
        closeCurrentViewers();
        activeViews.clear();
        lastTakeMillis.clear();
        viewRefreshScheduled.set(false);
    }

    /** 清空所有内容槽位。 */
    public void clearContent() {
        closeCurrentViewers();
        rebuildPages(Collections.<ItemStack>emptyList());
        recordMutation(TrashMutation.clear(CleanupItemDestination.globalTrash(),
                TrashMutationReason.GLOBAL_REFRESH, System.currentTimeMillis()));
    }

    /** 返回公共垃圾桶页数。 */
    public int getPageCount() {
        return pages.size();
    }

    /** 返回公共垃圾桶内容物品总数量。 */
    public int getStoredItemAmount() {
        int amount = 0;
        for (Inventory page : pages) {
            for (Integer slotValue : layout.getContentSlots()) {
                int slot = slotValue.intValue();
                ItemStack itemStack = page.getItem(slot);
                if (!InventorySlotUtil.isEmpty(itemStack)) {
                    amount += itemStack.getAmount();
                }
            }
        }
        return amount;
    }

    /** 返回公共垃圾桶内容堆叠数量。 */
    public int getStoredStackCount() {
        int count = 0;
        for (Inventory page : pages) {
            for (Integer slotValue : layout.getContentSlots()) {
                int slot = slotValue.intValue();
                if (!InventorySlotUtil.isEmpty(page.getItem(slot))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 玩家从公共垃圾桶取出物品。 */
    private void takeItem(Player player, Inventory inventory, int slot) {
        if (!hasTrashPermission(player, "WorldListTrashCan.GlobalTrashTakeItem")) {
            player.sendMessage(message("global-trash.no-take-permission", "&c你没有权限从公共垃圾桶取出物品。"));
            return;
        }
        if (isCoolingDown(player)) {
            return;
        }
        ItemStack itemStack = inventory.getItem(slot);
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        lastTakeMillis.put(player.getUniqueId(), System.currentTimeMillis());
        if (leftovers.isEmpty()) {
            logGlobalTrash(player, "-global", itemStack, itemStack.getAmount());
            recordTake(player, itemStack, itemStack.getAmount());
            inventory.clear(slot);
            return;
        }
        ItemStack remaining = leftovers.values().iterator().next();
        int moved = itemStack.getAmount() - remaining.getAmount();
        if (moved > 0) {
            logGlobalTrash(player, "-global", itemStack, moved);
            recordTake(player, itemStack, moved);
        }
        itemStack.setAmount(remaining.getAmount());
        inventory.setItem(slot, itemStack);
    }

    /** 玩家手动把背包物品放入公共垃圾桶。 */
    private void putFromPlayer(Player player, InventoryClickEvent event) {
        if (!hasTrashPermission(player, "WorldListTrashCan.GlobalTrashPutItem")) {
            return;
        }
        ItemStack itemStack = event.getCurrentItem();
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        int amount = itemStack.getAmount();
        if (addItem(itemStack, false, TrashMutationReason.MANUAL_DEPOSIT)) {
            logGlobalTrash(player, "+global", itemStack, amount);
            itemStack.setAmount(0);
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

    /** 记录玩家从公共垃圾桶实际取出的数量。 */
    private void recordTake(Player player, ItemStack itemStack, int amount) {
        recordMutation(TrashMutation.take(CleanupItemDestination.globalTrash(), itemStack, amount,
                player.getUniqueId(), player.getName(), System.currentTimeMillis()));
    }

    /** 在附属存在时转发变更；没有附属时保持空操作。 */
    private void recordMutation(TrashMutation mutation) {
        if (auditBridge != null) {
            auditBridge.recordTrashMutation(mutation);
        }
    }

    /** 创建单页 GUI 并绘制展示物。 */
    private Inventory createPage(int pageIndex, int maxPages) {
        Inventory inventory = Bukkit.createInventory(null, layout.getInventorySize(),
                message("global-trash.gui.title", "&8公共垃圾桶 {page}/{max}",
                        "{page}", String.valueOf(pageIndex + 1), "{max}", String.valueOf(maxPages)));
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null || item.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                continue;
            }
            TrashConfig.GlobalTrashItemConfig displayItem = visibleItem(item, pageIndex, maxPages);
            if (displayItem != null) {
                inventory.setItem(slot, createLayoutItem(displayItem, pageIndex, maxPages, null));
            }
        }
        return inventory;
    }

    /** 快照旧分页中的物品。 */
    private List<ItemStack> snapshotContent() {
        List<ItemStack> result = new ArrayList<>();
        if (layout == null) {
            return result;
        }
        for (Inventory page : pages) {
            for (Integer slotValue : layout.getContentSlots()) {
                int slot = slotValue.intValue();
                ItemStack itemStack = page.getItem(slot);
                if (!InventorySlotUtil.isEmpty(itemStack)) {
                    result.add(itemStack.clone());
                }
            }
        }
        return result;
    }

    /** 按当前布局重建正常页和仅承载旧存量的临时溢出页。 */
    private void rebuildPages(List<ItemStack> items) {
        List<Inventory> packedPages = packItems(items);
        int totalPages = packedPages.size();
        List<Inventory> rebuilt = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            Inventory page = createPage(pageIndex, totalPages);
            copyContent(packedPages.get(pageIndex), page);
            rebuilt.add(page);
        }
        pages.clear();
        pages.addAll(rebuilt);
        int overflowPages = Math.max(0, totalPages - writablePageCount);
        if (overflowPages > 0) {
            plugin.getLogger().warning("[GlobalTrash] 新布局正常容量不足以容纳旧物品，已创建 "
                    + overflowPages + " 个临时溢出页；这些页面可取出但不接收新物品。");
        }
    }

    /** 把旧物品打包进正常页，必要时追加临时溢出页。 */
    private List<Inventory> packItems(List<ItemStack> items) {
        List<Inventory> packedPages = new ArrayList<>();
        for (int index = 0; index < Math.max(1, writablePageCount); index++) {
            packedPages.add(createScratchPage());
        }
        if (items == null || items.isEmpty()) {
            return packedPages;
        }
        for (ItemStack itemStack : items) {
            if (InventorySlotUtil.isEmpty(itemStack)) {
                continue;
            }
            int remaining = itemStack.getAmount();
            int maxStack = Math.max(1, itemStack.getMaxStackSize());
            while (remaining > 0) {
                ItemStack chunk = itemStack.clone();
                chunk.setAmount(Math.min(remaining, maxStack));
                if (!addToPackedPages(packedPages, chunk)) {
                    Inventory overflowPage = createScratchPage();
                    packedPages.add(overflowPage);
                    if (!InventorySlotUtil.add(overflowPage, chunk, layout.getContentSlots())) {
                        throw new IllegalStateException("公共垃圾桶布局无法容纳单个物品堆叠");
                    }
                }
                remaining -= chunk.getAmount();
            }
        }
        return packedPages;
    }

    /** 尝试把一个物品堆叠放入已有打包页。 */
    private boolean addToPackedPages(List<Inventory> packedPages, ItemStack itemStack) {
        for (Inventory page : packedPages) {
            if (InventorySlotUtil.add(page, itemStack, layout.getContentSlots())) {
                return true;
            }
        }
        return false;
    }

    /** 创建仅用于重排旧内容的临时背包。 */
    private Inventory createScratchPage() {
        return Bukkit.createInventory(null, layout.getInventorySize(), "WorldListTrashCan packing");
    }

    /** 把临时背包的内容槽复制到最终 GUI。 */
    private void copyContent(Inventory source, Inventory target) {
        for (Integer slotValue : layout.getContentSlots()) {
            int slot = slotValue.intValue();
            ItemStack itemStack = source.getItem(slot);
            if (!InventorySlotUtil.isEmpty(itemStack)) {
                target.setItem(slot, itemStack.clone());
            }
        }
    }

    /** 返回当前页实际应显示的按钮或不可用替代物。 */
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

    /** 返回展示物名称，未覆盖时读取对应类型的语言节点。 */
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
        return " ";
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
                    InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                    if (holder instanceof GlobalTrashViewHolder
                            && ((GlobalTrashViewHolder) holder).belongsTo(GlobalTrashService.this)) {
                        player.closeInventory();
                    }
                }
            });
        }
    }

    /** 创建布局展示物。 */
    private ItemStack createLayoutItem(TrashConfig.GlobalTrashItemConfig item, int pageIndex,
                                       int maxPages, Player player) {
        Material material = layoutMaterials.get(Character.valueOf(item.getSymbol()));
        ItemStack itemStack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            String name = itemName(item);
            if (name != null && !name.isEmpty()) {
                meta.setDisplayName(renderColor(player,
                        textResolver.resolve(player, name, pageIndex, maxPages)));
            }
            if (!item.getLore().isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : item.getLore()) {
                    lore.add(renderColor(player,
                            textResolver.resolve(player, line, pageIndex, maxPages)));
                }
                meta.setLore(lore);
            }
            applyCustomModelData(meta, item.getModelId());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    /** 按玩家版本渲染颜色，内部存储页没有玩家时使用服务端版本渲染。 */
    private String renderColor(Player player, String text) {
        return player == null ? RichTextRenderer.color(text) : RichTextRenderer.color(player, text);
    }

    /** 尝试设置 CustomModelData，旧版本没有该 API 时自动忽略。 */
    private void applyCustomModelData(ItemMeta meta, int modelId) {
        if (meta == null || modelId < 0) {
            return;
        }
        try {
            Method method = meta.getClass().getMethod("setCustomModelData", Integer.class);
            method.invoke(meta, Integer.valueOf(modelId));
        } catch (ReflectiveOperationException ignored) {
            // 1.12 没有 CustomModelData，保持旧版本可加载。
        } catch (RuntimeException ignored) {
            // 反射目标来自服务端实现，异常时只跳过外观字段，不影响 GUI 可用性。
        }
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

    /** 转换颜色代码。 */
    private String color(String text) {
        return RichTextRenderer.color(text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }
}
