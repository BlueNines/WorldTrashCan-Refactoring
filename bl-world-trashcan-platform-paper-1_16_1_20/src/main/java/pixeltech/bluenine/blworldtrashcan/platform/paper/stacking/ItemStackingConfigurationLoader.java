package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 按当前现代服务端材质生成并读取独立的逐物品堆叠配置。 */
public final class ItemStackingConfigurationLoader {
    private static final String GLOBAL_FILE = "item-stacking.yml";
    private static final String ITEMS_FILE = "item-stacking-items.yml";

    /** 工具类不允许实例化。 */
    private ItemStackingConfigurationLoader() {
    }

    /** 读取总配置；总开关关闭时不读取也不生成逐物品文件。 */
    public static ItemStackingConfig load(Plugin plugin) {
        YamlConfiguration global = loadUtf8(plugin, new File(plugin.getDataFolder(), GLOBAL_FILE));
        BukkitConfigurationSource globalSource = new BukkitConfigurationSource(global);
        if (!global.getBoolean("enabled", false)) {
            return ItemStackingConfig.load(globalSource);
        }
        YamlConfiguration items = synchronizeItemFile(plugin);
        return ItemStackingConfig.load(globalSource, new BukkitConfigurationSource(items));
    }

    /** 补齐当前服务端新增的可用 Material，绝不覆盖服主已有条目。 */
    private static YamlConfiguration synchronizeItemFile(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), ITEMS_FILE);
        YamlConfiguration current;
        if (file.isFile()) {
            try {
                current = readUtf8(file);
            } catch (IOException | InvalidConfigurationException exception) {
                plugin.getLogger().warning("[ItemStacking] " + ITEMS_FILE + " 格式错误或无法读取: "
                        + exception.getMessage() + "；原文件不会被追加或覆盖，本次使用默认逐物品规则。");
                return new YamlConfiguration();
            }
        } else {
            current = new YamlConfiguration();
        }
        Set<String> configuredMaterials = new HashSet<>();
        for (String key : current.getKeys(false)) {
            if (key != null) {
                configuredMaterials.add(key.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<Material> missing = new ArrayList<>();
        for (Material material : Material.values()) {
            if (isConfigurableItem(material) && !configuredMaterials.contains(material.name())) {
                missing.add(material);
            }
        }
        if (missing.isEmpty()) {
            return current;
        }
        try {
            appendMissingRules(file, missing, !file.isFile() || file.length() == 0L);
            plugin.getLogger().info("[ItemStacking] 已向 " + ITEMS_FILE + " 补充 "
                    + missing.size() + " 个当前版本物品配置，已有配置未被覆盖。");
            return loadUtf8(plugin, file);
        } catch (IOException exception) {
            plugin.getLogger().warning("[ItemStacking] 无法补齐 " + ITEMS_FILE + ": "
                    + exception.getMessage() + "；本次使用默认逐物品规则。");
            return current;
        }
    }

    /** 判断 Material 是否能作为当前版本的真实物品进入逐物品配置。 */
    private static boolean isConfigurableItem(Material material) {
        return material != null && material != Material.AIR && !material.isLegacy() && material.isItem();
    }

    /** 使用 UTF-8 读取 YAML，格式错误时保留原文件并返回空配置。 */
    private static YamlConfiguration loadUtf8(Plugin plugin, File file) {
        if (!file.isFile()) {
            return new YamlConfiguration();
        }
        try {
            return readUtf8(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("[ItemStacking] 读取 " + file.getName() + " 失败: "
                    + exception.getMessage() + "；原文件不会被覆盖。");
            return new YamlConfiguration();
        }
    }

    /** 使用 UTF-8 严格读取 YAML，并把异常交给调用方决定是否允许追加。 */
    private static YamlConfiguration readUtf8(File file)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    /** 以追加方式写入缺失规则，保留原文件顺序、注释和服主修改。 */
    private static void appendMissingRules(File file, List<Material> materials,
                                           boolean writeHeader) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (writeHeader) {
                writeHeader(writer);
            } else {
                writer.newLine();
                writer.write("# 以下条目由服务器版本升级后自动补充；已有条目不会被覆盖。");
                writer.newLine();
            }
            for (Material material : materials) {
                writeRule(writer, material);
            }
        }
    }

    /** 写入逐物品文件的完整中文字段说明。 */
    private static void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("# 地面掉落物逻辑堆叠的独立物品配置。");
        writer.newLine();
        writer.write("# 本文件仅在 item-stacking.yml 的 enabled: true 时生成和读取。");
        writer.newLine();
        writer.write("# 每个根节点都是当前服务端真实存在且可作为物品的 Bukkit Material。");
        writer.newLine();
        writer.write("# enabled: 是否由本插件接管该物品；false 时保留服务端原版掉落物合并行为。");
        writer.newLine();
        writer.write("# max-stack-size: 单个地面实体最多代表的实际数量；-1 继承 item-stacking.yml 的全局上限，其它值最小为 2。");
        writer.newLine();
        writer.write("# display-name: 地面悬浮名和个人桶回收提示共用；default 或空值使用 item-stacking.yml 所选语言的内置名称。");
        writer.newLine();
        writer.write("# 物品自身已有自定义名称时永远优先，不会被 display-name 覆盖。");
        writer.newLine();
        writer.write("# 修改后执行 /wtc reload 生效；新增版本物品会自动追加，已有值不会被改写。");
        writer.newLine();
        writer.newLine();
    }

    /** 写入单个 Material 的默认完整规则。 */
    private static void writeRule(BufferedWriter writer, Material material) throws IOException {
        writer.write(material.name());
        writer.write(':');
        writer.newLine();
        writer.write("  enabled: true");
        writer.newLine();
        writer.write("  max-stack-size: -1");
        writer.newLine();
        writer.write("  display-name: \"default\"");
        writer.newLine();
    }
}
