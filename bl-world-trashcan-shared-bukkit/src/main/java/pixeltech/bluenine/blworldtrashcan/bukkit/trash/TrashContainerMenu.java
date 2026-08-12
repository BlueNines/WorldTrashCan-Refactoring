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
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 公共和个人垃圾桶共用的分页、排序、展示与取放菜单实现。 */
final class TrashContainerMenu {
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final TrashContainerMenuPolicy policy;
    private final GlobalTrashTextResolver textResolver;
    private final GlobalTrashActionExecutor actionExecutor;
    private final LayoutItemGlint layoutItemGlint;
    private final CustomModelDataSupport customModelDataSupport;
    private final Map<Character, Material> layoutMaterials = new HashMap<>();
    private final Map<UUID, TrashContainerViewHolder> activeViews = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSortPreferences> sortPreferences = new ConcurrentHashMap<>();
    private TrashConfig.TrashContainerConfig config;
    private TrashConfig.GlobalTrashLayoutConfig layout;

    /** 创建通用容器菜单。 */
    TrashContainerMenu(Plugin plugin, ServerPlatform platform, ItemSnapshotMapper itemSnapshotMapper,
                       CustomModelDataSupport customModelDataSupport, TrashContainerMenuPolicy policy) {
        this.plugin = plugin;
        this.platform = platform;
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.policy = policy;
        this.textResolver = new GlobalTrashTextResolver(plugin, policy.getLogName());
        this.actionExecutor = new GlobalTrashActionExecutor(
                plugin, platform, textResolver, policy.getLogName());
        this.layoutItemGlint = new LayoutItemGlint(plugin.getLogger());
        this.customModelDataSupport = customModelDataSupport == null
                ? CustomModelDataSupport.unsupported(plugin.getLogger()) : customModelDataSupport;
    }

    /** 重载菜单配置并关闭旧视图，容器 Store 存量由调用方保留。 */
    void reload(TrashConfig.TrashContainerConfig nextConfig) {
        closeCurrentViewers();
        this.config = nextConfig;
        this.layout = nextConfig == null
                ? TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null)
                : nextConfig.getLayout();
        layoutMaterials.clear();
        resolveLayoutMaterials();
        validateLayoutActions();
        if (layout.getValidationError() != null) {
            plugin.getLogger().warning('[' + policy.getLogName() + "] 布局无效，已回退默认布局: "
                    + layout.getValidationError());
        }
    }

    /** 返回当前每页内容槽数量。 */
    int getContentSlotsPerPage() {
        return layout == null ? 1 : Math.max(1, layout.getContentSlots().size());
    }

    /** 打开指定 Store 的首页。 */
    void open(Player player, TrashContainerStore store) {
        if (player == null || store == null || !isEnabled()) {
            if (player != null) {
                policy.sendDisabled(player);
            }
            return;
        }
        TrashConfig.GlobalTrashSortType sortType = sortPreference(player.getUniqueId(), config.getMode());
        openPage(player, store, 0, store.createViewSnapshot(sortType));
    }

    /** 处理属于本菜单的点击。 */
    boolean handleClick(InventoryClickEvent event) {
        TrashContainerViewHolder holder = viewHolder(event.getInventory());
        if (holder == null) {
            return false;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return true;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.getUniqueId().equals(holder.getPlayerId())) {
            player.closeInventory();
            return true;
        }
        int pageIndex = holder.getPageIndex();
        TrashContainerStore.ViewSnapshot snapshot = holder.getSnapshot();
        if (snapshot == null || pageIndex < 0 || pageIndex >= snapshot.getPageCount()) {
            player.closeInventory();
            return true;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < layout.getInventorySize()) {
            handleTopClick(player, holder, rawSlot, event.getClick());
            return true;
        }
        if (rawSlot >= layout.getInventorySize() && config.isAllowPlayerPut()) {
            putFromPlayer(player, holder.getStore(), event);
        }
        return true;
    }

    /** 处理顶部菜单按钮和内容槽点击。 */
    private void handleTopClick(Player player, TrashContainerViewHolder holder,
                                int rawSlot, ClickType clickType) {
        int pageIndex = holder.getPageIndex();
        TrashContainerStore.ViewSnapshot snapshot = holder.getSnapshot();
        TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(rawSlot);
        if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE && pageIndex > 0) {
            openPage(player, holder.getStore(), pageIndex - 1, snapshot);
            return;
        }
        if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE
                && pageIndex < snapshot.getPageCount() - 1) {
            openPage(player, holder.getStore(), pageIndex + 1, snapshot);
            return;
        }
        if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.ACTIONS
                && isNormalActionClick(clickType)) {
            actionExecutor.execute(player, item.getActions(), pageIndex, snapshot.getPageCount());
            return;
        }
        if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.CLOSE) {
            player.closeInventory();
            return;
        }
        if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.SORT
                && isNormalActionClick(clickType)) {
            changeSort(player, holder.getStore(), snapshot.getSortType(), clickType);
            return;
        }
        if (!layout.isContentSlot(rawSlot)) {
            return;
        }
        int contentIndex = contentIndex(rawSlot);
        if (contentIndex >= 0) {
            takeItem(player, holder, contentIndex, clickType);
            syncContentSlot(holder, rawSlot);
        }
    }

    /** 阻止拖拽修改展示菜单。 */
    boolean handleDrag(InventoryDragEvent event) {
        if (viewHolder(event.getInventory()) == null) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /** 关闭菜单时释放活动视图。 */
    boolean handleClose(InventoryCloseEvent event) {
        TrashContainerViewHolder holder = viewHolder(event.getInventory());
        if (holder == null) {
            return false;
        }
        activeViews.remove(holder.getPlayerId(), holder);
        return true;
    }

    /** 玩家退出时释放展示偏好和活动视图。 */
    void handleQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }
        activeViews.remove(playerId);
        sortPreferences.remove(playerId);
    }

    /** 关闭全部旧视图并释放菜单缓存。 */
    void close() {
        closeCurrentViewers();
        activeViews.clear();
        sortPreferences.clear();
    }

    /** 为玩家打开指定快照页。 */
    private void openPage(Player player, TrashContainerStore store, int requestedPage,
                          TrashContainerStore.ViewSnapshot snapshot) {
        int pageCount = snapshot == null ? 1 : snapshot.getPageCount();
        int pageIndex = Math.max(0, Math.min(requestedPage, pageCount - 1));
        TrashContainerViewHolder holder = new TrashContainerViewHolder(
                this, player.getUniqueId(), pageIndex, store, snapshot);
        Inventory inventory = createViewPage(player, holder, pageIndex);
        holder.bind(inventory);
        activeViews.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    /** 创建指定页的只读展示库存。 */
    private Inventory createViewPage(Player player, TrashContainerViewHolder holder, int pageIndex) {
        TrashContainerStore.ViewSnapshot snapshot = holder.getSnapshot();
        int maxPages = snapshot == null ? 1 : snapshot.getPageCount();
        Inventory inventory = Bukkit.createInventory(holder, layout.getInventorySize(),
                policy.getTitle(player, pageIndex, maxPages));
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item == null) {
                continue;
            }
            if (item.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                int contentIndex = contentIndex(slot);
                TrashContainerStore.DisplayReference reference = snapshot == null
                        ? null : snapshot.getReference(pageIndex, contentIndex);
                TrashContainerStore.DisplayItem display = holder.getStore().getDisplayItem(reference);
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

    /** 取出条目并只扣除实际交付给玩家的数量。 */
    private void takeItem(Player player, TrashContainerViewHolder holder,
                          int contentIndex, ClickType clickType) {
        if (!policy.canTake(player)) {
            return;
        }
        TrashContainerStore store = holder.getStore();
        synchronized (store) {
            TrashContainerStore.ViewSnapshot snapshot = holder.getSnapshot();
            TrashContainerStore.DisplayReference reference = snapshot == null ? null
                    : snapshot.getReference(holder.getPageIndex(), contentIndex);
            TrashContainerStore.DisplayItem display = store.getDisplayItem(reference);
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
            if (!InventorySlotUtil.hasAnyStorageSpace(player.getInventory(), itemStack)) {
                policy.onTakeInventoryFull(player);
                return;
            }
            if (!policy.beforeTake(player, itemStack, actualRequested)) {
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
            int moved = actualRequested - leftoverAmount(leftovers);
            if (moved <= 0) {
                return;
            }
            int removed = store.remove(display.getEntryId(), moved);
            if (removed <= 0) {
                return;
            }
            itemStack.setAmount(removed);
            policy.afterTake(player, itemStack, display.getTrackingKey(), removed);
        }
    }

    /** 玩家手动把背包物品尽可能放入当前容器。 */
    private void putFromPlayer(Player player, TrashContainerStore store, InventoryClickEvent event) {
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || event.getSlot() < 0 || event.getSlot() >= clickedInventory.getSize()) {
            return;
        }
        ItemStack itemStack = clickedInventory.getItem(event.getSlot());
        if (InventorySlotUtil.isEmpty(itemStack) || !policy.canManualPut(player, itemStack)) {
            return;
        }
        String expectedIdentity = store.identityKey(itemStack);
        if (expectedIdentity == null) {
            return;
        }
        scheduleManualPut(player.getUniqueId(), clickedInventory, event.getSlot(),
                itemStack.getAmount(), expectedIdentity, store);
    }

    /** 下一 Tick 在玩家合法线程重新确认原槽位后执行手动放入。 */
    private void scheduleManualPut(final UUID playerId, final Inventory sourceInventory,
                                   final int sourceSlot, final int expectedAmount,
                                   final String expectedIdentity, final TrashContainerStore store) {
        Consumer<Player> action = new Consumer<Player>() {
            /** 原事件取消已结算后，原子完成入库和源槽位扣减。 */
            @Override
            public void accept(Player player) {
                applyManualPut(player, sourceInventory, sourceSlot, expectedAmount,
                        expectedIdentity, store);
            }
        };
        if (platform != null) {
            platform.executeForPlayer(playerId, action);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            /** 无平台适配器时在 Bukkit 下一 Tick 执行兼容路径。 */
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    action.accept(player);
                }
            }
        });
    }

    /** 确认玩家背包槽位未变化后写入容器并扣除实际接收数量。 */
    private void applyManualPut(Player player, Inventory sourceInventory, int sourceSlot,
                                int expectedAmount, String expectedIdentity,
                                TrashContainerStore store) {
        if (player == null || sourceInventory == null || sourceSlot < 0
                || sourceSlot >= sourceInventory.getSize()) {
            return;
        }
        ItemStack itemStack = sourceInventory.getItem(sourceSlot);
        String currentIdentity = store.identityKey(itemStack);
        if (InventorySlotUtil.isEmpty(itemStack) || itemStack.getAmount() != expectedAmount
                || !expectedIdentity.equals(currentIdentity)) {
            return;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        TrashWriteResult result = store.add(cleanItemStack, true);
        int accepted = result.getAcceptedAmount();
        if (!result.isAccepted()) {
            return;
        }
        int remaining = itemStack.getAmount() - accepted;
        if (remaining <= 0) {
            sourceInventory.setItem(sourceSlot, null);
        } else {
            ItemStack remainder = itemStack.clone();
            remainder.setAmount(remaining);
            sourceInventory.setItem(sourceSlot, remainder);
        }
        cleanItemStack.setAmount(accepted);
        policy.afterManualPut(player, cleanItemStack, result.getTrackingKey(), accepted);
    }

    /** 只同步刚操作的内容槽，避免整页闪烁。 */
    private void syncContentSlot(TrashContainerViewHolder holder, int rawSlot) {
        if (holder == null || holder.getInventory() == null || !layout.isContentSlot(rawSlot)) {
            return;
        }
        Player player = Bukkit.getPlayer(holder.getPlayerId());
        TrashContainerStore.ViewSnapshot snapshot = holder.getSnapshot();
        if (player == null || snapshot == null) {
            return;
        }
        int contentIndex = contentIndex(rawSlot);
        TrashContainerStore.DisplayReference reference = snapshot.getReference(holder.getPageIndex(), contentIndex);
        TrashContainerStore.DisplayItem display = holder.getStore().getDisplayItem(reference);
        holder.getInventory().setItem(rawSlot, display == null ? null
                : createContentItem(display, player, holder.getPageIndex(), snapshot.getPageCount()));
    }

    /** 切换当前模式排序并创建新快照。 */
    private void changeSort(Player player, TrashContainerStore store,
                            TrashConfig.GlobalTrashSortType current, ClickType clickType) {
        TrashConfig.GlobalTrashSortType next = clickType == ClickType.RIGHT
                ? current.previous() : current.next();
        preferences(player.getUniqueId()).set(config.getMode(), next);
        openPage(player, store, 0, store.createViewSnapshot(next));
    }

    /** 返回指定模式的玩家排序偏好。 */
    private TrashConfig.GlobalTrashSortType sortPreference(UUID playerId, TrashConfig.GlobalTrashMode mode) {
        PlayerSortPreferences preferences = sortPreferences.get(playerId);
        TrashConfig.GlobalTrashSortType cached = preferences == null ? null : preferences.get(mode);
        if (cached != null) {
            return cached;
        }
        return mode == TrashConfig.GlobalTrashMode.STACKED
                ? config.getStacked().getDefaultSort() : config.getCompact().getDefaultSort();
    }

    /** 返回或创建玩家的双模式排序偏好。 */
    private PlayerSortPreferences preferences(UUID playerId) {
        PlayerSortPreferences existing = sortPreferences.get(playerId);
        if (existing != null) {
            return existing;
        }
        PlayerSortPreferences created = new PlayerSortPreferences();
        PlayerSortPreferences raced = sortPreferences.putIfAbsent(playerId, created);
        return raced == null ? created : raced;
    }

    /** 从 Bukkit 库存识别本菜单视图。 */
    private TrashContainerViewHolder viewHolder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof TrashContainerViewHolder)) {
            return null;
        }
        TrashContainerViewHolder view = (TrashContainerViewHolder) holder;
        return view.belongsTo(this) ? view : null;
    }

    /** 返回当前页面应显示的按钮或不可用替代物。 */
    private TrashConfig.GlobalTrashItemConfig visibleItem(TrashConfig.GlobalTrashItemConfig item,
                                                           int pageIndex, int maxPages) {
        boolean unavailable = item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE && pageIndex <= 0
                || item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE && pageIndex >= maxPages - 1;
        if (!unavailable) {
            return item;
        }
        Character fallback = item.getUnavailableItem();
        return fallback == null ? null : layout.getItem(fallback.charValue());
    }

    /** 解析所有布局展示物材质。 */
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
                plugin.getLogger().warning('[' + policy.getLogName() + "] 布局字符 '"
                        + item.getSymbol() + "' 的材质候选全部无效，已降级为 STONE。");
            }
            layoutMaterials.put(Character.valueOf(item.getSymbol()), material);
        }
    }

    /** 启动或重载时校验 actions。 */
    private void validateLayoutActions() {
        Set<Character> validated = new HashSet<>();
        for (int slot = 0; slot < layout.getInventorySize(); slot++) {
            TrashConfig.GlobalTrashItemConfig item = layout.getItemAt(slot);
            if (item != null && item.getType() == TrashConfig.GlobalTrashItemType.ACTIONS
                    && validated.add(Character.valueOf(item.getSymbol()))) {
                actionExecutor.validate(item.getSymbol(), item.getActions());
            }
        }
    }

    /** 创建紧凑或堆叠模式内容展示物。 */
    private ItemStack createContentItem(TrashContainerStore.DisplayItem display, Player player,
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
            lore.add(resolveContentText(player, compact.getAmountLore(), pageIndex, maxPages, display, 0));
        }
        int maxOriginal = compact.getMaxOriginalLoreLines();
        int visibleOriginal = maxOriginal < 0 ? originalLore.size() : Math.min(maxOriginal, originalLore.size());
        for (int index = 0; index < visibleOriginal; index++) {
            lore.add(resolveContentText(player, originalLore.get(index), pageIndex, maxPages, display, 0));
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
            meta.setDisplayName(resolveContentText(player, meta.getDisplayName(), pageIndex, maxPages, display, 0));
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    /** 创建布局按钮展示物。 */
    private ItemStack createLayoutItem(TrashConfig.GlobalTrashItemConfig item, int pageIndex,
                                       int maxPages, Player player,
                                       TrashConfig.GlobalTrashSortType sortType) {
        Material material = layoutMaterials.get(Character.valueOf(item.getSymbol()));
        ItemStack itemStack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        String name = itemName(item);
        if (name != null && !name.isEmpty()) {
            meta.setDisplayName(renderColor(player, resolveLayoutText(player, name, pageIndex, maxPages, sortType)));
        }
        List<String> configuredLore = itemLore(item);
        if (!configuredLore.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String line : configuredLore) {
                lore.add(renderColor(player, resolveLayoutText(player, line, pageIndex, maxPages, sortType)));
            }
            meta.setLore(lore);
        }
        customModelDataSupport.apply(meta, item.getModelId());
        if (item.isGlow()) {
            layoutItemGlint.apply(meta);
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    /** 返回布局物品名称。 */
    private String itemName(TrashConfig.GlobalTrashItemConfig item) {
        if (item.getName() != null) {
            return item.getName();
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE) {
            return policy.message("gui.back", "&a上一页");
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.NEXT_PAGE) {
            return policy.message("gui.next", "&a下一页");
        }
        if (item.getType() == TrashConfig.GlobalTrashItemType.SORT) {
            return policy.message("gui.sort.name", "&#FFD166排序方式");
        }
        return " ";
    }

    /** 返回布局物品 Lore。 */
    private List<String> itemLore(TrashConfig.GlobalTrashItemConfig item) {
        if (!item.getLore().isEmpty() || item.getType() != TrashConfig.GlobalTrashItemType.SORT) {
            return item.getLore();
        }
        List<String> lore = new ArrayList<>();
        lore.add(policy.message("gui.sort.current", "&#C9D4E2当前：&#FFD166{sort}"));
        lore.add(policy.message("gui.sort.next", "&#5AC8FA左键 &#C9D4E2切换为 &#FFD166{next-sort}"));
        lore.add(policy.message("gui.sort.previous", "&#5AC8FA右键 &#C9D4E2切换为 &#FFD166{previous-sort}"));
        return lore;
    }

    /** 解析内容 Lore 的数量和玩家变量。 */
    private String resolveContentText(Player player, String text, int pageIndex, int maxPages,
                                      TrashContainerStore.DisplayItem display, int omittedCount) {
        TrashConfig.CompactGlobalTrashConfig compact = config.getCompact();
        String resolved = (text == null ? "" : text)
                .replace("{amount}", String.valueOf(display.getLogicalAmount()))
                .replace("{take-amount}", String.valueOf(compact.getLeftClickAmount()))
                .replace("{shift-take-amount}", String.valueOf(compact.getShiftLeftClickAmount()))
                .replace("{right-take-amount}", String.valueOf(compact.getRightClickAmount()))
                .replace("{shift-right-take-amount}", String.valueOf(compact.getShiftRightClickAmount()))
                .replace("{count}", String.valueOf(omittedCount));
        return renderColor(player, textResolver.resolve(player, resolved, pageIndex, maxPages));
    }

    /** 解析布局排序和玩家变量。 */
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

    /** 返回当前作用域语言下的排序名称。 */
    private String sortName(TrashConfig.GlobalTrashSortType sortType) {
        String suffix = "gui.sort.types." + sortType.getConfigValue();
        if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_DESC) {
            return policy.message(suffix, "数量从多到少");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_ASC) {
            return policy.message(suffix, "数量从少到多");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.NAME_ASC) {
            return policy.message(suffix, "名称 A-Z");
        }
        if (sortType == TrashConfig.GlobalTrashSortType.MATERIAL_ASC) {
            return policy.message(suffix, "材质 A-Z");
        }
        return policy.message(suffix, "进入顺序");
    }

    /** 按点击类型计算取出数量。 */
    private int takeAmount(ClickType clickType, TrashContainerStore.DisplayItem display) {
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
        return clickType == ClickType.SHIFT_RIGHT ? compact.getShiftRightClickAmount() : 0;
    }

    /** 返回玩家背包未接收数量。 */
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

    /** 清理插件内部临时物品标记。 */
    private ItemStack sanitize(ItemStack itemStack) {
        return itemSnapshotMapper == null ? itemStack : itemSnapshotMapper.sanitizeForStorage(itemStack);
    }

    /** 返回内容槽连续下标。 */
    private int contentIndex(int rawSlot) {
        List<Integer> slots = layout.getContentSlots();
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).intValue() == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    /** 判断菜单是否可以打开。 */
    private boolean isEnabled() {
        return config != null && config.isEnabled() && layout != null && !layout.getContentSlots().isEmpty();
    }

    /** 只允许普通左右键触发动作按钮。 */
    private boolean isNormalActionClick(ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }

    /** 按玩家版本渲染颜色。 */
    private String renderColor(Player player, String text) {
        return player == null ? RichTextRenderer.color(text) : RichTextRenderer.color(player, text);
    }

    /** 在合法玩家线程关闭所有旧视图。 */
    private void closeCurrentViewers() {
        List<UUID> viewerIds = new ArrayList<>(activeViews.keySet());
        activeViews.clear();
        for (final UUID playerId : viewerIds) {
            if (platform == null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && isViewingThisMenu(player)) {
                    player.closeInventory();
                }
                continue;
            }
            platform.executeForPlayer(playerId, new Consumer<Player>() {
                /** 在玩家合法线程关闭失效视图。 */
                @Override
                public void accept(Player player) {
                    if (isViewingThisMenu(player)) {
                        player.closeInventory();
                    }
                }
            });
        }
    }

    /** 判断玩家顶部库存是否属于当前菜单。 */
    private boolean isViewingThisMenu(Player player) {
        InventoryView open = player == null ? null : player.getOpenInventory();
        if (open == null) {
            return false;
        }
        InventoryHolder holder = open.getTopInventory().getHolder();
        return holder instanceof TrashContainerViewHolder
                && ((TrashContainerViewHolder) holder).belongsTo(this);
    }

    /** 单个玩家在两种模式中的排序偏好。 */
    private static final class PlayerSortPreferences {
        private volatile TrashConfig.GlobalTrashSortType compact;
        private volatile TrashConfig.GlobalTrashSortType stacked;

        /** 返回指定模式偏好。 */
        private TrashConfig.GlobalTrashSortType get(TrashConfig.GlobalTrashMode mode) {
            return mode == TrashConfig.GlobalTrashMode.STACKED ? stacked : compact;
        }

        /** 更新指定模式偏好。 */
        private void set(TrashConfig.GlobalTrashMode mode, TrashConfig.GlobalTrashSortType sortType) {
            if (mode == TrashConfig.GlobalTrashMode.STACKED) {
                stacked = sortType;
            } else {
                compact = sortType;
            }
        }
    }
}
