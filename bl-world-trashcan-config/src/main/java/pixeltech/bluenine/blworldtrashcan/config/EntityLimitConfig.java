package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 实体数量限制配置。 */
public final class EntityLimitConfig {
    private final WorldLimitConfig worldLimit;
    private final GatherLimitConfig gatherLimit;
    private final ScanConfig scanConfig;

    /** 创建实体限制配置。 */
    public EntityLimitConfig(WorldLimitConfig worldLimit, GatherLimitConfig gatherLimit, ScanConfig scanConfig) {
        this.worldLimit = worldLimit;
        this.gatherLimit = gatherLimit;
        this.scanConfig = scanConfig == null ? ScanConfig.defaults() : scanConfig;
    }

    /** 返回世界实体数量限制。 */
    public WorldLimitConfig getWorldLimit() {
        return worldLimit;
    }

    /** 返回密集实体限制。 */
    public GatherLimitConfig getGatherLimit() {
        return gatherLimit;
    }

    /** 返回低占用扫描配置。 */
    public ScanConfig getScanConfig() {
        return scanConfig;
    }

    /** 单世界实体数量限制配置。 */
    public static final class WorldLimitConfig {
        private final boolean enabled;
        private final Set<String> ignoredWorlds;
        private final Map<String, Integer> limits;

        /** 创建单世界实体限制配置。 */
        public WorldLimitConfig(boolean enabled, Set<String> ignoredWorlds, Map<String, Integer> limits) {
            this.enabled = enabled;
            this.ignoredWorlds = normalizeSet(ignoredWorlds);
            this.limits = normalizeIntegerMap(limits);
        }

        /** 判断世界实体数量限制是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 判断世界是否跳过限制。 */
        public boolean isIgnoredWorld(String worldName) {
            return ignoredWorlds.contains(normalize(worldName));
        }

        /** 返回指定实体的最大数量，未配置时为 0。 */
        public int getMaxCount(String entityType) {
            Integer value = limits.get(normalize(entityType));
            return value == null ? 0 : value;
        }

        /** 返回配置了世界上限的实体类型。 */
        public Set<String> getLimitedTypes() {
            return limits.keySet();
        }
    }

    /** 密集实体限制配置。 */
    public static final class GatherLimitConfig {
        private final boolean enabled;
        private final boolean dropItems;
        private final Set<String> ignoredWorlds;
        private final Map<String, GatherRule> rules;

        /** 创建密集实体限制配置。 */
        public GatherLimitConfig(boolean enabled, boolean dropItems, Set<String> ignoredWorlds,
                                 Map<String, GatherRule> rules) {
            this.enabled = enabled;
            this.dropItems = dropItems;
            this.ignoredWorlds = normalizeSet(ignoredWorlds);
            this.rules = normalizeGatherMap(rules);
        }

        /** 判断密集实体限制是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 判断被清理实体是否掉落物品。 */
        public boolean isDropItems() {
            return dropItems;
        }

        /** 判断世界是否跳过限制。 */
        public boolean isIgnoredWorld(String worldName) {
            return ignoredWorlds.contains(normalize(worldName));
        }

        /** 返回指定实体的密集限制规则。 */
        public GatherRule getRule(String entityType) {
            return rules.get(normalize(entityType));
        }

        /** 返回配置了密集清理的实体类型。 */
        public Set<String> getLimitedTypes() {
            return rules.keySet();
        }
    }

    /** 单条密集实体限制规则。 */
    public static final class GatherRule {
        private final int maxCount;
        private final int radius;
        private final int removeCount;

        /** 创建密集实体限制规则。 */
        public GatherRule(int maxCount, int radius, int removeCount) {
            this.maxCount = Math.max(1, maxCount);
            this.radius = Math.max(1, Math.min(16, radius));
            this.removeCount = Math.max(1, removeCount);
        }

        /** 返回范围内允许的最大数量。 */
        public int getMaxCount() {
            return maxCount;
        }

        /** 返回检测半径。 */
        public int getRadius() {
            return radius;
        }

        /** 返回每次清理数量。 */
        public int getRemoveCount() {
            return removeCount;
        }
    }

    /** 低占用实体扫描和候选队列配置。 */
    public static final class ScanConfig {
        private final int targetFullCycleSeconds;
        private final int scanIntervalTicks;
        private final int minChunksPerScan;
        private final int maxChunksPerScan;
        private final int maxScanMillisPerRun;
        private final int removeIntervalTicks;
        private final int maxRemovesPerRun;
        private final int maxPendingRemovals;
        private final int candidateTtlSeconds;
        private final int maxCandidateRetries;
        private final int maxDirtyChunks;
        private final int staleChunkSeconds;
        private final int maxIndexEntities;
        private final int maxIndexEntitiesPerChunk;
        private final int logSummarySeconds;

        /** 创建低占用实体扫描配置。 */
        public ScanConfig(int targetFullCycleSeconds, int scanIntervalTicks, int minChunksPerScan,
                          int maxChunksPerScan, int maxScanMillisPerRun, int removeIntervalTicks,
                          int maxRemovesPerRun, int maxPendingRemovals, int candidateTtlSeconds,
                          int maxCandidateRetries, int maxDirtyChunks, int staleChunkSeconds,
                          int maxIndexEntities, int maxIndexEntitiesPerChunk, int logSummarySeconds) {
            this.targetFullCycleSeconds = clamp(targetFullCycleSeconds, 30, 3600);
            this.scanIntervalTicks = clamp(scanIntervalTicks, 1, 20 * 60);
            this.minChunksPerScan = clamp(minChunksPerScan, 1, 512);
            this.maxChunksPerScan = clamp(Math.max(minChunksPerScan, maxChunksPerScan), 1, 2048);
            this.maxScanMillisPerRun = clamp(maxScanMillisPerRun, 1, 50);
            this.removeIntervalTicks = clamp(removeIntervalTicks, 1, 20 * 60);
            this.maxRemovesPerRun = clamp(maxRemovesPerRun, 1, 1024);
            this.maxPendingRemovals = clamp(maxPendingRemovals, 1, 100000);
            this.candidateTtlSeconds = clamp(candidateTtlSeconds, 5, 3600);
            this.maxCandidateRetries = clamp(maxCandidateRetries, 0, 100);
            this.maxDirtyChunks = clamp(maxDirtyChunks, 1, 100000);
            this.staleChunkSeconds = clamp(staleChunkSeconds, 30, 86400);
            this.maxIndexEntities = clamp(maxIndexEntities, 100, 2000000);
            this.maxIndexEntitiesPerChunk = clamp(maxIndexEntitiesPerChunk, 1, 100000);
            this.logSummarySeconds = Math.max(0, logSummarySeconds);
        }

        /** 返回默认低占用扫描配置。 */
        public static ScanConfig defaults() {
            return new ScanConfig(300, 20, 4, 64, 4, 2, 20, 2000,
                    120, 3, 4096, 600, 50000, 512, 60);
        }

        /** 返回目标完整扫描周期秒数。 */
        public int getTargetFullCycleSeconds() {
            return targetFullCycleSeconds;
        }

        /** 返回扫描任务间隔 tick。 */
        public int getScanIntervalTicks() {
            return scanIntervalTicks;
        }

        /** 返回每轮扫描的最小 chunk 数。 */
        public int getMinChunksPerScan() {
            return minChunksPerScan;
        }

        /** 返回每轮扫描的最大 chunk 数。 */
        public int getMaxChunksPerScan() {
            return maxChunksPerScan;
        }

        /** 返回每轮主线程扫描预算毫秒数。 */
        public int getMaxScanMillisPerRun() {
            return maxScanMillisPerRun;
        }

        /** 返回候选清理任务间隔 tick。 */
        public int getRemoveIntervalTicks() {
            return removeIntervalTicks;
        }

        /** 返回每轮最多移除实体数。 */
        public int getMaxRemovesPerRun() {
            return maxRemovesPerRun;
        }

        /** 返回最多排队候选数。 */
        public int getMaxPendingRemovals() {
            return maxPendingRemovals;
        }

        /** 返回候选过期秒数。 */
        public int getCandidateTtlSeconds() {
            return candidateTtlSeconds;
        }

        /** 返回候选最大重试次数。 */
        public int getMaxCandidateRetries() {
            return maxCandidateRetries;
        }

        /** 返回最多记录的脏 chunk 数。 */
        public int getMaxDirtyChunks() {
            return maxDirtyChunks;
        }

        /** 返回索引 chunk 失效秒数。 */
        public int getStaleChunkSeconds() {
            return staleChunkSeconds;
        }

        /** 返回最多索引实体数。 */
        public int getMaxIndexEntities() {
            return maxIndexEntities;
        }

        /** 返回单个 chunk 最多索引实体数。 */
        public int getMaxIndexEntitiesPerChunk() {
            return maxIndexEntitiesPerChunk;
        }

        /** 返回摘要日志间隔秒数，0 表示关闭。 */
        public int getLogSummarySeconds() {
            return logSummarySeconds;
        }

        /** 把整数限制在闭区间内。 */
        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
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

    /** 标准化整数映射。 */
    private static Map<String, Integer> normalizeIntegerMap(Map<String, Integer> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String key = normalize(entry.getKey());
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (!key.isEmpty() && value > 0) {
                result.put(key, value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 标准化密集规则映射。 */
    private static Map<String, GatherRule> normalizeGatherMap(Map<String, GatherRule> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, GatherRule> result = new HashMap<>();
        for (Map.Entry<String, GatherRule> entry : values.entrySet()) {
            String key = normalize(entry.getKey());
            if (!key.isEmpty() && entry.getValue() != null) {
                result.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 标准化比较字符串。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
