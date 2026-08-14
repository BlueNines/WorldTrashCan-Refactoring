package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 配置加载时预编译、运行时无正则分配的星号通配规则集合。 */
public final class WildcardPatternSet {
    private final List<CompiledPattern> patterns;

    /** 创建已经预编译的规则集合。 */
    private WildcardPatternSet(List<CompiledPattern> patterns) {
        this.patterns = patterns;
    }

    /** 编译规则；plainTextMeansContains 决定不含星号时使用包含还是完整匹配。 */
    public static WildcardPatternSet compile(Set<String> values, boolean plainTextMeansContains) {
        if (values == null || values.isEmpty()) {
            return new WildcardPatternSet(Collections.<CompiledPattern>emptyList());
        }
        Set<String> normalizedValues = new HashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                normalizedValues.add(normalized);
            }
        }
        if (normalizedValues.isEmpty()) {
            return new WildcardPatternSet(Collections.<CompiledPattern>emptyList());
        }
        List<CompiledPattern> compiled = new ArrayList<>(normalizedValues.size());
        for (String normalized : normalizedValues) {
            compiled.add(CompiledPattern.compile(normalized, plainTextMeansContains));
        }
        return new WildcardPatternSet(Collections.unmodifiableList(compiled));
    }

    /** 判断是否没有有效规则。 */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** 判断文本是否命中任一规则。 */
    public boolean matches(String text) {
        if (text == null || patterns.isEmpty()) {
            return false;
        }
        return matchesNormalized(normalize(text));
    }

    /** 判断已经完成小写和首尾空白处理的文本是否命中，供热路径复用。 */
    boolean matchesNormalized(String normalized) {
        if (normalized == null || patterns.isEmpty()) {
            return false;
        }
        for (CompiledPattern pattern : patterns) {
            if (pattern.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 判断任意文本是否命中任一规则。 */
    public boolean matchesAny(Iterable<String> values) {
        if (values == null || patterns.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (matches(value)) {
                return true;
            }
        }
        return false;
    }

    /** 标准化配置和被匹配文本。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 单条已经预处理的匹配规则。 */
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

        /** 创建单条预编译规则。 */
        private CompiledPattern(int mode, String literal, String[] segments,
                                boolean anchoredStart, boolean anchoredEnd) {
            this.mode = mode;
            this.literal = literal;
            this.segments = segments;
            this.anchoredStart = anchoredStart;
            this.anchoredEnd = anchoredEnd;
        }

        /** 编译单条规则。 */
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

        /** 拆分星号之间的非空文本段。 */
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

        /** 执行一次无临时数组分配的匹配。 */
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
