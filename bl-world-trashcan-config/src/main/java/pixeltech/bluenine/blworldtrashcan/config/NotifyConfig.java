package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 清理倒计时通知配置。 */
public final class NotifyConfig {
    private final boolean chatEnabled;
    private final String chatClickCommand;
    private final Map<Integer, String> chatMessages;
    private final ConsoleConfig console;
    private final boolean actionBarEnabled;
    private final Map<Integer, String> actionBarMessages;
    private final boolean bossBarEnabled;
    private final Map<Integer, BossBarMessage> bossBarMessages;
    private final boolean commandEnabled;
    private final Map<Integer, List<String>> commandMessages;
    private final boolean titleEnabled;
    private final Map<Integer, TitleMessage> titleMessages;
    private final boolean soundEnabled;
    private final Map<Integer, SoundMessage> soundMessages;

    /** 创建通知配置。 */
    public NotifyConfig(boolean chatEnabled, String chatClickCommand,
                        Map<Integer, String> chatMessages, ConsoleConfig console,
                        boolean actionBarEnabled,
                        Map<Integer, String> actionBarMessages, boolean bossBarEnabled,
                        Map<Integer, BossBarMessage> bossBarMessages, boolean commandEnabled,
                        Map<Integer, List<String>> commandMessages, boolean titleEnabled,
                        Map<Integer, TitleMessage> titleMessages, boolean soundEnabled,
                        Map<Integer, SoundMessage> soundMessages) {
        this.chatEnabled = chatEnabled;
        this.chatClickCommand = chatClickCommand == null ? "" : chatClickCommand;
        this.chatMessages = safeMap(chatMessages);
        this.console = console == null ? ConsoleConfig.defaults() : console;
        this.actionBarEnabled = actionBarEnabled;
        this.actionBarMessages = safeMap(actionBarMessages);
        this.bossBarEnabled = bossBarEnabled;
        this.bossBarMessages = safeMap(bossBarMessages);
        this.commandEnabled = commandEnabled;
        this.commandMessages = safeMap(commandMessages);
        this.titleEnabled = titleEnabled;
        this.titleMessages = safeMap(titleMessages);
        this.soundEnabled = soundEnabled;
        this.soundMessages = safeMap(soundMessages);
    }

    /** 判断聊天通知是否启用。 */
    public boolean isChatEnabled() {
        return chatEnabled;
    }

    /** 返回清理完成聊天点击命令。 */
    public String getChatClickCommand() {
        return chatClickCommand;
    }

    /** 返回聊天通知映射。 */
    public Map<Integer, String> getChatMessages() {
        return chatMessages;
    }

    /** 返回控制台通知配置。 */
    public ConsoleConfig getConsole() {
        return console;
    }

    /** 判断 ActionBar 通知是否启用。 */
    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    /** 返回 ActionBar 通知映射。 */
    public Map<Integer, String> getActionBarMessages() {
        return actionBarMessages;
    }

    /** 判断 BossBar 通知是否启用。 */
    public boolean isBossBarEnabled() {
        return bossBarEnabled;
    }

    /** 返回 BossBar 通知映射。 */
    public Map<Integer, BossBarMessage> getBossBarMessages() {
        return bossBarMessages;
    }

    /** 判断命令通知是否启用。 */
    public boolean isCommandEnabled() {
        return commandEnabled;
    }

    /** 返回命令通知映射。 */
    public Map<Integer, List<String>> getCommandMessages() {
        return commandMessages;
    }

    /** 判断 Title 通知是否启用。 */
    public boolean isTitleEnabled() {
        return titleEnabled;
    }

    /** 返回 Title 通知映射。 */
    public Map<Integer, TitleMessage> getTitleMessages() {
        return titleMessages;
    }

    /** 判断声音通知是否启用。 */
    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    /** 返回声音通知映射。 */
    public Map<Integer, SoundMessage> getSoundMessages() {
        return soundMessages;
    }

    /** 返回不可变映射。 */
    private static <K, V> Map<K, V> safeMap(Map<K, V> value) {
        return value == null ? Collections.<K, V>emptyMap() : Collections.unmodifiableMap(value);
    }

    /** 控制台清理日志配置。 */
    public static final class ConsoleConfig {
        private static final int MAX_ENTRIES_LIMIT = 100;
        private final boolean enabled;
        private final boolean detailsEnabled;
        private final int maxEntries;
        private final String entityFormat;
        private final String itemsFormat;
        private final String othersFormat;

        /** 创建控制台清理日志配置。 */
        public ConsoleConfig(boolean enabled, boolean detailsEnabled, int maxEntries,
                             String entityFormat, String itemsFormat, String othersFormat) {
            this.enabled = enabled;
            this.detailsEnabled = detailsEnabled;
            this.maxEntries = Math.max(1, Math.min(MAX_ENTRIES_LIMIT, maxEntries));
            this.entityFormat = safeText(entityFormat, "{name}_{type}: {count}");
            this.itemsFormat = safeText(itemsFormat, "items: {count}");
            this.othersFormat = safeText(othersFormat, "others: {count}");
        }

        /** 返回默认控制台清理日志配置。 */
        private static ConsoleConfig defaults() {
            return new ConsoleConfig(true, true, 10,
                    "{name}_{type}: {count}", "items: {count}", "others: {count}");
        }

        /** 判断控制台通知是否启用。 */
        public boolean isEnabled() {
            return enabled;
        }

        /** 判断清理完成后是否输出详细统计。 */
        public boolean isDetailsEnabled() {
            return detailsEnabled;
        }

        /** 返回最多显示的实体分组数量。 */
        public int getMaxEntries() {
            return maxEntries;
        }

        /** 返回实体明细格式。 */
        public String getEntityFormat() {
            return entityFormat;
        }

        /** 返回物品汇总格式。 */
        public String getItemsFormat() {
            return itemsFormat;
        }

        /** 返回未显示实体汇总格式。 */
        public String getOthersFormat() {
            return othersFormat;
        }

        /** 返回非空格式文本。 */
        private static String safeText(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value;
        }
    }

    /** BossBar 通知内容。 */
    public static final class BossBarMessage {
        private final String text;
        private final String style;
        private final String color;

        /** 创建 BossBar 通知。 */
        public BossBarMessage(String text, String style, String color) {
            this.text = text == null ? "" : text;
            this.style = style == null || style.trim().isEmpty() ? "SOLID" : style.trim();
            this.color = color == null || color.trim().isEmpty() ? "GREEN" : color.trim();
        }

        /** 返回 BossBar 文本。 */
        public String getText() {
            return text;
        }

        /** 返回 BossBar 样式名。 */
        public String getStyle() {
            return style;
        }

        /** 返回 BossBar 颜色名。 */
        public String getColor() {
            return color;
        }
    }

    /** Title 通知内容。 */
    public static final class TitleMessage {
        private final String title;
        private final String subtitle;

        /** 创建 Title 通知。 */
        public TitleMessage(String title, String subtitle) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
        }

        /** 返回主标题。 */
        public String getTitle() {
            return title;
        }

        /** 返回副标题。 */
        public String getSubtitle() {
            return subtitle;
        }
    }

    /** 声音通知内容。 */
    public static final class SoundMessage {
        private final String sound;
        private final float volume;
        private final float pitch;

        /** 创建声音通知。 */
        public SoundMessage(String sound, float volume, float pitch) {
            this.sound = sound == null ? "" : sound;
            this.volume = volume;
            this.pitch = pitch;
        }

        /** 返回声音名。 */
        public String getSound() {
            return sound;
        }

        /** 返回音量。 */
        public float getVolume() {
            return volume;
        }

        /** 返回音调。 */
        public float getPitch() {
            return pitch;
        }
    }
}
