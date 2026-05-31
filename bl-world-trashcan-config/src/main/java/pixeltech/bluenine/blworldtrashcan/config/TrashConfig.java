package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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
        private final Set<String> bannedWorlds;

        /** 创建世界垃圾桶配置。 */
        public WorldTrashConfig(boolean enabled, String signCreateText, String signCreatedText,
                                int defaultMaxCount, Set<String> bannedWorlds) {
            this.enabled = enabled;
            this.signCreateText = defaultString(signCreateText, "[世界垃圾桶]");
            this.signCreatedText = defaultString(signCreatedText, "&b[&c世界垃圾桶&b]");
            this.defaultMaxCount = Math.max(0, defaultMaxCount);
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

        /** 判断世界是否禁止创建世界垃圾桶。 */
        public boolean isBannedWorld(String worldName) {
            return bannedWorlds.contains(normalize(worldName));
        }
    }

    /** 公共垃圾桶配置。 */
    public static final class GlobalTrashConfig {
        private final boolean enabled;
        private final int maxPages;
        private final int takeDelayMillis;
        private final int clearEveryCleanups;
        private final boolean allowPlayerPut;
        private final boolean logEnabled;
        private final Set<String> bannedMaterials;

        /** 创建公共垃圾桶配置。 */
        public GlobalTrashConfig(boolean enabled, int maxPages, int takeDelayMillis,
                                 int clearEveryCleanups, boolean allowPlayerPut,
                                 boolean logEnabled, Set<String> bannedMaterials) {
            this.enabled = enabled;
            this.maxPages = Math.max(1, maxPages);
            this.takeDelayMillis = Math.max(0, takeDelayMillis);
            this.clearEveryCleanups = clearEveryCleanups;
            this.allowPlayerPut = allowPlayerPut;
            this.logEnabled = logEnabled;
            this.bannedMaterials = normalizeMaterialSet(bannedMaterials);
        }

        /** 判断公共垃圾桶是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回最大页数。 */
        public int getMaxPages() {
            return maxPages;
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

        /** 判断物品是否禁止进入公共垃圾桶。 */
        public boolean isBannedMaterial(String materialName) {
            return bannedMaterials.contains(normalizeMaterial(materialName));
        }

        /** 返回公共垃圾桶物品黑名单。 */
        public Set<String> getBannedMaterials() {
            return bannedMaterials;
        }
    }

    /** 个人垃圾桶配置。 */
    public static final class PersonalTrashConfig {
        private final boolean enabled;
        private final boolean trackPlayerDroppedItems;
        private final boolean autoClearWhenFull;
        private final double takeCost;

        /** 创建个人垃圾桶配置。 */
        public PersonalTrashConfig(boolean enabled, boolean trackPlayerDroppedItems,
                                   boolean autoClearWhenFull, double takeCost) {
            this.enabled = enabled;
            this.trackPlayerDroppedItems = trackPlayerDroppedItems;
            this.autoClearWhenFull = autoClearWhenFull;
            this.takeCost = takeCost;
        }

        /** 判断个人垃圾桶是否启用。 */
        public boolean isEnabled() {
            return enabled;
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
