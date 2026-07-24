package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 执行公共垃圾桶 actions 按钮的三种轻量动作。 */
final class GlobalTrashActionExecutor {
    private static final String CONSOLE = "[console]";
    private static final String COMMAND = "[command]";
    private static final String MESSAGE = "[message]";

    private final Plugin plugin;
    private final ServerPlatform platform;
    private final GlobalTrashTextResolver textResolver;
    private final Set<String> warnedActions = ConcurrentHashMap.newKeySet();

    /** 创建动作执行器。 */
    GlobalTrashActionExecutor(Plugin plugin, ServerPlatform platform, GlobalTrashTextResolver textResolver) {
        this.plugin = plugin;
        this.platform = platform;
        this.textResolver = textResolver;
    }

    /** 按配置顺序分派按钮动作。 */
    void execute(Player player, List<String> actions, int pageIndex, int maxPages) {
        if (player == null || actions == null || actions.isEmpty()) {
            return;
        }
        for (String action : actions) {
            executeOne(player, action, pageIndex, maxPages);
        }
    }

    /** 检查动作列表并对无效前缀输出一次配置警告。 */
    void validate(char symbol, List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            plugin.getLogger().warning("[GlobalTrash] actions 布局字符 '" + symbol
                    + "' 没有配置 actions，按钮将保持无操作。");
            return;
        }
        for (String action : actions) {
            if (!isSupported(action)) {
                warnUnknown(action, "布局字符 '" + symbol + "'");
            }
        }
    }

    /** 执行单条动作，未知动作只跳过并警告。 */
    private void executeOne(Player player, String action, int pageIndex, int maxPages) {
        String parsed = textResolver.resolve(player, action, pageIndex, maxPages).trim();
        String normalized = parsed.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(CONSOLE)) {
            dispatchConsole(commandBody(parsed, CONSOLE));
            return;
        }
        if (normalized.startsWith(COMMAND)) {
            String command = commandBody(parsed, COMMAND);
            if (!command.isEmpty()) {
                player.performCommand(command);
            }
            return;
        }
        if (normalized.startsWith(MESSAGE)) {
            player.sendMessage(RichTextRenderer.color(player, parsed.substring(MESSAGE.length()).trim()));
            return;
        }
        warnUnknown(action, "运行期");
    }

    /** 在普通端直接执行控制台命令，Folia 端切到全局区域调度器。 */
    private void dispatchConsole(final String command) {
        if (command.isEmpty()) {
            return;
        }
        Runnable dispatch = new Runnable() {
            /** 以控制台身份执行已经去除斜杠的命令。 */
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        };
        if (platform != null && platform.capabilities().has(Capability.SCHEDULER_REGION)) {
            platform.scheduler().runLater(dispatch, 1L);
            return;
        }
        dispatch.run();
    }

    /** 返回去掉动作前缀和可选开头斜杠后的命令正文。 */
    private String commandBody(String parsed, String prefix) {
        String command = parsed.substring(prefix.length()).trim();
        return command.startsWith("/") ? command.substring(1).trim() : command;
    }

    /** 判断动作是否使用支持的三种前缀。 */
    static boolean isSupported(String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(CONSOLE)
                || normalized.startsWith(COMMAND)
                || normalized.startsWith(MESSAGE);
    }

    /** 对同一条未知动作只记录一次，避免点击刷屏。 */
    private void warnUnknown(String action, String source) {
        String value = action == null ? "null" : action.trim();
        if (warnedActions.add(source + '\n' + value)) {
            plugin.getLogger().warning("[GlobalTrash] " + source + " 存在未知 action，已跳过: " + value);
        }
    }
}
