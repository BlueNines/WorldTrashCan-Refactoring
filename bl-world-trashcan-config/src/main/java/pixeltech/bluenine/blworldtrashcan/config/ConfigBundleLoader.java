package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 把拆分后的 YAML 配置读取为类型化配置对象。 */
public final class ConfigBundleLoader {
    /** 读取完整配置集合。 */
    public ConfigBundle load(ConfigurationSource main, ConfigurationSource cleanup, ConfigurationSource trash) {
        CleanupSettings cleanupSettings = new CleanupSettings(
                toSet(cleanup.getStringList("ignored-materials")),
                toSet(cleanup.getStringList("ignored-name-fragments")),
                toSet(cleanup.getStringList("ignored-lore-fragments")),
                cleanup.getBoolean("entities.clear-monsters", true),
                cleanup.getBoolean("entities.clear-animals", false),
                cleanup.getBoolean("entities.clear-projectiles", true),
                cleanup.getBoolean("entities.clear-named-entities", false),
                cleanup.getBoolean("entities.ignore-entities-in-boat", false)
        );
        CleanupConfig cleanupConfig = new CleanupConfig(
                cleanup.getInt("interval-seconds", 360),
                toSet(cleanup.getStringList("ignored-worlds")),
                cleanupSettings
        );
        TrashConfig trashConfig = new TrashConfig(
                new TrashConfig.WorldTrashConfig(
                        trash.getBoolean("world-trash.enabled", true),
                        trash.getString("world-trash.sign-create-text", "[世界垃圾桶]"),
                        trash.getString("world-trash.sign-created-text", "&b[&c世界垃圾桶&b]"),
                        trash.getInt("world-trash.default-max-count", 3),
                        toSet(trash.getStringList("world-trash.banned-worlds"))
                ),
                new TrashConfig.GlobalTrashConfig(
                        trash.getBoolean("global-trash.enabled", true),
                        trash.getInt("global-trash.max-pages", 5),
                        trash.getInt("global-trash.take-delay-millis", 500),
                        trash.getInt("global-trash.clear-every-cleanups", 3),
                        trash.getBoolean("global-trash.allow-player-put", true)
                ),
                new TrashConfig.PersonalTrashConfig(
                        trash.getBoolean("personal-trash.enabled", true),
                        trash.getBoolean("personal-trash.track-player-dropped-items", true),
                        trash.getBoolean("personal-trash.auto-clear-when-full", true),
                        trash.getDouble("personal-trash.take-cost", -1D)
                )
        );
        return new ConfigBundle(
                cleanupConfig,
                trashConfig,
                main.getString("language", "message_zh.yml"),
                main.getBoolean("debug", false)
        );
    }

    /** 把列表转成集合。 */
    private Set<String> toSet(List<String> values) {
        return values == null ? new HashSet<String>() : new HashSet<>(values);
    }
}
