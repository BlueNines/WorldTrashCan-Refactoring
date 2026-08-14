package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import me.croabeast.prismatic.PrismaticAPI;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 统一渲染传统颜色、RGB、渐变和可点击消息。 */
public final class RichTextRenderer {
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final char LITERAL_AMPERSAND_PLACEHOLDER = '\uE000';
    private static final char[] LEGACY_COLOR_CODES = "0123456789abcdef".toCharArray();
    private static final int[][] LEGACY_COLOR_RGB = {
            {0, 0, 0},
            {0, 0, 170},
            {0, 170, 0},
            {0, 170, 170},
            {170, 0, 0},
            {170, 0, 170},
            {255, 170, 0},
            {170, 170, 170},
            {85, 85, 85},
            {85, 85, 255},
            {85, 255, 85},
            {85, 255, 255},
            {255, 85, 85},
            {255, 85, 255},
            {255, 255, 85},
            {255, 255, 255},
    };

    /** 禁止实例化工具类。 */
    private RichTextRenderer() {
    }

    /** 按服务端版本渲染消息；1.16.5+ 保留 RGB，低版本降级。 */
    public static String color(String text) {
        String raw = text == null ? "" : text;
        try {
            return PrismaticAPI.legacy().colorize(raw);
        } catch (RuntimeException error) {
            return legacyFallback(raw);
        } catch (LinkageError error) {
            return legacyFallback(raw);
        }
    }

    /** 按接收玩家版本渲染消息；ViaVersion 玩家会自动降级。 */
    public static String color(Player player, String text) {
        String raw = text == null ? "" : text;
        try {
            return PrismaticAPI.legacy().colorize(player, raw);
        } catch (RuntimeException error) {
            return legacyFallback(raw);
        } catch (LinkageError error) {
            return legacyFallback(raw);
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
        BaseComponent[] components = click(player, text, "suggest_command", command);
        restoreLiteralAmpersands(components);
        return components;
    }

    /** 临时保护变量值中的字面量 &，组件颜色编译完成后会自动还原。 */
    public static String escapeLiteralAmpersands(String value) {
        return value == null ? "" : value.replace('&', LITERAL_AMPERSAND_PLACEHOLDER);
    }

    /** 渲染带点击事件的 Bungee 组件。 */
    private static BaseComponent[] click(Player player, String text, String action, String command) {
        String raw = text == null ? "" : text;
        String clickAction = action == null ? "" : action;
        String clickCommand = command == null ? "" : command;
        return withClickEvent(components(player, raw), clickAction, clickCommand);
    }

    /** 去除颜色后返回可执行命令文本。 */
    public static String stripColor(String text) {
        return ChatColor.stripColor(color(text));
    }

    /** 在 PrismaticAPI 不可用时，把 RGB 近似降级为传统颜色并继续兼容 &a 写法。 */
    private static String legacyFallback(String raw) {
        return ChatColor.translateAlternateColorCodes('&', downgradeHexColors(raw));
    }

    /** 把 &#RRGGBB 颜色转换为最接近的传统 16 色代码。 */
    private static String downgradeHexColors(String raw) {
        Matcher matcher = HEX_COLOR_PATTERN.matcher(raw == null ? "" : raw);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("&" + nearestLegacyColor(matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 计算 RGB 最接近的传统颜色代码。 */
    private static char nearestLegacyColor(String hex) {
        int red = Integer.parseInt(hex.substring(0, 2), 16);
        int green = Integer.parseInt(hex.substring(2, 4), 16);
        int blue = Integer.parseInt(hex.substring(4, 6), 16);
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < LEGACY_COLOR_RGB.length; index++) {
            int[] color = LEGACY_COLOR_RGB[index];
            int redDistance = red - color[0];
            int greenDistance = green - color[1];
            int blueDistance = blue - color[2];
            int distance = redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return LEGACY_COLOR_CODES[bestIndex];
    }

    /** 给已经渲染完成的组件补上点击事件。 */
    private static BaseComponent[] withClickEvent(BaseComponent[] components, String action, String command) {
        ClickEvent.Action clickAction = "suggest_command".equalsIgnoreCase(action)
                ? ClickEvent.Action.SUGGEST_COMMAND
                : ClickEvent.Action.RUN_COMMAND;
        ClickEvent clickEvent = new ClickEvent(clickAction, command == null ? "" : command);
        BaseComponent[] result = components == null ? new BaseComponent[0] : components;
        for (BaseComponent component : result) {
            applyClickEvent(component, clickEvent);
        }
        if (result.length == 0) {
            TextComponent component = new TextComponent("");
            component.setClickEvent(clickEvent);
            return new BaseComponent[]{component};
        }
        return result;
    }

    /** 递归给组件和 extra 子组件设置点击事件。 */
    private static void applyClickEvent(BaseComponent component, ClickEvent clickEvent) {
        if (component == null) {
            return;
        }
        component.setClickEvent(clickEvent);
        List<BaseComponent> extra = component.getExtra();
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (BaseComponent child : extra) {
            applyClickEvent(child, clickEvent);
        }
    }

    /** 还原组件树中为配置文本保留的字面量 &。 */
    private static void restoreLiteralAmpersands(BaseComponent[] components) {
        if (components == null) {
            return;
        }
        for (BaseComponent component : components) {
            restoreLiteralAmpersands(component);
        }
    }

    /** 递归还原单个文本组件及其子组件中的字面量 &。 */
    private static void restoreLiteralAmpersands(BaseComponent component) {
        if (component == null) {
            return;
        }
        if (component instanceof TextComponent) {
            TextComponent text = (TextComponent) component;
            text.setText(text.getText().replace(LITERAL_AMPERSAND_PLACEHOLDER, '&'));
        }
        List<BaseComponent> extra = component.getExtra();
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (BaseComponent child : extra) {
            restoreLiteralAmpersands(child);
        }
    }

}
