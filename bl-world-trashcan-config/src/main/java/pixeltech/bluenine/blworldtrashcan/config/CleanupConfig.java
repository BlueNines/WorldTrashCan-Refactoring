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
    private final Set<String> directRemoveWorlds;
    private final CleanupSettings settings;
    private final CleanupGuardConfig guardConfig;
    private final FoliaCleanupConfig foliaCleanup;
    private final MovingItemConfig movingItems;
    private final FilledShulkerBoxConfig filledShulkerBoxes;

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings) {
        this(intervalSeconds, ignoredWorlds, Collections.<String>emptySet(), settings,
                CleanupGuardConfig.defaults(), FoliaCleanupConfig.defaults());
    }

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings,
                         FoliaCleanupConfig foliaCleanup) {
        this(intervalSeconds, ignoredWorlds, Collections.<String>emptySet(), settings,
                CleanupGuardConfig.defaults(), foliaCleanup);
    }

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings,
                         CleanupGuardConfig guardConfig, FoliaCleanupConfig foliaCleanup) {
        this(intervalSeconds, ignoredWorlds, Collections.<String>emptySet(), settings, guardConfig, foliaCleanup);
    }

    /** 创建包含强制直接删除世界列表的清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, Set<String> directRemoveWorlds,
                         CleanupSettings settings, CleanupGuardConfig guardConfig,
                         FoliaCleanupConfig foliaCleanup) {
        this(intervalSeconds, ignoredWorlds, directRemoveWorlds, settings, guardConfig, foliaCleanup,
                MovingItemConfig.defaults(), FilledShulkerBoxConfig.defaults());
    }

    /** 创建包含全部扫地保护项的清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, Set<String> directRemoveWorlds,
                         CleanupSettings settings, CleanupGuardConfig guardConfig,
                         FoliaCleanupConfig foliaCleanup, MovingItemConfig movingItems) {
        this(intervalSeconds, ignoredWorlds, directRemoveWorlds, settings, guardConfig, foliaCleanup,
                movingItems, FilledShulkerBoxConfig.defaults());
    }

    /** 创建包含全部扫地物品保护项的清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, Set<String> directRemoveWorlds,
                         CleanupSettings settings, CleanupGuardConfig guardConfig,
                         FoliaCleanupConfig foliaCleanup, MovingItemConfig movingItems,
                         FilledShulkerBoxConfig filledShulkerBoxes) {
        this.intervalSeconds = Math.max(0, intervalSeconds);
        this.ignoredWorlds = normalizeWorlds(ignoredWorlds);
        this.directRemoveWorlds = normalizeWorlds(directRemoveWorlds);
        this.settings = settings;
        this.guardConfig = guardConfig == null ? CleanupGuardConfig.defaults() : guardConfig;
        this.foliaCleanup = foliaCleanup == null ? FoliaCleanupConfig.defaults() : foliaCleanup;
        this.movingItems = movingItems == null ? MovingItemConfig.defaults() : movingItems;
        this.filledShulkerBoxes = filledShulkerBoxes == null
                ? FilledShulkerBoxConfig.defaults()
                : filledShulkerBoxes;
    }

    /** 返回清理间隔秒数，0 表示只允许手动清理。 */
    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    /** 判断世界是否跳过清理。 */
    public boolean isIgnoredWorld(String worldName) {
        return containsWorld(ignoredWorlds, worldName);
    }

    /** 判断该世界里的扫地物品是否必须绕过所有垃圾桶并直接删除。 */
    public boolean isDirectRemoveWorld(String worldName) {
        return containsWorld(directRemoveWorlds, worldName);
    }

    /** 返回核心清理策略配置。 */
    public CleanupSettings getSettings() {
        return settings;
    }

    /** 返回扫地前置门禁配置。 */
    public CleanupGuardConfig getGuardConfig() {
        return guardConfig;
    }

    /** 返回 Folia 专用清理保护配置。 */
    public FoliaCleanupConfig getFoliaCleanup() {
        return foliaCleanup;
    }

    /** 返回扫地时的移动物品保护配置。 */
    public MovingItemConfig getMovingItems() {
        return movingItems;
    }

    /** 返回装有物品的潜影盒保护配置。 */
    public FilledShulkerBoxConfig getFilledShulkerBoxes() {
        return filledShulkerBoxes;
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

    /** 判断标准化世界集合是否包含指定世界名。 */
    private static boolean containsWorld(Set<String> worlds, String worldName) {
        if (worldName == null) {
            return false;
        }
        return worlds.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    /** 扫地启动前置门禁配置。 */
    public static final class CleanupGuardConfig {
        private static final int DEFAULT_MIN_ONLINE_PLAYERS = 1;
        private static final int DEFAULT_MIN_TOTAL_ENTITIES = 150;

        private final int minOnlinePlayers;
        private final int minTotalEntities;

        /** 创建扫地启动前置门禁配置。 */
        public CleanupGuardConfig(int minOnlinePlayers, int minTotalEntities) {
            this.minOnlinePlayers = Math.max(0, minOnlinePlayers);
            this.minTotalEntities = Math.max(0, minTotalEntities);
        }

        /** 返回默认扫地启动前置门禁配置。 */
        public static CleanupGuardConfig defaults() {
            return new CleanupGuardConfig(DEFAULT_MIN_ONLINE_PLAYERS, DEFAULT_MIN_TOTAL_ENTITIES);
        }

        /** 返回清理需要的最少在线玩家数，低于该值时跳过本轮扫地。 */
        public int getMinOnlinePlayers() {
            return minOnlinePlayers;
        }

        /** 返回清理需要的最少目标实体数，低于该值时跳过本轮扫地。 */
        public int getMinTotalEntities() {
            return minTotalEntities;
        }
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

    /** 扫地时的移动物品保护配置。 */
    public static final class MovingItemConfig {
        private static final double DEFAULT_MINIMUM_SPEED = 0.01D;

        private final boolean enabled;
        private final double minimumSpeed;
        private final double minimumSpeedSquared;

        /** 创建移动物品保护配置。 */
        public MovingItemConfig(boolean enabled, double minimumSpeed) {
            this.enabled = enabled;
            this.minimumSpeed = normalizeMinimumSpeed(minimumSpeed);
            this.minimumSpeedSquared = this.minimumSpeed * this.minimumSpeed;
        }

        /** 返回默认关闭的移动物品保护配置。 */
        public static MovingItemConfig defaults() {
            return new MovingItemConfig(false, DEFAULT_MINIMUM_SPEED);
        }

        /** 判断扫地时是否检查并跳过移动物品。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回最低移动速度，单位为方块/tick。 */
        public double getMinimumSpeed() {
            return minimumSpeed;
        }

        /** 判断速度平方是否达到移动阈值。 */
        public boolean isMoving(double velocitySquared) {
            return enabled && velocitySquared >= minimumSpeedSquared;
        }

        /** 规范化最低移动速度，非法值回退到安全默认值。 */
        private static double normalizeMinimumSpeed(double minimumSpeed) {
            if (minimumSpeed <= 0D || Double.isNaN(minimumSpeed) || Double.isInfinite(minimumSpeed)) {
                return DEFAULT_MINIMUM_SPEED;
            }
            return minimumSpeed;
        }
    }

    /** 扫地时保护装有物品的潜影盒配置。 */
    public static final class FilledShulkerBoxConfig {
        private final boolean enabled;

        /** 创建装有物品的潜影盒保护配置。 */
        public FilledShulkerBoxConfig(boolean enabled) {
            this.enabled = enabled;
        }

        /** 返回默认关闭的潜影盒保护配置。 */
        public static FilledShulkerBoxConfig defaults() {
            return new FilledShulkerBoxConfig(false);
        }

        /** 判断是否启用装有物品的潜影盒保护。 */
        public boolean isEnabled() {
            return enabled;
        }
    }
}
