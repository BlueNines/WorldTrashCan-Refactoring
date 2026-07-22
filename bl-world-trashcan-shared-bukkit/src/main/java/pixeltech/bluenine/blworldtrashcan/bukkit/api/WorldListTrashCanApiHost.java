package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import pixeltech.bluenine.blworldtrashcan.bukkit.command.WorldListTrashCanCommandNames;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.worldlisttrashcan.api.audit.WorldListTrashCanAuditBridge;
import pixeltech.worldlisttrashcan.api.command.WorldListTrashCanCommandRegistry;

/** 管理主插件公开 API 的注册和生命周期。 */
public final class WorldListTrashCanApiHost implements Listener {
    private final Plugin plugin;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final DefaultWorldListTrashCanCommandRegistry commandRegistry;
    private boolean enabled;

    /** 创建稳定 API 宿主。 */
    public WorldListTrashCanApiHost(Plugin plugin, ServerPlatform platform) {
        this.plugin = plugin;
        this.auditBridge = new DefaultWorldListTrashCanAuditBridge(plugin, platform);
        this.commandRegistry = new DefaultWorldListTrashCanCommandRegistry(
                plugin, WorldListTrashCanCommandNames.reserved());
    }

    /** 注册 Bukkit 服务和附属插件禁用监听。 */
    public void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        Bukkit.getServicesManager().register(WorldListTrashCanAuditBridge.class, auditBridge,
                plugin, ServicePriority.Normal);
        Bukkit.getServicesManager().register(WorldListTrashCanCommandRegistry.class, commandRegistry,
                plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** 注销公开服务并释放全部附属对象引用。 */
    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        Bukkit.getServicesManager().unregister(WorldListTrashCanAuditBridge.class, auditBridge);
        Bukkit.getServicesManager().unregister(WorldListTrashCanCommandRegistry.class, commandRegistry);
        auditBridge.close();
        commandRegistry.close();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    /** 返回清理流程使用的审计桥。 */
    public DefaultWorldListTrashCanAuditBridge auditBridge() {
        return auditBridge;
    }

    /** 返回命令入口使用的副指令注册器。 */
    public DefaultWorldListTrashCanCommandRegistry commandRegistry() {
        return commandRegistry;
    }

    /** 在附属插件禁用后移除所有跨 ClassLoader 引用。 */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin owner = event.getPlugin();
        if (owner == plugin) {
            return;
        }
        commandRegistry.removeOwner(owner);
        auditBridge.removeOwner(owner, false);
    }
}
