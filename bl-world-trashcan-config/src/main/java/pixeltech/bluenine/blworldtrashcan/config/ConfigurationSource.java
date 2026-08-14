package pixeltech.bluenine.blworldtrashcan.config;

import java.util.List;
import java.util.Map;

/** 配置读取来源，隔离 Bukkit FileConfiguration 和核心配置解析。 */
public interface ConfigurationSource {
    /** 判断配置路径是否存在。 */
    boolean contains(String path);

    /** 判断配置路径是否为列表；不支持类型检测的来源默认返回 false。 */
    default boolean isList(String path) {
        return false;
    }

    /** 读取字符串。 */
    String getString(String path, String fallback);

    /** 读取布尔值。 */
    boolean getBoolean(String path, boolean fallback);

    /** 读取整数。 */
    int getInt(String path, int fallback);

    /** 读取小数。 */
    double getDouble(String path, double fallback);

    /** 读取字符串列表。 */
    List<String> getStringList(String path);

    /** 读取映射列表。 */
    List<Map<?, ?>> getMapList(String path);
}
