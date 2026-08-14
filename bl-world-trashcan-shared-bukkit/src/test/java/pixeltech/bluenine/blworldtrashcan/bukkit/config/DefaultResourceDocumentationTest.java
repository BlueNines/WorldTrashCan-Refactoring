package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证四个平台默认配置的 UTF-8、示例、结构版本和 look 键提示。 */
public final class DefaultResourceDocumentationTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final List<String> MODULES = Arrays.asList(
            "bl-world-trashcan-plugin-legacy-1_12",
            "bl-world-trashcan-plugin-bukkit-1_13_1_15",
            "bl-world-trashcan-plugin-paper-1_16_1_20",
            "bl-world-trashcan-plugin-folia-1_20"
    );
    private static final List<String> LANGUAGES = Arrays.asList(
            "message_zh.yml", "message_zh_TW.yml", "message_en.yml", "message_es.yml"
    );

    /** 验证所有默认 YAML 都能解析且没有 UTF-8 替换字符。 */
    @Test
    public void everyDefaultYamlIsUtf8AndParseable() throws Exception {
        Path root = repositoryRoot();
        int parsed = 0;
        for (String module : MODULES) {
            Path resources = root.resolve(module).resolve("src/main/resources");
            try (Stream<Path> files = Files.walk(resources)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".yml")) {
                        continue;
                    }
                    String text = read(file);
                    assertFalse(file + " 包含 UTF-8 替换字符", text.indexOf('\uFFFD') >= 0);
                    load(file);
                    parsed++;
                }
            }
        }
        assertTrue("没有扫描到默认 YAML", parsed > 20);
    }

    /** 验证 cleanup.yml 的空规则、结构版本和三类示例都保持安全默认值。 */
    @Test
    public void cleanupDefaultsContainSafeUsageExamples() throws Exception {
        Path root = repositoryRoot();
        for (String module : MODULES) {
            Path file = resources(root, module).resolve("cleanup.yml");
            String text = read(file);
            YamlConfiguration yaml = load(file);

            assertEquals(2, yaml.getInt("config-schema-version"));
            assertTrue(yaml.isList("entities.named-whitelist"));
            assertTrue(yaml.getMapList("entities.named-whitelist").isEmpty());
            assertTrue(yaml.isList("entities.named-blacklist"));
            assertTrue(yaml.getMapList("entities.named-blacklist").isEmpty());
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 直删世界填写示例"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 五类物品匹配填写示例"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 命名实体规则填写示例"));
            assertEquals(1, activeKeyOccurrences(text, "  named-whitelist:"));
            assertEquals(1, activeKeyOccurrences(text, "  named-blacklist:"));
        }
    }

    /** 验证 trash.yml 的结构版本、关闭状态和两类布局示例。 */
    @Test
    public void trashDefaultsContainSafeUsageExamples() throws Exception {
        Path root = repositoryRoot();
        for (String module : MODULES) {
            Path file = resources(root, module).resolve("trash.yml");
            String text = read(file);
            YamlConfiguration yaml = load(file);

            assertEquals(3, yaml.getInt("config-schema-version"));
            assertFalse(yaml.getBoolean("global-trash.admission-whitelist.enabled"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 公共桶准入白名单填写示例"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 个人桶 actions/close 最小示例"));
            assertFalse(yaml.contains("personal-trash.gui.layout.items.d"));
            assertFalse(yaml.contains("personal-trash.gui.layout.items.e"));
        }
    }

    /** 验证四语言 look 标签都直接标明目标配置键，且四个平台内容一致。 */
    @Test
    public void lookMessagesPointToMatchingConfigKeys() throws Exception {
        Path root = repositoryRoot();
        for (String language : LANGUAGES) {
            String expectedText = null;
            for (String module : MODULES) {
                Path file = resources(root, module).resolve("messages").resolve(language);
                String text = read(file);
                YamlConfiguration yaml = load(file);

                assertContains(yaml, "protection.entity-result", "type-patterns");
                assertContains(yaml, "protection.entity-custom-name", "name-patterns");
                assertContains(yaml, "protection.entity-plain-name", "name-patterns");
                assertContains(yaml, "protection.hand-item", "material-patterns");
                assertContains(yaml, "protection.hand-item-name", "name-key-patterns");
                assertContains(yaml, "protection.hand-item-lore-title", "lore-key-patterns");
                assertContains(yaml, "protection.hand-item-pdc-title", "pdc-key-patterns");
                assertContains(yaml, "protection.hand-item-nbt-title", "nbt-key-patterns");
                if (expectedText == null) {
                    expectedText = text;
                } else {
                    assertEquals(language + " 在四个平台应保持一致", expectedText, text);
                }
            }
        }
    }

    /** 返回插件模块的资源目录。 */
    private Path resources(Path root, String module) {
        return root.resolve(module).resolve("src/main/resources");
    }

    /** 向上查找包含全部插件模块的仓库根目录。 */
    private Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("bl-world-trashcan-plugin-universal"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位 WorldListTrashCan 仓库根目录");
    }

    /** 以 UTF-8 读取文件。 */
    private String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), UTF8);
    }

    /** 使用 Bukkit YAML 解析默认资源。 */
    private YamlConfiguration load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    /** 断言消息路径存在并包含目标配置键。 */
    private void assertContains(YamlConfiguration yaml, String path, String expected) {
        assertTrue(path + " 应包含 " + expected, yaml.getString(path, "").contains(expected));
    }

    /** 统计文本片段出现次数。 */
    private int occurrences(String text, String expected) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(expected, index)) >= 0) {
            count++;
            index += expected.length();
        }
        return count;
    }

    /** 统计未被注释的精确 YAML 键行。 */
    private int activeKeyOccurrences(String text, String keyLine) {
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line.equals(keyLine) || line.startsWith(keyLine + " ")) {
                count++;
            }
        }
        return count;
    }
}
