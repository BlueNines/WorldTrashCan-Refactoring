package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 按实体类型分桶、在 reload 时预编译的自定义名称白黑名单。 */
public final class NamedEntityRules {
    private static final NamedEntityRules EMPTY = new NamedEntityRules(RuleIndex.empty(), RuleIndex.empty());

    private final RuleIndex whitelist;
    private final RuleIndex blacklist;

    /** 保存已经编译的白名单和黑名单索引。 */
    private NamedEntityRules(RuleIndex whitelist, RuleIndex blacklist) {
        this.whitelist = whitelist;
        this.blacklist = blacklist;
    }

    /** 返回共享空规则，缺少配置时不会创建额外对象。 */
    public static NamedEntityRules empty() {
        return EMPTY;
    }

    /** 编译名称白名单和黑名单；无有效规则时返回共享空对象。 */
    public static NamedEntityRules compile(List<RuleSpec> whitelistSpecs, List<RuleSpec> blacklistSpecs) {
        RuleIndex compiledWhitelist = RuleIndex.compile(whitelistSpecs);
        RuleIndex compiledBlacklist = RuleIndex.compile(blacklistSpecs);
        if (compiledWhitelist.isEmpty() && compiledBlacklist.isEmpty()) {
            return EMPTY;
        }
        return new NamedEntityRules(compiledWhitelist, compiledBlacklist);
    }

    /** 判断是否至少有一条有效名称规则。 */
    public boolean hasRules() {
        return !whitelist.isEmpty() || !blacklist.isEmpty();
    }

    /** 判断是否有有效名称白名单。 */
    public boolean hasWhitelist() {
        return !whitelist.isEmpty();
    }

    /** 判断是否有有效名称黑名单。 */
    public boolean hasBlacklist() {
        return !blacklist.isEmpty();
    }

    /** 按白名单优先级匹配实体类型和 Bukkit 自定义名称。 */
    public Match match(String typeKey, String customName) {
        if (!hasRules() || customName == null || customName.isEmpty()) {
            return Match.NONE;
        }
        String normalizedType = normalizeType(typeKey);
        boolean whitelistApplicable = whitelist.hasApplicableType(normalizedType);
        boolean blacklistApplicable = blacklist.hasApplicableType(normalizedType);
        if (!whitelistApplicable && !blacklistApplicable) {
            return Match.NONE;
        }
        NameText text = NameText.from(customName);
        if (whitelistApplicable && whitelist.matches(normalizedType, text)) {
            return Match.WHITELIST;
        }
        if (blacklistApplicable && blacklist.matches(normalizedType, text)) {
            return Match.BLACKLIST;
        }
        return Match.NONE;
    }

    /** 标准化实体类型，类型通常已经是大写常量，因此仅在名称规则启用时执行。 */
    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 名称规则的最终匹配结果。 */
    public enum Match {
        NONE,
        WHITELIST,
        BLACKLIST
    }

    /** 一条配置规则；类型内部 OR、名称内部 OR、类型和名称之间 AND。 */
    public static final class RuleSpec {
        private final Set<String> typePatterns;
        private final Set<String> namePatterns;

        /** 创建一条不可变规则描述。 */
        public RuleSpec(Set<String> typePatterns, Set<String> namePatterns) {
            this.typePatterns = immutableCopy(typePatterns);
            this.namePatterns = immutableCopy(namePatterns);
        }

        /** 返回实体类型规则。 */
        public Set<String> getTypePatterns() {
            return typePatterns;
        }

        /** 返回自定义名称规则。 */
        public Set<String> getNamePatterns() {
            return namePatterns;
        }

        /** 复制非空字符串集合。 */
        private static Set<String> immutableCopy(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> result = new HashSet<>();
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    result.add(value.trim());
                }
            }
            return result.isEmpty() ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(result);
        }
    }

    /** 单侧名单的类型索引。 */
    private static final class RuleIndex {
        private final Map<String, List<NamePatternSet>> exactTypes;
        private final List<WildcardRule> wildcardTypes;

        /** 保存精确类型桶和少量通配类型规则。 */
        private RuleIndex(Map<String, List<NamePatternSet>> exactTypes, List<WildcardRule> wildcardTypes) {
            this.exactTypes = exactTypes;
            this.wildcardTypes = wildcardTypes;
        }

        /** 返回空索引。 */
        private static RuleIndex empty() {
            return new RuleIndex(Collections.<String, List<NamePatternSet>>emptyMap(),
                    Collections.<WildcardRule>emptyList());
        }

        /** 编译配置规则并按无通配符的实体类型分桶。 */
        private static RuleIndex compile(List<RuleSpec> specs) {
            if (specs == null || specs.isEmpty()) {
                return empty();
            }
            Map<String, List<NamePatternSet>> exact = new HashMap<>();
            List<WildcardRule> wildcard = new ArrayList<>();
            for (RuleSpec spec : specs) {
                if (spec == null || spec.getTypePatterns().isEmpty() || spec.getNamePatterns().isEmpty()) {
                    continue;
                }
                NamePatternSet names = NamePatternSet.compile(spec.getNamePatterns());
                if (names.isEmpty()) {
                    continue;
                }
                for (String rawType : spec.getTypePatterns()) {
                    String type = normalizeType(rawType);
                    if (type.isEmpty()) {
                        continue;
                    }
                    if (type.indexOf('*') >= 0) {
                        wildcard.add(new WildcardRule(
                                WildcardPatternSet.compile(Collections.singleton(type), false), names));
                        continue;
                    }
                    List<NamePatternSet> bucket = exact.get(type);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        exact.put(type, bucket);
                    }
                    bucket.add(names);
                }
            }
            if (exact.isEmpty() && wildcard.isEmpty()) {
                return empty();
            }
            Map<String, List<NamePatternSet>> immutableExact = new HashMap<>();
            for (Map.Entry<String, List<NamePatternSet>> entry : exact.entrySet()) {
                immutableExact.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            return new RuleIndex(Collections.unmodifiableMap(immutableExact),
                    Collections.unmodifiableList(new ArrayList<>(wildcard)));
        }

        /** 判断索引是否为空。 */
        private boolean isEmpty() {
            return exactTypes.isEmpty() && wildcardTypes.isEmpty();
        }

        /** 先判断类型是否可能命中，避免无关实体进行名称颜色处理。 */
        private boolean hasApplicableType(String type) {
            if (isEmpty() || type.isEmpty()) {
                return false;
            }
            if (exactTypes.containsKey(type)) {
                return true;
            }
            for (WildcardRule rule : wildcardTypes) {
                if (rule.matchesType(type)) {
                    return true;
                }
            }
            return false;
        }

        /** 匹配已经确认存在候选规则的实体。 */
        private boolean matches(String type, NameText text) {
            List<NamePatternSet> exact = exactTypes.get(type);
            if (exact != null) {
                for (NamePatternSet names : exact) {
                    if (names.matches(text)) {
                        return true;
                    }
                }
            }
            for (WildcardRule rule : wildcardTypes) {
                if (rule.matchesType(type) && rule.matchesName(text)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 带通配实体类型的一条规则。 */
    private static final class WildcardRule {
        private final WildcardPatternSet types;
        private final NamePatternSet names;

        /** 保存预编译类型与名称规则。 */
        private WildcardRule(WildcardPatternSet types, NamePatternSet names) {
            this.types = types;
            this.names = names;
        }

        /** 匹配已经标准化的实体类型。 */
        private boolean matchesType(String type) {
            return types.matchesNormalized(type);
        }

        /** 匹配已经只转换一次的实体名称。 */
        private boolean matchesName(NameText text) {
            return names.matches(text);
        }
    }

    /** 将有颜色和无颜色规则分开，支持按配置是否含颜色决定语义。 */
    private static final class NamePatternSet {
        private final WildcardPatternSet colored;
        private final WildcardPatternSet plain;

        /** 保存两类预编译名称规则。 */
        private NamePatternSet(WildcardPatternSet colored, WildcardPatternSet plain) {
            this.colored = colored;
            this.plain = plain;
        }

        /** 编译名称规则；无星号时按包含匹配。 */
        private static NamePatternSet compile(Set<String> values) {
            Set<String> coloredValues = new HashSet<>();
            Set<String> plainValues = new HashSet<>();
            for (String value : values) {
                if (EntityNameCodec.containsColorCode(value)) {
                    coloredValues.add(EntityNameCodec.normalizeForMatch(value));
                } else {
                    plainValues.add(EntityNameCodec.normalizeForMatch(EntityNameCodec.stripColors(value)));
                }
            }
            return new NamePatternSet(WildcardPatternSet.compile(coloredValues, true),
                    WildcardPatternSet.compile(plainValues, true));
        }

        /** 判断是否没有有效名称规则。 */
        private boolean isEmpty() {
            return colored.isEmpty() && plain.isEmpty();
        }

        /** 匹配实体名称；同一个实体只生成一次有色和去色文本。 */
        private boolean matches(NameText text) {
            return (!colored.isEmpty() && colored.matchesNormalized(text.colored))
                    || (!plain.isEmpty() && plain.matchesNormalized(text.plain));
        }
    }

    /** 单个实体名称的两种标准形式。 */
    private static final class NameText {
        private final String colored;
        private final String plain;

        /** 保存名称的有色和去色形式。 */
        private NameText(String colored, String plain) {
            this.colored = colored;
            this.plain = plain;
        }

        /** 对一个候选实体只执行一次颜色标准化。 */
        private static NameText from(String customName) {
            String normalized = EntityNameCodec.normalizeForMatch(customName);
            return new NameText(normalized, EntityNameCodec.stripNormalizedColors(normalized));
        }
    }
}
