package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** 不依赖 JUnit 的命名实体规则与颜色格式自测。 */
public final class NamedEntityRulesSelfTest {
    /** 执行全部命名实体规则自测。 */
    public static void main(String[] args) {
        assertMatch("empty", NamedEntityRules.empty().match("ZOMBIE", "&c小怪"),
                NamedEntityRules.Match.NONE);

        NamedEntityRules rules = NamedEntityRules.compile(
                Collections.singletonList(rule("ZOMBIE", "&6世界 Boss")),
                Arrays.asList(
                        rule("ZOMBIE", "&c特殊的怪物"),
                        rule("SKELETON", "*精英*骷髅*"),
                        rule("MOD_*", "巡逻怪")
                ));

        assertTrue("has rules", rules.hasRules());
        assertTrue("has whitelist", rules.hasWhitelist());
        assertTrue("has blacklist", rules.hasBlacklist());
        assertMatch("colored blacklist",
                rules.match("ZOMBIE", "§7[Lv.30] §c特殊的怪物 §8[120/120]"),
                NamedEntityRules.Match.BLACKLIST);
        assertMatch("ampersand runtime blacklist",
                rules.match("zombie", "&7[Lv.30] &c特殊的怪物"),
                NamedEntityRules.Match.BLACKLIST);
        assertMatch("wrong color", rules.match("ZOMBIE", "§6特殊的怪物"),
                NamedEntityRules.Match.NONE);
        assertMatch("whitelist priority", rules.match("ZOMBIE", "§6世界 Boss §c特殊的怪物"),
                NamedEntityRules.Match.WHITELIST);
        assertMatch("plain ignores colors", rules.match("MOD_RAIDER", "§a巡§b逻§c怪"),
                NamedEntityRules.Match.BLACKLIST);
        assertMatch("name wildcard", rules.match("SKELETON", "§c[20] 精英远古骷髅 [100]"),
                NamedEntityRules.Match.BLACKLIST);
        assertMatch("type and name", rules.match("CREEPER", "§c特殊的怪物"),
                NamedEntityRules.Match.NONE);
        assertMatch("unnamed", rules.match("ZOMBIE", ""), NamedEntityRules.Match.NONE);

        String expanded = "§x§1§2§A§b§F§0渐变怪";
        assertEquals("hex config", "&#12abf0渐变怪", EntityNameCodec.toConfigText(expanded));
        assertEquals("strip colors", "渐变怪", EntityNameCodec.stripColors(expanded));
    }

    /** 创建只含一个类型和名称的规则。 */
    private static NamedEntityRules.RuleSpec rule(String type, String name) {
        return new NamedEntityRules.RuleSpec(
                new HashSet<>(Collections.singletonList(type)),
                new HashSet<>(Collections.singletonList(name)));
    }

    /** 断言匹配结果。 */
    private static void assertMatch(String name, NamedEntityRules.Match actual, NamedEntityRules.Match expected) {
        if (actual != expected) {
            throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
        }
    }

    /** 断言布尔结果。 */
    private static void assertTrue(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name + " expected=true");
        }
    }

    /** 断言字符串结果。 */
    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
        }
    }
}
