package pixeltech.bluenine.blworldtrashcan.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 旧配置迁移计划摘要，正式迁移器会把它写入迁移报告。 */
public final class LegacyMigrationPlan {
    private final List<String> migratedKeys = new ArrayList<>();
    private final List<String> deprecatedKeys = new ArrayList<>();
    private final List<String> manualKeys = new ArrayList<>();

    /** 记录已自动迁移的旧字段。 */
    public void addMigratedKey(String key) {
        addIfPresent(migratedKeys, key);
    }

    /** 记录已废弃的旧字段。 */
    public void addDeprecatedKey(String key) {
        addIfPresent(deprecatedKeys, key);
    }

    /** 记录需要人工确认的旧字段。 */
    public void addManualKey(String key) {
        addIfPresent(manualKeys, key);
    }

    /** 返回自动迁移字段。 */
    public List<String> getMigratedKeys() {
        return Collections.unmodifiableList(migratedKeys);
    }

    /** 返回废弃字段。 */
    public List<String> getDeprecatedKeys() {
        return Collections.unmodifiableList(deprecatedKeys);
    }

    /** 返回人工确认字段。 */
    public List<String> getManualKeys() {
        return Collections.unmodifiableList(manualKeys);
    }

    /** 添加非空字段名。 */
    private static void addIfPresent(List<String> target, String key) {
        if (key != null && !key.trim().isEmpty()) {
            target.add(key);
        }
    }
}
