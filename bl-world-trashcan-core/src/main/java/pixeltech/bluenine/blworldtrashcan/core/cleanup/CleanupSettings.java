package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 清理策略所需的核心配置快照。 */
public final class CleanupSettings {
    private final Set<String> ignoredMaterialKeys;
    private final WildcardPatternSet ignoredNamePatterns;
    private final WildcardPatternSet ignoredLorePatterns;
    private final CustomItemRoutingSettings customItemRouting;
    private final boolean entityCleanupEnabled;
    private final boolean clearExperienceOrb;
    private final boolean clearMonster;
    private final boolean clearAnimals;
    private final boolean clearProjectile;
    private final boolean clearNamedEntity;
    private final boolean ignoreEntitiesInBoat;
    private final boolean ignoreEntitiesWithSaddle;
    private final boolean ignoreEntitiesWithOwner;
    private final List<CompiledPattern> entityWhitePatterns;
    private final List<CompiledPattern> entityBlackPatterns;
    private final NamedEntityRules namedEntityRules;

    /** 创建清理配置快照。 */
    public CleanupSettings(Set<String> ignoredMaterialKeys, Set<String> ignoredNameFragments,
                           Set<String> ignoredLoreFragments, boolean entityCleanupEnabled, boolean clearExperienceOrb,
                           boolean clearMonster, boolean clearAnimals, boolean clearProjectile,
                           boolean clearNamedEntity, boolean ignoreEntitiesInBoat,
                           boolean ignoreEntitiesWithSaddle, boolean ignoreEntitiesWithOwner,
                           Set<String> entityWhitePatterns, Set<String> entityBlackPatterns) {
        this(ignoredMaterialKeys, ignoredNameFragments, ignoredLoreFragments,
                entityCleanupEnabled, clearExperienceOrb, clearMonster, clearAnimals,
                clearProjectile, clearNamedEntity, ignoreEntitiesInBoat,
                ignoreEntitiesWithSaddle, ignoreEntitiesWithOwner,
                entityWhitePatterns, entityBlackPatterns, CustomItemRoutingSettings.defaults());
    }

    /** 创建包含自定义物品路由的核心配置快照。 */
    public CleanupSettings(Set<String> ignoredMaterialKeys, Set<String> ignoredNameFragments,
                           Set<String> ignoredLoreFragments, boolean entityCleanupEnabled,
                           boolean clearExperienceOrb, boolean clearMonster, boolean clearAnimals,
                           boolean clearProjectile, boolean clearNamedEntity,
                           boolean ignoreEntitiesInBoat, boolean ignoreEntitiesWithSaddle,
                           boolean ignoreEntitiesWithOwner, Set<String> entityWhitePatterns,
                           Set<String> entityBlackPatterns,
                           CustomItemRoutingSettings customItemRouting) {
        this(ignoredMaterialKeys, ignoredNameFragments, ignoredLoreFragments,
                entityCleanupEnabled, clearExperienceOrb, clearMonster, clearAnimals,
                clearProjectile, clearNamedEntity, ignoreEntitiesInBoat,
                ignoreEntitiesWithSaddle, ignoreEntitiesWithOwner, entityWhitePatterns,
                entityBlackPatterns, customItemRouting, NamedEntityRules.empty());
    }

    /** 创建包含自定义物品路由和命名实体规则的核心配置快照。 */
    public CleanupSettings(Set<String> ignoredMaterialKeys, Set<String> ignoredNameFragments,
                           Set<String> ignoredLoreFragments, boolean entityCleanupEnabled,
                           boolean clearExperienceOrb, boolean clearMonster, boolean clearAnimals,
                           boolean clearProjectile, boolean clearNamedEntity,
                           boolean ignoreEntitiesInBoat, boolean ignoreEntitiesWithSaddle,
                           boolean ignoreEntitiesWithOwner, Set<String> entityWhitePatterns,
                           Set<String> entityBlackPatterns,
                           CustomItemRoutingSettings customItemRouting,
                           NamedEntityRules namedEntityRules) {
        this.ignoredMaterialKeys = normalizeSet(ignoredMaterialKeys);
        this.ignoredNamePatterns = WildcardPatternSet.compile(ignoredNameFragments, true);
        this.ignoredLorePatterns = WildcardPatternSet.compile(ignoredLoreFragments, true);
        this.customItemRouting = customItemRouting == null
                ? CustomItemRoutingSettings.defaults()
                : customItemRouting;
        this.entityCleanupEnabled = entityCleanupEnabled;
        this.clearExperienceOrb = clearExperienceOrb;
        this.clearMonster = clearMonster;
        this.clearAnimals = clearAnimals;
        this.clearProjectile = clearProjectile;
        this.clearNamedEntity = clearNamedEntity;
        this.ignoreEntitiesInBoat = ignoreEntitiesInBoat;
        this.ignoreEntitiesWithSaddle = ignoreEntitiesWithSaddle;
        this.ignoreEntitiesWithOwner = ignoreEntitiesWithOwner;
        this.entityWhitePatterns = compilePatterns(entityWhitePatterns, false);
        this.entityBlackPatterns = compilePatterns(entityBlackPatterns, false);
        this.namedEntityRules = namedEntityRules == null ? NamedEntityRules.empty() : namedEntityRules;
    }

    /** 判断物品类型是否跳过清理。 */
    public boolean isIgnoredMaterial(String materialKey) {
        return ignoredMaterialKeys.contains(normalize(materialKey));
    }

    /** 判断名字片段是否命中跳过规则。 */
    public boolean matchesIgnoredName(String displayName) {
        return ignoredNamePatterns.matches(displayName);
    }

    /** 判断 lore 片段是否命中跳过规则。 */
    public boolean matchesIgnoredLore(Iterable<String> lore) {
        if (lore == null) {
            return false;
        }
        for (String line : lore) {
            if (ignoredLorePatterns.matches(line)) {
                return true;
            }
        }
        return false;
    }

    /** 判断是否启用实体清理总开关。 */
    public boolean isEntityCleanupEnabled() {
        return entityCleanupEnabled;
    }

    /** 判断是否清理怪物。 */
    public boolean isClearMonster() {
        return clearMonster;
    }

    /** 判断是否清理经验球。 */
    public boolean isClearExperienceOrb() {
        return clearExperienceOrb;
    }

    /** 判断是否清理动物或普通生物。 */
    public boolean isClearAnimals() {
        return clearAnimals;
    }

    /** 判断是否清理投射物。 */
    public boolean isClearProjectile() {
        return clearProjectile;
    }

    /** 判断是否清理有自定义名的实体。 */
    public boolean isClearNamedEntity() {
        return clearNamedEntity;
    }

    /** 判断是否跳过船内实体。 */
    public boolean isIgnoreEntitiesInBoat() {
        return ignoreEntitiesInBoat;
    }

    /** 判断是否跳过实际装备鞍的实体。 */
    public boolean isIgnoreEntitiesWithSaddle() {
        return ignoreEntitiesWithSaddle;
    }

    /** 判断是否跳过拥有 Bukkit Tameable 主人的实体。 */
    public boolean isIgnoreEntitiesWithOwner() {
        return ignoreEntitiesWithOwner;
    }

    /** 返回自定义物品扫地路由设置。 */
    public CustomItemRoutingSettings getCustomItemRouting() {
        return customItemRouting;
    }

    /** 判断实体是否命中白名单规则。 */
    public boolean matchesEntityWhitelist(String typeKey, String entityName) {
        return matchesPatterns(typeKey, entityWhitePatterns) || matchesPatterns(entityName, entityWhitePatterns);
    }

    /** 判断实体是否命中黑名单规则。 */
    public boolean matchesEntityBlacklist(String typeKey, String entityName) {
        return matchesPatterns(typeKey, entityBlackPatterns) || matchesPatterns(entityName, entityBlackPatterns);
    }

    /** 判断是否配置了至少一条有效的命名实体规则。 */
    public boolean hasNamedEntityRules() {
        return namedEntityRules.hasRules();
    }

    /** 按白名单优先级匹配实体类型和自定义名称。 */
    public NamedEntityRules.Match matchNamedEntity(String typeKey, String customName) {
        return namedEntityRules.match(typeKey, customName);
    }

    /** 复制并标准化字符串集合。 */
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

    /** 在配置快照创建时完成规则标准化和通配片段预处理。 */
    private static List<CompiledPattern> compilePatterns(Set<String> values, boolean plainTextMeansContains) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalizedValues = new HashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                normalizedValues.add(normalized);
            }
        }
        if (normalizedValues.isEmpty()) {
            return Collections.emptyList();
        }
        List<CompiledPattern> result = new ArrayList<>(normalizedValues.size());
        for (String normalized : normalizedValues) {
            result.add(CompiledPattern.compile(normalized, plainTextMeansContains));
        }
        return Collections.unmodifiableList(result);
    }

    /** 判断文本是否命中任一已经预处理的规则。 */
    private static boolean matchesPatterns(String text, List<CompiledPattern> patterns) {
        if (text == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        for (CompiledPattern pattern : patterns) {
            if (pattern.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 标准化比较文本。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 配置加载时生成的轻量通配规则，匹配热路径不再拆分字符串。 */
    private static final class CompiledPattern {
        private static final int EXACT = 0;
        private static final int CONTAINS = 1;
        private static final int STARTS_WITH = 2;
        private static final int ENDS_WITH = 3;
        private static final int SEGMENTS = 4;
        private static final int MATCH_ALL = 5;
        private static final String[] NO_SEGMENTS = new String[0];

        private final int mode;
        private final String literal;
        private final String[] segments;
        private final boolean anchoredStart;
        private final boolean anchoredEnd;

        /** 创建一条已经预处理的匹配规则。 */
        private CompiledPattern(int mode, String literal, String[] segments,
                                boolean anchoredStart, boolean anchoredEnd) {
            this.mode = mode;
            this.literal = literal;
            this.segments = segments;
            this.anchoredStart = anchoredStart;
            this.anchoredEnd = anchoredEnd;
        }

        /** 将配置文本编译为无运行时拆分开销的匹配规则。 */
        private static CompiledPattern compile(String pattern, boolean plainTextMeansContains) {
            if (pattern.indexOf('*') < 0) {
                int mode = plainTextMeansContains ? CONTAINS : EXACT;
                return new CompiledPattern(mode, pattern, NO_SEGMENTS, false, false);
            }
            boolean anchoredStart = !pattern.startsWith("*");
            boolean anchoredEnd = !pattern.endsWith("*");
            String[] segments = splitSegments(pattern);
            if (segments.length == 0) {
                return new CompiledPattern(MATCH_ALL, "", NO_SEGMENTS, false, false);
            }
            if (segments.length == 1) {
                int mode = CONTAINS;
                if (anchoredStart) {
                    mode = STARTS_WITH;
                } else if (anchoredEnd) {
                    mode = ENDS_WITH;
                }
                return new CompiledPattern(mode, segments[0], NO_SEGMENTS, false, false);
            }
            return new CompiledPattern(SEGMENTS, "", segments, anchoredStart, anchoredEnd);
        }

        /** 只在配置快照创建时拆分多段星号通配规则。 */
        private static String[] splitSegments(String pattern) {
            List<String> result = new ArrayList<>();
            int start = 0;
            while (start <= pattern.length()) {
                int wildcard = pattern.indexOf('*', start);
                int end = wildcard < 0 ? pattern.length() : wildcard;
                if (end > start) {
                    result.add(pattern.substring(start, end));
                }
                if (wildcard < 0) {
                    break;
                }
                start = wildcard + 1;
            }
            return result.toArray(new String[result.size()]);
        }

        /** 在热路径中执行无临时数组分配的文本匹配。 */
        private boolean matches(String value) {
            if (mode == EXACT) {
                return value.equals(literal);
            }
            if (mode == CONTAINS) {
                return value.contains(literal);
            }
            if (mode == STARTS_WITH) {
                return value.startsWith(literal);
            }
            if (mode == ENDS_WITH) {
                return value.endsWith(literal);
            }
            if (mode == MATCH_ALL) {
                return true;
            }
            int valueIndex = 0;
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                int found = value.indexOf(segment, valueIndex);
                if (found < 0 || (index == 0 && anchoredStart && found != 0)) {
                    return false;
                }
                valueIndex = found + segment.length();
            }
            return !anchoredEnd || value.endsWith(segments[segments.length - 1]);
        }
    }
}
