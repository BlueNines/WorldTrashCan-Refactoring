package pixeltech.bluenine.blworldtrashcan.config;

import java.util.List;
import java.util.Map;

/** 配置读取来源，隔离 Bukkit FileConfiguration 和核心配置解析。 */
public interface ConfigurationSource {
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
