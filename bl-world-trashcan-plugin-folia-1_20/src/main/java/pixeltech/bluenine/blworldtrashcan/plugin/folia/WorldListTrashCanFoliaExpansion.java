package pixeltech.bluenine.blworldtrashcan.plugin.folia;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI 变量扩展，兼容旧 %Wtc_ClearTime% 写法。 */
public final class WorldListTrashCanFoliaExpansion extends PlaceholderExpansion {
    private final WorldListTrashCanFoliaPlugin plugin;

    /** 创建 PAPI 扩展。 */
    public WorldListTrashCanFoliaExpansion(WorldListTrashCanFoliaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重载后保持注册。 */
    @Override
    public boolean persist() {
        return true;
    }

    /** 返回作者。 */
    @Override
    public String getAuthor() {
        return "BlueNine";
    }

    /** 返回兼容旧版的变量前缀。 */
    @Override
    public String getIdentifier() {
        return "Wtc";
    }

    /** 返回扩展版本。 */
    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 处理变量请求。 */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params != null && params.equalsIgnoreCase("ClearTime")) {
            return String.valueOf(plugin.getRemainingClearSeconds());
        }
        return "";
    }
}
