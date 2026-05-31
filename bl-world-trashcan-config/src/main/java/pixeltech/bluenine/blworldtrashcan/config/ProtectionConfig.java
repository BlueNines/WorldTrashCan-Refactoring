package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 防护和简单优化功能配置。 */
public final class ProtectionConfig {
    private final RateLimitConfig chatRateLimit;
    private final CommandRateLimitConfig commandRateLimit;
    private final boolean dropProtectionEnabled;
    private final boolean removeUnpickableArrow;
    private final boolean preventFarmlandTrampling;

    /** 创建防护配置。 */
    public ProtectionConfig(RateLimitConfig chatRateLimit, CommandRateLimitConfig commandRateLimit,
                            boolean dropProtectionEnabled, boolean removeUnpickableArrow,
                            boolean preventFarmlandTrampling) {
        this.chatRateLimit = chatRateLimit;
        this.commandRateLimit = commandRateLimit;
        this.dropProtectionEnabled = dropProtectionEnabled;
        this.removeUnpickableArrow = removeUnpickableArrow;
        this.preventFarmlandTrampling = preventFarmlandTrampling;
    }

    /** 返回聊天限频配置。 */
    public RateLimitConfig getChatRateLimit() {
        return chatRateLimit;
    }

    /** 返回命令限频配置。 */
    public CommandRateLimitConfig getCommandRateLimit() {
        return commandRateLimit;
    }

    /** 判断防丢弃模式是否启用。 */
    public boolean isDropProtectionEnabled() {
        return dropProtectionEnabled;
    }

    /** 判断是否清理不可拾取箭矢。 */
    public boolean isRemoveUnpickableArrow() {
        return removeUnpickableArrow;
    }

    /** 判断是否阻止踩踏农田。 */
    public boolean isPreventFarmlandTrampling() {
        return preventFarmlandTrampling;
    }

    /** 通用限频配置。 */
    public static class RateLimitConfig {
        private final boolean enabled;
        private final long intervalMillis;
        private final String message;
        private final String command;

        /** 创建限频配置。 */
        public RateLimitConfig(boolean enabled, double intervalSeconds, String message, String command) {
            this.enabled = enabled;
            this.intervalMillis = Math.max(0L, Math.round(intervalSeconds * 1000D));
            this.message = defaultString(message);
            this.command = defaultString(command);
        }

        /** 判断限频是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 返回限频间隔毫秒。 */
        public long getIntervalMillis() {
            return intervalMillis;
        }

        /** 返回触发限频时的提示。 */
        public String getMessage() {
            return message;
        }

        /** 返回触发限频时执行的控制台命令。 */
        public String getCommand() {
            return command;
        }
    }

    /** 命令限频配置。 */
    public static final class CommandRateLimitConfig extends RateLimitConfig {
        private final Set<String> whitelist;

        /** 创建命令限频配置。 */
        public CommandRateLimitConfig(boolean enabled, double intervalSeconds, String message,
                                      String command, Set<String> whitelist) {
            super(enabled, intervalSeconds, message, command);
            this.whitelist = normalizeCommands(whitelist);
        }

        /** 判断命令是否在白名单内。 */
        public boolean isWhitelisted(String commandLine) {
            String normalized = normalizeCommand(commandLine);
            for (String prefix : whitelist) {
                if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                    return true;
                }
            }
            return false;
        }

        /** 标准化白名单命令集合。 */
        private static Set<String> normalizeCommands(Set<String> commands) {
            if (commands == null || commands.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> result = new HashSet<>();
            for (String command : commands) {
                String normalized = normalizeCommand(command);
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
            return Collections.unmodifiableSet(result);
        }
    }

    /** 返回非空字符串。 */
    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    /** 标准化命令文本。 */
    private static String normalizeCommand(String value) {
        String command = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (command.isEmpty() || command.charAt(0) == '/') {
            return command;
        }
        return "/" + command;
    }
}
