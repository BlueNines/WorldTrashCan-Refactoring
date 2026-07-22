package pixeltech.worldlisttrashcan.api.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 定义一个一级 /wtc 副指令的名称、别名和权限。 */
public final class SubcommandDefinition {
    private final String name;
    private final List<String> aliases;
    private final String permission;

    /** 创建不可变副指令定义。 */
    public SubcommandDefinition(String name, List<String> aliases, String permission) {
        this.name = normalize(Objects.requireNonNull(name, "name"));
        this.aliases = immutableAliases(Objects.requireNonNull(aliases, "aliases"));
        this.permission = Objects.requireNonNull(permission, "permission").trim();
    }

    /** 返回规范副指令名称。 */
    public String getName() {
        return name;
    }

    /** 返回不可变别名列表。 */
    public List<String> getAliases() {
        return aliases;
    }

    /** 返回权限节点；空字符串表示公开命令。 */
    public String getPermission() {
        return permission;
    }

    /** 规范化命令名称。 */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** 返回规范化后的不可变别名列表。 */
    private static List<String> immutableAliases(List<String> aliases) {
        List<String> values = new ArrayList<>();
        for (String alias : aliases) {
            values.add(normalize(Objects.requireNonNull(alias, "alias")));
        }
        return Collections.unmodifiableList(values);
    }
}
