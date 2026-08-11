package pixeltech.bluenine.blworldtrashcan.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 扫地世界 include/exclude 通配过滤器。 */
public final class CleanupWorldFilter {
    private static final Set<String> DEFAULT_INCLUDE = Collections.singleton("*");
    private static final Set<String> DEFAULT_EXCLUDE = Collections.singleton("*dungeon*");

    private final List<Pattern> includePatterns;
    private final List<Pattern> excludePatterns;
    private final boolean legacyIgnoredWorldsIgnored;

    /** 创建已经完成通配预编译的世界过滤器。 */
    public CleanupWorldFilter(Set<String> include, Set<String> exclude) {
        this(include, exclude, false);
    }

    /** 创建世界过滤器并记录旧 ignored-worlds 是否被新配置覆盖。 */
    private CleanupWorldFilter(Set<String> include, Set<String> exclude, boolean legacyIgnoredWorldsIgnored) {
        this.includePatterns = compilePatterns(include);
        this.excludePatterns = compilePatterns(exclude);
        this.legacyIgnoredWorldsIgnored = legacyIgnoredWorldsIgnored;
    }

    /** 返回默认允许所有世界、排除 dungeon 世界的过滤器。 */
    public static CleanupWorldFilter defaults() {
        return new CleanupWorldFilter(DEFAULT_INCLUDE, DEFAULT_EXCLUDE);
    }

    /** 从新配置创建过滤器，缺失的单个列表使用对应默认值。 */
    public static CleanupWorldFilter configured(Set<String> include, Set<String> exclude) {
        return configured(include, exclude, false);
    }

    /** 从新配置创建过滤器，并记录是否同时存在废弃旧节点。 */
    public static CleanupWorldFilter configured(Set<String> include, Set<String> exclude,
                                                boolean legacyIgnoredWorldsIgnored) {
        return new CleanupWorldFilter(
                include == null ? DEFAULT_INCLUDE : include,
                exclude == null ? DEFAULT_EXCLUDE : exclude,
                legacyIgnoredWorldsIgnored
        );
    }

    /** 从旧 ignored-worlds 创建过滤器，并追加默认 dungeon 保护。 */
    public static CleanupWorldFilter fromLegacy(Set<String> ignoredWorlds) {
        Set<String> exclude = new HashSet<>(DEFAULT_EXCLUDE);
        if (ignoredWorlds != null) {
            exclude.addAll(ignoredWorlds);
        }
        return new CleanupWorldFilter(DEFAULT_INCLUDE, exclude);
    }

    /** 判断指定 Bukkit 世界名是否允许参与普通扫地。 */
    public boolean allows(String worldName) {
        String normalized = normalize(worldName);
        if (normalized.isEmpty() || !matchesAny(includePatterns, normalized)) {
            return false;
        }
        return !matchesAny(excludePatterns, normalized);
    }

    /** 判断 include 是否至少包含一条有效规则。 */
    public boolean hasIncludeRules() {
        return !includePatterns.isEmpty();
    }

    /** 判断本次加载是否因新配置存在而忽略了旧 ignored-worlds。 */
    public boolean isLegacyIgnoredWorldsIgnored() {
        return legacyIgnoredWorldsIgnored;
    }

    /** 在配置加载时把星号通配文本编译为正则。 */
    private static List<Pattern> compilePatterns(Set<String> values) {
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
        List<Pattern> patterns = new ArrayList<>(normalizedValues.size());
        for (String value : normalizedValues) {
            patterns.add(Pattern.compile(toRegex(value)));
        }
        return Collections.unmodifiableList(patterns);
    }

    /** 把只支持星号的通配文本转换为全部锚定的安全正则。 */
    private static String toRegex(String wildcard) {
        StringBuilder regex = new StringBuilder(wildcard.length() + 8);
        regex.append('^');
        int literalStart = 0;
        for (int index = 0; index < wildcard.length(); index++) {
            if (wildcard.charAt(index) != '*') {
                continue;
            }
            appendLiteral(regex, wildcard, literalStart, index);
            regex.append(".*");
            literalStart = index + 1;
        }
        appendLiteral(regex, wildcard, literalStart, wildcard.length());
        regex.append('$');
        return regex.toString();
    }

    /** 向正则追加经过转义的普通文本片段。 */
    private static void appendLiteral(StringBuilder target, String value, int start, int end) {
        if (end > start) {
            target.append(Pattern.quote(value.substring(start, end)));
        }
    }

    /** 判断文本是否命中任一预编译规则。 */
    private static boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 标准化世界名和配置规则。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
