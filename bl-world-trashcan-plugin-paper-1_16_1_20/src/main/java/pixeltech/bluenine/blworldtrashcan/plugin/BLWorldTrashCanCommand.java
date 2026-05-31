package pixeltech.bluenine.blworldtrashcan.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 新架构主命令，当前只提供架构验证命令。 */
public final class BLWorldTrashCanCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB_COMMANDS = Arrays.asList("help", "reload", "platform");
    private final BLWorldTrashCanPlugin plugin;

    /** 创建命令执行器。 */
    public BLWorldTrashCanCommand(BLWorldTrashCanPlugin plugin) {
        this.plugin = plugin;
    }

    /** 处理主命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        if ("reload".equals(sub)) {
            if (!sender.hasPermission("blworldtrashcan.admin")) {
                sender.sendMessage("§c你没有权限执行该命令。");
                return true;
            }
            plugin.reloadPlugin();
            sender.sendMessage("§aBLWorldTrashCan 已重载。");
            return true;
        }
        if ("platform".equals(sub)) {
            sendPlatform(sender);
            return true;
        }
        sendHelp(sender);
        return true;
    }

    /** 处理命令补全。 */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        return Collections.emptyList();
    }

    /** 发送帮助信息。 */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§b/blwtc help §7- 查看帮助");
        sender.sendMessage("§b/blwtc platform §7- 查看当前版本产物能力");
        sender.sendMessage("§b/blwtc reload §7- 重载插件");
    }

    /** 发送平台能力信息。 */
    private void sendPlatform(CommandSender sender) {
        sender.sendMessage("§a当前平台: §f" + plugin.getPlatform().id());
        for (Capability capability : Capability.values()) {
            String state = plugin.getPlatform().capabilities().has(capability) ? "§a启用" : "§7禁用";
            sender.sendMessage("§7- §f" + capability.name().toLowerCase().replace('_', '-') + "§7: " + state);
        }
    }

    /** 按前缀过滤补全项。 */
    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }
}
