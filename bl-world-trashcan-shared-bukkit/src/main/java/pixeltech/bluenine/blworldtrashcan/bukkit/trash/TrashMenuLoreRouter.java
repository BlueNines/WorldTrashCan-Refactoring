package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 可选的菜单 Lore 路由；默认直接保留 Bukkit 已生成的完整展示。 */
class TrashMenuLoreRouter {
    private static final TrashMenuLoreRouter BUKKIT = new TrashMenuLoreRouter();

    /** 仅在外置 PacketEvents 已启用时链接其适配类，缺少前置不影响菜单。 */
    static TrashMenuLoreRouter select(Plugin plugin, String scope) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (dependency == null || !dependency.isEnabled()) {
            return BUKKIT;
        }
        try {
            return new PacketEventsTrashMenuLoreRouter(plugin, dependency, scope);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("[" + scope + "] PacketEvents Lore 路由不可用，保留 Bukkit 展示: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return BUKKIT;
        }
    }

    /** 返回是否需要准备发包提示快照；无前置时跳过全部相关分配。 */
    boolean isPacketRoute() {
        return false;
    }

    /** 在打开菜单前登记独立会话；默认路由无需保存玩家状态。 */
    void beginView(UUID playerId, Object view, int size) {
    }

    /** 在玩家合法线程更新一个内容槽的已解析模板尾部。 */
    void updateSlot(UUID playerId, Object view, int slot, List<String> tail) {
    }

    /** 只移除指定会话，翻页关闭旧窗口不能移除新会话。 */
    void closeView(UUID playerId, Object view) {
    }

    /** 重载或关闭旧菜单时释放全部发包快照。 */
    void clearViews() {
    }

    /** 插件停用时释放可选监听；Bukkit 路由无资源需要释放。 */
    void close() {
    }

    /** 计算首个内容占位符后的模板行数，重复占位符与原展开规则一致。 */
    static int tailSize(List<String> template) {
        boolean contentSeen = false;
        int count = 0;
        for (String line : template) {
            if (line != null && "{content}".equals(line.trim())) {
                contentSeen = true;
            } else if (contentSeen) {
                count++;
            }
        }
        return count;
    }

    /** 仅移动精确匹配的模板提示块，保留其它行对象；找不到或已在尾部时不改包。 */
    static <T> List<T> moveTail(List<T> lines, List<String> tail, Function<T, String> text) {
        int size = tail.size();
        if (size == 0 || lines.size() < size) {
            return lines;
        }
        for (int start = lines.size() - size; start >= 0; start--) {
            boolean matches = true;
            for (int offset = 0; offset < size; offset++) {
                if (!tail.get(offset).equals(text.apply(lines.get(start + offset)))) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            if (start + size == lines.size()) {
                return lines;
            }
            List<T> result = new ArrayList<>(lines.size());
            result.addAll(lines.subList(0, start));
            result.addAll(lines.subList(start + size, lines.size()));
            result.addAll(lines.subList(start, start + size));
            return result;
        }
        return lines;
    }
}
