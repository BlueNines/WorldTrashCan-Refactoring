package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import pixeltech.bluenine.blworldtrashcan.config.ConfigurationSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bukkit FileConfiguration 的配置来源适配器。 */
public final class BukkitConfigurationSource implements ConfigurationSource {
    private final FileConfiguration configuration;

    /** 创建 Bukkit 配置来源。 */
    public BukkitConfigurationSource(FileConfiguration configuration) {
        this.configuration = configuration;
    }

    /** 判断配置路径是否存在。 */
    @Override
    public boolean contains(String path) {
        return configuration.contains(path);
    }

    /** 判断配置路径是否为列表。 */
    @Override
    public boolean isList(String path) {
        return configuration.isList(path);
    }

    /** 读取字符串配置。 */
    @Override
    public String getString(String path, String fallback) {
        return configuration.getString(path, fallback);
    }

    /** 读取布尔配置。 */
    @Override
    public boolean getBoolean(String path, boolean fallback) {
        return configuration.getBoolean(path, fallback);
    }

    /** 读取整数配置。 */
    @Override
    public int getInt(String path, int fallback) {
        return configuration.getInt(path, fallback);
    }

    /** 读取小数配置。 */
    @Override
    public double getDouble(String path, double fallback) {
        return configuration.getDouble(path, fallback);
    }

    /** 读取字符串列表配置。 */
    @Override
    public List<String> getStringList(String path) {
        return configuration.getStringList(path);
    }

    /** 读取映射列表配置。 */
    @Override
    public List<Map<?, ?>> getMapList(String path) {
        return configuration.getMapList(path);
    }

    /** 递归读取配置节点中的字符串映射，保留点号分隔的完整相对路径。 */
    @Override
    public Map<String, String> getStringMap(String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) {
            return java.util.Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (entry.getValue() instanceof String) {
                values.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return values;
    }
}
