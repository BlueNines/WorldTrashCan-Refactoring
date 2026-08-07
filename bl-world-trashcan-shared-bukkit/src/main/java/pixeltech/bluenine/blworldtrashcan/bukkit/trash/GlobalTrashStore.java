package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSimilarIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 公共垃圾桶的模型优先内存存储，不使用 Bukkit Inventory 保存业务状态。 */
public final class GlobalTrashStore {
    private final ItemIdentityProvider identityProvider;
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();
    private TrashConfig.GlobalTrashConfig config;
    private int contentSlotsPerPage = 1;

    /** 创建公共垃圾桶模型存储。 */
    public GlobalTrashStore(ItemIdentityProvider identityProvider) {
        this.identityProvider = identityProvider == null
                ? new BukkitSimilarIdentityProvider() : identityProvider;
    }

    /** 应用布局容量和模式配置，但保留当前运行期物品存量。 */
    public synchronized void configure(TrashConfig.GlobalTrashConfig nextConfig, int contentSlots) {
        this.config = nextConfig;
        this.contentSlotsPerPage = Math.max(1, contentSlots);
    }

    /** 返回固定的身份实现名称。 */
    public String getIdentityProviderId() {
        return identityProvider.id();
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

    /** 添加物品；allowPartial 为 true 时返回尽可能接受的数量。 */
    public synchronized int add(ItemStack itemStack, boolean allowPartial) {
        if (config == null || itemStack == null || itemStack.getAmount() <= 0) {
            return 0;
        }
        String key = identityProvider.key(itemStack);
        if (key == null) {
            return 0;
        }
        long requested = itemStack.getAmount();
        long capacity = capacityFor(key, itemStack);
        long accepted = allowPartial ? Math.min(requested, capacity) : requested <= capacity ? requested : 0L;
        if (accepted <= 0L) {
            return 0;
        }
        StoredEntry entry = entries.get(key);
        if (entry == null) {
            ItemStack sample = itemStack.clone();
            sample.setAmount(1);
            entry = new StoredEntry(key, sample, accepted);
            entries.put(key, entry);
        } else {
            entry.amount += accepted;
        }
        return (int) accepted;
    }

    /** 按身份键移除已经成功交给玩家的数量。 */
    public synchronized int remove(String key, long requestedAmount) {
        if (key == null || requestedAmount <= 0L) {
            return 0;
        }
        StoredEntry entry = entries.get(key);
        if (entry == null) {
            return 0;
        }
        long removed = Math.min(requestedAmount, entry.amount);
        entry.amount -= removed;
        if (entry.amount <= 0L) {
            entries.remove(key);
        }
        return (int) Math.min(Integer.MAX_VALUE, removed);
    }

    /** 读取指定页内容槽位置的展示快照。 */
    public synchronized DisplayItem getDisplayItem(int pageIndex, int contentIndex) {
        if (pageIndex < 0 || contentIndex < 0) {
            return null;
        }
        List<DisplayItem> displayItems = buildDisplayItems();
        long flatIndex = (long) pageIndex * contentSlotsPerPage + contentIndex;
        if (flatIndex < 0L || flatIndex >= displayItems.size()) {
            return null;
        }
        return displayItems.get((int) flatIndex);
    }

    /** 返回当前需要显示的页数，旧存量超出新容量时保留临时可取页面。 */
    public synchronized int getPageCount() {
        int normalPages = configuredMaxPages();
        List<DisplayItem> displayItems = buildDisplayItems();
        int requiredPages = (displayItems.size() + contentSlotsPerPage - 1) / contentSlotsPerPage;
        return Math.max(normalPages, Math.max(1, requiredPages));
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
        return buildDisplayItems().size();
    }

    /** 清空公共垃圾桶模型存量。 */
    public synchronized void clear() {
        entries.clear();
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

    /** 构造紧凑模式或旧堆叠模式的展示快照列表。 */
    private List<DisplayItem> buildDisplayItems() {
        List<DisplayItem> result = new ArrayList<>();
        for (StoredEntry entry : entries.values()) {
            if (config == null || config.getMode() == TrashConfig.GlobalTrashMode.COMPACT) {
                result.add(new DisplayItem(entry.key, entry.sample, entry.amount, 1));
                continue;
            }
            int maxStack = maxStackSize(entry.sample);
            long remaining = entry.amount;
            while (remaining > 0L) {
                int amount = (int) Math.min((long) maxStack, remaining);
                result.add(new DisplayItem(entry.key, entry.sample, entry.amount, amount));
                remaining -= amount;
            }
        }
        return result;
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
        private final String key;
        private final ItemStack sample;
        private long amount;

        /** 创建存储条目。 */
        private StoredEntry(String key, ItemStack sample, long amount) {
            this.key = key;
            this.sample = sample;
            this.amount = amount;
        }
    }

    /** 一个页面槽位对应的只读展示快照。 */
    public static final class DisplayItem {
        private final String key;
        private final ItemStack sample;
        private final long logicalAmount;
        private final int displayAmount;

        /** 创建展示快照。 */
        private DisplayItem(String key, ItemStack sample, long logicalAmount, int displayAmount) {
            this.key = key;
            this.sample = sample.clone();
            this.sample.setAmount(1);
            this.logicalAmount = logicalAmount;
            this.displayAmount = displayAmount;
        }

        /** 返回模型身份键。 */
        public String getKey() {
            return key;
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
