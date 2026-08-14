package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.config.LegacyMigrationPlan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 旧 WorldListTrashCan 配置到新拆分配置的 Bukkit 迁移器。 */
public final class BukkitLegacyConfigMigrator {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CONFIG_SCHEMA_KEY = "config-schema-version";
    private static final int CONFIG_SCHEMA_VERSION = 2;
    private static final String BACKUP_FOLDER = "old-version-config";
    private static final String STAGING_FOLDER = ".migration-staging";
    private static final String REPORT_FILE = "migration-report.md";
    private static final String COMPLETE_FILE = "migration-complete.yml";
    private static final String[] DEFAULT_RESOURCES = {
            "config.yml",
            "platform.yml",
            "cleanup.yml",
            "trash.yml",
            "entity-limits.yml",
            "protections.yml",
            "messages/message_zh.yml",
            "messages/message_zh_TW.yml",
            "messages/message_en.yml",
            "messages/message_es.yml",
            "data/worlds.yml"
    };
    private final JavaPlugin plugin;
    private File targetRoot;

    /** 创建旧配置迁移器。 */
    public BukkitLegacyConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 如果检测到旧配置且尚未迁移，则执行一次迁移。 */
    public boolean migrateIfNeeded() {
        File dataFolder = plugin.getDataFolder();
        File backupFolder = new File(dataFolder, BACKUP_FOLDER);
        File completeFile = new File(backupFolder, COMPLETE_FILE);
        try {
            if (completeFile.isFile()) {
                validateCompleteMarker(completeFile);
                rejectLegacyFilesAfterCompletedMigration(dataFolder);
                return false;
            }
            LegacySource source = findLegacySource(dataFolder, backupFolder);
            if (source == null) {
                return false;
            }
            ensureDirectory(dataFolder);
            if (source.currentDataFolder) {
                archiveCurrentDataFolder(dataFolder, backupFolder);
                source = legacySourceFromFolder(backupFolder, false);
            }
            if (source == null) {
                throw new IOException("旧配置完成备份后无法重新读取");
            }
            File stagingFolder = new File(dataFolder, STAGING_FOLDER);
            File reportFile = new File(backupFolder, REPORT_FILE);
            migrate(source, reportFile, stagingFolder);
            writeCompleteMarker(completeFile, source);
            plugin.getLogger().info("[Migration] 已完成旧 WorldListTrashCan 配置迁移，旧文件: "
                    + BACKUP_FOLDER + "，报告: " + BACKUP_FOLDER + "/" + REPORT_FILE);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("[MigrationError] " + exception.getMessage());
            throw new IllegalStateException("旧配置迁移失败，已阻止插件继续加载", exception);
        }
    }

    /** 转义 YAML 双引号字符串内容。 */
    private String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 查找当前目录或未完成备份中的旧配置来源。 */
    private LegacySource findLegacySource(File dataFolder, File backupFolder) throws IOException {
        LegacySource current = legacySourceFromFolder(dataFolder, true);
        if (current != null) {
            return current;
        }
        return legacySourceFromFolder(backupFolder, false);
    }

    /** 从指定目录创建旧配置来源。 */
    private LegacySource legacySourceFromFolder(File folder, boolean currentDataFolder) throws IOException {
        File configFile = new File(folder, "config.yml");
        File dataFile = new File(folder, "data/data.yml");
        if (!isLegacySource(configFile, dataFile)) {
            return null;
        }
        return new LegacySource(folder, configFile, dataFile, currentDataFolder);
    }

    /** 判断给定文件是否包含旧配置结构。 */
    private boolean isLegacySource(File configFile, File dataFile) throws IOException {
        boolean legacyConfig = false;
        boolean currentConfig = false;
        if (configFile.isFile()) {
            YamlConfiguration config = loadYamlStrict(configFile, "旧 config.yml", "legacy-config-invalid");
            legacyConfig = config.isConfigurationSection("Set") || config.contains("GlobalBanItem");
            currentConfig = config.getInt(CONFIG_SCHEMA_KEY, -1) == CONFIG_SCHEMA_VERSION;
        }
        boolean legacyData = false;
        if (dataFile.isFile()) {
            YamlConfiguration data = loadYamlStrict(dataFile, "旧 data/data.yml", "legacy-data-invalid");
            legacyData = data.isConfigurationSection("WorldData");
        }
        if (currentConfig && (legacyConfig || legacyData)) {
            throw new IOException("mixed-current-legacy | 检测到新版 config.yml 与旧版配置结构混放，已拒绝自动覆盖新版配置");
        }
        return legacyConfig || legacyData;
    }

    /** 严格读取 YAML，解析失败时禁止继续生成迁移成功标记。 */
    private YamlConfiguration loadYamlStrict(File file, String label, String errorCode) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (Exception exception) {
            throw new IOException(errorCode + " | " + label + " 无法解析: " + file.getAbsolutePath() + ": "
                    + exception.getMessage(), exception);
        }
    }

    /** 执行旧配置到新配置的迁移。 */
    private void migrate(LegacySource source, File reportFile, File stagingFolder) throws IOException {
        recreateDirectory(stagingFolder);
        saveDefaultResources(stagingFolder);
        targetRoot = stagingFolder;
        LegacyMigrationPlan plan = new LegacyMigrationPlan();
        YamlConfiguration oldConfig = source.configFile.isFile()
                ? loadYamlStrict(source.configFile, "旧 config.yml", "legacy-config-invalid")
                : new YamlConfiguration();
        YamlConfiguration oldData = source.dataFile.isFile()
                ? loadYamlStrict(source.dataFile, "旧 data/data.yml", "legacy-data-invalid")
                : new YamlConfiguration();
        migrateMainConfig(oldConfig, plan);
        migrateCleanupConfig(oldConfig, plan);
        migrateTrashConfig(oldConfig, plan);
        migrateNotifyConfig(oldConfig, plan);
        migrateProtectionConfig(oldConfig, plan);
        migrateEntityLimitConfig(oldConfig, plan);
        migrateWorldTrashData(oldData, plan);
        recordUnsupportedKeys(oldConfig, plan);
        recordUnsupportedDataKeys(oldData, plan);
        recordLegacyMessageFiles(source, plan);
        validateNewConfigTree(stagingFolder);
        writeReport(reportFile, source, plan);
        publishStaging(stagingFolder, plugin.getDataFolder());
        validateNewConfigTree(plugin.getDataFolder());
    }

    /** 将旧插件数据目录的全部内容移动到 old-version-config。 */
    private void archiveCurrentDataFolder(File dataFolder, File backupFolder) throws IOException {
        ensureDirectory(dataFolder);
        ensureDirectory(backupFolder);
        File[] children = dataFolder.listFiles();
        if (children == null) {
            throw new IOException("无法读取插件数据目录: " + dataFolder.getAbsolutePath());
        }
        for (File child : children) {
            if (child.equals(backupFolder) || STAGING_FOLDER.equals(child.getName())) {
                continue;
            }
            File target = new File(backupFolder, child.getName());
            moveIntoBackup(child, target);
        }
    }

    /** 可重入地把旧文件移动到备份目录，内容冲突时拒绝覆盖。 */
    private void moveIntoBackup(File source, File target) throws IOException {
        if (!target.exists()) {
            Files.move(source.toPath(), target.toPath());
            return;
        }
        if (source.isDirectory() && target.isDirectory()) {
            File[] children = source.listFiles();
            if (children == null) {
                throw new IOException("无法读取待备份目录: " + source.getAbsolutePath());
            }
            for (File child : children) {
                moveIntoBackup(child, new File(target, child.getName()));
            }
            Files.deleteIfExists(source.toPath());
            return;
        }
        if (source.isFile() && target.isFile() && sameFileContent(source, target)) {
            Files.deleteIfExists(source.toPath());
            return;
        }
        throw new IOException("backup-content-conflict | 旧配置备份目标存在不同内容，拒绝覆盖: "
                + target.getAbsolutePath());
    }

    /** 流式比较两个文件，避免为日志等大文件一次性分配内存。 */
    private boolean sameFileContent(File first, File second) throws IOException {
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

    /** 在成功标记存在时拒绝重新放回根目录的旧配置。 */
    private void rejectLegacyFilesAfterCompletedMigration(File dataFolder) throws IOException {
        LegacySource source = legacySourceFromFolder(dataFolder, true);
        if (source != null) {
            plugin.getLogger().severe("[MigrationGuard] legacy-root-after-complete");
            throw new IllegalStateException("检测到迁移完成后重新放回的旧版配置，请移除根目录旧文件后重启");
        }
    }

    /** 校验迁移完成标记，避免空文件或损坏标记错误跳过迁移。 */
    private void validateCompleteMarker(File completeFile) throws IOException {
        YamlConfiguration marker = loadYamlStrict(completeFile, "迁移完成标记", "complete-marker-yaml-invalid");
        String fingerprint = marker.getString("source-sha256", "");
        if (!"complete".equals(marker.getString("status"))
                || marker.getInt("target-config-schema-version", -1) != CONFIG_SCHEMA_VERSION
                || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("complete-marker-invalid | 迁移完成标记内容不完整或已损坏: "
                    + completeFile.getAbsolutePath());
        }
    }

    /** 重新创建空的迁移暂存目录。 */
    private void recreateDirectory(File directory) throws IOException {
        if (directory.exists()) {
            deleteTree(directory.toPath());
        }
        ensureDirectory(directory);
    }

    /** 删除迁移器自己创建的目录树。 */
    private void deleteTree(Path root) throws IOException {
        Path expectedParent = plugin.getDataFolder().getCanonicalFile().toPath();
        Path resolved = root.toFile().getCanonicalFile().toPath();
        if (!resolved.startsWith(expectedParent) || resolved.equals(expectedParent)) {
            throw new IOException("拒绝删除插件数据目录之外的路径: " + resolved);
        }
        Files.walkFileTree(resolved, new SimpleFileVisitor<Path>() {
            /** 删除普通文件。 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            /** 删除已经清空的目录。 */
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 把 jar 内新版默认资源保存到暂存目录。 */
    private void saveDefaultResources(File stagingFolder) throws IOException {
        for (String resource : DEFAULT_RESOURCES) {
            InputStream inputStream = plugin.getResource(resource);
            if (inputStream == null) {
                throw new IOException("插件 jar 缺少默认资源: " + resource);
            }
            File target = new File(stagingFolder, resource);
            File parent = target.getParentFile();
            if (parent != null) {
                ensureDirectory(parent);
            }
            try (InputStream input = inputStream) {
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 校验新版配置目录的文件、格式版本和 YAML 语法。 */
    private void validateNewConfigTree(File root) throws IOException {
        for (String resource : DEFAULT_RESOURCES) {
            File file = new File(root, resource);
            if (!file.isFile()) {
                throw new IOException("新版配置缺少文件: " + resource);
            }
            if (resource.endsWith(".yml") || resource.endsWith(".yaml")) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file);
                } catch (Exception exception) {
                    throw new IOException("新版配置 YAML 无法解析: " + resource + ": " + exception.getMessage(), exception);
                }
                if ("config.yml".equals(resource)
                        && yaml.getInt(CONFIG_SCHEMA_KEY, -1) != CONFIG_SCHEMA_VERSION) {
                    throw new IOException("config.yml 缺少正确的 " + CONFIG_SCHEMA_KEY);
                }
            }
        }
    }

    /** 将校验通过的暂存配置发布到插件数据目录。 */
    private void publishStaging(final File stagingFolder, final File dataFolder) throws IOException {
        final Path sourceRoot = stagingFolder.toPath();
        final Path targetRootPath = dataFolder.toPath();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            /** 创建目标子目录。 */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Path relative = sourceRoot.relativize(directory);
                Files.createDirectories(targetRootPath.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            /** 发布单个配置文件。 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path target = targetRootPath.resolve(sourceRoot.relativize(file));
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        deleteTree(stagingFolder.toPath());
    }

    /** 迁移主配置。 */
    private void migrateMainConfig(YamlConfiguration oldConfig, LegacyMigrationPlan plan) throws IOException {
        YamlConfiguration target = loadTarget("config.yml");
        target.set(CONFIG_SCHEMA_KEY, CONFIG_SCHEMA_VERSION);
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
                "global-trash.gui.layout.items.a.model-id", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.GlobalItems.NextItem.ModelId",
                "global-trash.gui.layout.items.c.model-id", plan);
        copyInt(oldConfig, target, "Set.GlobalTrash.GlobalItems.BackgroundItem.ModelId",
                "global-trash.gui.layout.items.b.model-id", plan);
        copyStringAsList(oldConfig, target, "Set.GlobalTrash.GlobalItems.BackItem.Material",
                "global-trash.gui.layout.items.a.material", plan);
        copyStringAsList(oldConfig, target, "Set.GlobalTrash.GlobalItems.NextItem.Material",
                "global-trash.gui.layout.items.c.material", plan);
        copyStringAsList(oldConfig, target, "Set.GlobalTrash.GlobalItems.BackgroundItem.Material",
                "global-trash.gui.layout.items.b.material", plan);
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
        copyBoolean(oldConfig, target, "Set.ChatConsoleLogFlag", "notify.console.enabled", plan);
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
        for (String path : oldConfig.getKeys(true)) {
            if (!oldConfig.isConfigurationSection(path) && !plan.isHandledSourceKey(path)) {
                plan.addManualKey(path + " | 新版没有自动映射，原值仍保留在 old-version-config/config.yml");
            }
        }
    }

    /** 记录旧世界数据中无法自动识别的字段。 */
    private void recordUnsupportedDataKeys(YamlConfiguration oldData, LegacyMigrationPlan plan) {
        for (String path : oldData.getKeys(true)) {
            if (oldData.isConfigurationSection(path) || isKnownWorldDataLeaf(path)) {
                continue;
            }
            plan.addManualKey(path + " | 未识别的旧运行数据，原值仍保留在 old-version-config/data/data.yml");
        }
    }

    /** 判断旧世界数据叶子路径是否属于已迁移字段。 */
    private boolean isKnownWorldDataLeaf(String path) {
        return path.startsWith("WorldData.") && (path.endsWith(".SignLocation")
                || path.endsWith(".RashMaxCount") || path.endsWith(".BanItem"));
    }

    /** 把旧语言文件记录为人工合并项，防止服主误以为自定义文案已自动转换。 */
    private void recordLegacyMessageFiles(LegacySource source, LegacyMigrationPlan plan) {
        File messageFolder = new File(source.folder, "message");
        File[] files = messageFolder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && (file.getName().endsWith(".yml") || file.getName().endsWith(".yaml"))) {
                plan.addManualKey("message/" + file.getName()
                        + " | 新版语言键结构已变化，文件已完整备份，请按需人工合并到 messages/");
            }
        }
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
        plan.addHandledSourceKey(oldPath);
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
            plan.addHandledSourceKey(oldPath);
        }
    }

    /** 拷贝布尔字段。 */
    private void copyBoolean(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                             String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getBoolean(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
            plan.addHandledSourceKey(oldPath);
        }
    }

    /** 拷贝整数字段。 */
    private void copyInt(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                         String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getInt(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
            plan.addHandledSourceKey(oldPath);
        }
    }

    /** 拷贝小数字段。 */
    private void copyDouble(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                            String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getDouble(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
            plan.addHandledSourceKey(oldPath);
        }
    }

    /** 拷贝字符串列表字段。 */
    private void copyStringList(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                                String newPath, LegacyMigrationPlan plan) {
        if (oldConfig.contains(oldPath)) {
            target.set(newPath, oldConfig.getStringList(oldPath));
            plan.addMigratedKey(oldPath + " -> " + newPath);
            plan.addHandledSourceKey(oldPath);
        }
    }

    /** 把旧版单个材质字符串迁移为新版材质候选列表。 */
    private void copyStringAsList(YamlConfiguration oldConfig, YamlConfiguration target, String oldPath,
                                  String newPath, LegacyMigrationPlan plan) {
        if (!oldConfig.contains(oldPath)) {
            return;
        }
        String value = oldConfig.getString(oldPath, "").trim();
        target.set(newPath, value.isEmpty() ? Collections.emptyList() : Collections.singletonList(value));
        plan.addMigratedKey(oldPath + " -> " + newPath);
        plan.addHandledSourceKey(oldPath);
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
        plan.addHandledSourceKey(oldPath);
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
        plan.addHandledSourceKey(oldPath);
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
        return YamlConfiguration.loadConfiguration(new File(targetRoot, path));
    }

    /** 保存目标 YAML。 */
    private void saveTarget(YamlConfiguration target, String path) throws IOException {
        File file = new File(targetRoot, path);
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
            writer.write("# WorldListTrashCan 旧配置迁移报告\n\n");
            writer.write("- 迁移时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
            writer.write("- 来源目录: `" + source.folder.getAbsolutePath() + "`\n");
            writer.write("- 来源类型: old-version-config 隔离备份\n\n");
            writeSection(writer, "自动迁移字段", plan.getMigratedKeys());
            writeSection(writer, "已废弃字段", plan.getDeprecatedKeys());
            writeSection(writer, "需要人工确认字段", plan.getManualKeys());
            writer.write("## 说明\n\n");
            writer.write("- 迁移器只迁移当前新实现已经承接的旧功能字段。\n");
            writer.write("- 只有 migration-complete.yml 表示迁移成功；迁移失败不会生成成功标记。\n");
            writer.write("- Bukkit YAML 保存运行时配置会重写文件注释；默认带注释配置仍保留在插件 jar 内。\n");
        }
    }

    /** 在所有配置发布并复核成功后写入一次性完成标记。 */
    private void writeCompleteMarker(File completeFile, LegacySource source) throws IOException {
        File parent = completeFile.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        StringBuilder text = new StringBuilder();
        text.append("status: complete\n");
        text.append("source-format: legacy-single-config\n");
        text.append("target-config-schema-version: ").append(CONFIG_SCHEMA_VERSION).append('\n');
        text.append("target-plugin-version: \"")
                .append(escapeYamlDoubleQuoted(plugin.getDescription().getVersion())).append("\"\n");
        text.append("migrated-at: \"").append(timestamp).append("\"\n");
        text.append("source-sha256: \"").append(sourceFingerprint(source)).append("\"\n");
        Files.write(completeFile.toPath(), text.toString().getBytes(UTF8));
    }

    /** 计算旧主配置和旧世界数据的联合 SHA-256。 */
    private String sourceFingerprint(LegacySource source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, source.configFile);
            updateDigest(digest, source.dataFile);
            byte[] result = digest.digest();
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte value : result) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    /** 把存在的旧文件内容加入摘要。 */
    private void updateDigest(MessageDigest digest, File file) throws IOException {
        if (file.isFile()) {
            digest.update(Files.readAllBytes(file.toPath()));
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
