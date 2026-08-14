package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 验证当前版本配置更新器只注释旧节点、保留用户内容并强制备份。 */
public final class BukkitCurrentConfigUpdaterTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Logger LOGGER = Logger.getLogger(BukkitCurrentConfigUpdaterTest.class.getName());

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** 验证旧 Lore 配置被保守迁移，原文本进入 .bak 且用户注释保留。 */
    @Test
    public void migratesLegacyLoreWithBackupAndComments() throws Exception {
        String original = "# 服主自己的文件头\r\n"
                + "global-trash:\r\n"
                + "  compact:\r\n"
                + "    # 服主自己的数量说明\r\n"
                + "    show-amount-lore: true\r\n"
                + "    amount-lore: \"&b自定义数量 {amount} %player_name%\"\r\n"
                + "    omitted-lore: \"&7省略 {count} 行\"\r\n"
                + "    # 服主自己的操作说明\r\n"
                + "    action-lore:\r\n"
                + "      - \"&e拿走 {take-amount}\"\r\n"
                + "      - \"&6批量 {shift-take-amount}\"\r\n";
        File file = writeTrash(original);

        assertTrue(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));

        String updated = read(file);
        assertTrue(updated.contains("# 服主自己的文件头\r\n"));
        assertTrue(updated.contains("    # 服主自己的数量说明\r\n"));
        assertTrue(updated.contains("    # amount-lore: \"&b自定义数量 {amount} %player_name%\""));
        assertTrue(updated.contains("    # action-lore:\r\n"));
        assertTrue(updated.contains("      # - \"&e拿走 {take-amount}\""));
        assertTrue(updated.contains("item-lore 于 7.2.0 加入"));

        YamlConfiguration yaml = load(file);
        assertEquals(2, yaml.getInt("config-schema-version"));
        assertEquals(Arrays.asList(
                "&b自定义数量 {amount} %player_name%",
                "{content}",
                "&e拿走 {take-amount}",
                "&6批量 {shift-take-amount}"
        ), yaml.getStringList("global-trash.compact.item-lore"));
        assertFalse(yaml.contains("global-trash.compact.amount-lore"));
        assertFalse(yaml.contains("global-trash.compact.action-lore"));
        assertEquals("&7省略 {count} 行", yaml.getString("global-trash.compact.omitted-lore"));
        assertEquals(original, read(singleBackup()));
    }

    /** 验证关闭数量且操作列表显式为空时只生成原 Lore 占位符。 */
    @Test
    public void preservesFalseAmountSwitchAndEmptyActions() throws Exception {
        File file = writeTrash("global-trash:\n"
                + "  compact:\n"
                + "    show-amount-lore: false\n"
                + "    amount-lore: \"不应出现\"\n"
                + "    action-lore: []\n");

        assertTrue(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));

        assertEquals(Collections.singletonList("{content}"),
                load(file).getStringList("global-trash.compact.item-lore"));
    }

    /** 验证已有 item-lore 时绝不覆盖，也不自动注释同时存在的旧节点。 */
    @Test
    public void existingItemLoreAlwaysWinsWithoutLegacyRewrite() throws Exception {
        File file = writeTrash("global-trash:\n"
                + "  compact:\n"
                + "    amount-lore: \"旧数量\"\n"
                + "    item-lore:\n"
                + "      - \"用户新模板 {amount}\"\n");

        assertTrue(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));

        YamlConfiguration yaml = load(file);
        assertEquals(Collections.singletonList("用户新模板 {amount}"),
                yaml.getStringList("global-trash.compact.item-lore"));
        assertEquals("旧数量", yaml.getString("global-trash.compact.amount-lore"));
        assertTrue(read(file).contains("    amount-lore: \"旧数量\""));
    }

    /** 验证迁移可重复执行，第二次不改字节也不产生新备份。 */
    @Test
    public void secondRunIsByteStable() throws Exception {
        File file = writeTrash("global-trash:\n"
                + "  compact:\n"
                + "    amount-lore: \"数量 {amount}\"\n"
                + "    action-lore:\n"
                + "      - \"拿取\"\n");
        assertTrue(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));
        byte[] firstResult = Files.readAllBytes(file.toPath());

        assertFalse(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));

        assertTrue(Arrays.equals(firstResult, Files.readAllBytes(file.toPath())));
        assertEquals(1, backups().length);
    }

    /** 验证非法 YAML 在创建备份和覆盖原文件之前终止。 */
    @Test
    public void invalidYamlNeverCreatesBackupOrChangesFile() throws Exception {
        String original = "global-trash:\n  compact:\n    action-lore: [\n";
        File file = writeTrash(original);

        try {
            BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER);
            fail("非法 YAML 应拒绝更新");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("YAML 无法解析"));
        }

        assertEquals(original, read(file));
        assertEquals(0, backups().length);
    }

    /** 验证来自更高版本的配置不会被旧插件改写。 */
    @Test
    public void newerSchemaIsLeftUntouched() throws Exception {
        String original = "config-schema-version: 99\n"
                + "global-trash:\n"
                + "  compact:\n"
                + "    amount-lore: \"数量 {amount}\"\n";
        File file = writeTrash(original);

        assertFalse(BukkitCurrentConfigUpdater.updateTrashFile(file, LOGGER));

        assertEquals(original, read(file));
        assertEquals(0, backups().length);
    }

    /** 验证 cleanup.yml 一次备份同时加入结构版本、名称名单和全部 -5 通知。 */
    @Test
    public void updatesCleanupFileInOneVerifiedBackup() throws Exception {
        String original = "# 服主 cleanup 注释\r\n"
                + "entities:\r\n"
                + "  clear-named-entities: false\r\n"
                + "  blacklist:\r\n"
                + "    - \"ZOMBIE\"\r\n"
                + "notify:\r\n"
                + "  chat:\r\n"
                + "    messages:\r\n"
                + "      - \"0;聊天完成\"\r\n"
                + "  actionbar:\r\n"
                + "    messages:\r\n"
                + "      - \"0;动作栏完成\"\r\n"
                + "  bossbar:\r\n"
                + "    messages:\r\n"
                + "      - \"0;BossBar完成;SOLID;BLUE\"\r\n"
                + "  title:\r\n"
                + "    messages:\r\n"
                + "      - \"0;标题;副标题\"\r\n";
        File file = writeCleanup(original);

        assertTrue(BukkitCurrentConfigUpdater.updateCleanupFile(file, cleanupDefaults(), LOGGER));

        YamlConfiguration yaml = load(file);
        assertEquals(1, yaml.getInt("config-schema-version"));
        assertTrue(yaml.isList("entities.named-whitelist"));
        assertTrue(yaml.getMapList("entities.named-whitelist").isEmpty());
        assertTrue(yaml.isList("entities.named-blacklist"));
        assertTrue(yaml.getMapList("entities.named-blacklist").isEmpty());
        assertTrue(hasMessage(yaml.getStringList("notify.chat.messages"), "-5;聊天跳过"));
        assertTrue(hasMessage(yaml.getStringList("notify.actionbar.messages"), "-5;动作栏跳过"));
        assertTrue(hasMessage(yaml.getStringList("notify.bossbar.messages"), "-5;BossBar跳过;SOLID;YELLOW"));
        assertTrue(hasMessage(yaml.getStringList("notify.title.messages"), "-5;标题跳过;副标题"));
        assertTrue(read(file).contains("# 服主 cleanup 注释\r\n"));
        assertEquals(original, read(singleBackup()));
        assertEquals(1, backups().length);
    }

    /** 验证 cleanup.yml 第二次更新保持字节和备份数量不变。 */
    @Test
    public void cleanupSecondRunIsByteStable() throws Exception {
        File file = writeCleanup("entities:\n  blacklist: []\nnotify:\n  chat:\n    messages:\n      - \"0;完成\"\n");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("notify.chat.messages", Arrays.asList("0;完成", "-5;跳过"));

        assertTrue(BukkitCurrentConfigUpdater.updateCleanupFile(file, defaults, LOGGER));
        byte[] first = Files.readAllBytes(file.toPath());
        assertFalse(BukkitCurrentConfigUpdater.updateCleanupFile(file, defaults, LOGGER));

        assertTrue(Arrays.equals(first, Files.readAllBytes(file.toPath())));
        assertEquals(1, backups().length);
    }

    /** 验证 cleanup.yml 已有名称规则时不覆盖服主内容。 */
    @Test
    public void existingCleanupNamedRulesArePreserved() throws Exception {
        File file = writeCleanup("entities:\n"
                + "  named-whitelist:\n"
                + "    - type-patterns: [\"ZOMBIE\"]\n"
                + "      name-patterns: [\"&6Boss\"]\n"
                + "  named-blacklist: []\n");

        assertTrue(BukkitCurrentConfigUpdater.updateCleanupFile(file, new YamlConfiguration(), LOGGER));

        assertEquals(Collections.singletonList("&6Boss"),
                load(file).getMapList("entities.named-whitelist").get(0).get("name-patterns"));
        assertEquals(1, backups().length);
    }

    /** 验证 cleanup.yml 较新结构版本不会被旧 Jar 改写。 */
    @Test
    public void newerCleanupSchemaIsLeftUntouched() throws Exception {
        String original = "config-schema-version: 99\nentities:\n  named-whitelist: []\n  named-blacklist: []\n";
        File file = writeCleanup(original);

        assertFalse(BukkitCurrentConfigUpdater.updateCleanupFile(file, new YamlConfiguration(), LOGGER));

        assertEquals(original, read(file));
        assertEquals(0, backups().length);
    }

    /** 在临时目录写入 trash.yml。 */
    private File writeTrash(String text) throws IOException {
        File file = folder.newFile("trash.yml");
        Files.write(file.toPath(), text.getBytes(UTF8));
        return file;
    }

    /** 在临时目录写入 cleanup.yml。 */
    private File writeCleanup(String text) throws IOException {
        File file = folder.newFile("cleanup.yml");
        Files.write(file.toPath(), text.getBytes(UTF8));
        return file;
    }

    /** 创建包含四个 -5 默认通知的 Jar 内资源替身。 */
    private YamlConfiguration cleanupDefaults() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("notify.chat.messages", Arrays.asList("0;聊天完成", "-5;聊天跳过"));
        defaults.set("notify.actionbar.messages", Arrays.asList("0;动作栏完成", "-5;动作栏跳过"));
        defaults.set("notify.bossbar.messages", Arrays.asList(
                "0;BossBar完成;SOLID;BLUE", "-5;BossBar跳过;SOLID;YELLOW"));
        defaults.set("notify.title.messages", Arrays.asList("0;标题;副标题", "-5;标题跳过;副标题"));
        return defaults;
    }

    /** 判断列表是否包含指定消息。 */
    private boolean hasMessage(List<String> values, String expected) {
        return values != null && values.contains(expected);
    }

    /** 以 UTF-8 读取文件。 */
    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), UTF8);
    }

    /** 严格加载测试 YAML。 */
    private YamlConfiguration load(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    /** 返回当前测试目录中的全部备份。 */
    private File[] backups() {
        File[] files = folder.getRoot().listFiles((directory, name) -> name.endsWith(".bak"));
        return files == null ? new File[0] : files;
    }

    /** 返回唯一备份。 */
    private File singleBackup() {
        File[] files = backups();
        assertEquals(1, files.length);
        return files[0];
    }
}
