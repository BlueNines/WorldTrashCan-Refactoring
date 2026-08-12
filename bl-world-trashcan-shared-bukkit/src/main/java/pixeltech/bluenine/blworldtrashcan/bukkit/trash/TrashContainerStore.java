package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSimilarIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 公共和个人垃圾桶共用的模型存储，不使用 Bukkit Inventory 保存业务状态。 */
public class TrashContainerStore {
    private final ItemIdentityProvider identityProvider;
    private final String trackingPrefix;
    private final String lifecycleId = UUID.randomUUID().toString();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();
    private final Map<Long, StoredEntry> entriesById = new LinkedHashMap<>();
    private TrashConfig.TrashContainerConfig config;
    private int contentSlotsPerPage = 1;
    private long nextEntryId = 1L;

    /** 创建使用 global 追踪前缀的兼容容器存储。 */
    public TrashContainerStore(ItemIdentityProvider identityProvider) {
        this(identityProvider, "global");
    }

    /** 创建带独立审计追踪前缀的容器存储。 */
    public TrashContainerStore(ItemIdentityProvider identityProvider, String trackingPrefix) {
        this.identityProvider = identityProvider == null
                ? new BukkitSimilarIdentityProvider() : identityProvider;
        String normalizedPrefix = trackingPrefix == null ? "container" : trackingPrefix.trim();
        this.trackingPrefix = normalizedPrefix.isEmpty() ? "container" : normalizedPrefix;
    }

    /** 应用布局容量和模式配置，但保留当前运行期物品存量。 */
    public synchronized void configure(TrashConfig.TrashContainerConfig nextConfig, int contentSlots) {
        this.config = nextConfig;
        this.contentSlotsPerPage = Math.max(1, contentSlots);
    }

    /** 返回固定的身份实现名称。 */
    public String getIdentityProviderId() {
        return identityProvider.id();
    }

    /** 返回启动时固定的物品身份实现，供同一插件内其它虚拟存储复用。 */
    ItemIdentityProvider getIdentityProvider() {
        return identityProvider;
    }

    /** 返回物品的完整身份键，供菜单延迟操作确认原槽位未变化。 */
    String identityKey(ItemStack itemStack) {
        return itemStack == null ? null : identityProvider.key(itemStack);
    }

    /** 判断指定物品是否可以完整放入当前正常存储容量。 */
    public synchronized boolean hasSpace(ItemStack itemStack) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return false;
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return false;
        }
        return capacityFor(key, itemStack) >= itemStack.getAmount();
    }

    /** 判断指定物品至少有一个数量可以进入当前正常存储容量。 */
    public synchronized boolean hasAnySpace(ItemStack itemStack) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return false;
        }
        String key = identityProvider.key(itemStack);
        return key != null && capacityFor(key, itemStack) > 0L;
    }

    /** 预判一次写入是否可能成功；正式写入结果仍是路由的唯一权威。 */
    public synchronized boolean canAccept(ItemStack itemStack, boolean allowPartial,
                                          boolean allowClearRetry) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return false;
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return false;
        }
        long capacity = capacityFor(key, itemStack);
        boolean accepted = allowPartial ? capacity > 0L : capacity >= itemStack.getAmount();
        if (accepted || !allowClearRetry
                || rejectionStatus(key) != TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY) {
            return accepted;
        }
        return canEmptyContainerAccept(itemStack, false);
    }

    /** 添加物品；allowPartial 为 true 时返回尽可能接受的数量和实际条目追踪键。 */
    public synchronized TrashWriteResult add(ItemStack itemStack, boolean allowPartial) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return TrashWriteResult.rejected();
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return TrashWriteResult.rejected();
        }
        int requested = itemStack.getAmount();
        long capacity = capacityFor(key, itemStack);
        long accepted = allowPartial ? Math.min(requested, capacity) : requested <= capacity ? requested : 0L;
        if (accepted <= 0L) {
            return TrashWriteResult.rejected(rejectionStatus(key));
        }
        StoredEntry entry = entries.get(key);
        if (entry == null) {
            ItemStack sample = itemStack.clone();
            sample.setAmount(1);
            long entryId = nextEntryId();
            entry = new StoredEntry(entryId, key, trackingKey(entryId), sample, accepted,
                    sortName(sample), sample.getType().name().toLowerCase(Locale.ROOT));
            entries.put(key, entry);
            entriesById.put(Long.valueOf(entry.entryId), entry);
        } else {
            entry.amount += accepted;
        }
        return TrashWriteResult.accepted((int) accepted, requested, entry.trackingKey, false);
    }

    /** 容量拒绝时原子清空并只重试一次，其它拒绝和部分接收绝不清空。 */
    public synchronized TrashWriteResult addWithClearRetry(ItemStack itemStack, boolean allowPartial) {
        TrashWriteResult first = add(itemStack, allowPartial);
        if (first.getStatus() != TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY) {
            return first;
        }
        if (!canEmptyContainerAccept(itemStack, false)) {
            return first;
        }
        clear();
        TrashWriteResult retried = add(itemStack, allowPartial);
        if (!retried.isAccepted()) {
            return retried;
        }
        return TrashWriteResult.accepted(retried.getAcceptedAmount(), itemStack.getAmount(),
                retried.getTrackingKey(), true);
    }

    /** 按身份键移除已经成功交给玩家的数量。 */
    public synchronized int remove(String key, long requestedAmount) {
        StoredEntry entry = key == null ? null : entries.get(key);
        return removeEntry(entry, requestedAmount);
    }

    /** 按稳定条目 ID 移除数量，避免旧视图命中新建的同类物品。 */
    public synchronized int remove(long entryId, long requestedAmount) {
        return removeEntry(entriesById.get(Long.valueOf(entryId)), requestedAmount);
    }

    /** 创建一次打开会话使用的稳定排序和分页引用快照。 */
    public synchronized ViewSnapshot createViewSnapshot(TrashConfig.GlobalTrashSortType sortType) {
        TrashConfig.GlobalTrashSortType effectiveSort = sortType == null
                ? TrashConfig.GlobalTrashSortType.INSERTION : sortType;
        List<StoredEntry> ordered = new ArrayList<>(entries.values());
        if (effectiveSort != TrashConfig.GlobalTrashSortType.INSERTION) {
            Collections.sort(ordered, comparator(effectiveSort));
        }
        List<DisplayReference> references = new ArrayList<>();
        for (StoredEntry entry : ordered) {
            if (config == null || config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
                references.add(new DisplayReference(entry.entryId, 0L, 1));
                continue;
            }
            int maxStack = maxStackSize(entry.sample);
            long offset = 0L;
            while (offset < entry.amount) {
                references.add(new DisplayReference(entry.entryId, offset, maxStack));
                offset += maxStack;
            }
        }
        int requiredPages = (references.size() + contentSlotsPerPage - 1) / contentSlotsPerPage;
        int pageCount = Math.max(configuredMaxPages(), Math.max(1, requiredPages));
        return new ViewSnapshot(effectiveSort, contentSlotsPerPage, pageCount, references);
    }

    /** 解析打开会话中的稳定引用，条目失效时返回 null。 */
    public synchronized DisplayItem getDisplayItem(DisplayReference reference) {
        if (reference == null) {
            return null;
        }
        StoredEntry entry = entriesById.get(Long.valueOf(reference.entryId));
        if (entry == null || entry.amount <= reference.offset) {
            return null;
        }
        int displayAmount = config == null || config.getMode() == TrashConfig.GlobalTrashMode.COMPACT
                ? 1 : (int) Math.min((long) reference.maxDisplayAmount, entry.amount - reference.offset);
        return new DisplayItem(entry.entryId, entry.key, entry.trackingKey,
                entry.sample, entry.amount, displayAmount);
    }

    /** 读取指定页内容槽位置的插入顺序展示快照。 */
    public synchronized DisplayItem getDisplayItem(int pageIndex, int contentIndex) {
        ViewSnapshot snapshot = createViewSnapshot(TrashConfig.GlobalTrashSortType.INSERTION);
        return getDisplayItem(snapshot.getReference(pageIndex, contentIndex));
    }

    /** 返回当前需要显示的页数，旧存量超出新容量时保留临时可取页面。 */
    public synchronized int getPageCount() {
        return createViewSnapshot(TrashConfig.GlobalTrashSortType.INSERTION).getPageCount();
    }

    /** 返回公共垃圾桶内的物品总数量，溢出 int 时饱和到最大值。 */
    public synchronized int getStoredItemAmount() {
        long amount = 0L;
        for (StoredEntry entry : entries.values()) {
            if (Long.MAX_VALUE - amount < entry.amount) {
                return Integer.MAX_VALUE;
            }
            amount += entry.amount;
        }
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    /** 返回当前模式实际显示的物品堆叠数量。 */
    public synchronized int getStoredStackCount() {
        return createViewSnapshot(TrashConfig.GlobalTrashSortType.INSERTION).getReferenceCount();
    }

    /** 清空公共垃圾桶模型存量，但不复用本生命周期已经发出的条目 ID。 */
    public synchronized void clear() {
        entries.clear();
        entriesById.clear();
    }

    /** 移除指定存储条目的数量并同步两个索引。 */
    private int removeEntry(StoredEntry entry, long requestedAmount) {
        if (entry == null || requestedAmount <= 0L) {
            return 0;
        }
        long removed = Math.min(requestedAmount, entry.amount);
        entry.amount -= removed;
        if (entry.amount <= 0L) {
            entries.remove(entry.key);
            entriesById.remove(Long.valueOf(entry.entryId));
        }
        return (int) Math.min(Integer.MAX_VALUE, removed);
    }

    /** 返回不会在当前插件生命周期内重复的条目 ID。 */
    private long nextEntryId() {
        long result = nextEntryId++;
        if (nextEntryId <= 0L) {
            nextEntryId = 1L;
        }
        while (entriesById.containsKey(Long.valueOf(result))) {
            result = nextEntryId++;
        }
        return result;
    }

    /** 使用本存储生命周期和稳定条目 ID 生成不包含物品 NBT 的追踪键。 */
    private String trackingKey(long entryId) {
        return trackingPrefix + ':' + lifecycleId + ':' + entryId;
    }

    /** 创建只读取缓存字段和当前数量的稳定比较器。 */
    private Comparator<StoredEntry> comparator(final TrashConfig.GlobalTrashSortType sortType) {
        return new Comparator<StoredEntry>() {
            /** 比较两个存储条目，并始终用条目 ID 稳定兜底。 */
            @Override
            public int compare(StoredEntry left, StoredEntry right) {
                int compared = 0;
                if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_DESC) {
                    compared = Long.compare(right.amount, left.amount);
                } else if (sortType == TrashConfig.GlobalTrashSortType.AMOUNT_ASC) {
                    compared = Long.compare(left.amount, right.amount);
                } else if (sortType == TrashConfig.GlobalTrashSortType.NAME_ASC) {
                    compared = left.sortName.compareTo(right.sortName);
                } else if (sortType == TrashConfig.GlobalTrashSortType.MATERIAL_ASC) {
                    compared = left.materialName.compareTo(right.materialName);
                }
                return compared != 0 ? compared : Long.compare(left.entryId, right.entryId);
            }
        };
    }

    /** 提取一次并缓存用于名称排序的稳定文本。 */
    private String sortName(ItemStack itemStack) {
        ItemMeta meta = null;
        try {
            meta = itemStack == null ? null : itemStack.getItemMeta();
        } catch (RuntimeException ignored) {
            // 纯模型单元测试没有 Bukkit ItemFactory，按材质名稳定降级。
        }
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName()
                : itemStack == null ? "" : itemStack.getType().name();
        String stripped = ChatColor.stripColor(name);
        return (stripped == null ? name : stripped).toLowerCase(Locale.ROOT);
    }

    /** 计算指定身份在当前正常页容量中的可用数量。 */
    private long capacityFor(String key, ItemStack incoming) {
        if (config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
            return compactCapacity(key);
        }
        return stackedCapacity(key, incoming);
    }

    /** 计算紧凑模式的单条目和空条目容量。 */
    private long compactCapacity(String key) {
        StoredEntry entry = entries.get(key);
        long maxAmount = config.getCompact().getMaxAmountPerEntry();
        if (entry != null) {
            return maxAmount < 0L ? Long.MAX_VALUE : Math.max(0L, maxAmount - entry.amount);
        }
        long maxEntries = (long) configuredMaxPages() * contentSlotsPerPage;
        if (entries.size() >= maxEntries) {
            return 0L;
        }
        return maxAmount < 0L ? Long.MAX_VALUE : maxAmount;
    }

    /** 返回零接收时的精确拒绝原因。 */
    private TrashWriteResult.Status rejectionStatus(String key) {
        if (config != null && config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
            StoredEntry entry = entries.get(key);
            long maxAmount = config.getCompact().getMaxAmountPerEntry();
            if (entry != null && maxAmount >= 0L && entry.amount >= maxAmount) {
                return TrashWriteResult.Status.REJECTED_ENTRY_LIMIT;
            }
        }
        return TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY;
    }

    /** 判断清空后的容器能否按当前写入模式接收本次请求。 */
    private boolean canEmptyContainerAccept(ItemStack itemStack, boolean allowPartial) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return false;
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return false;
        }
        long capacity;
        if (config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
            capacity = config.getCompact().getMaxAmountPerEntry();
            if (capacity < 0L) {
                capacity = Long.MAX_VALUE;
            }
        } else {
            long totalSlots = (long) configuredMaxPages() * contentSlotsPerPage;
            capacity = multiplySaturated(totalSlots, maxStackSize(itemStack));
        }
        return allowPartial ? capacity > 0L : capacity >= itemStack.getAmount();
    }

    /** 计算旧堆叠模式中现有堆叠和空槽位的容量。 */
    private long stackedCapacity(String key, ItemStack incoming) {
        long totalSlots = (long) configuredMaxPages() * contentSlotsPerPage;
        long usedSlots = 0L;
        long freeInMatching = 0L;
        for (StoredEntry entry : entries.values()) {
            int maxStack = maxStackSize(entry.sample);
            long used = stackSlots(entry.amount, maxStack);
            usedSlots += used;
            if (entry.key.equals(key)) {
                freeInMatching += used * maxStack - entry.amount;
            }
        }
        long emptySlots = Math.max(0L, totalSlots - usedSlots);
        long emptyCapacity = multiplySaturated(emptySlots, maxStackSize(incoming));
        return addSaturated(freeInMatching, emptyCapacity);
    }

    /** 返回当前模式配置的正常页数。 */
    private int configuredMaxPages() {
        if (config == null) {
            return 1;
        }
        return config.getMode() == TrashConfig.GlobalTrashMode.COMPACT
                ? config.getCompact().getMaxPages() : config.getStacked().getMaxPages();
    }

    /** 返回物品可以使用的最大堆叠数。 */
    private int maxStackSize(ItemStack itemStack) {
        return itemStack == null ? 1 : Math.max(1, itemStack.getMaxStackSize());
    }

    /** 计算一个逻辑物品需要的显示堆叠数。 */
    private long stackSlots(long amount, int maxStack) {
        if (amount <= 0L) {
            return 0L;
        }
        return amount / maxStack + (amount % maxStack == 0L ? 0L : 1L);
    }

    /** 饱和计算乘法，避免无限存量导致溢出。 */
    private long multiplySaturated(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    /** 饱和计算加法，避免容量计算溢出。 */
    private long addSaturated(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** 一个逻辑物品在模型中的存储条目。 */
    private static final class StoredEntry {
        private final long entryId;
        private final String key;
        private final String trackingKey;
        private final ItemStack sample;
        private final String sortName;
        private final String materialName;
        private long amount;

        /** 创建带稳定 ID 和预计算排序字段的存储条目。 */
        private StoredEntry(long entryId, String key, String trackingKey, ItemStack sample, long amount,
                            String sortName, String materialName) {
            this.entryId = entryId;
            this.key = key;
            this.trackingKey = trackingKey;
            this.sample = sample;
            this.amount = amount;
            this.sortName = sortName;
            this.materialName = materialName;
        }
    }

    /** 打开会话保存的轻量展示引用。 */
    public static final class DisplayReference {
        private final long entryId;
        private final long offset;
        private final int maxDisplayAmount;

        /** 创建不持有 ItemStack 的稳定条目引用。 */
        private DisplayReference(long entryId, long offset, int maxDisplayAmount) {
            this.entryId = entryId;
            this.offset = offset;
            this.maxDisplayAmount = maxDisplayAmount;
        }

        /** 返回引用的稳定条目 ID。 */
        public long getEntryId() {
            return entryId;
        }
    }

    /** 一次打开公共垃圾桶时冻结的排序和分页引用。 */
    public static final class ViewSnapshot {
        private final TrashConfig.GlobalTrashSortType sortType;
        private final int contentSlotsPerPage;
        private final int pageCount;
        private final List<DisplayReference> references;

        /** 创建不复制 ItemStack 的不可变视图快照。 */
        private ViewSnapshot(TrashConfig.GlobalTrashSortType sortType, int contentSlotsPerPage,
                             int pageCount, List<DisplayReference> references) {
            this.sortType = sortType;
            this.contentSlotsPerPage = contentSlotsPerPage;
            this.pageCount = pageCount;
            this.references = Collections.unmodifiableList(new ArrayList<>(references));
        }

        /** 返回指定页和内容下标的稳定引用。 */
        public DisplayReference getReference(int pageIndex, int contentIndex) {
            if (pageIndex < 0 || contentIndex < 0) {
                return null;
            }
            long flatIndex = (long) pageIndex * contentSlotsPerPage + contentIndex;
            return flatIndex >= 0L && flatIndex < references.size()
                    ? references.get((int) flatIndex) : null;
        }

        /** 返回本次打开固定使用的排序方式。 */
        public TrashConfig.GlobalTrashSortType getSortType() {
            return sortType;
        }

        /** 返回本次打开固定使用的总页数。 */
        public int getPageCount() {
            return pageCount;
        }

        /** 返回轻量展示引用数量。 */
        public int getReferenceCount() {
            return references.size();
        }
    }

    /** 一个页面槽位对应的只读展示快照。 */
    public static final class DisplayItem {
        private final long entryId;
        private final String key;
        private final String trackingKey;
        private final ItemStack sample;
        private final long logicalAmount;
        private final int displayAmount;

        /** 创建展示快照。 */
        private DisplayItem(long entryId, String key, String trackingKey, ItemStack sample,
                            long logicalAmount, int displayAmount) {
            this.entryId = entryId;
            this.key = key;
            this.trackingKey = trackingKey;
            this.sample = sample.clone();
            this.sample.setAmount(1);
            this.logicalAmount = logicalAmount;
            this.displayAmount = displayAmount;
        }

        /** 返回稳定条目 ID。 */
        public long getEntryId() {
            return entryId;
        }

        /** 返回模型身份键。 */
        public String getKey() {
            return key;
        }

        /** 返回主存储条目的不透明审计追踪键。 */
        public String getTrackingKey() {
            return trackingKey;
        }

        /** 返回不带显示数量修改的物品样本副本。 */
        public ItemStack getSample() {
            return sample.clone();
        }

        /** 返回逻辑物品累计数量。 */
        public long getLogicalAmount() {
            return logicalAmount;
        }

        /** 返回当前展示物数量。 */
        public int getDisplayAmount() {
            return displayAmount;
        }
    }
}
