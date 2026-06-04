package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import me.croabeast.prismatic.PrismaticAPI;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 统一渲染传统颜色、RGB、渐变和可点击消息。 */
public final class RichTextRenderer {
    private RichTextRenderer() {
    }

    /** 按服务端版本渲染消息；1.16.5+ 保留 RGB，低版本降级。 */
    public static String color(String text) {
        String raw = text == null ? "" : text;
        try {
            return PrismaticAPI.legacy().colorize(raw);
        } catch (RuntimeException error) {
            return ChatColor.translateAlternateColorCodes('&', raw);
        } catch (LinkageError error) {
            return ChatColor.translateAlternateColorCodes('&', raw);
        }
    }

    /** 按接收玩家版本渲染消息；ViaVersion 玩家会自动降级。 */
    public static String color(Player player, String text) {
        String raw = text == null ? "" : text;
        try {
            return PrismaticAPI.legacy().colorize(player, raw);
        } catch (RuntimeException error) {
            return ChatColor.translateAlternateColorCodes('&', raw);
        } catch (LinkageError error) {
            return ChatColor.translateAlternateColorCodes('&', raw);
        }
    }

    /** 按命令发送者渲染消息。 */
    public static String color(CommandSender sender, String text) {
        if (sender instanceof Player) {
            return color((Player) sender, text);
        }
        return color(text);
    }

    /** 渲染普通 Bungee 组件。 */
    public static BaseComponent[] components(Player player, String text) {
        String raw = text == null ? "" : text;
        try {
            return PrismaticAPI.chatComponent(raw).compile(player);
        } catch (RuntimeException error) {
            return TextComponent.fromLegacyText(color(player, raw));
        } catch (LinkageError error) {
            return TextComponent.fromLegacyText(color(player, raw));
        }
    }

    /** 渲染带点击命令的 Bungee 组件。 */
    public static BaseComponent[] clickable(Player player, String text, String command) {
        return click(player, text, "run_command", command);
    }

    /** 渲染带建议命令的 Bungee 组件。 */
    public static BaseComponent[] suggest(Player player, String text, String command) {
        return click(player, text, "suggest_command", command);
    }

    /** 渲染带点击事件的 Bungee 组件。 */
    private static BaseComponent[] click(Player player, String text, String action, String command) {
        String raw = text == null ? "" : text;
        String clickAction = action == null ? "" : action;
        String clickCommand = command == null ? "" : command;
        try {
            return PrismaticAPI.chatComponent(raw)
                    .setClick(clickAction, clickCommand)
                    .compile(player);
        } catch (RuntimeException error) {
            return fallbackClickable(player, raw, clickAction, clickCommand);
        } catch (LinkageError error) {
            return fallbackClickable(player, raw, clickAction, clickCommand);
        }
    }

    /** 去除颜色后返回可执行命令文本。 */
    public static String stripColor(String text) {
        return ChatColor.stripColor(color(text));
    }

    /** 构造可点击消息的兜底组件。 */
    private static BaseComponent[] fallbackClickable(Player player, String text, String action, String command) {
        TextComponent component = new TextComponent(color(player, text));
        ClickEvent.Action clickAction = "suggest_command".equalsIgnoreCase(action)
                ? ClickEvent.Action.SUGGEST_COMMAND
                : ClickEvent.Action.RUN_COMMAND;
        component.setClickEvent(new ClickEvent(clickAction, command));
        return new BaseComponent[]{component};
    }
}
