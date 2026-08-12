package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CustomItemRoutingSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.ItemMatchRules;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 把拆分后的 YAML 配置读取为类型化配置对象。 */
public final class ConfigBundleLoader {
    /** 读取完整配置集合；兼容既有纯配置调用并解析 model-id。 */
    public ConfigBundle load(ConfigurationSource main, ConfigurationSource cleanup, ConfigurationSource trash,
                              ConfigurationSource protections, ConfigurationSource entityLimits) {
        return load(main, cleanup, trash, protections, entityLimits, true);
    }

    /** 按当前运行时能力读取完整配置集合。 */
    public ConfigBundle load(ConfigurationSource main, ConfigurationSource cleanup, ConfigurationSource trash,
                             ConfigurationSource protections, ConfigurationSource entityLimits,
                             boolean supportsCustomModelData) {
        int legacyBackModelId = supportsCustomModelData
                ? trash.getInt("global-trash.gui.back-model-id", -1) : -1;
        int legacyNextModelId = supportsCustomModelData
                ? trash.getInt("global-trash.gui.next-model-id", -1) : -1;
        int legacyBackgroundModelId = supportsCustomModelData
                ? trash.getInt("global-trash.gui.background-model-id", -1) : -1;
        GlobalTrashLayoutParser layoutParser = new GlobalTrashLayoutParser();
        TrashConfig.GlobalTrashLayoutConfig globalTrashLayout = layoutParser.parse(
                trash, "global-trash.gui.layout", legacyBackModelId, legacyNextModelId,
                legacyBackgroundModelId, supportsCustomModelData);
        int legacyGlobalMaxPages = trash.getInt("global-trash.max-pages", 5);
        List<String> compactActionLore = trash.contains("global-trash.compact.action-lore")
                ? trash.getStringList("global-trash.compact.action-lore")
                : TrashConfig.CompactGlobalTrashConfig.defaults().getActionLore();
        TrashConfig.CompactGlobalTrashConfig compactGlobalTrash = new TrashConfig.CompactGlobalTrashConfig(
                trash.getInt("global-trash.compact.max-pages", legacyGlobalMaxPages),
                trash.getInt("global-trash.compact.max-amount-per-entry", 9999),
                trash.getInt("global-trash.compact.left-click-amount", 1),
                trash.getInt("global-trash.compact.shift-left-click-amount", 64),
                trash.getBoolean("global-trash.compact.right-click-enabled", false),
                trash.getInt("global-trash.compact.right-click-amount", 1),
                trash.getInt("global-trash.compact.shift-right-click-amount", 64),
                trash.getBoolean("global-trash.compact.show-amount-lore", true),
                trash.getInt("global-trash.compact.max-original-lore-lines", 5),
                trash.getString("global-trash.compact.amount-lore", "&#38BDF8数量：&#F5B82E{amount}"),
                trash.getString("global-trash.compact.omitted-lore",
                "&#64748B...省略 &#AAB6C5{count} &#64748B行..."),
                compactActionLore,
                TrashConfig.GlobalTrashSortType.parse(
                        trash.getString("global-trash.compact.default-sort", "insertion"))
        );
        TrashConfig.StackedGlobalTrashConfig stackedGlobalTrash = new TrashConfig.StackedGlobalTrashConfig(
                trash.getInt("global-trash.stacked.max-pages", legacyGlobalMaxPages),
                TrashConfig.GlobalTrashSortType.parse(
                        trash.getString("global-trash.stacked.default-sort", "insertion")));
        TrashConfig.GlobalTrashLayoutConfig personalTrashLayout = layoutParser.parse(
                trash, "personal-trash.gui.layout", -1, -1, -1, supportsCustomModelData);
        TrashConfig.CompactGlobalTrashConfig compactPersonalTrash = new TrashConfig.CompactGlobalTrashConfig(
                trash.getInt("personal-trash.compact.max-pages", 2),
                trash.getInt("personal-trash.compact.max-amount-per-entry", 9999),
                trash.getInt("personal-trash.compact.left-click-amount", 1),
                trash.getInt("personal-trash.compact.shift-left-click-amount", 64),
                trash.getBoolean("personal-trash.compact.right-click-enabled", false),
                trash.getInt("personal-trash.compact.right-click-amount", 1),
                trash.getInt("personal-trash.compact.shift-right-click-amount", 64),
                trash.getBoolean("personal-trash.compact.show-amount-lore", true),
                trash.getInt("personal-trash.compact.max-original-lore-lines", 5),
                trash.getString("personal-trash.compact.amount-lore", "&#38BDF8数量：&#F5B82E{amount}"),
                trash.getString("personal-trash.compact.omitted-lore",
                        "&#64748B...省略 &#AAB6C5{count} &#64748B行..."),
                trash.contains("personal-trash.compact.action-lore")
                        ? trash.getStringList("personal-trash.compact.action-lore")
                        : TrashConfig.CompactGlobalTrashConfig.defaults().getActionLore(),
                TrashConfig.GlobalTrashSortType.parse(
                        trash.getString("personal-trash.compact.default-sort", "insertion"))
        );
        TrashConfig.StackedGlobalTrashConfig stackedPersonalTrash = new TrashConfig.StackedGlobalTrashConfig(
                trash.getInt("personal-trash.stacked.max-pages", 2),
                TrashConfig.GlobalTrashSortType.parse(
                        trash.getString("personal-trash.stacked.default-sort", "insertion")));
        boolean legacyItemProtectionConfigured = cleanup.contains("ignored-materials")
                || cleanup.contains("ignored-name-fragments")
                || cleanup.contains("ignored-lore-fragments");
        CleanupSettings cleanupSettings = new CleanupSettings(
                mergedSet(cleanup, "custom-data-items.ignored-materials", "ignored-materials"),
                mergedSet(cleanup, "custom-data-items.ignored-name-fragments", "ignored-name-fragments"),
                mergedSet(cleanup, "custom-data-items.ignored-lore-fragments", "ignored-lore-fragments"),
                cleanup.getBoolean("entities.enabled", true),
                cleanup.getBoolean("entities.clear-experience-orbs", true),
                cleanup.getBoolean("entities.clear-monsters", true),
                cleanup.getBoolean("entities.clear-animals", false),
                cleanup.getBoolean("entities.clear-projectiles", true),
                cleanup.getBoolean("entities.clear-named-entities", false),
                cleanup.getBoolean("entities.ignore-entities-in-boat", false),
                cleanup.getBoolean("entities.ignore-entities-with-saddle", true),
                cleanup.getBoolean("entities.ignore-entities-with-owner", true),
                toSet(cleanup.getStringList("entities.whitelist")),
                toSet(cleanup.getStringList("entities.blacklist")),
                new CustomItemRoutingSettings(
                        cleanup.getBoolean("custom-data-items.routing.enabled", false),
                        itemMatchRules(cleanup, "custom-data-items.routing.detection"),
                        CustomItemRoutingSettings.Mode.parse(
                                cleanup.getString("custom-data-items.routing.mode", "personal-only")),
                        CustomItemRoutingSettings.UnavailableAction.parse(cleanup.getString(
                                "custom-data-items.routing.personal-unavailable", "keep-ground"))
                )
        );
        CleanupConfig cleanupConfig = new CleanupConfig(
                cleanup.getInt("interval-seconds", 360),
                parseCleanupWorldFilter(cleanup),
                toSet(cleanup.getStringList("direct-remove-worlds")),
                cleanupSettings,
                new CleanupConfig.CleanupGuardConfig(
                        cleanup.getInt("guards.min-online-players", 1),
                        cleanup.getInt("guards.min-total-entities", 150)
                ),
                new CleanupConfig.FoliaCleanupConfig(
                        cleanup.getInt("folia.timeout-seconds", 30),
                        cleanup.getInt("folia.max-chunks-per-cleanup", 4096),
                        cleanup.getInt("folia.chunk-batch-size", 64),
                        cleanup.getInt("folia.chunk-batch-delay-ticks", 1)
                ),
                new CleanupConfig.MovingItemConfig(
                        cleanup.getBoolean("moving-items.enabled", false),
                        cleanup.getDouble("moving-items.minimum-speed", 0.01D)
                ),
                new CleanupConfig.FilledShulkerBoxConfig(
                        cleanup.getBoolean("filled-shulker-boxes.enabled", false)
                ),
                legacyItemProtectionConfigured
        );
        TrashConfig trashConfig = new TrashConfig(
                new TrashConfig.WorldTrashConfig(
                        trash.getBoolean("world-trash.enabled", true),
                        trash.getString("world-trash.sign-create-text", "[世界垃圾桶]"),
                        trash.getString("world-trash.sign-created-text", "&b[&c世界垃圾桶&b]"),
                        trash.getInt("world-trash.default-max-count", 3),
                        trash.getBoolean("world-trash.allow-load-unloaded-chunks", false),
                        toSet(trash.getStringList("world-trash.banned-worlds"))
                ),
                new TrashConfig.GlobalTrashConfig(
                        trash.getBoolean("global-trash.enabled", true),
                        trash.getInt("global-trash.take-delay-millis", 500),
                        trash.getInt("global-trash.clear-every-cleanups", 3),
                        trash.getBoolean("global-trash.allow-player-put", true),
                        trash.getBoolean("global-trash.log-enabled", true),
                        globalTrashLayout,
                        toSet(trash.getStringList("global-trash.banned-materials")),
                        TrashConfig.GlobalTrashMode.parse(trash.getString("global-trash.mode", "compact")),
                        compactGlobalTrash,
                        stackedGlobalTrash,
                        new TrashConfig.GlobalTrashAdmissionWhitelistConfig(
                                trash.getBoolean("global-trash.admission-whitelist.enabled", false),
                                itemMatchRules(trash, "global-trash.admission-whitelist"),
                                RejectedCleanupAction.parse(trash.getString(
                                        "global-trash.admission-whitelist.rejected-cleanup-action",
                                        "keep-ground"))
                        )
                ),
                new TrashConfig.PersonalTrashConfig(
                        trash.getBoolean("personal-trash.enabled", true),
                        trash.getInt("personal-trash.take-delay-millis", 0),
                        trash.getBoolean("personal-trash.allow-player-put", true),
                        personalTrashLayout,
                        TrashConfig.GlobalTrashMode.parse(trash.getString("personal-trash.mode", "compact")),
                        compactPersonalTrash,
                        stackedPersonalTrash,
                        trash.getBoolean("personal-trash.track-player-dropped-items", true),
                        trash.getBoolean("personal-trash.auto-clear-when-full", false),
                        trash.getDouble("personal-trash.take-cost", -1D),
                        parseDamageRecoveryMode(trash.getString("personal-trash.damage-recovery.mode", "disabled")),
                        trash.getInt("personal-trash.damage-recovery.delay-seconds", 6),
                        trash.getBoolean("personal-trash.notify.enabled", true),
                        trash.getInt("personal-trash.notify.max-display-items", 3)
                )
        );
        ProtectionConfig protectionConfig = new ProtectionConfig(
                new ProtectionConfig.RateLimitConfig(
                        protections.getBoolean("chat-rate-limit.enabled", true),
                        protections.getDouble("chat-rate-limit.interval-seconds", 0.7D),
                        protections.getString("chat-rate-limit.message", "&c请不要刷屏"),
                        protections.getString("chat-rate-limit.command", "")
                ),
                new ProtectionConfig.CommandRateLimitConfig(
                        protections.getBoolean("command-rate-limit.enabled", true),
                        protections.getDouble("command-rate-limit.interval-seconds", 0.7D),
                        protections.getString("command-rate-limit.message", "&c请不要频繁使用指令"),
                        protections.getString("command-rate-limit.command", ""),
                        toSet(protections.getStringList("command-rate-limit.whitelist"))
                ),
                protections.getBoolean("drop-protection.enabled", true),
                protections.getBoolean("simple-optimize.remove-unpickable-arrow", true),
                protections.getBoolean("simple-optimize.prevent-farmland-trampling", true)
        );
        EntityLimitConfig entityLimitConfig = new EntityLimitConfig(
                new EntityLimitConfig.WorldLimitConfig(
                        entityLimits.getBoolean("world-limits.enabled", false),
                        toSet(entityLimits.getStringList("world-limits.ignored-worlds")),
                        parseWorldLimits(entityLimits.getMapList("world-limits.defaults"))
                ),
                new EntityLimitConfig.GatherLimitConfig(
                        entityLimits.getBoolean("gather-limits.enabled", false),
                        entityLimits.getBoolean("gather-limits.drop-items", true),
                        toSet(entityLimits.getStringList("gather-limits.ignored-worlds")),
                        parseGatherLimits(entityLimits.getMapList("gather-limits.defaults"))
                ),
                new EntityLimitConfig.ScanConfig(
                        entityLimits.getInt("scanner.target-full-cycle-seconds", 300),
                        entityLimits.getInt("scanner.scan-interval-ticks", 20),
                        entityLimits.getInt("scanner.min-chunks-per-scan", 4),
                        entityLimits.getInt("scanner.max-chunks-per-scan", 64),
                        entityLimits.getInt("scanner.max-scan-millis-per-run", 4),
                        entityLimits.getInt("scanner.remove-interval-ticks", 2),
                        entityLimits.getInt("scanner.max-removes-per-run", 20),
                        entityLimits.getInt("scanner.max-pending-removals", 2000),
                        entityLimits.getInt("scanner.candidate-ttl-seconds", 120),
                        entityLimits.getInt("scanner.max-candidate-retries", 3),
                        entityLimits.getInt("scanner.max-dirty-chunks", 4096),
                        entityLimits.getInt("scanner.stale-chunk-seconds", 600),
                        entityLimits.getInt("scanner.max-index-entities", 50000),
                        entityLimits.getInt("scanner.max-index-entities-per-chunk", 512),
                        entityLimits.getInt("scanner.log-summary-seconds", 60)
                )
        );
        return new ConfigBundle(
                cleanupConfig,
                trashConfig,
                protectionConfig,
                entityLimitConfig,
                loadNotifyConfig(cleanup),
                main.getString("language", "message_zh.yml"),
                main.getBoolean("debug", false)
        );
    }

    /** 读取新世界过滤器；新节点不存在时兼容旧 ignored-worlds。 */
    private CleanupWorldFilter parseCleanupWorldFilter(ConfigurationSource cleanup) {
        boolean hasInclude = cleanup.contains("world-filter.include");
        boolean hasExclude = cleanup.contains("world-filter.exclude");
        if (hasInclude || hasExclude) {
            Set<String> include = hasInclude ? toSet(cleanup.getStringList("world-filter.include")) : null;
            Set<String> exclude = hasExclude ? toSet(cleanup.getStringList("world-filter.exclude")) : null;
            return CleanupWorldFilter.configured(include, exclude, cleanup.contains("ignored-worlds"));
        }
        if (cleanup.contains("ignored-worlds")) {
            return CleanupWorldFilter.fromLegacy(toSet(cleanup.getStringList("ignored-worlds")));
        }
        return CleanupWorldFilter.defaults();
    }

    /** 把列表转成集合。 */
    private Set<String> toSet(List<String> values) {
        return values == null ? new HashSet<String>() : new HashSet<>(values);
    }

    /** 合并新旧列表节点，保护类配置优先保证旧值不丢失。 */
    private Set<String> mergedSet(ConfigurationSource source, String currentPath, String legacyPath) {
        Set<String> result = toSet(source.getStringList(currentPath));
        result.addAll(toSet(source.getStringList(legacyPath)));
        return result;
    }

    /** 从统一五类路径读取物品匹配规则。 */
    private ItemMatchRules itemMatchRules(ConfigurationSource source, String path) {
        return new ItemMatchRules(
                toSet(source.getStringList(path + ".material-patterns")),
                mergedSet(source, path + ".name-key-patterns", path + ".name-patterns"),
                mergedSet(source, path + ".lore-key-patterns", path + ".lore-patterns"),
                toSet(source.getStringList(path + ".pdc-key-patterns")),
                toSet(source.getStringList(path + ".nbt-key-patterns"))
        );
    }

    /** 解析世界实体限制列表。 */
    private Map<String, Integer> parseWorldLimits(List<Map<?, ?>> values) {
        Map<String, Integer> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (Map<?, ?> value : values) {
            String entity = stringValue(value.get("entity"));
            int maxCount = intValue(value.get("max-count"), 0);
            if (!entity.isEmpty() && maxCount > 0) {
                result.put(entity, maxCount);
            }
        }
        return result;
    }

    /** 解析密集实体限制列表。 */
    private Map<String, EntityLimitConfig.GatherRule> parseGatherLimits(List<Map<?, ?>> values) {
        Map<String, EntityLimitConfig.GatherRule> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (Map<?, ?> value : values) {
            String entity = stringValue(value.get("entity"));
            int maxCount = intValue(value.get("max-count"), 0);
            int radius = intValue(value.get("radius"), 8);
            int removeCount = intValue(value.get("remove-count"), 1);
            if (!entity.isEmpty() && maxCount > 0) {
                result.put(entity, new EntityLimitConfig.GatherRule(maxCount, radius, removeCount));
            }
        }
        return result;
    }

    /** 把对象转成字符串。 */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 把对象转成整数。 */
    private int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 解析玩家掉落物损坏回收模式。 */
    private TrashConfig.DamageRecoveryMode parseDamageRecoveryMode(String value) {
        String normalized = stringValue(value).toLowerCase();
        if ("1".equals(normalized) || "global".equals(normalized) || "global-trash".equals(normalized)) {
            return TrashConfig.DamageRecoveryMode.GLOBAL_TRASH;
        }
        if ("2".equals(normalized) || "personal".equals(normalized) || "personal-trash".equals(normalized)) {
            return TrashConfig.DamageRecoveryMode.PERSONAL_TRASH;
        }
        return TrashConfig.DamageRecoveryMode.DISABLED;
    }

    /** 读取通知配置。 */
    private NotifyConfig loadNotifyConfig(ConfigurationSource cleanup) {
        boolean consoleEnabled = cleanup.contains("notify.console.enabled")
                ? cleanup.getBoolean("notify.console.enabled", true)
                : cleanup.getBoolean("notify.chat.console-log", true);
        NotifyConfig.ConsoleConfig consoleConfig = new NotifyConfig.ConsoleConfig(
                consoleEnabled,
                cleanup.getBoolean("notify.console.details-enabled", true),
                cleanup.getInt("notify.console.max-entries", 10),
                cleanup.getString("notify.console.entity-format", "{name}_{type}: {count}"),
                cleanup.getString("notify.console.items-format", "items: {count}"),
                cleanup.getString("notify.console.others-format", "others: {count}")
        );
        return new NotifyConfig(
                cleanup.getBoolean("notify.chat.enabled", true),
                cleanup.getString("notify.chat.click-command", "/worldlisttrashcan globaltrash"),
                parseTextMessages(cleanup.getStringList("notify.chat.messages")),
                consoleConfig,
                cleanup.getBoolean("notify.actionbar.enabled", true),
                parseTextMessages(cleanup.getStringList("notify.actionbar.messages")),
                cleanup.getBoolean("notify.bossbar.enabled", false),
                parseBossBarMessages(cleanup.getStringList("notify.bossbar.messages")),
                cleanup.getBoolean("notify.command.enabled", false),
                parseCommandMessages(cleanup.getStringList("notify.command.commands")),
                cleanup.getBoolean("notify.title.enabled", false),
                parseTitleMessages(cleanup.getStringList("notify.title.messages")),
                cleanup.getBoolean("notify.sound.enabled", true),
                parseSoundMessages(cleanup.getStringList("notify.sound.messages"))
        );
    }

    /** 解析 count;text 格式的文本通知。 */
    private Map<Integer, String> parseTextMessages(List<String> values) {
        Map<Integer, String> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String[] parts = split(value, 2);
            if (parts.length >= 2) {
                result.put(intValue(parts[0], Integer.MIN_VALUE), parts[1]);
            }
        }
        result.remove(Integer.MIN_VALUE);
        return result;
    }

    /** 解析 count;text;style;color 格式的 BossBar 通知。 */
    private Map<Integer, NotifyConfig.BossBarMessage> parseBossBarMessages(List<String> values) {
        Map<Integer, NotifyConfig.BossBarMessage> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String[] parts = split(value, 4);
            if (parts.length < 2) {
                continue;
            }
            int count = intValue(parts[0], Integer.MIN_VALUE);
            if (count == Integer.MIN_VALUE) {
                continue;
            }
            String style = parts.length >= 3 ? parts[2] : "SOLID";
            String color = parts.length >= 4 ? parts[3] : "GREEN";
            result.put(count, new NotifyConfig.BossBarMessage(parts[1], style, color));
        }
        return result;
    }

    /** 解析 count;command1;command2 格式的命令通知。 */
    private Map<Integer, List<String>> parseCommandMessages(List<String> values) {
        Map<Integer, List<String>> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String[] parts = split(value, 0);
            if (parts.length < 2) {
                continue;
            }
            int count = intValue(parts[0], Integer.MIN_VALUE);
            if (count == Integer.MIN_VALUE) {
                continue;
            }
            List<String> commands = new ArrayList<>();
            for (int index = 1; index < parts.length; index++) {
                if (!parts[index].trim().isEmpty()) {
                    commands.add(parts[index]);
                }
            }
            if (!commands.isEmpty()) {
                result.put(count, commands);
            }
        }
        return result;
    }

    /** 解析 count;title;subtitle 格式的 Title 通知。 */
    private Map<Integer, NotifyConfig.TitleMessage> parseTitleMessages(List<String> values) {
        Map<Integer, NotifyConfig.TitleMessage> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String[] parts = split(value, 3);
            if (parts.length >= 2) {
                result.put(intValue(parts[0], Integer.MIN_VALUE),
                        new NotifyConfig.TitleMessage(parts[1], parts.length >= 3 ? parts[2] : ""));
            }
        }
        result.remove(Integer.MIN_VALUE);
        return result;
    }

    /** 解析 count;sound,volume,pitch 格式的声音通知。 */
    private Map<Integer, NotifyConfig.SoundMessage> parseSoundMessages(List<String> values) {
        Map<Integer, NotifyConfig.SoundMessage> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String[] parts = split(value, 2);
            if (parts.length < 2) {
                continue;
            }
            String[] soundParts = parts[1].split(",");
            String sound = soundParts.length > 0 ? soundParts[0] : "";
            float volume = soundParts.length > 1 ? floatValue(soundParts[1], 1F) : 1F;
            float pitch = soundParts.length > 2 ? floatValue(soundParts[2], 1F) : 1F;
            result.put(intValue(parts[0], Integer.MIN_VALUE), new NotifyConfig.SoundMessage(sound, volume, pitch));
        }
        result.remove(Integer.MIN_VALUE);
        return result;
    }

    /** 切分分号配置。 */
    private String[] split(String value, int limit) {
        if (value == null) {
            return new String[0];
        }
        return limit > 0 ? value.split(";", limit) : value.split(";");
    }

    /** 把对象转成浮点数。 */
    private float floatValue(Object value, float fallback) {
        try {
            return Float.parseFloat(stringValue(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
