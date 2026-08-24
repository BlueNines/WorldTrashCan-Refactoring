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
    private final Map<String, String> displayNameOverrides;

    /** 创建经过边界收敛的堆叠配置。 */
    public ItemStackingConfig(int maxLogicalAmount, double horizontalRadius, double verticalRadius,
                              int processIntervalTicks, int mergeDelayTicks, int maxChunksPerRun,
                              int maxItemsPerChunk, int timeBudgetMicros, int maxQueuedChunks,
                              int queueTtlSeconds, boolean displayNameEnabled, String displayNameFormat,
                              String displayNameLocale, Map<String, String> displayNameOverrides) {
        this(true, maxLogicalAmount, horizontalRadius, verticalRadius, processIntervalTicks,
                mergeDelayTicks, maxChunksPerRun, maxItemsPerChunk, timeBudgetMicros, maxQueuedChunks,
                queueTtlSeconds, displayNameEnabled, displayNameFormat, displayNameLocale,
                displayNameOverrides);
    }

    /** 创建包含功能开关的完整堆叠配置。 */
    private ItemStackingConfig(boolean featureEnabled, int maxLogicalAmount, double horizontalRadius,
                               double verticalRadius, int processIntervalTicks, int mergeDelayTicks,
                               int maxChunksPerRun, int maxItemsPerChunk, int timeBudgetMicros,
                               int maxQueuedChunks, int queueTtlSeconds, boolean displayNameEnabled,
                               String displayNameFormat, String displayNameLocale,
                               Map<String, String> displayNameOverrides) {
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
        Map<String, String> copiedOverrides = new LinkedHashMap<>();
        if (displayNameOverrides != null) {
            for (Map.Entry<String, String> entry : displayNameOverrides.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copiedOverrides.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        this.displayNameOverrides = Collections.unmodifiableMap(copiedOverrides);
    }

    /** 使用旧参数创建配置，保持平台测试和扩展调用兼容。 */
    public ItemStackingConfig(int maxLogicalAmount, double horizontalRadius, double verticalRadius,
                              int processIntervalTicks, int mergeDelayTicks, int maxChunksPerRun,
                              int maxItemsPerChunk, int timeBudgetMicros, int maxQueuedChunks,
                              int queueTtlSeconds, boolean displayNameEnabled, String displayNameFormat) {
        this(maxLogicalAmount, horizontalRadius, verticalRadius, processIntervalTicks, mergeDelayTicks,
                maxChunksPerRun, maxItemsPerChunk, timeBudgetMicros, maxQueuedChunks, queueTtlSeconds,
                displayNameEnabled, displayNameFormat, "zh_CN", Collections.<String, String>emptyMap());
    }

    /** 从独立配置文件读取设置。 */
    public static ItemStackingConfig load(ConfigurationSource source) {
        return new ItemStackingConfig(
                source.getBoolean("enabled", false),
                source.getInt("stack.max-logical-amount", 10000),
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
                source.getStringMap("display.custom-name.overrides")
        );
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

    /** 返回服主对物品键的名称覆盖。 */
    public Map<String, String> getDisplayNameOverrides() {
        return displayNameOverrides;
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
}
