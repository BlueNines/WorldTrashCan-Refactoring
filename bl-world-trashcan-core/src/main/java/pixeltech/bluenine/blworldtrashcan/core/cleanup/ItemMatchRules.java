package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.Set;

/** Material、名称、Lore、PDC 和 Raw NBT 五类物品识别规则。 */
public final class ItemMatchRules {
    private final WildcardPatternSet materialPatterns;
    private final WildcardPatternSet namePatterns;
    private final WildcardPatternSet lorePatterns;
    private final WildcardPatternSet pdcKeyPatterns;
    private final WildcardPatternSet nbtKeyPatterns;

    /** 创建五类任一命中即成立的规则。 */
    public ItemMatchRules(Set<String> materials, Set<String> names, Set<String> lore,
                          Set<String> pdcKeys, Set<String> nbtKeys) {
        this.materialPatterns = WildcardPatternSet.compile(materials, false);
        this.namePatterns = WildcardPatternSet.compile(names, true);
        this.lorePatterns = WildcardPatternSet.compile(lore, true);
        this.pdcKeyPatterns = WildcardPatternSet.compile(pdcKeys, false);
        this.nbtKeyPatterns = WildcardPatternSet.compile(nbtKeys, false);
    }

    /** 返回不包含任何有效规则的默认值。 */
    public static ItemMatchRules empty() {
        return new ItemMatchRules(null, null, null, null, null);
    }

    /** 判断五类规则是否全部为空。 */
    public boolean isEmpty() {
        return materialPatterns.isEmpty() && namePatterns.isEmpty() && lorePatterns.isEmpty()
                && pdcKeyPatterns.isEmpty() && nbtKeyPatterns.isEmpty();
    }

    /** 判断 Material、名称或 Lore 是否已经命中。 */
    public boolean matchesVisible(ItemSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return materialPatterns.matches(snapshot.getMaterialKey())
                || namePatterns.matches(snapshot.getDisplayName())
                || lorePatterns.matchesAny(snapshot.getLore());
    }

    /** 判断规则是否需要读取 PDC。 */
    public boolean requiresPdcKeys() {
        return !pdcKeyPatterns.isEmpty();
    }

    /** 判断规则是否需要读取 Raw NBT。 */
    public boolean requiresNbtKeys() {
        return !nbtKeyPatterns.isEmpty();
    }

    /** 判断任一 PDC key 是否命中。 */
    public boolean matchesPdcKeys(Iterable<String> keys) {
        return pdcKeyPatterns.matchesAny(keys);
    }

    /** 判断任一 Raw NBT 路径是否命中。 */
    public boolean matchesNbtKeys(Iterable<String> keys) {
        return nbtKeyPatterns.matchesAny(keys);
    }
}
