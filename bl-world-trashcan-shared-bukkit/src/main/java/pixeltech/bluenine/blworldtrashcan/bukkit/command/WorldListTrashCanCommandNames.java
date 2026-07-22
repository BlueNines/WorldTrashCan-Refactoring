package pixeltech.bluenine.blworldtrashcan.bukkit.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 集中维护五个平台共享的主插件命令名称。 */
public final class WorldListTrashCanCommandNames {
    private static final List<String> REGULAR = Collections.unmodifiableList(Arrays.asList(
            "help", "debughelp", "reload", "platform", "clear", "global", "personal", "stats", "add",
            "dropmode", "look", "ban", "globalban"));
    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "help", "debughelp", "reload", "platform", "clear", "global", "personal", "stats", "add",
            "dropmode", "look", "ban", "globalban", "debugopen", "debugworldtrash", "debugroute",
            "debugdrop", "debugdamage", "debugstock", "debugsummary", "debugdensity", "debugnotify",
            "debugplayer", "debugrgb", "debugrgbchannels"));
    private static final Set<String> RESERVED;

    static {
        Set<String> names = new HashSet<>(ALL);
        names.add("trash");
        names.add("globaltrash");
        names.add("playertrash");
        RESERVED = Collections.unmodifiableSet(names);
    }

    /** 禁止实例化。 */
    private WorldListTrashCanCommandNames() {
    }

    /** 返回常规帮助和补全使用的内置命令。 */
    public static List<String> regular() {
        return REGULAR;
    }

    /** 返回包含调试命令的全部内置命令。 */
    public static List<String> all() {
        return ALL;
    }

    /** 返回附属插件不可覆盖的名称和别名。 */
    public static Set<String> reserved() {
        return RESERVED;
    }
}
