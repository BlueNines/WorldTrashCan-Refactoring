package pixeltech.bluenine.blworldtrashcan.bukkit.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import pixeltech.bluenine.blworldtrashcan.storage.TrashLocation;
import pixeltech.bluenine.blworldtrashcan.storage.WorldTrashData;
import pixeltech.bluenine.blworldtrashcan.storage.WorldTrashStorage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 基于 Bukkit YAML 的世界垃圾桶数据存储。 */
public final class BukkitYamlWorldTrashStorage implements WorldTrashStorage {
    private final File file;

    /** 创建 YAML 存储。 */
    public BukkitYamlWorldTrashStorage(File file) {
        this.file = file;
    }

    /** 读取全部世界垃圾桶数据。 */
    @Override
    public Collection<WorldTrashData> loadAll() throws IOException {
        ensureFile();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<WorldTrashData> result = new ArrayList<>();
        ConfigurationSection worlds = yaml.getConfigurationSection("worlds");
        if (worlds == null) {
            return result;
        }
        for (String worldName : worlds.getKeys(false)) {
            String path = "worlds." + worldName + ".";
            result.add(new WorldTrashData(
                    worldName,
                    parseLocations(worldName, yaml, path),
                    new HashSet<>(yaml.getStringList(path + "banned-materials")),
                    yaml.getInt(path + "max-count", 0)
            ));
        }
        return result;
    }

    /** 保存单个世界垃圾桶数据。 */
    @Override
    public void save(WorldTrashData data) throws IOException {
        ensureFile();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = "worlds." + data.getWorldName() + ".";
        yaml.set(path + "max-count", data.getMaxTrashCanCount());
        yaml.set(path + "locations", formatLocations(data.getLocations()));
        yaml.set(path + "location-owners", null);
        writeLocationOwners(yaml, path, data.getLocations());
        yaml.set(path + "banned-materials", new ArrayList<>(data.getBannedMaterials()));
        yaml.save(file);
    }

    /** 确保存储文件存在。 */
    private void ensureFile() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent.getAbsolutePath());
        }
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Cannot create file: " + file.getAbsolutePath());
        }
    }

    /** 解析位置列表。 */
    private Set<TrashLocation> parseLocations(String worldName, YamlConfiguration yaml, String path) {
        Set<TrashLocation> locations = new HashSet<>();
        for (String line : yaml.getStringList(path + "locations")) {
            TrashLocation location = parseLocation(worldName, line,
                    yaml.getConfigurationSection(path + "location-owners." + locationKey(line)));
            if (location != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    /** 解析单个位置。 */
    private TrashLocation parseLocation(String worldName, String line, ConfigurationSection ownerSection) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new TrashLocation(worldName,
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    parseUuid(ownerSection == null ? "" : ownerSection.getString("uuid", "")),
                    ownerSection == null ? "" : ownerSection.getString("name", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 格式化位置列表。 */
    private List<String> formatLocations(Collection<TrashLocation> locations) {
        List<String> result = new ArrayList<>();
        for (TrashLocation location : locations) {
            result.add(location.getX() + "," + location.getY() + "," + location.getZ());
        }
        return result;
    }

    /** 写出每个新格式位置的创建者信息；旧未知位置不伪造 owner。 */
    private void writeLocationOwners(YamlConfiguration yaml, String path,
                                     Collection<TrashLocation> locations) {
        for (TrashLocation location : locations) {
            if (location.getOwnerUuid() == null && location.getOwnerName().isEmpty()) {
                continue;
            }
            String ownerPath = path + "location-owners." + locationKey(location) + ".";
            yaml.set(ownerPath + "uuid", location.getOwnerUuid() == null
                    ? "" : location.getOwnerUuid().toString());
            yaml.set(ownerPath + "name", location.getOwnerName());
        }
    }

    /** 返回不含点号的稳定坐标键。 */
    private String locationKey(TrashLocation location) {
        return location.getX() + "," + location.getY() + "," + location.getZ();
    }

    /** 规范旧位置字符串后作为 owner 映射键。 */
    private String locationKey(String line) {
        if (line == null) {
            return "";
        }
        String[] parts = line.split(",");
        if (parts.length != 3) {
            return line.trim();
        }
        return parts[0].trim() + "," + parts[1].trim() + "," + parts[2].trim();
    }

    /** 安全解析可选 UUID。 */
    private UUID parseUuid(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
