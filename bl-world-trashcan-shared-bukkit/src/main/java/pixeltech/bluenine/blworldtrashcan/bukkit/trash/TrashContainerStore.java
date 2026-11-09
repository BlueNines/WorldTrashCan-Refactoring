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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 公共和个人垃圾桶共用的模型存储，不使用 Bukkit Inventory 保存业务状态。 */
public class TrashContainerStore {
    private final ItemIdentityProvider identityProvider;
    private final String trackingPrefix;
    private final String lifecycleId = UUID.randomUUID().toString();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();
    private final Map<Long, StoredEntry> entriesById = new LinkedHashMap<>();
    private final Map<TrashConfig.GlobalTrashSortType, ViewSnapshot> sortedSnapshots =
            new EnumMap<>(TrashConfig.GlobalTrashSortType.class);
    private TrashConfig.TrashContainerConfig config;
    private int contentSlotsPerPage = 1;
    private long nextEntryId = 1L;
    private long nextGroupId = 1L;
    private final NavigableMap<Long, StackGroup> groups = new TreeMap<>();
    private Consumer<TrashContainerStore> changeListener;

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
        boolean wasStacked = isStacked();
        this.config = nextConfig;
        this.contentSlotsPerPage = Math.max(1, contentSlots);
        if (wasStacked != isStacked()) {
            groups.clear();
            for (StoredEntry entry : entries.values()) {
                entry.groups.clear();
                entry.partialGroups.clear();
                if (isStacked()) {
                    addGroups(entry, entry.amount);
                }
            }
        }
        sortedSnapshots.clear();
    }

    /** 仅菜单打开期间订阅存储变更；回调只排队，不直接跨线程读写玩家。 */
    synchronized void setChangeListener(Consumer<TrashContainerStore> listener) {
        changeListener = listener;
    }

    /** 返回当前是否使用独立余量的单组索引。 */
    synchronized boolean isStacked() {
        return config != null && config.getMode() == TrashConfig.GlobalTrashMode.STACKED;
    }

    /** 统一失效排序缓存并通知单组视图，失败写入不通知。 */
    private void changed() {
        sortedSnapshots.clear();
        if (isStacked() && changeListener != null) {
            changeListener.accept(this);
        }
    }

    /** 先补未满组，再批量创建新组；每组仅保存 ID 与数量，不复制物品数据。 */
    private void addGroups(StoredEntry entry, long amount) {
        int max = maxStackSize(entry.sample);
        Iterator<StackGroup> partial = entry.partialGroups.values().iterator();
        while (amount > 0 && partial.hasNext()) {
            StackGroup group = partial.next();
            int added = (int) Math.min(amount, max - group.amount);
            group.amount += added;
            amount -= added;
            if (group.amount == max) {
                partial.remove();
            }
        }
        while (amount > 0) {
            int added = (int) Math.min(amount, max);
            StackGroup group = new StackGroup(nextGroupId++, entry.entryId, added);
            groups.put(group.id, group);
            entry.groups.put(group.id, group);
            if (added < max) {
                entry.partialGroups.put(group.id, group);
            }
            amount -= added;
        }
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
        return add(itemStack, itemStack == null ? 0 : itemStack.getAmount(), allowPartial);
    }

    /** 按独立实际数量添加样品，避免为逻辑堆叠制造超上限 ItemStack。 */
    public synchronized TrashWriteResult add(ItemStack itemStack, int requested, boolean allowPartial) {
        if (config == null || itemStack == null || requested <= 0) {
            return TrashWriteResult.rejected();
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return TrashWriteResult.rejected();
        }
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
        if (isStacked()) {
            addGroups(entry, accepted);
        }
        changed();
        return TrashWriteResult.accepted((int) accepted, requested, entry.trackingKey, false);
    }

    /** 容量拒绝时原子清空并只重试一次，其它拒绝和部分接收绝不清空。 */
    public synchronized TrashWriteResult addWithClearRetry(ItemStack itemStack, boolean allowPartial) {
        return addWithClearRetry(itemStack, itemStack == null ? 0 : itemStack.getAmount(), allowPartial);
    }

    /** 按独立实际数量执行一次容量清空重试。 */
    public synchronized TrashWriteResult addWithClearRetry(ItemStack itemStack, int requested,
                                                           boolean allowPartial) {
        TrashWriteResult first = add(itemStack, requested, allowPartial);
        if (first.getStatus() != TrashWriteResult.Status.REJECTED_CONTAINER_CAPACITY) {
            return first;
        }
        if (!canEmptyContainerAccept(itemStack, false)) {
            return first;
        }
        clear();
        TrashWriteResult retried = add(itemStack, requested, allowPartial);
        if (!retried.isAccepted()) {
            return retried;
        }
        return TrashWriteResult.accepted(retried.getAcceptedAmount(), requested,
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

    /** 菜单只扣除被点击的独立组，不能从同类其它组补取。 */
    public synchronized int remove(DisplayReference reference, long requestedAmount) {
        if (reference == null || requestedAmount <= 0) {
            return 0;
        }
        if (!isStacked()) {
            return remove(reference.entryId, requestedAmount);
        }
        StackGroup group = groups.get(reference.groupId);
        StoredEntry entry = entriesById.get(reference.entryId);
        if (group == null || entry == null || group.entryId != entry.entryId) {
            return 0;
        }
        int removed = (int) Math.min(requestedAmount, group.amount);
        reduceGroup(entry, group, removed);
        entry.amount -= removed;
        removeEmptyEntry(entry);
        changed();
        return removed;
    }

    /** 按不透明追踪键回滚一次尚未提交的写入；仅错误路径执行线性查找。 */
    public synchronized int rollback(String trackingKey, long requestedAmount) {
        if (trackingKey == null || trackingKey.isEmpty() || requestedAmount <= 0L) {
            return 0;
        }
        for (StoredEntry entry : entriesById.values()) {
            if (trackingKey.equals(entry.trackingKey)) {
                return removeEntry(entry, requestedAmount);
            }
        }
        return 0;
    }

    /** 按排序方式复用当前库存的不可变引用快照，成功写入或扣减后才重新排序。 */
    public synchronized ViewSnapshot createViewSnapshot(TrashConfig.GlobalTrashSortType sortType) {
        TrashConfig.GlobalTrashSortType effectiveSort = sortType == null
                ? TrashConfig.GlobalTrashSortType.INSERTION : sortType;
        ViewSnapshot cached = sortedSnapshots.get(effectiveSort);
        if (cached != null) {
            return cached;
        }
        List<StoredEntry> ordered = new ArrayList<>(entries.values());
        if (effectiveSort != TrashConfig.GlobalTrashSortType.INSERTION) {
            Collections.sort(ordered, comparator(effectiveSort));
        }
        List<DisplayReference> references = new ArrayList<>();
        for (StoredEntry entry : ordered) {
            if (config == null || config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
                references.add(new DisplayReference(entry.entryId, 0L));
                continue;
            }
            for (StackGroup group : entry.groups.values()) {
                references.add(new DisplayReference(entry.entryId, group.id));
            }
        }
        int requiredPages = (references.size() + contentSlotsPerPage - 1) / contentSlotsPerPage;
        int pageCount = Math.max(configuredMaxPages(), Math.max(1, requiredPages));
        ViewSnapshot snapshot = new ViewSnapshot(effectiveSort, contentSlotsPerPage, pageCount, references,
                nextGroupId - 1);
        sortedSnapshots.put(effectiveSort, snapshot);
        return snapshot;
    }

    /** 只把指定物品身份缺少的引用补入旧快照，不移动任何仍有效的现有引用。 */
    synchronized ViewSnapshot refreshIdentityInSnapshot(ViewSnapshot current, String identityKey) {
        if (isStacked()) {
            return refreshStackedSnapshot(current);
        }
        if (current == null || config == null || identityKey == null) {
            return current;
        }
        StoredEntry changedEntry = entries.get(identityKey);
        if (changedEntry == null) {
            return current;
        }
        List<Integer> reusableIndexes = new ArrayList<>();
        Set<Long> referencedOffsets = new HashSet<>();
        for (int index = 0; index < current.references.size(); index++) {
            DisplayReference reference = current.references.get(index);
            StoredEntry referencedEntry = entriesById.get(Long.valueOf(reference.entryId));
            if (referencedEntry == null) {
                reusableIndexes.add(Integer.valueOf(index));
                continue;
            }
            if (reference.entryId == changedEntry.entryId) {
                referencedOffsets.add(Long.valueOf(reference.groupId));
            }
        }
        List<DisplayReference> missing = missingReferences(changedEntry, referencedOffsets);
        if (missing.isEmpty()) {
            return current;
        }
        List<DisplayReference> refreshed = new ArrayList<>(current.references);
        int reusableIndex = 0;
        for (DisplayReference reference : missing) {
            if (reusableIndex < reusableIndexes.size()) {
                refreshed.set(reusableIndexes.get(reusableIndex).intValue(), reference);
                reusableIndex++;
                continue;
            }
            refreshed.add(reference);
        }
        int requiredPages = (refreshed.size() + current.contentSlotsPerPage - 1)
                / current.contentSlotsPerPage;
        int pageCount = Math.max(current.pageCount, Math.max(configuredMaxPages(), requiredPages));
        return new ViewSnapshot(current.sortType, current.contentSlotsPerPage, pageCount, refreshed);
    }

    /** 返回本次身份变化后最应该展示的页面，优先定位新增引用。 */
    synchronized int findChangedIdentityPage(ViewSnapshot previous, ViewSnapshot refreshed,
                                             String identityKey, int currentPage) {
        if (refreshed == null || identityKey == null) {
            return -1;
        }
        StoredEntry changedEntry = entries.get(identityKey);
        if (changedEntry == null) {
            return -1;
        }
        Set<Long> previousOffsets = referencedOffsets(previous, changedEntry.entryId);
        int firstPage = -1;
        int currentMatchPage = -1;
        for (int index = 0; index < refreshed.references.size(); index++) {
            DisplayReference reference = refreshed.references.get(index);
            if (reference.entryId != changedEntry.entryId || getDisplayAmount(reference) <= 0) {
                continue;
            }
            int page = index / refreshed.contentSlotsPerPage;
            if (firstPage < 0) {
                firstPage = page;
            }
            if (!previousOffsets.contains(Long.valueOf(reference.groupId))) {
                return page;
            }
            if (page == currentPage) {
                currentMatchPage = page;
            }
        }
        return currentMatchPage >= 0 ? currentMatchPage : firstPage;
    }

    /** 收集旧快照中指定条目的展示偏移，供增量页定位使用。 */
    private Set<Long> referencedOffsets(ViewSnapshot snapshot, long entryId) {
        Set<Long> offsets = new HashSet<>();
        if (snapshot == null) {
            return offsets;
        }
        for (DisplayReference reference : snapshot.references) {
            if (reference.entryId == entryId) {
                offsets.add(Long.valueOf(reference.groupId));
            }
        }
        return offsets;
    }

    /** 生成指定条目尚未出现在旧视图中的紧凑或原始堆叠引用。 */
    private List<DisplayReference> missingReferences(StoredEntry entry, Set<Long> referencedOffsets) {
        List<DisplayReference> missing = new ArrayList<>();
        if (config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
            if (!referencedOffsets.contains(Long.valueOf(0L))) {
                missing.add(new DisplayReference(entry.entryId, 0L));
            }
            return missing;
        }
        for (StackGroup group : entry.groups.values()) {
            if (!referencedOffsets.contains(Long.valueOf(group.id))) {
                missing.add(new DisplayReference(entry.entryId, group.id));
            }
        }
        return missing;
    }

    /** 解析打开会话中的稳定引用，条目失效时返回 null。 */
    public synchronized DisplayItem getDisplayItem(DisplayReference reference) {
        if (reference == null) {
            return null;
        }
        StoredEntry entry = entriesById.get(Long.valueOf(reference.entryId));
        int displayAmount = getDisplayAmount(reference);
        if (entry == null || displayAmount <= 0) {
            return null;
        }
        return new DisplayItem(entry.entryId, entry.key, entry.trackingKey,
                entry.sample, entry.amount, displayAmount);
    }

    /** 只读轻量组数量，未变槽位不克隆物品、不渲染 Lore。 */
    synchronized int getDisplayAmount(DisplayReference reference) {
        if (reference == null || !entriesById.containsKey(reference.entryId)) {
            return 0;
        }
        if (!isStacked()) {
            return 1;
        }
        StackGroup group = groups.get(reference.groupId);
        return group == null || group.entryId != reference.entryId ? 0 : group.amount;
    }

    /** 只为打开后新增的组填空位；单纯取出无需复制或遍历整个快照。 */
    synchronized ViewSnapshot refreshStackedSnapshot(ViewSnapshot current) {
        if (current == null || current.lastGroupId == nextGroupId - 1) {
            return current;
        }
        List<DisplayReference> refreshed = null;
        int vacant = 0;
        for (StackGroup group : groups.tailMap(current.lastGroupId, false).values()) {
            if (refreshed == null) {
                refreshed = new ArrayList<>(current.references);
            }
            while (vacant < refreshed.size() && getDisplayAmount(refreshed.get(vacant)) > 0) {
                vacant++;
            }
            DisplayReference reference = new DisplayReference(group.entryId, group.id);
            if (vacant < refreshed.size()) {
                refreshed.set(vacant, reference);
            } else {
                refreshed.add(reference);
            }
            vacant++;
        }
        if (refreshed == null) {
            return new ViewSnapshot(current, current.pageCount, nextGroupId - 1);
        }
        int requiredPages = (refreshed.size() + contentSlotsPerPage - 1) / contentSlotsPerPage;
        return new ViewSnapshot(current.sortType, contentSlotsPerPage,
                Math.max(current.pageCount, requiredPages), refreshed, nextGroupId - 1);
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
        if (isStacked()) {
            return groups.size();
        }
        return createViewSnapshot(TrashConfig.GlobalTrashSortType.INSERTION).getReferenceCount();
    }

    /** 清空公共垃圾桶模型存量，但不复用本生命周期已经发出的条目 ID。 */
    public synchronized void clear() {
        entries.clear();
        entriesById.clear();
        groups.clear();
        changed();
    }

    /** 移除指定存储条目的数量并同步两个索引。 */
    private int removeEntry(StoredEntry entry, long requestedAmount) {
        if (entry == null || requestedAmount <= 0L) {
            return 0;
        }
        long removed = Math.min(requestedAmount, entry.amount);
        long remaining = removed;
        while (isStacked() && remaining > 0 && !entry.groups.isEmpty()) {
            StackGroup group = entry.groups.values().iterator().next();
            int reduction = (int) Math.min(remaining, group.amount);
            reduceGroup(entry, group, reduction);
            remaining -= reduction;
        }
        entry.amount -= removed;
        removeEmptyEntry(entry);
        changed();
        return (int) Math.min(Integer.MAX_VALUE, removed);
    }

    /** 原地减少指定组并维护空组和未满组索引。 */
    private void reduceGroup(StoredEntry entry, StackGroup group, int removed) {
        group.amount -= removed;
        if (group.amount == 0) {
            groups.remove(group.id);
            entry.groups.remove(group.id);
            entry.partialGroups.remove(group.id);
        } else {
            entry.partialGroups.put(group.id, group);
        }
    }

    /** 总量归零时移除业务条目，不复用旧 ID。 */
    private void removeEmptyEntry(StoredEntry entry) {
        if (entry.amount <= 0L) {
            entries.remove(entry.key);
            entriesById.remove(Long.valueOf(entry.entryId));
        }
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
        long usedSlots = groups.size();
        long freeInMatching = 0L;
        StoredEntry entry = entries.get(key);
        if (entry != null) {
            int maxStack = maxStackSize(entry.sample);
            long used = entry.groups.size();
            freeInMatching = used * maxStack - entry.amount;
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
        private final Map<Long, StackGroup> groups = new LinkedHashMap<>();
        private final Map<Long, StackGroup> partialGroups = new TreeMap<>();

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

    /** 单组的运行期索引；真实物品样本和总量仍只保存在业务条目中。 */
    private static final class StackGroup {
        private final long id;
        private final long entryId;
        private int amount;

        /** 创建不持有物品副本的稳定分组。 */
        private StackGroup(long id, long entryId, int amount) {
            this.id = id;
            this.entryId = entryId;
            this.amount = amount;
        }
    }

    /** 打开会话保存的轻量展示引用。 */
    public static final class DisplayReference {
        private final long entryId;
        private final long groupId;

        /** 创建不持有 ItemStack 的稳定条目引用。 */
        private DisplayReference(long entryId, long groupId) {
            this.entryId = entryId;
            this.groupId = groupId;
        }

        /** 返回引用的稳定条目 ID。 */
        public long getEntryId() {
            return entryId;
        }

        /** 返回稳定组 ID，紧凑模式为零。 */
        long getGroupId() {
            return groupId;
        }
    }

    /** 单次库存状态的不可变排序与分页引用；每名玩家独立选择何时替换。 */
    public static final class ViewSnapshot {
        private final TrashConfig.GlobalTrashSortType sortType;
        private final int contentSlotsPerPage;
        private final int pageCount;
        private final List<DisplayReference> references;
        private final long lastGroupId;

        /** 创建不复制 ItemStack 的不可变视图快照。 */
        private ViewSnapshot(TrashConfig.GlobalTrashSortType sortType, int contentSlotsPerPage,
                             int pageCount, List<DisplayReference> references) {
            this(sortType, contentSlotsPerPage, pageCount, references, 0L);
        }

        /** 保存快照已见到的最大组序号，增量同步只查找其后新增组。 */
        private ViewSnapshot(TrashConfig.GlobalTrashSortType sortType, int contentSlotsPerPage,
                             int pageCount, List<DisplayReference> references, long lastGroupId) {
            this.sortType = sortType;
            this.contentSlotsPerPage = contentSlotsPerPage;
            this.pageCount = pageCount;
            this.references = Collections.unmodifiableList(new ArrayList<>(references));
            this.lastGroupId = lastGroupId;
        }

        /** 沿用已打开窗口的页数，避免库存收缩令当前页失效；不复制引用数组。 */
        ViewSnapshot retainPageCount(int minimumPages) {
            return minimumPages <= pageCount ? this : new ViewSnapshot(this, minimumPages);
        }

        /** 为当前窗口共享排序引用，仅保留其已有的空白尾页。 */
        private ViewSnapshot(ViewSnapshot source, int pageCount) {
            this(source, pageCount, source.lastGroupId);
        }

        /** 共享不可变引用，仅推进会话的已见序号或保留尾页。 */
        private ViewSnapshot(ViewSnapshot source, int pageCount, long lastGroupId) {
            this.sortType = source.sortType;
            this.contentSlotsPerPage = source.contentSlotsPerPage;
            this.pageCount = pageCount;
            this.references = source.references;
            this.lastGroupId = lastGroupId;
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
