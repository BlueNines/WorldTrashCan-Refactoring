package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.FileConfiguration;
import pixeltech.bluenine.blworldtrashcan.config.ConfigurationSource;

import java.util.List;
import java.util.Map;

/** Bukkit FileConfiguration 的配置来源适配器。 */
public final class BukkitConfigurationSource implements ConfigurationSource {
    private final FileConfiguration configuration;

    /** 创建 Bukkit 配置来源。 */
    public BukkitConfigurationSource(FileConfiguration configuration) {
        this.configuration = configuration;
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
}
