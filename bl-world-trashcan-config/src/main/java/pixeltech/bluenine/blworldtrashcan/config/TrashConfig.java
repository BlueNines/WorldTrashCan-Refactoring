package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.ItemMatchRules;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 垃圾桶相关功能的类型化配置。 */
public final class TrashConfig {
    private final WorldTrashConfig worldTrash;
    private final GlobalTrashConfig globalTrash;
    private final PersonalTrashConfig personalTrash;

    /** 创建垃圾桶配置。 */
    public TrashConfig(WorldTrashConfig worldTrash, GlobalTrashConfig globalTrash, PersonalTrashConfig personalTrash) {
        this.worldTrash = worldTrash;
        this.globalTrash = globalTrash;
        this.personalTrash = personalTrash;
    }

    /** 返回世界垃圾桶配置。 */
    public WorldTrashConfig getWorldTrash() {
        return worldTrash;
    }

    /** 返回公共垃圾桶配置。 */
    public GlobalTrashConfig getGlobalTrash() {
        return globalTrash;
    }

    /** 返回个人垃圾桶配置。 */
    public PersonalTrashConfig getPersonalTrash() {
        return personalTrash;
    }

    /** 世界垃圾桶配置。 */
    public static final class WorldTrashConfig {
        private final boolean enabled;
        private final String signCreateText;
        private final String signCreatedText;
        private final int defaultMaxCount;
        private final boolean allowLoadUnloadedChunks;
        private final Set<String> bannedWorlds;

        /** 创建世界垃圾桶配置。 */
        public WorldTrashConfig(boolean enabled, String signCreateText, String signCreatedText,
                                int defaultMaxCount, boolean allowLoadUnloadedChunks, Set<String> bannedWorlds) {
            this.enabled = enabled;
            this.signCreateText = defaultString(signCreateText, "[世界垃圾桶]");
            this.signCreatedText = defaultString(signCreatedText, "&b[&c世界垃圾桶&b]");
            this.defaultMaxCount = Math.max(0, defaultMaxCount);
            this.allowLoadUnloadedChunks = allowLoadUnloadedChunks;
            this.bannedWorlds = normalizeSet(bannedWorlds);
        }

        /** 判断世界垃圾桶是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回创建识别文本。 */
        public String getSignCreateText() {
            return signCreateText;
        }

        /** 返回创建成功后显示的文本。 */
        public String getSignCreatedText() {
            return signCreatedText;
        }

        /** 返回默认最大数量。 */
        public int getDefaultMaxCount() {
            return defaultMaxCount;
        }

        /** 判断是否允许写入未加载区块里的世界垃圾桶。 */
        public boolean isAllowLoadUnloadedChunks() {
            return allowLoadUnloadedChunks;
        }

        /** 判断世界是否禁止创建世界垃圾桶。 */
        public boolean isBannedWorld(String worldName) {
            return bannedWorlds.contains(normalize(worldName));
        }
    }

    /** 公共与个人虚拟垃圾桶共同使用的容器配置契约。 */
    public interface TrashContainerConfig {
        /** 判断容器是否启用。 */
        boolean isEnabled();

        /** 返回容器显示模式。 */
        GlobalTrashMode getMode();

        /** 返回紧凑模式配置。 */
        CompactGlobalTrashConfig getCompact();

        /** 返回堆叠模式配置。 */
        StackedGlobalTrashConfig getStacked();

        /** 返回当前模式最大页数。 */
        int getMaxPages();

        /** 返回拿取冷却毫秒。 */
        int getTakeDelayMillis();

        /** 判断是否允许玩家手动放入。 */
        boolean isAllowPlayerPut();

        /** 返回字符布局配置。 */
        GlobalTrashLayoutConfig getLayout();
    }

    /** 公共垃圾桶配置。 */
    public static final class GlobalTrashConfig implements TrashContainerConfig {
        private final boolean enabled;
        private final int takeDelayMillis;
        private final int clearEveryCleanups;
        private final boolean allowPlayerPut;
        private final boolean logEnabled;
        private final GlobalTrashLayoutConfig layout;
        private final Set<String> bannedMaterials;
        private final GlobalTrashMode mode;
        private final CompactGlobalTrashConfig compact;
        private final StackedGlobalTrashConfig stacked;
        private final GlobalTrashAdmissionWhitelistConfig admissionWhitelist;

        /** 创建兼容旧调用方的公共垃圾桶配置，旧构造器默认使用堆叠显示模式。 */
        public GlobalTrashConfig(boolean enabled, int maxPages, int takeDelayMillis,
                                 int clearEveryCleanups, boolean allowPlayerPut,
                                 boolean logEnabled, GlobalTrashLayoutConfig layout,
                                 Set<String> bannedMaterials) {
            this(enabled, takeDelayMillis, clearEveryCleanups, allowPlayerPut, logEnabled, layout,
                    bannedMaterials, GlobalTrashMode.STACKED,
                    CompactGlobalTrashConfig.defaults(), new StackedGlobalTrashConfig(maxPages),
                    GlobalTrashAdmissionWhitelistConfig.defaults());
        }

        /** 创建包含紧凑模式和旧堆叠模式独立配置的公共垃圾桶配置。 */
        public GlobalTrashConfig(boolean enabled, int takeDelayMillis, int clearEveryCleanups,
                                 boolean allowPlayerPut, boolean logEnabled,
                                 GlobalTrashLayoutConfig layout, Set<String> bannedMaterials,
                                 GlobalTrashMode mode, CompactGlobalTrashConfig compact,
                                 StackedGlobalTrashConfig stacked) {
            this(enabled, takeDelayMillis, clearEveryCleanups, allowPlayerPut, logEnabled, layout,
                    bannedMaterials, mode, compact, stacked, GlobalTrashAdmissionWhitelistConfig.defaults());
        }

        /** 创建包含公共桶准入白名单的完整配置。 */
        public GlobalTrashConfig(boolean enabled, int takeDelayMillis, int clearEveryCleanups,
                                 boolean allowPlayerPut, boolean logEnabled,
                                 GlobalTrashLayoutConfig layout, Set<String> bannedMaterials,
                                 GlobalTrashMode mode, CompactGlobalTrashConfig compact,
                                 StackedGlobalTrashConfig stacked,
                                 GlobalTrashAdmissionWhitelistConfig admissionWhitelist) {
            this.enabled = enabled;
            this.takeDelayMillis = Math.max(0, takeDelayMillis);
            this.clearEveryCleanups = clearEveryCleanups;
            this.allowPlayerPut = allowPlayerPut;
            this.logEnabled = logEnabled;
            this.layout = layout == null ? GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null) : layout;
            this.bannedMaterials = normalizeMaterialSet(bannedMaterials);
            this.mode = mode == null ? GlobalTrashMode.COMPACT : mode;
            this.compact = compact == null ? CompactGlobalTrashConfig.defaults() : compact;
            this.stacked = stacked == null ? new StackedGlobalTrashConfig(5) : stacked;
            this.admissionWhitelist = admissionWhitelist == null
                    ? GlobalTrashAdmissionWhitelistConfig.defaults()
                    : admissionWhitelist;
        }

        /** 判断公共垃圾桶是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回当前显示模式。 */
        public GlobalTrashMode getMode() {
            return mode;
        }

        /** 返回紧凑模式配置。 */
        public CompactGlobalTrashConfig getCompact() {
            return compact;
        }

        /** 返回旧堆叠模式配置。 */
        public StackedGlobalTrashConfig getStacked() {
            return stacked;
        }

        /** 返回当前显示模式的最大页数。 */
        public int getMaxPages() {
            return mode == GlobalTrashMode.COMPACT ? compact.getMaxPages() : stacked.getMaxPages();
        }

        /** 返回拿取冷却毫秒。 */
        public int getTakeDelayMillis() {
            return takeDelayMillis;
        }

        /** 返回每多少次清理前清空公共垃圾桶。 */
        public int getClearEveryCleanups() {
            return clearEveryCleanups;
        }

        /** 判断是否允许玩家手动放入公共垃圾桶。 */
        public boolean isAllowPlayerPut() {
            return allowPlayerPut;
        }

        /** 判断是否记录公共垃圾桶拿取和放入日志。 */
        public boolean isLogEnabled() {
            return logEnabled;
        }

        /** 返回公共垃圾桶 GUI 布局。 */
        public GlobalTrashLayoutConfig getLayout() {
            return layout;
        }

        /** 判断物品是否禁止进入公共垃圾桶。 */
        public boolean isBannedMaterial(String materialName) {
            return bannedMaterials.contains(normalizeMaterial(materialName));
        }

        /** 返回公共垃圾桶物品黑名单。 */
        public Set<String> getBannedMaterials() {
            return bannedMaterials;
        }

        /** 返回公共垃圾桶准入白名单配置。 */
        public GlobalTrashAdmissionWhitelistConfig getAdmissionWhitelist() {
            return admissionWhitelist;
        }
    }

    /** 公共垃圾桶的可选准入白名单。 */
    public static final class GlobalTrashAdmissionWhitelistConfig {
        private final boolean enabled;
        private final ItemMatchRules rules;
        private final RejectedCleanupAction rejectedCleanupAction;

        /** 创建公共垃圾桶准入白名单。 */
        public GlobalTrashAdmissionWhitelistConfig(boolean enabled, ItemMatchRules rules,
                                                   RejectedCleanupAction rejectedCleanupAction) {
            this.enabled = enabled;
            this.rules = rules == null ? ItemMatchRules.empty() : rules;
            this.rejectedCleanupAction = rejectedCleanupAction == null
                    ? RejectedCleanupAction.KEEP_GROUND
                    : rejectedCleanupAction;
        }

        /** 返回默认关闭的白名单。 */
        public static GlobalTrashAdmissionWhitelistConfig defaults() {
            return new GlobalTrashAdmissionWhitelistConfig(
                    false, ItemMatchRules.empty(), RejectedCleanupAction.KEEP_GROUND);
        }

        /** 判断公共桶准入白名单是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回五类准入规则。 */
        public ItemMatchRules getRules() {
            return rules;
        }

        /** 返回扫地物品被拒绝且无其它去向时的动作。 */
        public RejectedCleanupAction getRejectedCleanupAction() {
            return rejectedCleanupAction;
        }
    }

    /** 公共垃圾桶展示模式。 */
    public enum GlobalTrashMode {
        COMPACT,
        STACKED;

        /** 从配置文本解析展示模式，未知值回退紧凑模式。 */
        public static GlobalTrashMode parse(String value) {
            if (value == null) {
                return COMPACT;
            }
            return "stacked".equalsIgnoreCase(value.trim()) ? STACKED : COMPACT;
        }
    }

    /** 公共垃圾桶玩家视图排序方式。 */
    public enum GlobalTrashSortType {
        INSERTION("insertion"),
        AMOUNT_DESC("amount-desc"),
        AMOUNT_ASC("amount-asc"),
        NAME_ASC("name-asc"),
        MATERIAL_ASC("material-asc");

        private final String configValue;

        /** 创建带稳定配置值的排序方式。 */
        GlobalTrashSortType(String configValue) {
            this.configValue = configValue;
        }

        /** 返回配置和界面占位符使用的稳定名称。 */
        public String getConfigValue() {
            return configValue;
        }

        /** 返回循环中的下一种排序方式。 */
        public GlobalTrashSortType next() {
            GlobalTrashSortType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        /** 返回循环中的上一种排序方式。 */
        public GlobalTrashSortType previous() {
            GlobalTrashSortType[] values = values();
            return values[(ordinal() + values.length - 1) % values.length];
        }

        /** 从配置文本解析排序方式，未知值回退插入顺序。 */
        public static GlobalTrashSortType parse(String value) {
            String normalized = value == null ? "" : value.trim();
            for (GlobalTrashSortType type : values()) {
                if (type.configValue.equalsIgnoreCase(normalized)) {
                    return type;
                }
            }
            return INSERTION;
        }
    }

    /** 公共垃圾桶紧凑显示模式配置。 */
    public static final class CompactGlobalTrashConfig {
        private final int maxPages;
        private final long maxAmountPerEntry;
        private final int leftClickAmount;
        private final int shiftLeftClickAmount;
        private final boolean rightClickEnabled;
        private final int rightClickAmount;
        private final int shiftRightClickAmount;
        private final boolean showAmountLore;
        private final int maxOriginalLoreLines;
        private final String amountLore;
        private final String omittedLore;
        private final List<String> actionLore;
        private final GlobalTrashSortType defaultSort;

        /** 创建紧凑模式配置。 */
        public CompactGlobalTrashConfig(int maxPages, long maxAmountPerEntry,
                                        int leftClickAmount, int shiftLeftClickAmount,
                                        boolean rightClickEnabled, int rightClickAmount,
                                        int shiftRightClickAmount, boolean showAmountLore,
                                        int maxOriginalLoreLines, String amountLore,
                                        String omittedLore, List<String> actionLore) {
            this(maxPages, maxAmountPerEntry, leftClickAmount, shiftLeftClickAmount,
                    rightClickEnabled, rightClickAmount, shiftRightClickAmount, showAmountLore,
                    maxOriginalLoreLines, amountLore, omittedLore, actionLore,
                    GlobalTrashSortType.INSERTION);
        }

        /** 创建包含独立默认排序的紧凑模式配置。 */
        public CompactGlobalTrashConfig(int maxPages, long maxAmountPerEntry,
                                        int leftClickAmount, int shiftLeftClickAmount,
                                        boolean rightClickEnabled, int rightClickAmount,
                                        int shiftRightClickAmount, boolean showAmountLore,
                                        int maxOriginalLoreLines, String amountLore,
                                        String omittedLore, List<String> actionLore,
                                        GlobalTrashSortType defaultSort) {
            this.maxPages = Math.max(1, maxPages);
            this.maxAmountPerEntry = maxAmountPerEntry == -1L
                    ? -1L : Math.max(1L, maxAmountPerEntry);
            this.leftClickAmount = Math.max(1, leftClickAmount);
            this.shiftLeftClickAmount = Math.max(1, shiftLeftClickAmount);
            this.rightClickEnabled = rightClickEnabled;
            this.rightClickAmount = Math.max(1, rightClickAmount);
            this.shiftRightClickAmount = Math.max(1, shiftRightClickAmount);
            this.showAmountLore = showAmountLore;
            this.maxOriginalLoreLines = maxOriginalLoreLines;
            this.amountLore = defaultString(amountLore, "&#38BDF8数量：&#F5B82E{amount}");
            this.omittedLore = defaultString(omittedLore, "&#64748B...省略 &#AAB6C5{count} &#64748B行...");
            this.actionLore = actionLore == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(actionLore));
            this.defaultSort = defaultSort == null ? GlobalTrashSortType.INSERTION : defaultSort;
        }

        /** 返回开箱默认紧凑模式配置。 */
        public static CompactGlobalTrashConfig defaults() {
            List<String> actions = new ArrayList<>();
            actions.add("&#38BDF8左键 &#D5DEE9取出 &#F5B82E{take-amount} &#D5DEE9个");
            actions.add("&#FFD166Shift + 左键 &#D5DEE9取出 &#F5B82E{shift-take-amount} &#D5DEE9个");
            return new CompactGlobalTrashConfig(5, 9999L, 1, 64, false,
                    1, 64, true, 5, "&#38BDF8数量：&#F5B82E{amount}",
                    "&#64748B...省略 &#AAB6C5{count} &#64748B行...", actions);
        }

        /** 返回紧凑模式最大页数。 */
        public int getMaxPages() {
            return maxPages;
        }

        /** 返回单个逻辑物品最大累计数量；-1 表示无限。 */
        public long getMaxAmountPerEntry() {
            return maxAmountPerEntry;
        }

        /** 返回普通左键取出数量。 */
        public int getLeftClickAmount() {
            return leftClickAmount;
        }

        /** 返回 Shift 左键取出数量。 */
        public int getShiftLeftClickAmount() {
            return shiftLeftClickAmount;
        }

        /** 判断是否启用右键取出。 */
        public boolean isRightClickEnabled() {
            return rightClickEnabled;
        }

        /** 返回普通右键取出数量。 */
        public int getRightClickAmount() {
            return rightClickAmount;
        }

        /** 返回 Shift 右键取出数量。 */
        public int getShiftRightClickAmount() {
            return shiftRightClickAmount;
        }

        /** 判断是否显示紧凑模式数量 Lore。 */
        public boolean isShowAmountLore() {
            return showAmountLore;
        }

        /** 返回最多显示的原始物品 Lore 行数；负数表示不截断。 */
        public int getMaxOriginalLoreLines() {
            return maxOriginalLoreLines;
        }

        /** 返回数量 Lore 模板。 */
        public String getAmountLore() {
            return amountLore;
        }

        /** 返回 Lore 省略模板。 */
        public String getOmittedLore() {
            return omittedLore;
        }

        /** 返回紧凑模式操作 Lore。 */
        public List<String> getActionLore() {
            return actionLore;
        }

        /** 返回紧凑模式没有玩家缓存时使用的排序方式。 */
        public GlobalTrashSortType getDefaultSort() {
            return defaultSort;
        }
    }

    /** 公共垃圾桶旧堆叠显示模式配置。 */
    public static final class StackedGlobalTrashConfig {
        private final int maxPages;
        private final GlobalTrashSortType defaultSort;

        /** 创建旧堆叠模式配置。 */
        public StackedGlobalTrashConfig(int maxPages) {
            this(maxPages, GlobalTrashSortType.INSERTION);
        }

        /** 创建带独立默认排序的旧堆叠模式配置。 */
        public StackedGlobalTrashConfig(int maxPages, GlobalTrashSortType defaultSort) {
            this.maxPages = Math.max(1, maxPages);
            this.defaultSort = defaultSort == null ? GlobalTrashSortType.INSERTION : defaultSort;
        }

        /** 返回旧堆叠模式最大页数。 */
        public int getMaxPages() {
            return maxPages;
        }

        /** 返回旧堆叠模式没有玩家缓存时使用的排序方式。 */
        public GlobalTrashSortType getDefaultSort() {
            return defaultSort;
        }
    }

    /** 公共垃圾桶 GUI 布局配置。 */
    public static final class GlobalTrashLayoutConfig {
        private static final List<String> DEFAULT_ROWS = createDefaultRows();
        private final List<String> rows;
        private final Map<Character, GlobalTrashItemConfig> items;
        private final List<Integer> contentSlots;
        private final GlobalTrashItemConfig[] slotItems;
        private final boolean[] contentSlotFlags;
        private final String validationError;

        /** 创建已经完成校验的布局配置。 */
        public GlobalTrashLayoutConfig(List<String> rows, Map<Character, GlobalTrashItemConfig> items,
                                       String validationError) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            this.slotItems = compileSlotItems(this.rows, this.items);
            this.contentSlotFlags = compileContentSlotFlags(this.slotItems);
            this.contentSlots = Collections.unmodifiableList(findContentSlots(this.contentSlotFlags));
            this.validationError = validationError;
        }

        /** 创建兼容旧固定布局的默认配置。 */
        public static GlobalTrashLayoutConfig defaultLayout(int backModelId, int nextModelId,
                                                             int backgroundModelId, String validationError) {
            Map<Character, GlobalTrashItemConfig> defaults = new LinkedHashMap<>();
            defaults.put(Character.valueOf('x'), new GlobalTrashItemConfig(
                    'x', GlobalTrashItemType.CONTENT, -1, Collections.<String>emptyList(),
                    null, Collections.<String>emptyList(), null));
            defaults.put(Character.valueOf('a'), new GlobalTrashItemConfig(
                    'a', GlobalTrashItemType.PREVIOUS_PAGE, backModelId,
                    Collections.singletonList("ARROW"), null, Collections.<String>emptyList(), Character.valueOf('b')));
            List<String> backgroundMaterials = new ArrayList<>();
            backgroundMaterials.add("BLACK_STAINED_GLASS_PANE");
            backgroundMaterials.add("STAINED_GLASS_PANE");
            backgroundMaterials.add("LEGACY_STAINED_GLASS_PANE");
            backgroundMaterials.add("GRAY_STAINED_GLASS_PANE");
            backgroundMaterials.add("GLASS_PANE");
            backgroundMaterials.add("THIN_GLASS");
            defaults.put(Character.valueOf('b'), new GlobalTrashItemConfig(
                    'b', GlobalTrashItemType.BACKGROUND, backgroundModelId,
                    backgroundMaterials, " ", Collections.<String>emptyList(), null));
            defaults.put(Character.valueOf('c'), new GlobalTrashItemConfig(
                    'c', GlobalTrashItemType.NEXT_PAGE, nextModelId,
                    Collections.singletonList("ARROW"), null, Collections.<String>emptyList(), Character.valueOf('b')));
            List<String> sortMaterials = new ArrayList<>();
            sortMaterials.add("COMPARATOR");
            sortMaterials.add("REDSTONE_COMPARATOR");
            defaults.put(Character.valueOf('s'), new GlobalTrashItemConfig(
                    's', GlobalTrashItemType.SORT, -1, sortMaterials,
                    null, Collections.<String>emptyList(), null));
            return new GlobalTrashLayoutConfig(DEFAULT_ROWS, defaults, validationError);
        }

        /** 返回 GUI 行定义。 */
        public List<String> getRows() {
            return rows;
        }

        /** 返回 GUI 总槽位数量。 */
        public int getInventorySize() {
            return rows.size() * 9;
        }

        /** 返回所有内容槽位。 */
        public List<Integer> getContentSlots() {
            return contentSlots;
        }

        /** 判断指定槽位是否为公共垃圾桶内容槽。 */
        public boolean isContentSlot(int slot) {
            return slot >= 0 && slot < contentSlotFlags.length && contentSlotFlags[slot];
        }

        /** 返回指定槽位对应的布局物品定义。 */
        public GlobalTrashItemConfig getItemAt(int slot) {
            if (slot < 0 || slot >= slotItems.length) {
                return null;
            }
            return slotItems[slot];
        }

        /** 返回指定布局字符对应的物品定义。 */
        public GlobalTrashItemConfig getItem(char symbol) {
            return items.get(Character.valueOf(symbol));
        }

        /** 返回布局校验错误；null 表示使用了有效配置。 */
        public String getValidationError() {
            return validationError;
        }

        /** 将字符布局预编译为按槽位读取的展示物表。 */
        private static GlobalTrashItemConfig[] compileSlotItems(
                List<String> rows, Map<Character, GlobalTrashItemConfig> items) {
            GlobalTrashItemConfig[] result = new GlobalTrashItemConfig[rows.size() * 9];
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                String row = rows.get(rowIndex);
                for (int column = 0; column < row.length(); column++) {
                    result[rowIndex * 9 + column] = items.get(Character.valueOf(row.charAt(column)));
                }
            }
            return result;
        }

        /** 将内容槽类型预编译为常量时间判断表。 */
        private static boolean[] compileContentSlotFlags(GlobalTrashItemConfig[] slotItems) {
            boolean[] result = new boolean[slotItems.length];
            for (int slot = 0; slot < slotItems.length; slot++) {
                GlobalTrashItemConfig item = slotItems[slot];
                result[slot] = item != null && item.getType() == GlobalTrashItemType.CONTENT;
            }
            return result;
        }

        /** 收集布局中的内容槽位。 */
        private static List<Integer> findContentSlots(boolean[] contentSlotFlags) {
            List<Integer> result = new ArrayList<>();
            for (int slot = 0; slot < contentSlotFlags.length; slot++) {
                if (contentSlotFlags[slot]) {
                    result.add(Integer.valueOf(slot));
                }
            }
            return result;
        }

        /** 创建兼容旧 GUI 的六行默认布局。 */
        private static List<String> createDefaultRows() {
            List<String> rows = new ArrayList<>();
            rows.add("xxxxxxxxx");
            rows.add("xxxxxxxxx");
            rows.add("xxxxxxxxx");
            rows.add("xxxxxxxxx");
            rows.add("xxxxxxxxx");
            rows.add("abbsbbbbc");
            return Collections.unmodifiableList(rows);
        }
    }

    /** 公共垃圾桶布局字符对应的展示物配置。 */
    public static final class GlobalTrashItemConfig {
        private final char symbol;
        private final GlobalTrashItemType type;
        private final int modelId;
        private final List<String> materials;
        private final String name;
        private final List<String> lore;
        private final List<String> actions;
        private final boolean glow;
        private final Character unavailableItem;

        /** 创建单个布局物品配置。 */
        public GlobalTrashItemConfig(char symbol, GlobalTrashItemType type, int modelId,
                                     List<String> materials, String name, List<String> lore,
                                     Character unavailableItem) {
            this(symbol, type, modelId, materials, name, lore,
                    Collections.<String>emptyList(), false, unavailableItem);
        }

        /** 创建带点击动作的单个布局物品配置。 */
        public GlobalTrashItemConfig(char symbol, GlobalTrashItemType type, int modelId,
                                     List<String> materials, String name, List<String> lore,
                                     List<String> actions, Character unavailableItem) {
            this(symbol, type, modelId, materials, name, lore, actions, false, unavailableItem);
        }

        /** 创建带点击动作和附魔光效设置的单个布局物品配置。 */
        public GlobalTrashItemConfig(char symbol, GlobalTrashItemType type, int modelId,
                                     List<String> materials, String name, List<String> lore,
                                     List<String> actions, boolean glow, Character unavailableItem) {
            this.symbol = symbol;
            this.type = type;
            this.modelId = modelId;
            this.materials = materials == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(materials));
            this.name = name;
            this.lore = lore == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(lore));
            this.actions = actions == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(actions));
            this.glow = glow && type != GlobalTrashItemType.CONTENT;
            this.unavailableItem = unavailableItem;
        }

        /** 返回布局字符。 */
        public char getSymbol() {
            return symbol;
        }

        /** 返回布局物品类型。 */
        public GlobalTrashItemType getType() {
            return type;
        }

        /** 返回 CustomModelData；负数表示不设置。 */
        public int getModelId() {
            return modelId;
        }

        /** 返回按顺序尝试的材质名称。 */
        public List<String> getMaterials() {
            return materials;
        }

        /** 返回名称覆盖；null 表示使用类型默认语言名称。 */
        public String getName() {
            return name;
        }

        /** 返回展示物 Lore。 */
        public List<String> getLore() {
            return lore;
        }

        /** 返回玩家点击时按顺序执行的动作。 */
        public List<String> getActions() {
            return actions;
        }

        /** 判断布局展示物是否启用附魔光效；内容槽始终返回 false。 */
        public boolean isGlow() {
            return glow;
        }

        /** 返回按钮不可用时显示的布局字符。 */
        public Character getUnavailableItem() {
            return unavailableItem;
        }
    }

    /** 公共垃圾桶布局槽位类型。 */
    public enum GlobalTrashItemType {
        CONTENT,
        PREVIOUS_PAGE,
        NEXT_PAGE,
        BACKGROUND,
        ACTIONS,
        SORT,
        CLOSE
    }

    /** 个人垃圾桶配置。 */
    public static final class PersonalTrashConfig implements TrashContainerConfig {
        private final boolean enabled;
        private final int takeDelayMillis;
        private final boolean allowPlayerPut;
        private final GlobalTrashLayoutConfig layout;
        private final GlobalTrashMode mode;
        private final CompactGlobalTrashConfig compact;
        private final StackedGlobalTrashConfig stacked;
        private final boolean trackPlayerDroppedItems;
        private final boolean autoClearWhenFull;
        private final double takeCost;
        private final DamageRecoveryMode damageRecoveryMode;
        private final int damageRecoveryDelaySeconds;
        private final boolean notifyWhenRouted;
        private final int notifyMaxDisplayItems;

        /** 创建个人垃圾桶配置。 */
        public PersonalTrashConfig(boolean enabled, boolean trackPlayerDroppedItems,
                                   boolean autoClearWhenFull, double takeCost,
                                   DamageRecoveryMode damageRecoveryMode, int damageRecoveryDelaySeconds,
                                   boolean notifyWhenRouted, int notifyMaxDisplayItems) {
            this(enabled, 0, true,
                    GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null),
                    GlobalTrashMode.COMPACT, personalCompactDefaults(),
                    new StackedGlobalTrashConfig(2), trackPlayerDroppedItems,
                    autoClearWhenFull, takeCost, damageRecoveryMode, damageRecoveryDelaySeconds,
                    notifyWhenRouted, notifyMaxDisplayItems);
        }

        /** 创建包含通用容器设置和个人专属策略的完整配置。 */
        public PersonalTrashConfig(boolean enabled, int takeDelayMillis, boolean allowPlayerPut,
                                   GlobalTrashLayoutConfig layout, GlobalTrashMode mode,
                                   CompactGlobalTrashConfig compact, StackedGlobalTrashConfig stacked,
                                   boolean trackPlayerDroppedItems, boolean autoClearWhenFull, double takeCost,
                                   DamageRecoveryMode damageRecoveryMode, int damageRecoveryDelaySeconds,
                                   boolean notifyWhenRouted, int notifyMaxDisplayItems) {
            this.enabled = enabled;
            this.takeDelayMillis = Math.max(0, takeDelayMillis);
            this.allowPlayerPut = allowPlayerPut;
            this.layout = layout == null ? GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null) : layout;
            this.mode = mode == null ? GlobalTrashMode.COMPACT : mode;
            this.compact = compact == null ? personalCompactDefaults() : compact;
            this.stacked = stacked == null ? new StackedGlobalTrashConfig(2) : stacked;
            this.trackPlayerDroppedItems = trackPlayerDroppedItems;
            this.autoClearWhenFull = autoClearWhenFull;
            this.takeCost = takeCost;
            this.damageRecoveryMode = damageRecoveryMode == null ? DamageRecoveryMode.DISABLED : damageRecoveryMode;
            this.damageRecoveryDelaySeconds = Math.max(0, damageRecoveryDelaySeconds);
            this.notifyWhenRouted = notifyWhenRouted;
            this.notifyMaxDisplayItems = Math.max(1, notifyMaxDisplayItems);
        }

        /** 判断个人垃圾桶是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回个人垃圾桶显示模式。 */
        public GlobalTrashMode getMode() {
            return mode;
        }

        /** 返回个人垃圾桶紧凑模式配置。 */
        public CompactGlobalTrashConfig getCompact() {
            return compact;
        }

        /** 返回个人垃圾桶堆叠模式配置。 */
        public StackedGlobalTrashConfig getStacked() {
            return stacked;
        }

        /** 返回当前模式最大页数。 */
        public int getMaxPages() {
            return mode == GlobalTrashMode.COMPACT ? compact.getMaxPages() : stacked.getMaxPages();
        }

        /** 返回个人垃圾桶拿取冷却毫秒。 */
        public int getTakeDelayMillis() {
            return takeDelayMillis;
        }

        /** 判断是否允许玩家手动放入个人垃圾桶。 */
        public boolean isAllowPlayerPut() {
            return allowPlayerPut;
        }

        /** 返回个人垃圾桶字符布局。 */
        public GlobalTrashLayoutConfig getLayout() {
            return layout;
        }

        /** 判断是否标记玩家主动丢弃的物品。 */
        public boolean isTrackPlayerDroppedItems() {
            return trackPlayerDroppedItems;
        }

        /** 判断个人垃圾桶满时是否自动清空。 */
        public boolean isAutoClearWhenFull() {
            return autoClearWhenFull;
        }

        /** 返回取出个人垃圾桶物品时的扣费金额，负数表示关闭扣费。 */
        public double getTakeCost() {
            return takeCost;
        }

        /** 返回仙人掌、岩浆等损坏回收模式。 */
        public DamageRecoveryMode getDamageRecoveryMode() {
            return damageRecoveryMode;
        }

        /** 返回玩家掉落物损坏回收有效时间，单位秒。 */
        public int getDamageRecoveryDelaySeconds() {
            return damageRecoveryDelaySeconds;
        }

        /** 判断物品进入个人垃圾桶时是否提示在线玩家。 */
        public boolean isNotifyWhenRouted() {
            return notifyWhenRouted;
        }

        /** 返回个人垃圾桶提示中最多完整展示的物品条目数。 */
        public int getNotifyMaxDisplayItems() {
            return notifyMaxDisplayItems;
        }

        /** 返回个人垃圾桶默认紧凑模式配置。 */
        private static CompactGlobalTrashConfig personalCompactDefaults() {
            List<String> actions = new ArrayList<>();
            actions.add("&#38BDF8左键 &#D5DEE9取出 &#F5B82E{take-amount} &#D5DEE9个");
            actions.add("&#FFD166Shift + 左键 &#D5DEE9取出 &#F5B82E{shift-take-amount} &#D5DEE9个");
            return new CompactGlobalTrashConfig(2, 9999L, 1, 64, false,
                    1, 64, true, 5, "&#38BDF8数量：&#F5B82E{amount}",
                    "&#64748B...省略 &#AAB6C5{count} &#64748B行...", actions);
        }
    }

    /** 玩家掉落物被仙人掌、岩浆等损坏时的回收模式。 */
    public enum DamageRecoveryMode {
        DISABLED,
        GLOBAL_TRASH,
        PERSONAL_TRASH
    }

    /** 返回非空默认字符串。 */
    private static String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    /** 标准化字符串集合。 */
    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 标准化比较字符串。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 标准化 Material 集合。 */
    private static Set<String> normalizeMaterialSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            String normalized = normalizeMaterial(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 标准化 Material 名称。 */
    private static String normalizeMaterial(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 返回公共垃圾桶物品黑名单。 */
    public Set<String> getGlobalTrashBannedMaterials() {
        return globalTrash.getBannedMaterials();
    }
}
