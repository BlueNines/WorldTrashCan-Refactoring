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
    private final boolean clearMonster;
    private final boolean clearAnimals;
    private final boolean clearProjectile;
    private final boolean clearNamedEntity;
    private final boolean ignoreEntitiesInBoat;

    /** 创建清理配置快照。 */
    public CleanupSettings(Set<String> ignoredMaterialKeys, Set<String> ignoredNameFragments,
                           Set<String> ignoredLoreFragments, boolean clearMonster, boolean clearAnimals,
                           boolean clearProjectile, boolean clearNamedEntity, boolean ignoreEntitiesInBoat) {
        this.ignoredMaterialKeys = normalizeSet(ignoredMaterialKeys);
        this.ignoredNameFragments = normalizeSet(ignoredNameFragments);
        this.ignoredLoreFragments = normalizeSet(ignoredLoreFragments);
        this.clearMonster = clearMonster;
        this.clearAnimals = clearAnimals;
        this.clearProjectile = clearProjectile;
        this.clearNamedEntity = clearNamedEntity;
        this.ignoreEntitiesInBoat = ignoreEntitiesInBoat;
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

    /** 标准化比较文本。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
