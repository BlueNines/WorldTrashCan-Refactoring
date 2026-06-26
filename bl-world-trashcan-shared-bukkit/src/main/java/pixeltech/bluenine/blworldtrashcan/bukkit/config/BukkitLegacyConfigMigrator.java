package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.config.LegacyMigrationPlan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 旧 WorldListTrashCan 配置到新拆分配置的 Bukkit 迁移器。 */
public final class BukkitLegacyConfigMigrator {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String REPORT_FILE = "legacy-migration-report.md";
    private static final String CLEANUP_FILE = "cleanup.yml";
    private static final int CLEANUP_GUARD_NOTIFY_KEY = -5;
    private static final String CLEANUP_GUARD_NOTIFY_COMMENT = "# -5 表示本轮被扫地启动门禁跳过。";
    private static final String[] CLEANUP_GUARD_NOTIFY_PATHS = {
            "notify.chat.messages",
            "notify.actionbar.messages",
            "notify.bossbar.messages",
            "notify.title.messages"
    };
    private final JavaPlugin plugin;

    /** 创建旧配置迁移器。 */
    public BukkitLegacyConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 如果检测到旧配置且尚未迁移，则执行一次迁移。 */
    public boolean migrateIfNeeded() {
        ensureCurrentRuntimeDefaults();
        if (!plugin.getConfig().getBoolean("migration-enabled", true)) {
            return false;
        }
        File reportFile = new File(plugin.getDataFolder(), REPORT_FILE);
        if (reportFile.exists()) {
            return false;
        }
        LegacySource source = findLegacySource();
        if (source == null) {
            return false;
        }
        try {
            migrate(source, reportFile);
            plugin.reloadConfig();
            plugin.getLogger().info("[Migration] 已完成旧 WorldListTrashCan 配置迁移，报告: " + reportFile.getName());
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("[Migration] 旧配置迁移失败: " + exception.getMessage());
            return false;
        }
    }

    /** 补齐当前版本运行时需要的新增默认配置。 */
    private void ensureCurrentRuntimeDefaults() {
        try {
            if (mergeCleanupGuardNotifyDefaults()) {
                plugin.getLogger().info("[Config] 已补齐 cleanup.yml 缺失的 -5 扫地门禁通知默认文案。");
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("[Config] 补齐 cleanup.yml 默认通知失败: " + exception.getMessage());
        }
    }

    /** 把 cleanup.yml 中缺失的扫地门禁通知默认项追加回原配置。 */
    private boolean mergeCleanupGuardNotifyDefaults() throws IOException {
        File file = new File(plugin.getDataFolder(), CLEANUP_FILE);
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadResourceYaml(CLEANUP_FILE);
        String original = new String(Files.readAllBytes(file.toPath()), UTF8);
        String updated = original;
        for (String path : CLEANUP_GUARD_NOTIFY_PATHS) {
            if (hasEventMessage(current.getStringList(path), CLEANUP_GUARD_NOTIFY_KEY)) {
                continue;
            }
            String defaultMessage = firstEventMessage(defaults.getStringList(path), CLEANUP_GUARD_NOTIFY_KEY);
            if (defaultMessage.isEmpty()) {
                continue;
            }
            updated = insertYamlListEntry(updated, path, defaultMessage, CLEANUP_GUARD_NOTIFY_COMMENT);
        }
        if (updated.equals(original)) {
            return false;
        }
        Files.write(file.toPath(), updated.getBytes(UTF8));
        return true;
    }

    /** 从插件 jar 内按 UTF-8 加载默认 YAML。 */
    private YamlConfiguration loadResourceYaml(String path) throws IOException {
        InputStream inputStream = plugin.getResource(path);
        if (inputStream == null) {
            return new YamlConfiguration();
        }
        try (Reader reader = new InputStreamReader(inputStream, UTF8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    /** 判断列表中是否已有指定事件键。 */
    private boolean hasEventMessage(List<String> values, int key) {
        return !firstEventMessage(values, key).isEmpty();
    }

    /** 返回列表中第一个指定事件键的消息。 */
    private String firstEventMessage(List<String> values, int key) {
        String keyText = String.valueOf(key);
        for (String value : values) {
            if (eventKey(value).equals(keyText)) {
                return value;
            }
        }
        return "";
    }

    /** 解析分号消息的事件键。 */
    private String eventKey(String value) {
        if (value == null) {
            return "";
        }
        int split = value.indexOf(';');
        String key = split >= 0 ? value.substring(0, split) : value;
        return key.trim();
    }

    /** 在指定 YAML 列表末尾插入一个新值。 */
    private String insertYamlListEntry(String text, String path, String value, String comment) {
        String separator = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\\r?\\n", -1);
        int keyLine = findYamlPathLine(lines, path);
        if (keyLine < 0) {
            return text;
        }
        int listIndent = countLeadingSpaces(lines[keyLine]) + 2;
        int lastListLine = findLastListLine(lines, keyLine + 1, countLeadingSpaces(lines[keyLine]), listIndent);
        if (lastListLine < 0) {
            return text;
        }
        List<String> output = new ArrayList<>();
        for (String line : lines) {
            output.add(line);
        }
        int insertAt = lastListLine + 1;
        String spaces = repeatSpace(listIndent);
        output.add(insertAt, spaces + "- \"" + escapeYamlDoubleQuoted(value) + "\"");
        if (!hasCommentNear(lines, keyLine + 1, lastListLine, comment)) {
            output.add(insertAt, spaces + comment);
        }
        return joinLines(output, separator);
    }

    /** 查找点分 YAML 路径最后一个键所在行。 */
    private int findYamlPathLine(String[] lines, String path) {
        String[] keys = path.split("\\.");
        int start = 0;
        int indent = 0;
        for (int index = 0; index < keys.length; index++) {
            int found = findYamlKeyLine(lines, start, indent, keys[index]);
            if (found < 0) {
                return -1;
            }
            start = found + 1;
            indent = countLeadingSpaces(lines[found]) + 2;
        }
        return start - 1;
    }

    /** 在指定缩进层级查找 YAML 键。 */
    private int findYamlKeyLine(String[] lines, int start, int indent, String key) {
        for (int index = start; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int currentIndent = countLeadingSpaces(lines[index]);
            if (currentIndent < indent) {
                return -1;
            }
            if (currentIndent == indent && isYamlKey(trimmed, key)) {
                return index;
            }
        }
        return -1;
    }

    /** 判断一行是否是目标 YAML 键。 */
    private boolean isYamlKey(String trimmed, String key) {
        return trimmed.equals(key + ":") || trimmed.startsWith(key + ": ");
    }

    /** 查找 YAML 列表最后一个条目行。 */
    private int findLastListLine(String[] lines, int start, int parentIndent, int fallbackListIndent) {
        int last = -1;
        for (int index = start; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = countLeadingSpaces(lines[index]);
            if (indent <= parentIndent) {
                break;
            }
            if (indent >= fallbackListIndent && trimmed.startsWith("- ")) {
                last = index;
            }
        }
        return last;
    }

    /** 判断列表附近是否已经有相同注释。 */
    private boolean hasCommentNear(String[] lines, int start, int end, String comment) {
        for (int index = start; index <= end && index < lines.length; index++) {
            if (lines[index].trim().equals(comment)) {
                return true;
            }
        }
        return false;
    }

    /** 统计行首空格数。 */
    private int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /** 生成指定数量空格。 */
    private String repeatSpace(int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /** 转义 YAML 双引号字符串内容。 */
    private String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 使用指定换行符拼接多行文本。 */
    private String joinLines(List<String> lines, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                builder.append(separator);
            }
            builder.append(lines.get(index));
        }
        return builder.toString();
    }

    /** 查找可迁移的旧配置来源。 */
    private LegacySource findLegacySource() {
        File dataFolder = plugin.getDataFolder();
        File currentConfig = new File(dataFolder, "config.yml");
        File currentData = new File(dataFolder, "data/data.yml");
        if (isLegacySource(currentConfig, currentData)) {
            return new LegacySource(dataFolder, currentConfig, currentData, true);
        }
        File parent = dataFolder.getParentFile();
        if (parent == null) {
            return null;
        }
        String configuredFolder = plugin.getConfig().getString("migration-legacy-folder", "WorldListTrashCan");
        File legacyFolder = resolveLegacyFolder(parent, configuredFolder);
        File legacyConfig = new File(legacyFolder, "config.yml");
        File legacyData = new File(legacyFolder, "data/data.yml");
        if (isLegacySource(legacyConfig, legacyData)) {
            return new LegacySource(legacyFolder, legacyConfig, legacyData, false);
        }
        return null;
    }

    /** 解析旧插件数据目录配置。 */
    private File resolveLegacyFolder(File parent, String configuredFolder) {
        String value = configuredFolder == null || configuredFolder.trim().isEmpty() ? "WorldListTrashCan" : configuredFolder.trim();
        File file = new File(value);
        return file.isAbsolute() ? file : new File(parent, value);
    }

    /** 判断给定文件是否包含旧配置结构。 */
    private boolean isLegacySource(File configFile, File dataFile) {
        if (configFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            if (config.isConfigurationSection("Set") || config.contains("GlobalBanItem")) {
                return true;
            }
        }
        if (dataFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            return data.isConfigurationSection("WorldData");
        }
        return false;
    }

    /** 执行旧配置到新配置的迁移。 */
    private void migrate(LegacySource source, File reportFile) throws IOException {
        LegacyMigrationPlan plan = new LegacyMigrationPlan();
        YamlConfiguration oldConfig = source.configFile.exists()
                ? YamlConfiguration.loadConfiguration(source.configFile)
                : new YamlConfiguration();
        YamlConfiguration oldData = source.dataFile.exists()
                ? YamlConfiguration.loadConfiguration(source.dataFile)
                : new YamlConfiguration();
        if (source.currentDataFolder) {
            backupCurrentLegacyFiles(source);
            plugin.saveResource("config.yml", true);
        }
        migrateMainConfig(oldConfig, plan);
        migrateCleanupConfig(oldConfig, plan);
        migrateTrashConfig(oldConfig, plan);
        migrateNotifyConfig(oldConfig, plan);
        migrateProtectionConfig(oldConfig, plan);
        migrateEntityLimitConfig(oldConfig, plan);
        migrateWorldTrashData(oldData, plan);
        recordUnsupportedKeys(oldConfig, plan);
        writeReport(reportFile, source, plan);
    }

    /** 备份当前目录中的旧配置文件。 */
    private void backupCurrentLegacyFiles(LegacySource source) throws IOException {
        File backupDir = new File(plugin.getDataFolder(), "legacy-migration-backup");
        ensureDirectory(backupDir);
        if (source.configFile.exists()) {
            Files.copy(source.configFile.toPath(), new File(backupDir, "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (source.dataFile.exists()) {
            File dataBackupDir = new File(backupDir, "data");
            ensureDirectory(dataBackupDir);
            Files.copy(source.dataFile.toPath(), new File(dataBackupDir, "data.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 迁移主配置。 */
    private void migrateMainConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("config.yml");
        copyString(oldConfig, target, "Set.Lang", "language", plan);
        copyBoolean(oldConfig, target, "Set.Debug", "debug", plan);
        saveTarget(target, "config.yml");
    }

    /** 迁移清理配置。 */
    private void migrateCleanupConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("cleanup.yml");
        copyInt(oldConfig, target, "Set.SecondCount", "interval-seconds", plan);
        copyStringList(oldConfig, target, "Set.WorldClearWhiteList", "ignored-worlds", plan);
        copyStringList(oldConfig, target, "Set.NoClearContainerType", "ignored-materials", plan);
        copyStringList(oldConfig, target, "Set.NoClearContainerName", "ignored-name-fragments", plan);
        copyStringList(oldConfig, target, "Set.NoClearContainerLore", "ignored-lore-fragments", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.Flag", "entities.enabled", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.ClearExpBottle", "entities.clear-experience-orbs", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.ClearMonster", "entities.clear-monsters", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.ClearAnimals", "entities.clear-animals", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.ClearProjectile", "entities.clear-projectiles", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.ClearReNameEntity", "entities.clear-named-entities", plan);
        copyBoolean(oldConfig, target, "Set.ClearEntity.IgnoreEntitiesInBoat", "entities.ignore-entities-in-boat", plan);
        copyStringList(oldConfig, target, "Set.ClearEntity.WhiteNameList", "entities.whitelist", plan);
        copyStringList(oldConfig, target, "Set.ClearEntity.BlackNameList", "entities.blacklist", plan);
        saveTarget(target, "cleanup.yml");
    }

    /** 迁移垃圾桶配置。 */
    private void migrateTrashConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("trash.yml");
        copyBoolean(oldConfig, target, "Set.GlobalTrash.Flag", "global-trash.enabled", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.MaxPage", "global-trash.max-pages", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.Delay", "global-trash.take-delay-millis", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.EveryClearGlobalTrash", "global-trash.clear-every-cleanups", plan);
        copyBoolean(oldConfig, target, "Set.GlobalTrash.Log.Enable", "global-trash.log-enabled", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.GlobalItems.BackItem.ModelId",
                "global-trash.gui.back-model-id", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.GlobalItems.NextItem.ModelId",
                "global-trash.gui.next-model-id", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.GlobalItems.BackgroundItem.ModelId",
                "global-trash.gui.background-model-id", plan);
        copyStringList(oldConfig, target, "GlobalBanItem", "global-trash.banned-materials", plan);
        copyString(oldConfig, target, "Set.SighCheckName", "world-trash.sign-create-text", plan);
        copyString(oldConfig, target, "Set.SighCheckedName", "world-trash.sign-created-text", plan);
        copyInt(oldConfig, target, "Set.DefaultRashCanMax", "world-trash.default-max-count", plan);
        copyStringList(oldConfig, target, "Set.BanWorldNameList", "world-trash.banned-worlds", plan);
        copyBoolean(oldConfig, target, "Set.PersonalTrashCan.Flag", "personal-trash.enabled", plan);
        copyBoolean(oldConfig, target, "Set.PersonalTrashCan.NoWorldTrashCanEnterPersonalTrashCan",
                "personal-trash.track-player-dropped-items", plan);
        copyBoolean(oldConfig, target, "Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Model2.AutoClear",
                "personal-trash.auto-clear-when-full", plan);
        copyDouble(oldConfig, target, "Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Model2.Coins",
                "personal-trash.take-cost", plan);
        copyDamageRecoveryMode(oldConfig, target, plan);
        copyInt(oldConfig, target, "Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Delay",
                "personal-trash.damage-recovery.delay-seconds", plan);
        saveTarget(target, "trash.yml");
    }

    /** 迁移通知配置。 */
    private void migrateNotifyConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("cleanup.yml");
        copyBoolean(oldConfig, target, "Set.ChatFlag", "notify.chat.enabled", plan);
        copyBoolean(oldConfig, target, "Set.ChatConsoleLogFlag", "notify.chat.console-log", plan);
        copyString(oldConfig, target, "Set.ChatClickCommand", "notify.chat.click-command", plan);
        copyStringList(oldConfig, target, "Set.ChatMessageForCount", "notify.chat.messages", plan);
        copyBoolean(oldConfig, target, "Set.ActionBarFlag", "notify.actionbar.enabled", plan);
        copyStringList(oldConfig, target, "Set.ActionBarMessageForCount", "notify.actionbar.messages", plan);
        copyBoolean(oldConfig, target, "Set.CommandFlag", "notify.command.enabled", plan);
        copyStringList(oldConfig, target, "Set.CommandForCount", "notify.command.commands", plan);
        copyBoolean(oldConfig, target, "Set.TitleFlag", "notify.title.enabled", plan);
        copyStringList(oldConfig, target, "Set.TitleMessageForCount", "notify.title.messages", plan);
        copyBoolean(oldConfig, target, "Set.SoundFlag", "notify.sound.enabled", plan);
        copyStringList(oldConfig, target, "Set.SoundForCount", "notify.sound.messages", plan);
        copyBoolean(oldConfig, target, "Set.BossBarFlag", "notify.bossbar.enabled", plan);
        copyStringList(oldConfig, target, "Set.BossBarMessageForCount", "notify.bossbar.messages", plan);
        saveTarget(target, "cleanup.yml");
    }

    /** 迁移保护类配置。 */
    private void migrateProtectionConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("protections.yml");
        copyBoolean(oldConfig, target, "ChatSet.QuickSendMessage.Flag", "chat-rate-limit.enabled", plan);
        copyDouble(oldConfig, target, "ChatSet.QuickSendMessage.Time", "chat-rate-limit.interval-seconds", plan);
        copyString(oldConfig, target, "ChatSet.QuickSendMessage.Message", "chat-rate-limit.message", plan);
        copyString(oldConfig, target, "ChatSet.QuickSendMessage.Command", "chat-rate-limit.command", plan);
        copyBoolean(oldConfig, target, "ChatSet.QuickUseCommand.Flag", "command-rate-limit.enabled", plan);
        copyDouble(oldConfig, target, "ChatSet.QuickUseCommand.Time", "command-rate-limit.interval-seconds", plan);
        copyString(oldConfig, target, "ChatSet.QuickUseCommand.Message", "command-rate-limit.message", plan);
        copyString(oldConfig, target, "ChatSet.QuickUseCommand.Command", "command-rate-limit.command", plan);
        copyStringList(oldConfig, target, "ChatSet.QuickUseCommand.WhiteList", "command-rate-limit.whitelist", plan);
        copyBoolean(oldConfig, target, "DropItemCheck.Flag", "drop-protection.enabled", plan);
        copyBoolean(oldConfig, target, "SimpleOptimize.NotPickArrow", "simple-optimize.remove-unpickable-arrow", plan);
        copyBoolean(oldConfig, target, "SimpleOptimize.NotTreadingFarmLand", "simple-optimize.prevent-farmland-trampling", plan);
        saveTarget(target, "protections.yml");
    }

    /** 迁移实体限制配置。 */
    private void migrateEntityLimitConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("entity-limits.yml");
        copyBoolean(oldConfig, target, "WorldEntityLimitCount.Flag", "world-limits.enabled", plan);
        copyStringList(oldConfig, target, "WorldEntityLimitCount.BanWorldNameList", "world-limits.ignored-worlds", plan);
        copyWorldLimitRules(oldConfig, target, "WorldEntityLimitCount.DefaultCount", "world-limits.defaults", plan);
        copyBoolean(oldConfig, target, "GatherEntityLimitCount.Flag", "gather-limits.enabled", plan);
        copyBoolean(oldConfig, target, "GatherEntityLimitCount.ItemDropFlag", "gather-limits.drop-items", plan);
        copyStringList(oldConfig, target, "GatherEntityLimitCount.BanWorldNameList", "gather-limits.ignored-worlds", plan);
        copyGatherLimitRules(oldConfig, target, "GatherEntityLimitCount.DefaultCount", "gather-limits.defaults", plan);
        saveTarget(target, "entity-limits.yml");
    }

    /** 迁移世界垃圾桶运行数据。 */
    private void migrateWorldTrashData(YamlConfiguration oldData, LegacyMigrationPlan plan) throws IOException {
        ConfigurationSection oldWorlds = oldData.getConfigurationSection("WorldData");
        if (oldWorlds == null) {
            return;
        }
        YamlConfiguration target = loadTarget("data/worlds.yml");
        for (String worldName : oldWorlds.getKeys(false)) {
            String oldPath = "WorldData." + worldName + ".";
            String newPath = "worlds." + worldName + ".";
            List<String> locations = parseLocationList(oldData.getList(oldPath + "SignLocation"));
            target.set(newPath + "locations", locations);
            target.set(newPath + "max-count", oldData.getInt(oldPath + "RashMaxCount", 0));
            target.set(newPath + "banned-materials", oldData.getStringList(oldPath + "BanItem"));
            plan.addMigratedKey(oldPath + "SignLocation -> " + newPath + "locations");
            plan.addMigratedKey(oldPath + "RashMaxCount -> " + newPath + "max-count");
            plan.addMigratedKey(oldPath + "BanItem -> " + newPath + "banned-materials");
        }
        saveTarget(target, "data/worlds.yml");
    }

    /** 记录当前新实现尚不能自动承接的旧字段。 */
    private void recordUnsupportedKeys(YamlConfiguration oldConfig, LegacyMigrationPlan plan) {
        // 当前已确认的旧配置残留会在这里集中记录，避免报告漏掉人工处理项。
    }

    /** 迁移旧仙人掌、岩浆损坏回收模式。 */
    private void copyDamageRecoveryMode(YamlConfiguration oldConfig, YamlConfiguration target, LegacyMigrationPlan plan) {
        String oldPath = "Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.UseModel";
        if (!oldConfig.contains(oldPath)) {
            return;
        }
        int mode = oldConfig.getInt(oldPath, 3);
        String value = "disabled";
        if (mode == 1) {
            value = "global-trash";
        } else if (mode == 2) {
            value = "personal-trash";
        }
        target.set("personal-trash.damage-recovery.mode", value);
        plan.addMigratedKey(oldPath + " -> personal-trash.damage-recovery.mode");
    }

    /** 如果旧字段存在则写入人工确认列表。 */
    private void addManualIfPresent(YamlConfiguration oldConfig, LegacyMigrationPlan plan, String oldPath, String reason) {
        if (oldConfig.contains(oldPath)) {
            plan.addManualKey(oldPath + " | " + reason);
        }
    }

    /** 拷贝字符串字段。 */
    private void copyString(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                            String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getString(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
        }
    }

    /** 拷贝布尔字段。 */
    private void copyBoolean(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                             String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getBoolean(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
        }
    }

    /** 拷贝整数字段。 */
    private void copyInt(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                         String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getInt(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
        }
    }

    /** 拷贝小数字段。 */
    private void copyDouble(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                            String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getDouble(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
        }
    }

    /** 拷贝字符串列表字段。 */
    private void copyStringList(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                                String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getStringList(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
        }
    }

    /** 迁移世界实体数量限制列表。 */
    private void copyWorldLimitRules(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                                     String newPath, LegacyMigrationPlan plan) {
        if (!oldConfig.contains(oldPath)) {
            return;
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String line : oldConfig.getStringList(oldPath)) {
            String[] parts = splitRule(line);
            if (parts.length >= 2) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("entity", normalizeEntity(parts[0]));
                rule.put("max-count", intValue(parts[1], 0));
                rules.add(rule);
            }
        }
        target.set(newPath, rules);
        plan.addMigratedKey(oldPath + " -> " + newPath);
    }

    /** 迁移密集实体限制列表。 */
    private void copyGatherLimitRules(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                                      String newPath, LegacyMigrationPlan plan) {
        if (!oldConfig.contains(oldPath)) {
            return;
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String line : oldConfig.getStringList(oldPath)) {
            String[] parts = splitRule(line);
            if (parts.length >= 4) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("entity", normalizeEntity(parts[0]));
                rule.put("max-count", intValue(parts[1], 0));
                rule.put("radius", intValue(parts[2], 8));
                rule.put("remove-count", intValue(parts[3], 1));
                rules.add(rule);
            }
        }
        target.set(newPath, rules);
        plan.addMigratedKey(oldPath + " -> " + newPath);
    }

    /** 解析旧分号规则。 */
    private String[] splitRule(String line) {
        return line == null ? new String[0] : line.split(";");
    }

    /** 标准化实体名。 */
    private String normalizeEntity(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    /** 解析整数值。 */
    private int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 解析旧位置列表。 */
    private List<String> parseLocationList(List<?> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            String location = parseLocation(value);
            if (!location.isEmpty()) {
                result.add(location);
            }
        }
        return result;
    }

    /** 解析单个旧位置。 */
    private String parseLocation(Object value) {
        if (value == null) {
            return "";
        }
        String[] parts = String.valueOf(value).split(",");
        if (parts.length != 3) {
            return "";
        }
        int x = coordinate(parts[0]);
        int y = coordinate(parts[1]);
        int z = coordinate(parts[2]);
        return x + "," + y + "," + z;
    }

    /** 解析旧位置坐标。 */
    private int coordinate(String value) {
        try {
            return (int) Math.floor(Double.parseDouble(value.trim()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** 加载目标 YAML。 */
    private YamlConfiguration loadTarget(String path) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), path));
    }

    /** 保存目标 YAML。 */
    private void saveTarget(YamlConfiguration target, String path) throws IOException {
        File file = new File(plugin.getDataFolder(), path);
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        target.save(file);
    }

    /** 写入迁移报告。 */
    private void writeReport(File reportFile, LegacySource source, LegacyMigrationPlan plan) throws IOException {
        File parent = reportFile.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(reportFile.toPath()), UTF8)) {
            writer.write("# BLWorldTrashCan 旧配置迁移报告\n\n");
            writer.write("- 迁移时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
            writer.write("- 来源目录: `" + source.folder.getAbsolutePath() + "`\n");
            writer.write("- 来源类型: " + (source.currentDataFolder ? "当前插件数据目录旧结构" : "相邻旧插件数据目录") + "\n\n");
            writeSection(writer, "自动迁移字段", plan.getMigratedKeys());
            writeSection(writer, "已废弃字段", plan.getDeprecatedKeys());
            writeSection(writer, "需要人工确认字段", plan.getManualKeys());
            writer.write("## 说明\n\n");
            writer.write("- 迁移器只迁移当前新实现已经承接的旧功能字段。\n");
            writer.write("- 已生成本报告后，后续启动不会重复迁移；如需重跑，请先备份并删除本报告。\n");
            writer.write("- Bukkit YAML 保存运行时配置会重写文件注释；默认带注释配置仍保留在插件 jar 内。\n");
        }
    }

    /** 写入报告分节。 */
    private void writeSection(Writer writer, String title, List<String> values) throws IOException {
        writer.write("## " + title + "\n\n");
        if (values.isEmpty()) {
            writer.write("- 无\n\n");
            return;
        }
        for (String value : values) {
            writer.write("- `" + value + "`\n");
        }
        writer.write("\n");
    }

    /** 确保目录存在。 */
    private void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
        }
    }

    /** 旧配置来源。 */
    private static final class LegacySource {
        private final File folder;
        private final File configFile;
        private final File dataFile;
        private final boolean currentDataFolder;

        /** 创建旧配置来源。 */
        private LegacySource(File folder, File configFile, File dataFile, boolean currentDataFolder) {
            this.folder = folder;
            this.configFile = configFile;
            this.dataFile = dataFile;
            this.currentDataFolder = currentDataFolder;
        }
    }
}
