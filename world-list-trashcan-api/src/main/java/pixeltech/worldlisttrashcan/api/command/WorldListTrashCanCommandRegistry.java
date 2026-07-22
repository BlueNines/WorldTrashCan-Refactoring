package pixeltech.worldlisttrashcan.api.command;

import org.bukkit.plugin.Plugin;

/** 允许附属插件注册一级 /wtc 副指令。 */
public interface WorldListTrashCanCommandRegistry {
    int API_VERSION = 1;

    /** 返回当前副指令 API 版本。 */
    int getApiVersion();

    /** 注册副指令并返回可重复关闭的注册句柄。 */
    SubcommandRegistration register(Plugin owner, SubcommandDefinition definition,
                                    WorldListTrashCanSubcommand subcommand);
}
