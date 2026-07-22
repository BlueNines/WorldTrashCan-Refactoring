package pixeltech.worldlisttrashcan.api.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/** 处理一个一级 /wtc 副指令。 */
public interface WorldListTrashCanSubcommand {

    /** 返回帮助面板中的参数用法，不包含 /wtc 和副指令名称。 */
    String getUsage(CommandSender sender);

    /** 返回帮助面板中的本地化简短描述。 */
    String getDescription(CommandSender sender);

    /** 执行副指令；args 不包含 /wtc 和一级副指令名称。 */
    void execute(CommandSender sender, String[] args);

    /** 返回当前参数位置的补全；没有建议时返回空列表。 */
    List<String> tabComplete(CommandSender sender, String[] args);
}
