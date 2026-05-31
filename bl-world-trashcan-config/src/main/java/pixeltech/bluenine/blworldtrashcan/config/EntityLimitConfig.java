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

    /** 创建实体限制配置。 */
    public EntityLimitConfig(WorldLimitConfig worldLimit, GatherLimitConfig gatherLimit) {
        this.worldLimit = worldLimit;
        this.gatherLimit = gatherLimit;
    }

    /** 返回世界实体数量限制。 */
    public WorldLimitConfig getWorldLimit() {
        return worldLimit;
    }

    /** 返回密集实体限制。 */
    public GatherLimitConfig getGatherLimit() {
        return gatherLimit;
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
