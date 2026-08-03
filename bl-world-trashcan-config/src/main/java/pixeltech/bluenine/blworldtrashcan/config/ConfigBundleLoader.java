package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 把拆分后的 YAML 配置读取为类型化配置对象。 */
public final class ConfigBundleLoader {
    /** 读取完整配置集合。 */
    public ConfigBundle load(ConfigurationSource main, ConfigurationSource cleanup, ConfigurationSource trash,
                             ConfigurationSource protections, ConfigurationSource entityLimits) {
        int legacyBackModelId = trash.getInt("global-trash.gui.back-model-id", -1);
        int legacyNextModelId = trash.getInt("global-trash.gui.next-model-id", -1);
        int legacyBackgroundModelId = trash.getInt("global-trash.gui.background-model-id", -1);
        TrashConfig.GlobalTrashLayoutConfig globalTrashLayout = new GlobalTrashLayoutParser().parse(
                trash, legacyBackModelId, legacyNextModelId, legacyBackgroundModelId);
        CleanupSettings cleanupSettings = new CleanupSettings(
                toSet(cleanup.getStringList("ignored-materials")),
                toSet(cleanup.getStringList("ignored-name-fragments")),
                toSet(cleanup.getStringList("ignored-lore-fragments")),
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
                toSet(cleanup.getStringList("entities.blacklist"))
        );
        CleanupConfig cleanupConfig = new CleanupConfig(
                cleanup.getInt("interval-seconds", 360),
                toSet(cleanup.getStringList("ignored-worlds")),
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
                )
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
                        trash.getInt("global-trash.max-pages", 5),
                        trash.getInt("global-trash.take-delay-millis", 500),
                        trash.getInt("global-trash.clear-every-cleanups", 3),
                        trash.getBoolean("global-trash.allow-player-put", true),
                        trash.getBoolean("global-trash.log-enabled", true),
                        globalTrashLayout,
                        toSet(trash.getStringList("global-trash.banned-materials"))
                ),
                new TrashConfig.PersonalTrashConfig(
                        trash.getBoolean("personal-trash.enabled", true),
                        trash.getBoolean("personal-trash.track-player-dropped-items", true),
                        trash.getBoolean("personal-trash.auto-clear-when-full", true),
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

    /** 把列表转成集合。 */
    private Set<String> toSet(List<String> values) {
        return values == null ? new HashSet<String>() : new HashSet<>(values);
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
