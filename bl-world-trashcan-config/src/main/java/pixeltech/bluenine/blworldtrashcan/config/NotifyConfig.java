package pixeltech.bluenine.blworldtrashcan.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 清理倒计时通知配置。 */
public final class NotifyConfig {
    private final boolean chatEnabled;
    private final boolean chatConsoleLog;
    private final String chatClickCommand;
    private final Map<Integer, String> chatMessages;
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
    public NotifyConfig(boolean chatEnabled, boolean chatConsoleLog, String chatClickCommand,
                        Map<Integer, String> chatMessages, boolean actionBarEnabled,
                        Map<Integer, String> actionBarMessages, boolean bossBarEnabled,
                        Map<Integer, BossBarMessage> bossBarMessages, boolean commandEnabled,
                        Map<Integer, List<String>> commandMessages, boolean titleEnabled,
                        Map<Integer, TitleMessage> titleMessages, boolean soundEnabled,
                        Map<Integer, SoundMessage> soundMessages) {
        this.chatEnabled = chatEnabled;
        this.chatConsoleLog = chatConsoleLog;
        this.chatClickCommand = chatClickCommand == null ? "" : chatClickCommand;
        this.chatMessages = safeMap(chatMessages);
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

    /** 判断聊天通知是否输出到控制台。 */
    public boolean isChatConsoleLog() {
        return chatConsoleLog;
    }

    /** 返回清理完成聊天点击命令。 */
    public String getChatClickCommand() {
        return chatClickCommand;
    }

    /** 返回聊天通知映射。 */
    public Map<Integer, String> getChatMessages() {
        return chatMessages;
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
