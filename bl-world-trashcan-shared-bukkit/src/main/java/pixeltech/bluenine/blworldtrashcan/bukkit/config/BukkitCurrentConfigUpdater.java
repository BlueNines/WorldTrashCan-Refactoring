package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    private static final int TRASH_SCHEMA_VERSION = 2;
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
            return false;
        }
        boolean changed = false;
        try {
            changed = updateTrashFile(new File(plugin.getDataFolder(), TRASH_FILE), plugin.getLogger());
        } catch (IOException exception) {
            plugin.getLogger().warning("[ConfigUpdate] trash.yml 更新失败，原文件保持不变: "
                    + exception.getMessage());
        }
        if (new BukkitLegacyConfigMigrator(plugin).repairCurrentRuntimeDefaults()) {
            changed = true;
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
        String updated = buildUpdatedText(original, schemaVersion, migrations);
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

    /** 读取 trash.yml 的结构版本；缺失时视为最早版本。 */
    private static int readSchemaVersion(YamlConfiguration yaml) throws IOException {
        if (!yaml.contains(TRASH_SCHEMA_PATH)) {
            return 0;
        }
        Object value = yaml.get(TRASH_SCHEMA_PATH);
        if (!(value instanceof Number)) {
            throw new IOException(TRASH_SCHEMA_PATH + " 必须是整数，已取消自动更新");
        }
        int version = ((Number) value).intValue();
        if (version < 0) {
            throw new IOException(TRASH_SCHEMA_PATH + " 不能小于 0，已取消自动更新");
        }
        return version;
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
                                           List<ScopeMigration> migrations) throws IOException {
        boolean bom = original.startsWith("\uFEFF");
        String body = bom ? original.substring(1) : original;
        String separator = body.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = body.split("\\r?\\n", -1);
        TextEdits edits = new TextEdits();
        prepareSchemaEdit(lines, schemaVersion, edits);
        for (ScopeMigration migration : migrations) {
            prepareScopeTextEdits(lines, migration, edits);
        }
        String updated = applyTextEdits(lines, separator, edits);
        return bom ? "\uFEFF" + updated : updated;
    }

    /** 添加或保守更新 trash.yml 的结构版本。 */
    private static void prepareSchemaEdit(String[] lines, int schemaVersion, TextEdits edits) throws IOException {
        if (schemaVersion == TRASH_SCHEMA_VERSION) {
            return;
        }
        if (schemaVersion == 0) {
            edits.addInsertion(0, Arrays.asList(
                    "# 配置结构版本，由插件自动维护；请勿手动删除或降低。",
                    TRASH_SCHEMA_PATH + ": " + TRASH_SCHEMA_VERSION,
                    ""
            ));
            return;
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
                TRASH_SCHEMA_PATH + ": " + TRASH_SCHEMA_VERSION
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

    /** 校验新 YAML，生成备份后再原子替换已有配置。 */
    static File replaceYamlWithBackup(File file, String updated) throws IOException {
        Path temporary = Files.createTempFile(file.toPath().getParent(), file.getName() + ".", ".update.tmp");
        try {
            Files.write(temporary, updated.getBytes(UTF8));
            loadStrict(temporary.toFile());
            File backup = createVerifiedBackup(file);
            replaceAtomically(temporary, file.toPath());
            return backup;
        } finally {
            Files.deleteIfExists(temporary);
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
