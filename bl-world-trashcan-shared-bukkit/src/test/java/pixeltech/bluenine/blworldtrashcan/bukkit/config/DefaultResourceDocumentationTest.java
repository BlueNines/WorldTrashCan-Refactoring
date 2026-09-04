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

    /** 验证四套清理配置都写明旧实体数变量与新增实际件数变量。 */
    @Test
    public void cleanupItemCountPlaceholdersAreDocumented() throws Exception {
        Path root = repositoryRoot();
        for (String module : MODULES) {
            String text = read(resources(root, module).resolve("cleanup.yml"));

            assertTrue(module + " 缺少旧版处理实体数变量说明", text.contains("%DealItemSum%"));
            assertTrue(module + " 缺少旧版公共桶实体数变量说明", text.contains("%GlobalTrashAddSum%"));
            assertTrue(module + " 缺少实际处理件数变量说明", text.contains("%DealItemAmount%"));
            assertTrue(module + " 缺少公共桶实际件数变量说明", text.contains("%GlobalTrashAddAmount%"));
            assertTrue(module + " 缺少三组 64 计为 3 的兼容示例", text.contains("计为 3，保持旧版语义"));
            assertTrue(module + " 缺少三组 64 计为 192 的精确件数示例", text.contains("计为 192"));
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

            assertEquals(5, yaml.getInt("config-schema-version"));
            assertFalse(yaml.getBoolean("global-trash.admission-whitelist.enabled"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 公共桶准入白名单填写示例"));
            assertEquals(1, occurrences(text, "[WorldListTrashCan] 7.4.1 个人桶 actions/close 最小示例"));
            assertEquals("/wtc personal", yaml.getString("personal-trash.notify.personal-click-command"));
            assertEquals("/wtc global", yaml.getString("personal-trash.notify.global-click-command"));
            assertFalse(yaml.contains("personal-trash.gui.layout.items.d"));
            assertFalse(yaml.contains("personal-trash.gui.layout.items.e"));
        }
    }

    /** 验证四套产物都默认关闭堆叠，并提供完整中文注释的独立配置。 */
    @Test
    public void itemStackingDefaultsAreDisabledAndDocumented() throws Exception {
        Path root = repositoryRoot();
        for (String module : MODULES) {
            Path resources = resources(root, module);
            YamlConfiguration config = load(resources.resolve("config.yml"));
            Path detailFile = resources.resolve("item-stacking.yml");
            YamlConfiguration detail = load(detailFile);
            String detailText = read(detailFile);

            assertFalse(module + " 必须默认关闭掉落物逻辑堆叠",
                    detail.getBoolean("enabled", true));
            assertFalse(module + " 不应继续在 config.yml 暴露旧堆叠开关",
                    config.contains("features.item-stacking.enabled"));
            assertEquals(1024, detail.getInt("stack.max-logical-amount"));
            assertEquals(8, detail.getInt("scheduler.max-chunks-per-run"));
            assertEquals(4096, detail.getInt("scheduler.max-queued-chunks"));
            assertEquals("zh_CN", detail.getString("display.custom-name.locale"));
            assertTrue(detail.contains("items.DIAMOND_BLOCK"));
            assertEquals(200, detail.getInt("items.DIAMOND_BLOCK.max-stack-size"));
            assertTrue(detailText.contains("max-stack-size 为 -1"));
            assertTrue(detailText.contains("DIAMOND_BLOCK"));
            assertTrue(detailText.contains("200"));
            assertTrue(detailText.contains("1024"));
            assertFalse("旧 display.custom-name.overrides 必须完全移除",
                    detail.contains("display.custom-name.overrides"));
            assertFalse(detailText.contains("minecraft:stone"));
            if (module.contains("legacy")) {
                assertTrue(detailText.contains("1.12.2 缺少掉落物实体 PDC"));
            } else {
                assertTrue(detailText.contains("items 只作为本文件中的小型稀疏覆盖表读取一次"));
                assertTrue(detailText.contains("不会自动生成完整清单"));
            }
            assertTrue(detailText.contains("不会无限占用内存"));
        }
    }

    /** 验证四套产物都提供默认关闭的实体生成拦截日志开关。 */
    @Test
    public void entityLimitBlockedSpawnLoggingIsDocumented() throws Exception {
        Path root = repositoryRoot();
        for (String module : MODULES) {
            Path file = resources(root, module).resolve("entity-limits.yml");
            String text = read(file);
            YamlConfiguration yaml = load(file);

            assertFalse(module + " 不应默认输出实体生成拦截日志",
                    yaml.getBoolean("world-limits.log-blocked-spawns", true));
            assertTrue(module + " 缺少实体生成拦截日志配置注释",
                    text.contains("达到上限而拦截实体生成时"));
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
                assertTrue(language + " 缺少个人按钮文案",
                        yaml.contains("personal-trash.recycle.personal-button"));
                assertTrue(language + " 缺少公共按钮文案",
                        yaml.contains("personal-trash.recycle.global-button"));
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
