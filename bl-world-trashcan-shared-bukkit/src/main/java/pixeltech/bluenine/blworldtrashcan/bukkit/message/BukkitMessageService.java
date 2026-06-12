package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 读取并格式化插件消息文件。 */
public final class BukkitMessageService {
    private static final String DEFAULT_FILE = "message_zh.yml";
    private final JavaPlugin plugin;
    private YamlConfiguration bundledDefaultMessages = new YamlConfiguration();
    private YamlConfiguration fallbackMessages = new YamlConfiguration();
    private YamlConfiguration activeMessages = new YamlConfiguration();
    private String activeFile = DEFAULT_FILE;

    /** 创建消息服务。 */
    public BukkitMessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 按配置语言重新加载消息文件。 */
    public void reload(String languageFile) {
        activeFile = normalizeLanguageFile(languageFile);
        saveBundledMessage(DEFAULT_FILE);
        saveBundledMessage(activeFile);
        bundledDefaultMessages = loadBundled(DEFAULT_FILE);
        fallbackMessages = loadResourceOrFile(DEFAULT_FILE);
        activeMessages = DEFAULT_FILE.equals(activeFile) ? fallbackMessages : loadResourceOrFile(activeFile);
        plugin.getLogger().info("[Message] 已加载语言文件: messages/" + activeFile);
    }

    /** 返回格式化后的单行消息。 */
    public String text(String key, String fallback, String... replacements) {
        return text(null, key, fallback, replacements);
    }

    /** 按玩家版本返回格式化后的单行消息。 */
    public String text(Player player, String key, String fallback, String... replacements) {
        String raw = activeMessages.getString(key);
        if (raw == null) {
            raw = fallbackMessages.getString(key);
        }
        if (raw == null) {
            raw = bundledDefaultMessages.getString(key, fallback);
        }
        return color(player, applyPlaceholders(raw, replacements));
    }

    /** 返回格式化后的多行消息。 */
    public List<String> list(String key, List<String> fallback, String... replacements) {
        return list(null, key, fallback, replacements);
    }

    /** 按玩家版本返回格式化后的多行消息。 */
    public List<String> list(Player player, String key, List<String> fallback, String... replacements) {
        List<String> raw = activeMessages.getStringList(key);
        if (raw.isEmpty()) {
            raw = fallbackMessages.getStringList(key);
        }
        if (raw.isEmpty()) {
            raw = bundledDefaultMessages.getStringList(key);
        }
        if (raw.isEmpty()) {
            raw = fallback == null ? Collections.<String>emptyList() : fallback;
        }
        raw = normalizeList(key, raw);
        List<String> result = new ArrayList<>();
        for (String line : raw) {
            result.add(color(player, applyPlaceholders(line, replacements)));
        }
        return result;
    }

    /** 返回当前启用的语言文件名。 */
    public String getActiveFile() {
        return activeFile;
    }

    /** 加载 jar 内自带语言资源。 */
    private YamlConfiguration loadBundled(String fileName) {
        InputStream stream = plugin.getResource("messages/" + fileName);
        if (stream == null) {
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception exception) {
            plugin.getLogger().warning("[Message] 读取内置语言资源失败 messages/" + fileName + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    /** 保存 jar 内自带的语言文件。 */
    private void saveBundledMessage(String fileName) {
        String path = "messages/" + fileName;
        if (plugin.getResource(path) == null) {
            return;
        }
        File target = new File(plugin.getDataFolder(), path);
        if (!target.exists()) {
            plugin.saveResource(path, false);
        }
    }

    /** 加载外部文件，外部不存在时加载 jar 内资源。 */
    private YamlConfiguration loadResourceOrFile(String fileName) {
        File file = new File(plugin.getDataFolder(), "messages/" + fileName);
        if (file.exists()) {
            try (InputStreamReader reader = new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            } catch (Exception exception) {
                plugin.getLogger().warning("[Message] 读取语言文件失败 messages/" + fileName + ": " + exception.getMessage());
            }
        }
        InputStream stream = plugin.getResource("messages/" + fileName);
        if (stream == null) {
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception exception) {
            plugin.getLogger().warning("[Message] 读取默认语言资源失败 messages/" + fileName + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    /** 规范化语言文件名，避免配置写入目录穿越。 */
    private String normalizeLanguageFile(String languageFile) {
        String value = languageFile == null ? "" : languageFile.trim().replace('\\', '/');
        if (value.isEmpty()) {
            return DEFAULT_FILE;
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (!value.endsWith(".yml") && !value.endsWith(".yaml")) {
            return DEFAULT_FILE;
        }
        return value;
    }

    /** 修正旧默认语言文件中把调试命令直接放进主帮助面板的问题。 */
    private List<String> normalizeList(String key, List<String> raw) {
        if (!"command.help".equals(key) || !containsLegacyDebugHelp(raw)) {
            return raw;
        }
        List<String> bundled = bundledDefaultMessages.getStringList(key);
        return bundled.isEmpty() ? raw : bundled;
    }

    /** 判断 help 列表是否仍是旧版调试命令堆叠写法。 */
    private boolean containsLegacyDebugHelp(List<String> raw) {
        for (String line : raw) {
            if (line.contains("/blwtc debugopen")
                    || line.contains("/blwtc debugworldtrash")
                    || line.contains("/blwtc debugroute")
                    || line.contains("/blwtc debugdrop")
                    || line.contains("/blwtc debugdamage")
                    || line.contains("/blwtc debugstock")
                    || line.contains("/blwtc debugsummary")
                    || line.contains("/blwtc debugplayer")
                    || line.contains("/blwtc debugrgbchannels")) {
                return true;
            }
        }
        return false;
    }

    /** 替换内置占位符和调用方传入的占位符。 */
    private String applyPlaceholders(String message, String... replacements) {
        String result = message == null ? "" : message;
        result = result.replace("{prefix}", prefix());
        if (replacements == null) {
            return result;
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            result = result.replace(replacements[index], replacements[index + 1] == null ? "" : replacements[index + 1]);
        }
        return result;
    }

    /** 返回消息前缀。 */
    private String prefix() {
        String raw = activeMessages.getString("prefix");
        if (raw == null) {
            raw = fallbackMessages.getString("prefix");
        }
        if (raw == null) {
            raw = bundledDefaultMessages.getString("prefix", "&7[&bBLWorldTrashCan&7] ");
        }
        return raw;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return RichTextRenderer.color(text);
    }

    /** 按玩家版本转换颜色代码。 */
    private String color(Player player, String text) {
        if (player == null) {
            return color(text);
        }
        return RichTextRenderer.color(player, text);
    }
}
