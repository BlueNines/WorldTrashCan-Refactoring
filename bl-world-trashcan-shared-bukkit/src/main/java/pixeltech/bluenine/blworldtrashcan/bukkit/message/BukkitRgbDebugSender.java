package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

/** 发送 RGB 调试消息，供真实客户端和协议客户端验收富文本兼容性。 */
public final class BukkitRgbDebugSender {
    private static final String CHAT_MESSAGE = "&#111827[&#38BDF8BLWorldTrashCan&#111827] &#D5DEE9清理通道 &#F5B82EONLINE &#64748B| &#38BDF8冷蓝 &#F5B82E鎏金 &#AAB6C5雾灰";
    private static final String ACTION_BAR_MESSAGE = "&#111827▌ &#38BDF8公共垃圾桶 &#D5DEE9正在接收物品 &#F5B82E+64 &#64748B/ &#E7C873高亮提示";
    private static final String TITLE_MESSAGE = "&#2563EB世界&#0F172A垃圾桶";
    private static final String SUBTITLE_MESSAGE = "&#334155冷蓝系统 &#64748B× &#F5B82E鎏金重点";
    private static final String BOSS_BAR_MESSAGE = "&#38BDF8清理队列 &#64748B| &#F5B82E高级蓝黄灰黑 RGB";
    private static final String GUI_TITLE = "&#0F172ABLWTC &#5AC8FA视觉样张";
    private static final String ITEM_NAME = "&#38BDF8冷蓝核心 &#64748B/ &#F5B82E鎏金重点";
    private static final String ITEM_LORE = "&#AAB6C5雾灰说明 &#64748B| &#E7C873高级 RGB 色调";

    /** 禁止实例化工具类。 */
    private BukkitRgbDebugSender() {
    }

    /** 向目标玩家发送所有 RGB 可见通道。 */
    public static void send(Plugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        sendChatActionTitle(player);
        sendBossBar(plugin, player);
        openInventory(player);
    }

    /** 仅发送聊天、ActionBar 和 Title，供不允许 GUI 遮挡的 RGB 截图矩阵使用。 */
    public static void sendChatActionTitle(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(RichTextRenderer.color(player, CHAT_MESSAGE));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, RichTextRenderer.components(player, ACTION_BAR_MESSAGE));
        player.sendTitle(RichTextRenderer.color(player, TITLE_MESSAGE), RichTextRenderer.color(player, SUBTITLE_MESSAGE), 5, 80, 20);
    }

    /** 发送带 RGB 标题的 BossBar，并在短延迟后移除。 */
    private static void sendBossBar(Plugin plugin, Player player) {
        BossBar bossBar = Bukkit.createBossBar(RichTextRenderer.color(player, BOSS_BAR_MESSAGE), BarColor.PURPLE, BarStyle.SOLID);
        bossBar.setProgress(1.0D);
        bossBar.addPlayer(player);
        try {
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                /** 移除本次 RGB 调试 BossBar。 */
                @Override
                public void run() {
                    bossBar.removeAll();
                }
            }, 100L);
        } catch (RuntimeException error) {
            plugin.getLogger().info("[DebugRGB] 当前平台不支持 Bukkit scheduler 延迟移除 BossBar，本轮调试 BossBar 将保留到玩家退出或插件重载。");
        } catch (LinkageError error) {
            plugin.getLogger().info("[DebugRGB] 当前平台不支持 Bukkit scheduler 延迟移除 BossBar，本轮调试 BossBar 将保留到玩家退出或插件重载。");
        }
    }

    /** 打开带 RGB 标题、展示名和 lore 的测试 GUI。 */
    private static void openInventory(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 9, RichTextRenderer.color(player, GUI_TITLE));
        ItemStack itemStack = new ItemStack(Material.CHEST);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta != null) {
            itemMeta.setDisplayName(RichTextRenderer.color(player, ITEM_NAME));
            itemMeta.setLore(Arrays.asList(RichTextRenderer.color(player, ITEM_LORE)));
            itemStack.setItemMeta(itemMeta);
        }
        inventory.setItem(4, itemStack);
        player.openInventory(inventory);
    }
}
