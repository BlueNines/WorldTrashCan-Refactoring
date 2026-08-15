package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** 当前重构版不同配置结构之间的保守更新器。 */
public final class BukkitCurrentConfigUpdater {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String UPDATE_ENABLED_PATH = "config-update.enabled";
    private static final String TRASH_FILE = "trash.yml";
    private static final String TRASH_SCHEMA_PATH = "config-schema-version";
    private static final int TRASH_SCHEMA_VERSION = 4;
    private static final String CLEANUP_FILE = "cleanup.yml";
    private static final int CLEANUP_SCHEMA_VERSION = 2;
    private static final String NAMED_WHITELIST_PATH = "entities.named-whitelist";
    private static final String NAMED_BLACKLIST_PATH = "entities.named-blacklist";
    private static final String CUSTOM_ITEM_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.4.1 五类物品匹配填写示例";
    private static final String NAMED_ENTITY_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.4.1 命名实体规则填写示例";
    private static final String DIRECT_REMOVE_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.4.1 直删世界填写示例";
    private static final String ADMISSION_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.4.1 公共桶准入白名单填写示例";
    private static final String PERSONAL_BUTTON_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.4.1 个人桶 actions/close 最小示例";
    private static final String PERSONAL_NOTIFY_EXAMPLE_MARKER =
            "[WorldListTrashCan] 7.5.0 个人桶通知点击示例";
    private static final String PERSONAL_NOTIFY_CLICK_COMMAND_PATH =
            "personal-trash.notify.click-command";
    private static final int CLEANUP_GUARD_NOTIFY_KEY = -5;
    private static final String CLEANUP_GUARD_NOTIFY_COMMENT = "# -5 表示本轮被扫地启动门禁跳过。";
    private static final String[] CLEANUP_GUARD_NOTIFY_PATHS = {
            "notify.chat.messages",
            "notify.actionbar.messages",
            "notify.bossbar.messages",
            "notify.title.messages"
    };
    private static final String LORE_CHANGE_VERSION = "7.2.0";
    private static final String[] COMPACT_SCOPES = {
            "global-trash.compact",
            "personal-trash.compact"
    };
    private static final String[] LEGACY_LORE_KEYS = {
            "show-amount-lore",
            "amount-lore",
            "action-lore"
    };
    private static final List<String> DEFAULT_ACTION_LORE = Collections.unmodifiableList(Arrays.asList(
            "&#38BDF8左键 &#D5DEE9取出 &#F5B82E{take-amount} &#D5DEE9个",
            "&#FFD166Shift + 左键 &#D5DEE9取出 &#F5B82E{shift-take-amount} &#D5DEE9个"
    ));
    private final JavaPlugin plugin;

    /** 创建当前版本配置更新器。 */
    public BukkitCurrentConfigUpdater(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 按总开关更新已有配置；关闭时只提示可迁移的旧写法。 */
    public boolean updateIfEnabled() {
        if (!plugin.getConfig().getBoolean(UPDATE_ENABLED_PATH, false)) {
            reportPendingTrashUpdate();
            reportPendingCleanupUpdate();
            return false;
        }
        boolean changed = false;
        try {
            changed = updateTrashFile(new File(plugin.getDataFolder(), TRASH_FILE), plugin.getLogger());
        } catch (IOException exception) {
            plugin.getLogger().warning("[ConfigUpdate] trash.yml 更新失败，原文件保持不变: "
                    + exception.getMessage());
        }
        try {
            YamlConfiguration cleanupDefaults = loadResourceYaml(CLEANUP_FILE);
            if (updateCleanupFile(new File(plugin.getDataFolder(), CLEANUP_FILE),
                    cleanupDefaults, plugin.getLogger())) {
                changed = true;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("[ConfigUpdate] cleanup.yml 更新失败，原文件保持不变: "
                    + exception.getMessage());
        }
        return changed;
    }

    /** 自动更新关闭时提示仍在使用旧 Lore 节点的配置。 */
    private void reportPendingTrashUpdate() {
        File file = new File(plugin.getDataFolder(), TRASH_FILE);
        if (!file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = loadStrict(file);
            if (readSchemaVersion(yaml) < TRASH_SCHEMA_VERSION) {
                plugin.getLogger().info("[ConfigUpdate] 检测到 trash.yml 可补充配置填写示例；当前业务配置继续原样使用。"
                        + "如需保守更新，请在 config.yml 设置 config-update.enabled: true 后执行 /wtc reload。");
                return;
            }
            for (String scope : COMPACT_SCOPES) {
                if (!yaml.contains(scope + ".item-lore") && hasLegacyLoreNode(yaml, scope)) {
                    plugin.getLogger().info("[ConfigUpdate] 检测到旧 compact Lore 配置；当前继续兼容使用。"
                            + "如需保守更新，请在 config.yml 设置 config-update.enabled: true 后执行 /wtc reload。");
                    return;
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("[ConfigUpdate] 无法检查 trash.yml 是否需要更新: "
                    + exception.getMessage());
        }
    }

    /** 自动更新关闭时提示 cleanup.yml 仍可进入独立结构版本。 */
    private void reportPendingCleanupUpdate() {
        File file = new File(plugin.getDataFolder(), CLEANUP_FILE);
        if (!file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = loadStrict(file);
            if (readSchemaVersion(yaml) < CLEANUP_SCHEMA_VERSION
                    || !yaml.contains(NAMED_WHITELIST_PATH)
                    || !yaml.contains(NAMED_BLACKLIST_PATH)) {
                plugin.getLogger().info("[ConfigUpdate] 检测到 cleanup.yml 可保守更新；当前缺失节点继续按空规则兼容。"
                        + "如需写入独立结构版本和新节点，请在 config.yml 设置 config-update.enabled: true 后执行 /wtc reload。");
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("[ConfigUpdate] 无法检查 cleanup.yml 是否需要更新: "
                    + exception.getMessage());
        }
    }

    /** 更新 trash.yml，并在替换前创建和核验唯一 .bak 备份。 */
    static boolean updateTrashFile(File file, Logger logger) throws IOException {
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration before = loadStrict(file);
        int schemaVersion = readSchemaVersion(before);
        if (schemaVersion > TRASH_SCHEMA_VERSION) {
            logger.severe("[ConfigUpdate] trash.yml 的配置结构版本 " + schemaVersion
                    + " 高于当前插件支持的 " + TRASH_SCHEMA_VERSION + "，已拒绝改写该文件。");
            return false;
        }
        List<ScopeMigration> migrations = new ArrayList<>();
        for (String scope : COMPACT_SCOPES) {
            ScopeMigration migration = prepareScopeMigration(before, scope, logger);
            if (migration != null) {
                migrations.add(migration);
            }
        }
        String original = new String(Files.readAllBytes(file.toPath()), UTF8);
        String updated = buildUpdatedText(original, schemaVersion, migrations,
                schemaVersion < TRASH_SCHEMA_VERSION);
        if (updated.equals(original)) {
            return false;
        }
        Path temporary = Files.createTempFile(file.toPath().getParent(), file.getName() + ".", ".update.tmp");
        try {
            Files.write(temporary, updated.getBytes(UTF8));
            YamlConfiguration after = loadStrict(temporary.toFile());
            validateTrashUpdate(before, after, migrations);
            File backup = createVerifiedBackup(file);
            replaceAtomically(temporary, file.toPath());
            logger.info("[ConfigUpdate] 已保守更新 trash.yml，旧节点只被注释，备份: "
                    + backup.getName());
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 更新 cleanup.yml 的结构版本、新名单和历史门禁通知，并且整轮只备份一次。 */
    static boolean updateCleanupFile(File file, YamlConfiguration defaults, Logger logger) throws IOException {
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration before = loadStrict(file);
        int schemaVersion = readSchemaVersion(before);
        if (schemaVersion > CLEANUP_SCHEMA_VERSION) {
            logger.severe("[ConfigUpdate] cleanup.yml 的配置结构版本 " + schemaVersion
                    + " 高于当前插件支持的 " + CLEANUP_SCHEMA_VERSION + "，已拒绝改写该文件。");
            return false;
        }
        validateNamedRuleNodeType(before, NAMED_WHITELIST_PATH);
        validateNamedRuleNodeType(before, NAMED_BLACKLIST_PATH);
        boolean addNamedWhitelist = !before.contains(NAMED_WHITELIST_PATH);
        boolean addNamedBlacklist = !before.contains(NAMED_BLACKLIST_PATH);
        List<NotifyInsertion> notifyInsertions = prepareNotifyInsertions(before, defaults);
        String original = new String(Files.readAllBytes(file.toPath()), UTF8);
        String updated = buildCleanupUpdatedText(original, schemaVersion,
                addNamedWhitelist, addNamedBlacklist, notifyInsertions,
                schemaVersion < CLEANUP_SCHEMA_VERSION);
        if (updated.equals(original)) {
            return false;
        }
        Path temporary = Files.createTempFile(file.toPath().getParent(), file.getName() + ".", ".update.tmp");
        try {
            Files.write(temporary, updated.getBytes(UTF8));
            YamlConfiguration after = loadStrict(temporary.toFile());
            validateCleanupUpdate(before, after, addNamedWhitelist, addNamedBlacklist, notifyInsertions);
            File backup = createVerifiedBackup(file);
            replaceAtomically(temporary, file.toPath());
            logger.info("[ConfigUpdate] 已保守更新 cleanup.yml，结构版本、新名单与门禁通知共用备份: "
                    + backup.getName());
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 读取 Jar 内默认 YAML，作为历史缺失默认值的唯一来源。 */
    private YamlConfiguration loadResourceYaml(String path) throws IOException {
        InputStream input = plugin.getResource(path);
        if (input == null) {
            throw new IOException("Jar 内缺少默认资源: " + path);
        }
        try (Reader reader = new InputStreamReader(input, UTF8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    /** 已存在的命名实体节点必须是列表，错误类型不允许静默覆盖。 */
    private static void validateNamedRuleNodeType(YamlConfiguration yaml, String path) throws IOException {
        if (yaml.contains(path) && !yaml.isList(path)) {
            throw new IOException(path + " 必须是规则列表，已取消自动更新");
        }
    }

    /** 计算当前 cleanup.yml 缺少的 -5 通知。 */
    private static List<NotifyInsertion> prepareNotifyInsertions(YamlConfiguration before,
                                                                  YamlConfiguration defaults) {
        List<NotifyInsertion> result = new ArrayList<>();
        if (defaults == null) {
            return result;
        }
        for (String path : CLEANUP_GUARD_NOTIFY_PATHS) {
            if (!before.isList(path) || hasEventMessage(before.getStringList(path), CLEANUP_GUARD_NOTIFY_KEY)) {
                continue;
            }
            String value = firstEventMessage(defaults.getStringList(path), CLEANUP_GUARD_NOTIFY_KEY);
            if (!value.isEmpty()) {
                result.add(new NotifyInsertion(path, value));
            }
        }
        return result;
    }

    /** 判断分号消息列表是否已有指定事件键。 */
    private static boolean hasEventMessage(List<String> values, int key) {
        return !firstEventMessage(values, key).isEmpty();
    }

    /** 返回分号消息列表中指定事件键的第一条消息。 */
    private static String firstEventMessage(List<String> values, int key) {
        if (values == null) {
            return "";
        }
        String expected = String.valueOf(key);
        for (String value : values) {
            if (expected.equals(eventKey(value))) {
                return value == null ? "" : value;
            }
        }
        return "";
    }

    /** 提取分号消息的事件键。 */
    private static String eventKey(String value) {
        if (value == null) {
            return "";
        }
        int split = value.indexOf(';');
        return (split < 0 ? value : value.substring(0, split)).trim();
    }

    /** 为 cleanup.yml 生成最小文本添加计划。 */
    private static String buildCleanupUpdatedText(String original, int schemaVersion,
                                                   boolean addNamedWhitelist, boolean addNamedBlacklist,
                                                   List<NotifyInsertion> notifyInsertions,
                                                   boolean addUsageExamples) throws IOException {
        boolean bom = original.startsWith("\uFEFF");
        String body = bom ? original.substring(1) : original;
        String separator = body.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = body.split("\\r?\\n", -1);
        TextEdits edits = new TextEdits();
        prepareSchemaEdit(lines, schemaVersion, CLEANUP_SCHEMA_VERSION, edits);
        prepareDirectRemoveExampleInsertion(lines, addUsageExamples, edits);
        prepareCustomItemExampleInsertion(lines, addUsageExamples, edits);
        prepareNamedRuleInsertions(lines, addNamedWhitelist, addNamedBlacklist, addUsageExamples, edits);
        prepareNotifyTextInsertions(lines, notifyInsertions, edits);
        String updated = applyTextEdits(lines, separator, edits);
        return bom ? "\uFEFF" + updated : updated;
    }

    /** 在 entities 段末尾添加缺失的默认空名称名单和完整注释。 */
    private static void prepareNamedRuleInsertions(String[] lines, boolean addNamedWhitelist,
                                                   boolean addNamedBlacklist, boolean addUsageExample,
                                                   TextEdits edits) throws IOException {
        boolean insertExample = addUsageExample && !containsMarker(lines, NAMED_ENTITY_EXAMPLE_MARKER);
        if (!addNamedWhitelist && !addNamedBlacklist && !insertExample) {
            return;
        }
        NodeRange entities = findNodeRange(lines, "entities");
        if (entities == null) {
            throw new IOException("无法定位 entities，已取消 cleanup.yml 自动更新");
        }
        int insertAt = entities.contentEnd;
        for (String path : Arrays.asList("entities.blacklist", NAMED_WHITELIST_PATH, NAMED_BLACKLIST_PATH)) {
            NodeRange existing = findNodeRange(lines, path);
            if (existing != null) {
                insertAt = Math.max(insertAt == entities.contentEnd ? 0 : insertAt, existing.contentEnd);
            }
        }
        if (insertAt <= 0) {
            insertAt = entities.contentEnd;
        }
        String indent = spaces(findChildIndent(lines, entities));
        List<String> block = new ArrayList<>();
        if (addNamedWhitelist) {
            block.add("");
            block.add(indent + "# [WorldListTrashCan] 7.4.0 加入：按实体类型 AND Bukkit 自定义名称保护实体。");
            block.add(indent + "# 默认 []；节点缺失或为空时直接跳过。type-patterns 与 name-patterns 都支持 * 通配。");
            block.add(indent + "# 名称含颜色时颜色参与匹配；名称不含颜色时忽略实体名称颜色。白名单优先于黑名单。");
            block.add(indent + "named-whitelist: []");
        }
        if (addNamedBlacklist) {
            block.add("");
            block.add(indent + "# [WorldListTrashCan] 7.4.0 加入：按实体类型 AND Bukkit 自定义名称强制清理实体。");
            block.add(indent + "# 默认 []；适合在 clear-named-entities: false 时仅清理指定 MythicMobs 小怪。");
            block.add(indent + "named-blacklist: []");
        }
        if (insertExample) {
            block.add("");
            block.add(indent + "# " + NAMED_ENTITY_EXAMPLE_MARKER + "。");
            block.add(indent + "# 使用时把已有 named-whitelist: [] 替换为下面内容；已有自定义规则时只追加列表项，不要新增重复键。");
            block.add(indent + "# named-whitelist:");
            block.add(indent + "#   - type-patterns:");
            block.add(indent + "#       - \"ZOMBIE\"");
            block.add(indent + "#       - \"SKELETON\"");
            block.add(indent + "#     name-patterns:");
            block.add(indent + "#       - \"&6世界 Boss\"");
            block.add(indent + "#       - \"&6副本 Boss\"");
            block.add(indent + "# named-blacklist:");
            block.add(indent + "#   - type-patterns:");
            block.add(indent + "#       - \"ZOMBIE\"");
            block.add(indent + "#     name-patterns:");
            block.add(indent + "#       - \"&c特殊的怪物\"");
        }
        edits.addInsertion(insertAt, block);
    }

    /** 在 direct-remove-worlds 前加入不会生效的世界名和通配示例。 */
    private static void prepareDirectRemoveExampleInsertion(String[] lines, boolean addUsageExample,
                                                            TextEdits edits) throws IOException {
        if (!addUsageExample || containsMarker(lines, DIRECT_REMOVE_EXAMPLE_MARKER)) {
            return;
        }
        NodeRange range = findNodeRange(lines, "direct-remove-worlds");
        if (range == null) {
            return;
        }
        String indent = spaces(range.indent);
        edits.addInsertion(range.start, Arrays.asList(
                indent + "# " + DIRECT_REMOVE_EXAMPLE_MARKER + "；把 [] 改为列表，不要保留重复节点。",
                indent + "# direct-remove-worlds:",
                indent + "#   - \"resource_world\"",
                indent + "#   - \"event_*\""
        ));
    }

    /** 在自定义数据 detection 中加入五类规则的具体填写值。 */
    private static void prepareCustomItemExampleInsertion(String[] lines, boolean addUsageExample,
                                                          TextEdits edits) throws IOException {
        if (!addUsageExample || containsMarker(lines, CUSTOM_ITEM_EXAMPLE_MARKER)) {
            return;
        }
        NodeRange detection = findNodeRange(lines, "custom-data-items.routing.detection");
        if (detection == null) {
            return;
        }
        String indent = spaces(findChildIndent(lines, detection));
        edits.addInsertion(detection.start + 1, Arrays.asList(
                indent + "# " + CUSTOM_ITEM_EXAMPLE_MARKER + "；请把需要的值填入下面对应列表，不要新增重复节点。",
                indent + "# material-patterns: \"DIAMOND_SWORD\"、\"*_SHULKER_BOX\"；填写 /wtc look 输出的 Material。",
                indent + "# name-key-patterns: \"*史诗武器*\"、\"&#FFD166限定道具\"；匹配物品显示名文本，不是数据 key。",
                indent + "# lore-key-patterns: \"*不可交易*\"、\"*灵魂绑定*\"；对原物品 Lore 的每一行分别匹配。",
                indent + "# pdc-key-patterns: \"myplugin:item_id\"、\"myplugin:*\"；直接复制 /wtc look 输出的 PDC Key。",
                indent + "# nbt-key-patterns: \"tag.PublicBukkitValues.myplugin:item_id\"；优先复制 /wtc look 输出的实际路径。"
        ));
    }

    /** 判断原文本是否已经包含指定版本的注释示例。 */
    private static boolean containsMarker(String[] lines, String marker) {
        for (String line : lines) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 在已有通知列表末尾添加缺失的 -5 文案。 */
    private static void prepareNotifyTextInsertions(String[] lines, List<NotifyInsertion> insertions,
                                                    TextEdits edits) throws IOException {
        for (NotifyInsertion insertion : insertions) {
            NodeRange range = findNodeRange(lines, insertion.path);
            if (range == null) {
                throw new IOException("无法定位通知列表 " + insertion.path + "，已取消 cleanup.yml 自动更新");
            }
            String sourceLine = lines[range.start].trim();
            if (!sourceLine.equals(lastPathSegment(insertion.path) + ":")) {
                throw new IOException(insertion.path + " 使用了行内列表，无法仅添加一行并保留原文");
            }
            String indent = spaces(findChildIndent(lines, range));
            edits.addInsertion(range.contentEnd, Arrays.asList(
                    indent + CLEANUP_GUARD_NOTIFY_COMMENT,
                    indent + "- \"" + escapeYamlDoubleQuoted(insertion.value) + "\""
            ));
        }
    }

    /** 返回点分路径最后一个键名。 */
    private static String lastPathSegment(String path) {
        int split = path.lastIndexOf('.');
        return split < 0 ? path : path.substring(split + 1);
    }

    /** 验证 cleanup.yml 更新只改变了允许的结构、新节点和指定通知列表。 */
    private static void validateCleanupUpdate(YamlConfiguration before, YamlConfiguration after,
                                              boolean addedWhitelist, boolean addedBlacklist,
                                              List<NotifyInsertion> insertions) throws IOException {
        if (after.getInt(TRASH_SCHEMA_PATH, -1) != CLEANUP_SCHEMA_VERSION) {
            throw new IOException("更新后的 cleanup.yml 配置结构版本校验失败");
        }
        if (addedWhitelist && (!after.isList(NAMED_WHITELIST_PATH)
                || !after.getMapList(NAMED_WHITELIST_PATH).isEmpty())) {
            throw new IOException(NAMED_WHITELIST_PATH + " 默认空列表校验失败");
        }
        if (addedBlacklist && (!after.isList(NAMED_BLACKLIST_PATH)
                || !after.getMapList(NAMED_BLACKLIST_PATH).isEmpty())) {
            throw new IOException(NAMED_BLACKLIST_PATH + " 默认空列表校验失败");
        }
        for (NotifyInsertion insertion : insertions) {
            List<String> expected = new ArrayList<>(before.getStringList(insertion.path));
            expected.add(insertion.value);
            if (!expected.equals(after.getStringList(insertion.path))) {
                throw new IOException(insertion.path + " 的 -5 通知更新结果不一致");
            }
        }
        Map<String, Object> beforeLeaves = leafValues(before);
        Map<String, Object> afterLeaves = leafValues(after);
        beforeLeaves.remove(TRASH_SCHEMA_PATH);
        afterLeaves.remove(TRASH_SCHEMA_PATH);
        if (addedWhitelist) {
            beforeLeaves.remove(NAMED_WHITELIST_PATH);
            afterLeaves.remove(NAMED_WHITELIST_PATH);
        }
        if (addedBlacklist) {
            beforeLeaves.remove(NAMED_BLACKLIST_PATH);
            afterLeaves.remove(NAMED_BLACKLIST_PATH);
        }
        for (NotifyInsertion insertion : insertions) {
            beforeLeaves.remove(insertion.path);
            afterLeaves.remove(insertion.path);
        }
        if (!beforeLeaves.equals(afterLeaves)) {
            throw new IOException("cleanup.yml 更新触及了允许范围以外的配置，已取消替换");
        }
    }

    /** 读取 trash.yml 的结构版本；缺失时视为最早版本。 */
    private static int readSchemaVersion(YamlConfiguration yaml) throws IOException {
        if (!yaml.contains(TRASH_SCHEMA_PATH)) {
            return 0;
        }
        Object value = yaml.get(TRASH_SCHEMA_PATH);
        if (!(value instanceof Number)) {
            throw new IOException(TRASH_SCHEMA_PATH + " 必须是整数，已取消自动更新");
        }
        if (value instanceof Float || value instanceof Double) {
            throw new IOException(TRASH_SCHEMA_PATH + " 必须是整数，已取消自动更新");
        }
        long rawVersion = ((Number) value).longValue();
        if (rawVersion < 0L || rawVersion > Integer.MAX_VALUE) {
            throw new IOException(TRASH_SCHEMA_PATH + " 必须在 0 到 " + Integer.MAX_VALUE
                    + " 之间，已取消自动更新");
        }
        return (int) rawVersion;
    }

    /** 为单个 compact 配置准备旧 Lore 到 item-lore 的迁移数据。 */
    private static ScopeMigration prepareScopeMigration(YamlConfiguration yaml, String scope,
                                                        Logger logger) throws IOException {
        String itemLorePath = scope + ".item-lore";
        boolean hasLegacy = hasLegacyLoreNode(yaml, scope);
        if (yaml.contains(itemLorePath)) {
            if (hasLegacy) {
                logger.warning("[ConfigUpdate] " + scope + " 同时存在 item-lore 与旧 Lore 节点；"
                        + "item-lore 继续优先，旧节点未被自动改动。");
            }
            return null;
        }
        if (!yaml.isConfigurationSection(scope)) {
            return null;
        }
        boolean showAmount = readBoolean(yaml, scope + ".show-amount-lore", true);
        String amountLore = readString(yaml, scope + ".amount-lore",
                "&#38BDF8数量：&#F5B82E{amount}");
        List<String> actionLore = readStringList(yaml, scope + ".action-lore", DEFAULT_ACTION_LORE);
        List<String> itemLore = new ArrayList<>();
        if (showAmount) {
            itemLore.add(amountLore);
        }
        itemLore.add("{content}");
        itemLore.addAll(actionLore);
        List<String> activeLegacyPaths = new ArrayList<>();
        for (String key : LEGACY_LORE_KEYS) {
            String path = scope + "." + key;
            if (yaml.contains(path)) {
                activeLegacyPaths.add(path);
            }
        }
        return new ScopeMigration(scope, itemLore, activeLegacyPaths);
    }

    /** 判断单个 compact 节点是否仍有旧 Lore 配置。 */
    private static boolean hasLegacyLoreNode(YamlConfiguration yaml, String scope) {
        for (String key : LEGACY_LORE_KEYS) {
            if (yaml.contains(scope + "." + key)) {
                return true;
            }
        }
        return false;
    }

    /** 严格读取布尔配置，避免错误类型被静默转换。 */
    private static boolean readBoolean(YamlConfiguration yaml, String path, boolean fallback) throws IOException {
        if (!yaml.contains(path)) {
            return fallback;
        }
        Object value = yaml.get(path);
        if (!(value instanceof Boolean)) {
            throw new IOException(path + " 必须是 true 或 false，已取消自动更新");
        }
        return ((Boolean) value).booleanValue();
    }

    /** 严格读取字符串配置，避免错误类型被迁移为不可预期文本。 */
    private static String readString(YamlConfiguration yaml, String path, String fallback) throws IOException {
        if (!yaml.contains(path)) {
            return fallback;
        }
        Object value = yaml.get(path);
        if (!(value instanceof String)) {
            throw new IOException(path + " 必须是字符串，已取消自动更新");
        }
        return (String) value;
    }

    /** 严格读取字符串列表；显式空列表保持为空。 */
    private static List<String> readStringList(YamlConfiguration yaml, String path,
                                               List<String> fallback) throws IOException {
        if (!yaml.contains(path)) {
            return new ArrayList<>(fallback);
        }
        Object value = yaml.get(path);
        if (value instanceof String) {
            return Collections.singletonList((String) value);
        }
        if (!(value instanceof List<?>)) {
            throw new IOException(path + " 必须是字符串列表，已取消自动更新");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof String)) {
                throw new IOException(path + " 只能包含字符串，已取消自动更新");
            }
            result.add((String) entry);
        }
        return result;
    }

    /** 根据迁移计划生成只注释旧节点并添加新节点的文本。 */
    private static String buildUpdatedText(String original, int schemaVersion,
                                           List<ScopeMigration> migrations,
                                           boolean addUsageExamples) throws IOException {
        boolean bom = original.startsWith("\uFEFF");
        String body = bom ? original.substring(1) : original;
        String separator = body.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = body.split("\\r?\\n", -1);
        TextEdits edits = new TextEdits();
        prepareSchemaEdit(lines, schemaVersion, TRASH_SCHEMA_VERSION, edits);
        for (ScopeMigration migration : migrations) {
            prepareScopeTextEdits(lines, migration, edits);
        }
        prepareAdmissionExampleInsertion(lines, addUsageExamples, edits);
        preparePersonalButtonExampleInsertion(lines, addUsageExamples, edits);
        preparePersonalNotifyClickCommandInsertion(lines, addUsageExamples, edits);
        String updated = applyTextEdits(lines, separator, edits);
        return bom ? "\uFEFF" + updated : updated;
    }

    /** 在公共桶准入白名单中加入五类规则的具体示例。 */
    private static void prepareAdmissionExampleInsertion(String[] lines, boolean addUsageExample,
                                                         TextEdits edits) throws IOException {
        if (!addUsageExample || containsMarker(lines, ADMISSION_EXAMPLE_MARKER)) {
            return;
        }
        NodeRange whitelist = findNodeRange(lines, "global-trash.admission-whitelist");
        if (whitelist == null) {
            return;
        }
        NodeRange enabled = findNodeRange(lines, "global-trash.admission-whitelist.enabled");
        int insertAt = enabled == null ? whitelist.start + 1 : enabled.contentEnd;
        String indent = spaces(findChildIndent(lines, whitelist));
        edits.addInsertion(insertAt, Arrays.asList(
                indent + "# " + ADMISSION_EXAMPLE_MARKER + "；请把需要的值填入下面对应列表，不要新增重复节点。",
                indent + "# material-patterns: \"STONE\"、\"*_INGOT\"；name-key-patterns: \"*可回收*\"，匹配物品显示名文本。",
                indent + "# lore-key-patterns: \"*允许进入公共垃圾桶*\"；pdc-key-patterns: \"myplugin:recyclable\"、\"myplugin:*\"。",
                indent + "# nbt-key-patterns: \"tag.PublicBukkitValues.myplugin:recyclable\"；PDC/NBT 优先复制 /wtc look 输出。"
        ));
    }

    /** 在个人桶布局中加入 actions 和 close 的最小可复制示例。 */
    private static void preparePersonalButtonExampleInsertion(String[] lines, boolean addUsageExample,
                                                              TextEdits edits) throws IOException {
        if (!addUsageExample || containsMarker(lines, PERSONAL_BUTTON_EXAMPLE_MARKER)) {
            return;
        }
        NodeRange items = findNodeRange(lines, "personal-trash.gui.layout.items");
        if (items == null) {
            return;
        }
        NodeRange nextPage = findNodeRange(lines, "personal-trash.gui.layout.items.c");
        int insertAt = nextPage == null ? items.contentEnd : nextPage.contentEnd;
        String indent = spaces(findChildIndent(lines, items));
        edits.addInsertion(insertAt, Arrays.asList(
                indent + "# " + PERSONAL_BUTTON_EXAMPLE_MARKER + "；先在 position 中放入 d 或 e。",
                indent + "# d:",
                indent + "#   type: \"actions\"",
                indent + "#   material:",
                indent + "#     - \"BOOK\"",
                indent + "#   name: \"&#FFD166个人桶信息\"",
                indent + "#   actions:",
                indent + "#     - \"[message] &#5AC8FA当前是第 &#FFD166{page}/{max-page} &#5AC8FA页\"",
                indent + "#     - \"[close]\"",
                indent + "# e:",
                indent + "#   type: \"close\"",
                indent + "#   material:",
                indent + "#     - \"BARRIER\"",
                indent + "#   name: \"&#F87171关闭菜单\""
        ));
    }

    /** 为旧版 trash.yml 补充个人桶通知点击命令，保留服主已有注释和配置。 */
    private static void preparePersonalNotifyClickCommandInsertion(String[] lines, boolean addUsageExample,
                                                                   TextEdits edits) throws IOException {
        if (!addUsageExample || containsMarker(lines, PERSONAL_NOTIFY_EXAMPLE_MARKER)
                || findNodeRange(lines, PERSONAL_NOTIFY_CLICK_COMMAND_PATH) != null) {
            return;
        }
        NodeRange notify = findNodeRange(lines, "personal-trash.notify");
        if (notify == null) {
            return;
        }
        NodeRange maxDisplayItems = findNodeRange(lines, "personal-trash.notify.max-display-items");
        NodeRange enabled = findNodeRange(lines, "personal-trash.notify.enabled");
        int insertAt = maxDisplayItems != null ? maxDisplayItems.contentEnd
                : enabled != null ? enabled.contentEnd : notify.start + 1;
        String indent = spaces(findChildIndent(lines, notify));
        edits.addInsertion(insertAt, Arrays.asList(
                indent + "# " + PERSONAL_NOTIFY_EXAMPLE_MARKER
                        + "；点击个人垃圾桶通知后执行命令，留空则只发送普通文本。",
                indent + "click-command: \"/wtc personal\""
        ));
    }

    /** 添加或保守更新 trash.yml 的结构版本。 */
    private static void prepareSchemaEdit(String[] lines, int schemaVersion,
                                          int targetVersion, TextEdits edits) throws IOException {
        if (schemaVersion == targetVersion) {
            return;
        }
        if (schemaVersion == 0) {
            NodeRange existingZero = findNodeRange(lines, TRASH_SCHEMA_PATH);
            if (existingZero == null) {
                edits.addInsertion(0, Arrays.asList(
                        "# 配置结构版本，由插件自动维护；请勿手动删除或降低。",
                        TRASH_SCHEMA_PATH + ": " + targetVersion,
                        ""
                ));
                return;
            }
        }
        NodeRange range = findNodeRange(lines, TRASH_SCHEMA_PATH);
        if (range == null) {
            throw new IOException("无法定位 " + TRASH_SCHEMA_PATH + "，已取消自动更新");
        }
        edits.addInsertion(range.start, Collections.singletonList(
                "# [WorldListTrashCan] 旧配置结构版本已停用，原值保留如下。"));
        edits.addCommentRange(range);
        edits.addInsertion(range.contentEnd, Arrays.asList(
                "# [WorldListTrashCan] 当前插件使用的配置结构版本。",
                TRASH_SCHEMA_PATH + ": " + targetVersion
        ));
    }

    /** 为单个 compact 节点准备最小化文本修改。 */
    private static void prepareScopeTextEdits(String[] lines, ScopeMigration migration,
                                              TextEdits edits) throws IOException {
        NodeRange scopeRange = findNodeRange(lines, migration.scope);
        if (scopeRange == null) {
            throw new IOException("无法定位 " + migration.scope + "，已取消自动更新");
        }
        List<NodeRange> legacyRanges = new ArrayList<>();
        for (String path : migration.activeLegacyPaths) {
            NodeRange range = findNodeRange(lines, path);
            if (range == null) {
                throw new IOException("无法可靠定位旧节点 " + path + "，已取消自动更新");
            }
            legacyRanges.add(range);
            String indent = spaces(range.indent);
            edits.addInsertion(range.start, Collections.singletonList(indent
                    + "# [WorldListTrashCan] 该配置于 " + LORE_CHANGE_VERSION
                    + " 弃用，已迁移到 item-lore；原内容保留如下。"));
            edits.addCommentRange(range);
        }
        int childIndent = findChildIndent(lines, scopeRange);
        int insertAt = findItemLoreInsertion(lines, migration.scope, scopeRange, legacyRanges);
        List<String> block = new ArrayList<>();
        String indent = spaces(childIndent);
        block.add(indent + "# [WorldListTrashCan] item-lore 于 " + LORE_CHANGE_VERSION
                + " 加入，用列表顺序统一控制数量、原 Lore 和操作提示。");
        block.add(indent + "# {content} 必须独占一行，用于在当前位置展开原物品 Lore。");
        block.add(indent + "item-lore:");
        for (String line : migration.itemLore) {
            block.add(indent + "  - \"" + escapeYamlDoubleQuoted(line) + "\"");
        }
        edits.addInsertion(insertAt, block);
    }

    /** 确定 item-lore 应插入的位置，优先紧跟最后一个旧节点。 */
    private static int findItemLoreInsertion(String[] lines, String scope, NodeRange scopeRange,
                                             List<NodeRange> legacyRanges) throws IOException {
        if (!legacyRanges.isEmpty()) {
            int insertAt = 0;
            for (NodeRange range : legacyRanges) {
                insertAt = Math.max(insertAt, range.contentEnd);
            }
            return insertAt;
        }
        NodeRange omitted = findNodeRange(lines, scope + ".omitted-lore");
        return omitted == null ? scopeRange.contentEnd : omitted.start;
    }

    /** 返回配置段直接子节点的缩进；空段使用两个空格。 */
    private static int findChildIndent(String[] lines, NodeRange parent) {
        int minimum = Integer.MAX_VALUE;
        for (int index = parent.start + 1; index < parent.boundaryEnd; index++) {
            if (!isActiveLine(lines[index])) {
                continue;
            }
            int indent = countLeadingSpaces(lines[index]);
            if (indent > parent.indent) {
                minimum = Math.min(minimum, indent);
            }
        }
        return minimum == Integer.MAX_VALUE ? parent.indent + 2 : minimum;
    }

    /** 应用插入和注释操作，不删除、不移动任何原始行。 */
    private static String applyTextEdits(String[] lines, String separator, TextEdits edits) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index <= lines.length; index++) {
            List<String> insertions = edits.insertions.get(Integer.valueOf(index));
            if (insertions != null) {
                for (String insertion : insertions) {
                    appendLine(builder, insertion, separator);
                }
            }
            if (index == lines.length) {
                break;
            }
            String line = edits.shouldComment(index) && isActiveLine(lines[index])
                    ? commentYamlLine(lines[index]) : lines[index];
            appendLine(builder, line, separator);
        }
        if (builder.length() >= separator.length()) {
            builder.setLength(builder.length() - separator.length());
        }
        return builder.toString();
    }

    /** 向结果追加一行并保留原文件换行风格。 */
    private static void appendLine(StringBuilder builder, String line, String separator) {
        builder.append(line).append(separator);
    }

    /** 在原缩进后注释一行 YAML 语法。 */
    private static String commentYamlLine(String line) {
        int indent = countLeadingSpaces(line);
        return line.substring(0, indent) + "# " + line.substring(indent);
    }

    /** 按完整点分路径定位唯一 YAML 节点。 */
    private static NodeRange findNodeRange(String[] lines, String path) throws IOException {
        String[] keys = path.split("\\.");
        int searchStart = 0;
        int searchEnd = lines.length;
        int parentIndent = -1;
        NodeRange current = null;
        for (String key : keys) {
            current = findDirectChild(lines, searchStart, searchEnd, parentIndent, key);
            if (current == null) {
                return null;
            }
            searchStart = current.start + 1;
            searchEnd = current.boundaryEnd;
            parentIndent = current.indent;
        }
        return current;
    }

    /** 在父节点范围内查找唯一直接子节点。 */
    private static NodeRange findDirectChild(String[] lines, int start, int end,
                                             int parentIndent, String key) throws IOException {
        int childIndent = Integer.MAX_VALUE;
        for (int index = start; index < end; index++) {
            if (!isActiveLine(lines[index])) {
                continue;
            }
            int indent = countLeadingSpaces(lines[index]);
            if (indent > parentIndent) {
                childIndent = Math.min(childIndent, indent);
            }
        }
        if (childIndent == Integer.MAX_VALUE) {
            return null;
        }
        int found = -1;
        for (int index = start; index < end; index++) {
            if (!isActiveLine(lines[index]) || countLeadingSpaces(lines[index]) != childIndent) {
                continue;
            }
            if (!isYamlKey(lines[index].trim(), key)) {
                continue;
            }
            if (found >= 0) {
                throw new IOException("配置路径存在重复键: " + key + "，已取消自动更新");
            }
            found = index;
        }
        return found < 0 ? null : measureNode(lines, found, end, childIndent);
    }

    /** 计算节点有效内容和父级边界，避免吞掉下一个节点前的用户注释。 */
    private static NodeRange measureNode(String[] lines, int start, int limit, int indent) {
        int boundary = limit;
        int lastActive = start;
        for (int index = start + 1; index < limit; index++) {
            if (!isActiveLine(lines[index])) {
                continue;
            }
            int currentIndent = countLeadingSpaces(lines[index]);
            if (currentIndent <= indent) {
                boundary = index;
                break;
            }
            lastActive = index;
        }
        return new NodeRange(start, lastActive + 1, boundary, indent);
    }

    /** 判断去除缩进后的行是否为指定 YAML 键。 */
    private static boolean isYamlKey(String trimmed, String key) {
        return trimmed.equals(key + ":") || trimmed.startsWith(key + ": ");
    }

    /** 判断一行是否包含有效 YAML 语法。 */
    private static boolean isActiveLine(String line) {
        String trimmed = line.trim();
        return !trimmed.isEmpty() && !trimmed.startsWith("#");
    }

    /** 统计一行开头的空格数量。 */
    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /** 生成指定数量的空格。 */
    private static String spaces(int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /** 转义 YAML 双引号字符串，保留用户文本语义。 */
    private static String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /** 验证迁移后 YAML 可解析、无关配置不变且新 Lore 与旧语义一致。 */
    private static void validateTrashUpdate(YamlConfiguration before, YamlConfiguration after,
                                            List<ScopeMigration> migrations) throws IOException {
        if (after.getInt(TRASH_SCHEMA_PATH, -1) != TRASH_SCHEMA_VERSION) {
            throw new IOException("更新后的 " + TRASH_SCHEMA_PATH + " 校验失败");
        }
        for (ScopeMigration migration : migrations) {
            if (!after.isList(migration.scope + ".item-lore")
                    || !after.getStringList(migration.scope + ".item-lore").equals(migration.itemLore)) {
                throw new IOException(migration.scope + ".item-lore 更新结果与旧配置语义不一致");
            }
            for (String path : migration.activeLegacyPaths) {
                if (after.contains(path)) {
                    throw new IOException("旧节点未被完整注释: " + path);
                }
            }
        }
        Map<String, Object> beforeLeaves = leafValues(before);
        Map<String, Object> afterLeaves = leafValues(after);
        removeMigrationPaths(beforeLeaves);
        removeMigrationPaths(afterLeaves);
        if (!beforeLeaves.equals(afterLeaves)) {
            throw new IOException("迁移触及了 Lore 和结构版本以外的配置，已取消替换");
        }
    }

    /** 收集 YAML 的叶子值，用于验证无关配置未被改变。 */
    private static Map<String, Object> leafValues(YamlConfiguration yaml) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 从语义比较集合中移除本次允许变化的路径。 */
    private static void removeMigrationPaths(Map<String, Object> values) {
        Set<String> changed = new HashSet<>();
        changed.add(TRASH_SCHEMA_PATH);
        for (String scope : COMPACT_SCOPES) {
            changed.add(scope + ".item-lore");
            for (String key : LEGACY_LORE_KEYS) {
                changed.add(scope + "." + key);
            }
        }
        changed.add(PERSONAL_NOTIFY_CLICK_COMMAND_PATH);
        for (String path : changed) {
            values.remove(path);
        }
    }

    /** 使用 Bukkit YAML 严格加载文件，解析失败时拒绝写入。 */
    private static YamlConfiguration loadStrict(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (Exception exception) {
            throw new IOException("YAML 无法解析: " + file.getAbsolutePath() + ": "
                    + exception.getMessage(), exception);
        }
    }

    /** 创建唯一 .bak 文件并流式核验内容。 */
    static File createVerifiedBackup(File source) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        File parent = source.getParentFile();
        File backup = new File(parent, source.getName() + "." + stamp + ".bak");
        int suffix = 1;
        while (backup.exists()) {
            backup = new File(parent, source.getName() + "." + stamp + "-" + suffix + ".bak");
            suffix++;
        }
        Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        if (!sameFileContent(source, backup)) {
            throw new IOException("备份内容校验失败: " + backup.getAbsolutePath());
        }
        return backup;
    }

    /** 流式比较两个文件，避免为配置备份重复分配完整字节数组。 */
    private static boolean sameFileContent(File first, File second) throws IOException {
        if (first.length() != second.length()) {
            return false;
        }
        try (InputStream firstInput = Files.newInputStream(first.toPath());
             InputStream secondInput = Files.newInputStream(second.toPath())) {
            byte[] firstBuffer = new byte[8192];
            byte[] secondBuffer = new byte[8192];
            while (true) {
                int firstRead = firstInput.read(firstBuffer);
                int secondRead = secondInput.read(secondBuffer);
                if (firstRead != secondRead) {
                    return false;
                }
                if (firstRead < 0) {
                    return true;
                }
                for (int index = 0; index < firstRead; index++) {
                    if (firstBuffer[index] != secondBuffer[index]) {
                        return false;
                    }
                }
            }
        }
    }

    /** 在同目录原子替换配置；文件系统不支持时降级为普通替换。 */
    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** cleanup.yml 中一条待追加的门禁通知。 */
    private static final class NotifyInsertion {
        private final String path;
        private final String value;

        /** 保存通知列表路径和默认消息。 */
        private NotifyInsertion(String path, String value) {
            this.path = path;
            this.value = value;
        }
    }

    /** 单个 compact 节点的迁移数据。 */
    private static final class ScopeMigration {
        private final String scope;
        private final List<String> itemLore;
        private final List<String> activeLegacyPaths;

        /** 保存迁移目标、结果和需要注释的旧路径。 */
        private ScopeMigration(String scope, List<String> itemLore, List<String> activeLegacyPaths) {
            this.scope = scope;
            this.itemLore = Collections.unmodifiableList(new ArrayList<>(itemLore));
            this.activeLegacyPaths = Collections.unmodifiableList(new ArrayList<>(activeLegacyPaths));
        }
    }

    /** YAML 节点在原文件中的范围。 */
    private static final class NodeRange {
        private final int start;
        private final int contentEnd;
        private final int boundaryEnd;
        private final int indent;

        /** 保存节点起点、有效内容终点、父级边界和缩进。 */
        private NodeRange(int start, int contentEnd, int boundaryEnd, int indent) {
            this.start = start;
            this.contentEnd = contentEnd;
            this.boundaryEnd = boundaryEnd;
            this.indent = indent;
        }
    }

    /** 不删除原始行的文本修改集合。 */
    private static final class TextEdits {
        private final Map<Integer, List<String>> insertions = new HashMap<>();
        private final List<NodeRange> commentRanges = new ArrayList<>();

        /** 在指定原始行之前添加文本。 */
        private void addInsertion(int line, List<String> values) {
            List<String> existing = insertions.get(Integer.valueOf(line));
            if (existing == null) {
                existing = new ArrayList<>();
                insertions.put(Integer.valueOf(line), existing);
            }
            existing.addAll(values);
        }

        /** 标记需要注释的完整节点语法范围。 */
        private void addCommentRange(NodeRange range) {
            commentRanges.add(range);
        }

        /** 判断原始行是否属于待注释节点。 */
        private boolean shouldComment(int line) {
            for (NodeRange range : commentRanges) {
                if (line >= range.start && line < range.contentEnd) {
                    return true;
                }
            }
            return false;
        }
    }
}
