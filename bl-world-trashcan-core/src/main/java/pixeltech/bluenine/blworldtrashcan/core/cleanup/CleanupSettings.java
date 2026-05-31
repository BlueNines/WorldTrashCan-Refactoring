package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 清理策略所需的核心配置快照。 */
public final class CleanupSettings {
    private final Set<String> ignoredMaterialKeys;
    private final Set<String> ignoredNameFragments;
    private final Set<String> ignoredLoreFragments;
    private final boolean clearExperienceOrb;
    private final boolean clearMonster;
    private final boolean clearAnimals;
    private final boolean clearProjectile;
    private final boolean clearNamedEntity;
    private final boolean ignoreEntitiesInBoat;
    private final Set<String> entityWhitePatterns;
    private final Set<String> entityBlackPatterns;

    /** 创建清理配置快照。 */
    public CleanupSettings(Set<String> ignoredMaterialKeys, Set<String> ignoredNameFragments,
                           Set<String> ignoredLoreFragments, boolean clearExperienceOrb,
                           boolean clearMonster, boolean clearAnimals, boolean clearProjectile,
                           boolean clearNamedEntity, boolean ignoreEntitiesInBoat,
                           Set<String> entityWhitePatterns, Set<String> entityBlackPatterns) {
        this.ignoredMaterialKeys = normalizeSet(ignoredMaterialKeys);
        this.ignoredNameFragments = normalizeSet(ignoredNameFragments);
        this.ignoredLoreFragments = normalizeSet(ignoredLoreFragments);
        this.clearExperienceOrb = clearExperienceOrb;
        this.clearMonster = clearMonster;
        this.clearAnimals = clearAnimals;
        this.clearProjectile = clearProjectile;
        this.clearNamedEntity = clearNamedEntity;
        this.ignoreEntitiesInBoat = ignoreEntitiesInBoat;
        this.entityWhitePatterns = normalizeSet(entityWhitePatterns);
        this.entityBlackPatterns = normalizeSet(entityBlackPatterns);
    }

    /** 判断物品类型是否跳过清理。 */
    public boolean isIgnoredMaterial(String materialKey) {
        return ignoredMaterialKeys.contains(normalize(materialKey));
    }

    /** 判断名字片段是否命中跳过规则。 */
    public boolean matchesIgnoredName(String displayName) {
        return containsAny(displayName, ignoredNameFragments);
    }

    /** 判断 lore 片段是否命中跳过规则。 */
    public boolean matchesIgnoredLore(Iterable<String> lore) {
        if (lore == null) {
            return false;
        }
        for (String line : lore) {
            if (containsAny(line, ignoredLoreFragments)) {
                return true;
            }
        }
        return false;
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

    /** 判断实体是否命中白名单规则。 */
    public boolean matchesEntityWhitelist(String typeKey, String entityName) {
        return matchesPattern(typeKey, entityWhitePatterns) || matchesPattern(entityName, entityWhitePatterns);
    }

    /** 判断实体是否命中黑名单规则。 */
    public boolean matchesEntityBlacklist(String typeKey, String entityName) {
        return matchesPattern(typeKey, entityBlackPatterns) || matchesPattern(entityName, entityBlackPatterns);
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

    /** 判断文本是否包含任一片段。 */
    private static boolean containsAny(String text, Set<String> fragments) {
        if (text == null || fragments.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        for (String fragment : fragments) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 判断文本是否命中通配规则。 */
    private static boolean matchesPattern(String text, Set<String> patterns) {
        if (text == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        for (String pattern : patterns) {
            if (wildcardMatch(normalized, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 使用星号通配规则进行匹配。 */
    private static boolean wildcardMatch(String value, String pattern) {
        if (pattern.indexOf('*') < 0) {
            return value.equals(pattern);
        }
        int valueIndex = 0;
        String[] parts = pattern.split("\\*", -1);
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) {
                continue;
            }
            int found = value.indexOf(part, valueIndex);
            if (found < 0) {
                return false;
            }
            if (index == 0 && !pattern.startsWith("*") && found != 0) {
                return false;
            }
            valueIndex = found + part.length();
        }
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return pattern.endsWith("*") || last.isEmpty() || value.endsWith(last);
    }

    /** 标准化比较文本。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
