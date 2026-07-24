package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;

/** 解析公共垃圾桶按钮的内置变量和可选 PlaceholderAPI 变量。 */
final class GlobalTrashTextResolver {
    private final Plugin plugin;
    private final boolean placeholderApiAvailable;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    /** 创建变量解析器，未安装 PlaceholderAPI 时保持零依赖降级。 */
    GlobalTrashTextResolver(Plugin plugin) {
        this.plugin = plugin;
        this.placeholderApiAvailable = plugin != null
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /** 按内置变量、PAPI 变量的固定顺序解析一段玩家文本。 */
    String resolve(Player player, String text, int pageIndex, int maxPages) {
        String resolved = replaceBuiltIns(text, player == null ? "" : player.getName(),
                player == null ? "" : player.getUniqueId().toString(),
                player == null ? "" : player.getWorld().getName(), pageIndex, maxPages);
        if (!placeholderApiAvailable || player == null || resolved.indexOf('%') < 0) {
            return resolved;
        }
        try {
            return PapiBridge.resolve(player, resolved);
        } catch (LinkageError error) {
            logFailureOnce(error);
            return resolved;
        } catch (RuntimeException exception) {
            logFailureOnce(exception);
            return resolved;
        }
    }

    /** 替换与玩家和当前页有关的内置变量。 */
    static String replaceBuiltIns(String text, String playerName, String playerUuid,
                                  String worldName, int pageIndex, int maxPages) {
        String source = text == null ? "" : text;
        int safeMaxPages = Math.max(1, maxPages);
        int page = Math.min(safeMaxPages, Math.max(0, pageIndex) + 1);
        int previousPage = Math.max(1, page - 1);
        int nextPage = Math.min(safeMaxPages, page + 1);
        return source.replace("{player}", safe(playerName))
                .replace("{uuid}", safe(playerUuid))
                .replace("{world}", safe(worldName))
                .replace("{page}", String.valueOf(page))
                .replace("{max-page}", String.valueOf(safeMaxPages))
                .replace("{previous-page}", String.valueOf(previousPage))
                .replace("{next-page}", String.valueOf(nextPage));
    }

    /** 把空字符串来源统一为安全空文本。 */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 首次解析失败时输出一次警告，避免每次点击刷屏。 */
    private void logFailureOnce(Throwable throwable) {
        if (plugin != null && failureLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning("[GlobalTrash] PlaceholderAPI 变量解析失败，已保留原文: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    /** 把可选依赖引用隔离到只在 PAPI 存在时加载的内部类。 */
    private static final class PapiBridge {
        /** 使用点击玩家解析 PlaceholderAPI 变量。 */
        private static String resolve(Player player, String text) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }

        /** 阻止实例化桥接类。 */
        private PapiBridge() {
        }
    }
}
