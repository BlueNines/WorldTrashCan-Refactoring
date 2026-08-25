package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 地面掉落物逻辑堆叠的类型化配置。 */
public final class ItemStackingConfig {
    private final boolean featureEnabled;
    private final int maxLogicalAmount;
    private final double horizontalRadius;
    private final double verticalRadius;
    private final int processIntervalTicks;
    private final int mergeDelayTicks;
    private final int maxChunksPerRun;
    private final int maxItemsPerChunk;
    private final int timeBudgetMicros;
    private final int maxQueuedChunks;
    private final int queueTtlSeconds;
    private final boolean displayNameEnabled;
    private final String displayNameFormat;
    private final String displayNameLocale;
    private final Map<String, ItemRule> itemRules;
    private final ItemRule defaultItemRule;

    /** 创建经过边界收敛的堆叠配置。 */
    public ItemStackingConfig(int maxLogicalAmount, double horizontalRadius, double verticalRadius,
                              int processIntervalTicks, int mergeDelayTicks, int maxChunksPerRun,
                              int maxItemsPerChunk, int timeBudgetMicros, int maxQueuedChunks,
                              int queueTtlSeconds, boolean displayNameEnabled, String displayNameFormat) {
        this(true, maxLogicalAmount, horizontalRadius, verticalRadius, processIntervalTicks,
                mergeDelayTicks, maxChunksPerRun, maxItemsPerChunk, timeBudgetMicros, maxQueuedChunks,
                queueTtlSeconds, displayNameEnabled, displayNameFormat, "zh_CN",
                Collections.<String, ItemRule>emptyMap());
    }

    /** 创建包含功能开关和逐物品规则的完整配置。 */
    private ItemStackingConfig(boolean featureEnabled, int maxLogicalAmount, double horizontalRadius,
                               double verticalRadius, int processIntervalTicks, int mergeDelayTicks,
                               int maxChunksPerRun, int maxItemsPerChunk, int timeBudgetMicros,
                               int maxQueuedChunks, int queueTtlSeconds, boolean displayNameEnabled,
                               String displayNameFormat, String displayNameLocale,
                               Map<String, ItemRule> itemRules) {
        this.featureEnabled = featureEnabled;
        this.maxLogicalAmount = Math.max(2, maxLogicalAmount);
        this.horizontalRadius = clamp(horizontalRadius, 0.1D, 16.0D);
        this.verticalRadius = clamp(verticalRadius, 0.1D, 16.0D);
        this.processIntervalTicks = clamp(processIntervalTicks, 1, 1200);
        this.mergeDelayTicks = clamp(mergeDelayTicks, 0, 1200);
        this.maxChunksPerRun = clamp(maxChunksPerRun, 1, 1024);
        this.maxItemsPerChunk = clamp(maxItemsPerChunk, 1, 10000);
        this.timeBudgetMicros = clamp(timeBudgetMicros, 100, 50000);
        this.maxQueuedChunks = clamp(maxQueuedChunks, 16, 100000);
        this.queueTtlSeconds = clamp(queueTtlSeconds, 1, 3600);
        this.displayNameEnabled = displayNameEnabled;
        this.displayNameFormat = displayNameFormat == null || displayNameFormat.trim().isEmpty()
                ? "&#38BDF8{name} &#64748Bx &#F5B82E{amount}" : displayNameFormat;
        this.displayNameLocale = normalizeLocale(displayNameLocale);
        this.itemRules = immutableRules(itemRules);
        this.defaultItemRule = new ItemRule(true, this.maxLogicalAmount, null);
    }

    /** 从统一配置文件读取全局设置和 items 稀疏覆盖规则。 */
    public static ItemStackingConfig load(ConfigurationSource source) {
        int globalMaximum = Math.max(2, source.getInt("stack.max-logical-amount", 1024));
        return new ItemStackingConfig(
                source.getBoolean("enabled", false),
                globalMaximum,
                source.getDouble("merge.horizontal-radius", 3.0D),
                source.getDouble("merge.vertical-radius", 1.5D),
                source.getInt("scheduler.process-interval-ticks", 5),
                source.getInt("scheduler.merge-delay-ticks", 10),
                source.getInt("scheduler.max-chunks-per-run", 8),
                source.getInt("scheduler.max-items-per-chunk", 256),
                source.getInt("scheduler.time-budget-micros", 1500),
                source.getInt("scheduler.max-queued-chunks", 4096),
                source.getInt("scheduler.queue-ttl-seconds", 30),
                source.getBoolean("display.custom-name.enabled", true),
                source.getString("display.custom-name.format",
                        "&#38BDF8{name} &#64748Bx &#F5B82E{amount}"),
                source.getString("display.custom-name.locale", "zh_CN"),
                loadItemRules(source, globalMaximum)
        );
    }

    /** 创建功能关闭且不含逐物品规则的默认配置。 */
    public static ItemStackingConfig defaults() {
        return new ItemStackingConfig(false, 1024, 3.0D, 1.5D, 5, 10,
                8, 256, 1500, 4096, 30, true,
                "&#38BDF8{name} &#64748Bx &#F5B82E{amount}", "zh_CN",
                Collections.<String, ItemRule>emptyMap());
    }

    /** 从 items 节点读取显式配置的 Material 规则。 */
    private static Map<String, ItemRule> loadItemRules(ConfigurationSource source, int globalMaximum) {
        Map<String, ItemRule> rules = new LinkedHashMap<>();
        for (String key : source.getKeys("items")) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            String normalized = key.trim().toUpperCase(Locale.ROOT);
            String path = "items." + key + ".";
            boolean enabled = source.getBoolean(path + "enabled", true);
            int configuredMaximum = source.getInt(path + "max-stack-size", -1);
            int maximum = configuredMaximum == -1 ? globalMaximum : Math.max(2, configuredMaximum);
            String displayName = normalizeDisplayName(source.getString(path + "display-name", "default"));
            rules.put(normalized, new ItemRule(enabled, maximum, displayName));
        }
        return rules;
    }

    /** 复制逐物品规则，防止重载后外部映射修改当前快照。 */
    private static Map<String, ItemRule> immutableRules(Map<String, ItemRule> itemRules) {
        if (itemRules == null || itemRules.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(itemRules));
    }

    /** 把 default 和空名称统一表示为使用内置翻译。 */
    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()
                || "default".equalsIgnoreCase(displayName.trim())) {
            return null;
        }
        return displayName;
    }

    /** 判断独立配置是否请求启用堆叠功能。 */
    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /** 返回最大逻辑数量。 */
    public int getMaxLogicalAmount() {
        return maxLogicalAmount;
    }

    /** 返回水平合并半径。 */
    public double getHorizontalRadius() {
        return horizontalRadius;
    }

    /** 返回垂直合并半径。 */
    public double getVerticalRadius() {
        return verticalRadius;
    }

    /** 返回队列处理周期。 */
    public int getProcessIntervalTicks() {
        return processIntervalTicks;
    }

    /** 返回生成后最短合并等待。 */
    public int getMergeDelayTicks() {
        return mergeDelayTicks;
    }

    /** 返回每轮最多处理 chunk 数。 */
    public int getMaxChunksPerRun() {
        return maxChunksPerRun;
    }

    /** 返回每个 chunk 最多检查物品数。 */
    public int getMaxItemsPerChunk() {
        return maxItemsPerChunk;
    }

    /** 返回每轮主线程预算微秒数。 */
    public int getTimeBudgetMicros() {
        return timeBudgetMicros;
    }

    /** 返回 dirty chunk 队列容量。 */
    public int getMaxQueuedChunks() {
        return maxQueuedChunks;
    }

    /** 返回队列条目过期秒数。 */
    public int getQueueTtlSeconds() {
        return queueTtlSeconds;
    }

    /** 判断是否显示逻辑数量悬浮名称。 */
    public boolean isDisplayNameEnabled() {
        return displayNameEnabled;
    }

    /** 返回悬浮名称格式。 */
    public String getDisplayNameFormat() {
        return displayNameFormat;
    }

    /** 返回悬浮名称使用的物品语言。 */
    public String getDisplayNameLocale() {
        return displayNameLocale;
    }

    /** 返回不可变的逐 Material 规则。 */
    public Map<String, ItemRule> getItemRules() {
        return itemRules;
    }

    /** 返回指定 Bukkit Material 名的规则，缺失时继承全局设置。 */
    public ItemRule getItemRule(String materialName) {
        if (materialName == null) {
            return defaultItemRule;
        }
        ItemRule rule = itemRules.get(materialName.toUpperCase(Locale.ROOT));
        return rule == null ? defaultItemRule : rule;
    }

    /** 将配置语言收敛到内置语言标识。 */
    private static String normalizeLocale(String locale) {
        if (locale == null) {
            return "zh_CN";
        }
        String normalized = locale.trim().replace('-', '_');
        if ("en_us".equalsIgnoreCase(normalized)) {
            return "en_US";
        }
        if ("ja_jp".equalsIgnoreCase(normalized)) {
            return "ja_JP";
        }
        return "zh_CN";
    }

    /** 收敛整数配置边界。 */
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** 收敛小数配置边界。 */
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** 单个 Bukkit Material 的堆叠和显示规则。 */
    public static final class ItemRule {
        private final boolean enabled;
        private final int maxStackSize;
        private final String displayName;

        /** 创建已经解析继承值的逐物品规则。 */
        public ItemRule(boolean enabled, int maxStackSize, String displayName) {
            this.enabled = enabled;
            this.maxStackSize = Math.max(2, maxStackSize);
            this.displayName = displayName;
        }

        /** 判断插件是否接管该物品的逻辑堆叠。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回该物品最终生效的逻辑上限。 */
        public int getMaxStackSize() {
            return maxStackSize;
        }

        /** 返回独立显示名；null 表示使用内置语言。 */
        public String getDisplayName() {
            return displayName;
        }
    }
}
