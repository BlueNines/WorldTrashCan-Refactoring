package pixeltech.bluenine.blworldtrashcan.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.FeatureRegistry;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.platform.paper.PaperPlatform;

/** Paper 1.16-1.20 产物入口。 */
public final class BLWorldTrashCanPlugin extends JavaPlugin {
    private FeatureRegistry featureRegistry;
    private ServerPlatform platform;

    /** 启动插件并注册当前产物的平台能力。 */
    @Override
    public void onEnable() {
        saveDefaultConfigs();
        this.platform = new PaperPlatform(this);
        this.featureRegistry = new FeatureRegistry();
        registerCommands();
        logCapabilities();
        featureRegistry.enableAll();
    }

    /** 禁用插件并按顺序释放功能模块。 */
    @Override
    public void onDisable() {
        if (featureRegistry != null) {
            featureRegistry.disableAll();
        }
    }

    /** 重载插件配置和功能模块。 */
    public void reloadPlugin() {
        reloadConfig();
        if (featureRegistry != null) {
            featureRegistry.reloadAll();
        }
    }

    /** 返回当前平台实现。 */
    public ServerPlatform getPlatform() {
        return platform;
    }

    /** 保存新架构默认配置文件。 */
    private void saveDefaultConfigs() {
        saveDefaultConfig();
        saveResourceIfMissing("platform.yml");
        saveResourceIfMissing("cleanup.yml");
        saveResourceIfMissing("trash.yml");
        saveResourceIfMissing("notify.yml");
        saveResourceIfMissing("entity-limits.yml");
        saveResourceIfMissing("protections.yml");
        saveResourceIfMissing("messages/message_zh.yml");
    }

    /** 仅在文件不存在时保存资源。 */
    private void saveResourceIfMissing(String path) {
        if (!getDataFolder().toPath().resolve(path).toFile().exists()) {
            saveResource(path, false);
        }
    }

    /** 注册主命令和旧命令别名。 */
    private void registerCommands() {
        BLWorldTrashCanCommand executor = new BLWorldTrashCanCommand(this);
        registerCommand("blworldtrashcan", executor);
        registerCommand("blwtc", executor);
        registerCommand("WorldListTrashCan", executor);
        registerCommand("wtc", executor);
    }

    /** 注册单个命令执行器。 */
    private void registerCommand(String name, BLWorldTrashCanCommand executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /** 输出当前产物能力报告。 */
    private void logCapabilities() {
        getLogger().info("Platform: " + platform.id());
        for (Capability capability : Capability.values()) {
            String state = platform.capabilities().has(capability) ? "enabled" : "disabled";
            getLogger().info("Capability " + capability.name().toLowerCase().replace('_', '-') + ": " + state);
        }
    }
}
