package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 清理功能的类型化配置。 */
public final class CleanupConfig {
    private final int intervalSeconds;
    private final Set<String> ignoredWorlds;
    private final CleanupSettings settings;
    private final FoliaCleanupConfig foliaCleanup;

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings) {
        this(intervalSeconds, ignoredWorlds, settings, FoliaCleanupConfig.defaults());
    }

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings,
                         FoliaCleanupConfig foliaCleanup) {
        this.intervalSeconds = Math.max(0, intervalSeconds);
        this.ignoredWorlds = normalizeWorlds(ignoredWorlds);
        this.settings = settings;
        this.foliaCleanup = foliaCleanup == null ? FoliaCleanupConfig.defaults() : foliaCleanup;
    }

    /** 返回清理间隔秒数，0 表示只允许手动清理。 */
    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    /** 判断世界是否跳过清理。 */
    public boolean isIgnoredWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        return ignoredWorlds.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    /** 返回核心清理策略配置。 */
    public CleanupSettings getSettings() {
        return settings;
    }

    /** 返回 Folia 专用清理保护配置。 */
    public FoliaCleanupConfig getFoliaCleanup() {
        return foliaCleanup;
    }

    /** 标准化世界名集合。 */
    private static Set<String> normalizeWorlds(Set<String> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.trim().isEmpty()) {
                result.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Folia 专用清理保护配置。 */
    public static final class FoliaCleanupConfig {
        private static final int DEFAULT_TIMEOUT_SECONDS = 30;
        private static final int DEFAULT_MAX_CHUNKS_PER_CLEANUP = 4096;
        private static final int DEFAULT_CHUNK_BATCH_SIZE = 64;
        private static final int DEFAULT_CHUNK_BATCH_DELAY_TICKS = 1;

        private final int timeoutSeconds;
        private final int maxChunksPerCleanup;
        private final int chunkBatchSize;
        private final int chunkBatchDelayTicks;

        /** 创建 Folia 清理保护配置。 */
        public FoliaCleanupConfig(int timeoutSeconds, int maxChunksPerCleanup,
                                  int chunkBatchSize, int chunkBatchDelayTicks) {
            this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
            this.maxChunksPerCleanup = Math.max(0, maxChunksPerCleanup);
            this.chunkBatchSize = chunkBatchSize <= 0 ? DEFAULT_CHUNK_BATCH_SIZE : chunkBatchSize;
            this.chunkBatchDelayTicks = chunkBatchDelayTicks <= 0
                    ? DEFAULT_CHUNK_BATCH_DELAY_TICKS
                    : chunkBatchDelayTicks;
        }

        /** 返回默认 Folia 清理保护配置。 */
        public static FoliaCleanupConfig defaults() {
            return new FoliaCleanupConfig(
                    DEFAULT_TIMEOUT_SECONDS,
                    DEFAULT_MAX_CHUNKS_PER_CLEANUP,
                    DEFAULT_CHUNK_BATCH_SIZE,
                    DEFAULT_CHUNK_BATCH_DELAY_TICKS
            );
        }

        /** 返回单轮清理超时时间。 */
        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        /** 返回单轮最多扫描的已加载 chunk 数，0 表示不限制。 */
        public int getMaxChunksPerCleanup() {
            return maxChunksPerCleanup;
        }

        /** 返回每批派发的 chunk 扫描任务数。 */
        public int getChunkBatchSize() {
            return chunkBatchSize;
        }

        /** 返回每批 chunk 扫描任务之间的延迟 tick，最小为 1。 */
        public int getChunkBatchDelayTicks() {
            return chunkBatchDelayTicks;
        }
    }
}
