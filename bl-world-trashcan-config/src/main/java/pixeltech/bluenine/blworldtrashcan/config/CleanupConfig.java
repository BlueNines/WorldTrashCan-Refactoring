package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 清理功能的类型化配置。 */
public final class CleanupConfig {
    private final int intervalSeconds;
    private final Set<String> ignoredWorlds;
    private final CleanupSettings settings;

    /** 创建清理配置。 */
    public CleanupConfig(int intervalSeconds, Set<String> ignoredWorlds, CleanupSettings settings) {
        this.intervalSeconds = Math.max(0, intervalSeconds);
        this.ignoredWorlds = normalizeWorlds(ignoredWorlds);
        this.settings = settings;
    }

    /** 返回清理间隔秒数，0 表示只允许手动清理。 */
    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    /** 判断世界是否跳过清理。 */
    public boolean isIgnoredWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        return ignoredWorlds.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    /** 返回核心清理策略配置。 */
    public CleanupSettings getSettings() {
        return settings;
    }

    /** 标准化世界名集合。 */
    private static Set<String> normalizeWorlds(Set<String> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.trim().isEmpty()) {
                result.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
