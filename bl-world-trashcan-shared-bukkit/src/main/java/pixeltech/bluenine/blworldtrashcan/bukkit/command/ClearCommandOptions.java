package pixeltech.bluenine.blworldtrashcan.bukkit.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 解析手动扫地命令的可选参数。 */
public final class ClearCommandOptions {
    private static final List<String> BOOLEAN_VALUES = Collections.unmodifiableList(Arrays.asList("true", "false"));

    /** 禁止实例化工具类。 */
    private ClearCommandOptions() {
    }

    /** 返回 clear 命令第二参数补全值。 */
    public static List<String> booleanValues() {
        return BOOLEAN_VALUES;
    }

    /** 解析是否忽略 guards；未填写时默认忽略，非法参数返回 null。 */
    public static Boolean parseIgnoreGuards(String[] args) {
        if (args == null || args.length < 2) {
            return Boolean.TRUE;
        }
        String value = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
