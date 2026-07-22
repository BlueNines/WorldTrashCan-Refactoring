package pixeltech.bluenine.blworldtrashcan.bukkit.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanCommandRegistry;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 五套命令入口共用的附属副指令委托。 */
public final class AddonCommandDispatcher {
    private final DefaultWorldListTrashCanCommandRegistry registry;
    private final BukkitMessageService messages;

    /** 创建附属命令委托。 */
    public AddonCommandDispatcher(DefaultWorldListTrashCanCommandRegistry registry, BukkitMessageService messages) {
        this.registry = registry;
        this.messages = messages;
    }

    /** 尝试执行附属副指令；命中名称时返回 true。 */
    public boolean dispatch(CommandSender sender, String subcommand, String[] args) {
        DefaultWorldListTrashCanCommandRegistry.DispatchResult result = registry.dispatch(sender, subcommand, args);
        if (result == DefaultWorldListTrashCanCommandRegistry.DispatchResult.NOT_FOUND) {
            return false;
        }
        if (result == DefaultWorldListTrashCanCommandRegistry.DispatchResult.NO_PERMISSION) {
            sender.sendMessage(messages.text(player(sender), "command.no-permission",
                    "{prefix}&c你没有权限执行该命令。"));
        } else if (result == DefaultWorldListTrashCanCommandRegistry.DispatchResult.FAILED) {
            sender.sendMessage(messages.text(player(sender), "command.addon-failed",
                    "{prefix}&c附属插件命令执行失败，请查看后台日志。"));
        }
        return true;
    }

    /** 合并内置命令和当前发送者可见的附属副指令。 */
    public List<String> completeFirstLevel(CommandSender sender, List<String> builtins, String input) {
        List<String> values = new ArrayList<>(builtins);
        values.addAll(registry.visibleNames(sender));
        return filter(values, input);
    }

    /** 返回附属副指令参数补全。 */
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> values = registry.tabComplete(sender, args);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        String input = args == null || args.length == 0 ? "" : args[args.length - 1];
        return filter(values, input);
    }

    /** 把附属副指令追加到常规帮助面板。 */
    public void sendHelp(CommandSender sender) {
        for (DefaultWorldListTrashCanCommandRegistry.HelpEntry entry : registry.helpEntries(sender)) {
            String usage = entry.getUsage().trim();
            String raw = "&b/wtc " + entry.getName()
                    + (usage.isEmpty() ? "" : " " + usage)
                    + " &7- " + entry.getDescription();
            sender.sendMessage(render(sender, raw));
        }
    }

    /** 按输入前缀过滤补全候选。 */
    private List<String> filter(List<String> values, String input) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(value);
            }
        }
        return result;
    }

    /** 返回玩家发送者；控制台返回 null。 */
    private Player player(CommandSender sender) {
        return sender instanceof Player ? (Player) sender : null;
    }

    /** 按发送者客户端版本渲染附属帮助。 */
    private String render(CommandSender sender, String raw) {
        Player player = player(sender);
        return player == null ? messages.render(raw) : messages.render(player, raw);
    }
}
