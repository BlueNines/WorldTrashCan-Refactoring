package pixeltech.bluenine.blworldtrashcan.plugin.folia;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 新架构主命令，当前只提供架构验证命令。 */
public final class BLWorldTrashCanFoliaCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB_COMMANDS = Arrays.asList("help", "reload", "platform", "clear", "global", "personal", "stats", "add",
            "dropmode", "look", "ban", "globalban", "debugopen", "debugworldtrash", "debugroute", "debugdrop", "debugdamage", "debugstock", "debugsummary", "debugplayer");
    private final BLWorldTrashCanFoliaPlugin plugin;

    /** 创建命令执行器。 */
    public BLWorldTrashCanFoliaCommand(BLWorldTrashCanFoliaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 返回格式化命令消息。 */
    private String message(String key, String fallback, String... replacements) {
        return plugin.messages().text(key, fallback, replacements);
    }

    /** 发送格式化消息列表。 */
    private void sendMessageList(CommandSender sender, String key, List<String> fallback) {
        for (String line : plugin.messages().list(key, fallback)) {
            sender.sendMessage(line);
        }
    }
    /** 处理主命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        if ("reload".equals(sub)) {
            if (!hasAdminPermission(sender)) {
                sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
                return true;
            }
            plugin.reloadPlugin();
            sender.sendMessage(message("command.reload-success", "{prefix}&aBLWorldTrashCan 已重载。"));
            return true;
        }
        if ("platform".equals(sub)) {
            sendPlatform(sender);
            return true;
        }
        if ("clear".equals(sub)) {
            if (!hasAdminPermission(sender)) {
                sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
                return true;
            }
            if (!plugin.isCleanupScanSupported()) {
                sender.sendMessage(message("command.folia-clear-unsupported", "{prefix}&c当前 Folia 产物尚未启用 region-safe 清理，已拒绝执行世界实体扫描。"));
                return true;
            }
            if (!plugin.startCleanupNow()) {
                sender.sendMessage(message("command.folia-clear-running", "{prefix}&e上一轮 Folia region-safe 清理仍在运行，本次没有重复启动。"));
                return true;
            }
            sender.sendMessage(message("command.folia-clear-started", "{prefix}&a已启动 Folia region-safe 清理；完成后请查看后台 [FoliaCleanup] 日志或执行 /blwtc stats。"));
            return true;
        }
        if ("global".equals(sub) || "trash".equals(sub) || "globaltrash".equals(sub)) {
            if (!requirePlayer(sender)) {
                return true;
            }
            if (!hasAnyPermission(sender, "blworldtrashcan.global.open", "WorldListTrashCan.GlobalTrashOpen")) {
                sender.sendMessage(message("command.no-global-open-permission", "{prefix}&c你没有权限打开公共垃圾桶。"));
                return true;
            }
            plugin.openGlobalTrash((Player) sender);
            return true;
        }
        if ("personal".equals(sub) || "playertrash".equals(sub)) {
            if (!requirePlayer(sender)) {
                return true;
            }
            if (!hasAnyPermission(sender, "blworldtrashcan.personal.open", "WorldListTrashCan.PlayerTrash")) {
                sender.sendMessage(message("command.no-personal-open-permission", "{prefix}&c你没有权限打开个人垃圾桶。"));
                return true;
            }
            plugin.openPersonalTrash((Player) sender);
            return true;
        }
        if ("stats".equals(sub)) {
            sendStats(sender);
            return true;
        }
        if ("dropmode".equals(sub)) {
            handleDropMode(sender);
            return true;
        }
        if ("look".equals(sub)) {
            handleLook(sender);
            return true;
        }
        if ("ban".equals(sub)) {
            handleWorldBan(sender);
            return true;
        }
        if ("globalban".equals(sub)) {
            handleGlobalBan(sender);
            return true;
        }
        if ("add".equals(sub)) {
            handleAdd(sender, args);
            return true;
        }
        if ("debugopen".equals(sub)) {
            handleDebugOpen(sender, args);
            return true;
        }
        if ("debugworldtrash".equals(sub)) {
            handleDebugWorldTrash(sender, args);
            return true;
        }
        if ("debugroute".equals(sub)) {
            handleDebugRoute(sender, args);
            return true;
        }
        if ("debugdrop".equals(sub)) {
            handleDebugDrop(sender, args);
            return true;
        }
        if ("debugdamage".equals(sub)) {
            handleDebugDamage(sender, args);
            return true;
        }
        if ("debugstock".equals(sub)) {
            handleDebugStock(sender);
            return true;
        }
        if ("debugsummary".equals(sub)) {
            handleDebugSummary(sender, args);
            return true;
        }
        if ("debugplayer".equals(sub)) {
            handleDebugPlayer(sender, args);
            return true;
        }
        sendHelp(sender);
        return true;
    }

    /** 处理命令补全。 */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        if (args.length == 3 && "debugroute".equalsIgnoreCase(args[0])) {
            return filter(Arrays.asList("world", "personal", "global"), args[2]);
        }
        return Collections.emptyList();
    }

    /** 发送帮助。 */
    private void sendHelp(CommandSender sender) {
        sendMessageList(sender, "command.help", Arrays.asList(
                "&b/blwtc help &7- 查看帮助",
                "&b/blwtc platform &7- 查看当前版本产物能力",
                "&b/blwtc clear &7- 立即执行一次后台清理",
                "&b/blwtc global &7- 打开公共垃圾桶",
                "&b/blwtc personal &7- 打开个人垃圾桶",
                "&b/blwtc dropmode &7- 切换防丢弃模式",
                "&b/blwtc look &7- 查询手持物品和右键实体类型",
                "&b/blwtc ban &7- 打开当前世界垃圾桶物品黑名单",
                "&b/blwtc globalban &7- 打开公共垃圾桶物品黑名单",
                "&b/blwtc stats &7- 查看清理和垃圾桶统计",
                "&b/blwtc add <数量> &7- 增加当前世界可创建的世界垃圾桶数量",
                "&b/blwtc add <世界名> <数量> &7- 后台增加指定世界可创建的世界垃圾桶数量",
                "&b/blwtc debugopen <玩家> <global|personal> &7- 后台测试打开 GUI",
                "&b/blwtc debugworldtrash <玩家> &7- 后台创建并登记测试世界垃圾桶",
                "&b/blwtc debugroute <玩家> <world|personal|global> <Material> <数量> &7- 后台测试指定路由",
                "&b/blwtc debugdrop <玩家> <Material> <数量> [owner] &7- 后台生成测试掉落物",
                "&b/blwtc debugdamage <玩家> <Material> <数量> &7- 后台测试仙人掌/岩浆损坏回收",
                "&b/blwtc debugstock &7- 后台查看当前垃圾桶库存",
                "&b/blwtc debugsummary <玩家> &7- 查看后台测试摘要",
                "&b/blwtc debugplayer <玩家> <dropmode|look|ban|globalban> &7- 后台测试玩家入口",
                "&b/blwtc reload &7- 重载插件"));
    }

    /** 发送运行统计。 */
    private void sendStats(CommandSender sender) {
        CleanupFeature.CleanupStats stats = plugin.getLastCleanupStats();
        sender.sendMessage(message("command.stats.header", "{prefix}&a清理统计:"));
        sender.sendMessage(message("command.stats.worlds", "&7- &f世界数: &a{worlds}", "{worlds}", String.valueOf(stats.getWorlds())));
        sender.sendMessage(message("command.stats.routed", "&7- &f回收物品: &a{routed} &7(世界 {world}, 个人 {personal}, 公共 {global})",
                "{routed}", String.valueOf(stats.getItemsRouted()),
                "{world}", String.valueOf(stats.getItemsToWorldTrash()),
                "{personal}", String.valueOf(stats.getItemsToPersonalTrash()),
                "{global}", String.valueOf(stats.getItemsToGlobalTrash())));
        sender.sendMessage(message("command.stats.removed-items", "&7- &f删除物品: &a{items}", "{items}", String.valueOf(stats.getItemsRemoved())));
        sender.sendMessage(message("command.stats.removed-entities", "&7- &f删除实体: &a{entities}", "{entities}", String.valueOf(stats.getEntitiesRemoved())));
        sender.sendMessage(message("command.stats.global-pages", "&7- &f公共垃圾桶页数: &a{pages}", "{pages}", String.valueOf(plugin.getGlobalTrashPageCount())));
        sender.sendMessage(message("command.stats.global-items", "&7- &f公共垃圾桶当前物品: &a{items} &7(堆叠 {stacks})",
                "{items}", String.valueOf(plugin.getGlobalTrashStoredItemAmount()),
                "{stacks}", String.valueOf(plugin.getGlobalTrashStoredStackCount())));
        sender.sendMessage(message("command.stats.personal-loaded", "&7- &f已加载个人垃圾桶: &a{count}", "{count}", String.valueOf(plugin.getPersonalTrashInventoryCount())));
        sender.sendMessage(message("command.stats.remaining", "&7- &f下次清理剩余秒数: &a{seconds}", "{seconds}", String.valueOf(plugin.getRemainingClearSeconds())));
    }

    /** 处理增加世界垃圾桶上限命令。 */
    private void handleAdd(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 2) {
            sendAddUsage(sender);
            return;
        }
        World targetWorld;
        int amountIndex;
        if (args.length >= 3) {
            targetWorld = Bukkit.getWorld(args[1]);
            if (targetWorld == null) {
                sender.sendMessage(message("command.world-not-found", "{prefix}&c未找到世界: &f{world}", "{world}", args[1]));
                return;
            }
            amountIndex = 2;
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(message("command.add-console-world-required", "{prefix}&c控制台执行 add 时必须指定世界名。"));
                sendAddUsage(sender);
                return;
            }
            targetWorld = ((Player) sender).getWorld();
            amountIndex = 1;
        }
        int delta = parseInt(args[amountIndex], 0);
        if (delta <= 0) {
            sender.sendMessage(message("command.add-positive-required", "{prefix}&c数量必须大于 0。"));
            return;
        }
        int next = plugin.addWorldTrashMax(targetWorld, delta);
        sender.sendMessage(message("command.add-success", "{prefix}&a世界 &f{world} &a垃圾桶上限已调整为 &f{count}&a。",
                "{world}", targetWorld.getName(), "{count}", String.valueOf(next)));
    }

    /** 发送 add 命令用法。 */
    private void sendAddUsage(CommandSender sender) {
        sendMessageList(sender, "command.add-usage", Arrays.asList("&c用法: /blwtc add <数量>", "&c用法: /blwtc add <世界名> <数量>"));
    }

    /** 处理防丢弃模式切换。 */
    private void handleDropMode(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        if (!hasAnyPermission(sender, "blworldtrashcan.dropmode", "WorldListTrashCan.DropMode")) {
            sender.sendMessage(message("command.no-dropmode-permission", "{prefix}&c你没有权限使用防丢弃模式。"));
            return;
        }
        plugin.toggleDropProtection((Player) sender);
    }

    /** 处理 look 查询。 */
    private void handleLook(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        if (!hasAnyPermission(sender, "blworldtrashcan.look", "WorldListTrashCan.Look")) {
            sender.sendMessage(message("command.no-look-permission", "{prefix}&c你没有权限使用查询功能。"));
            return;
        }
        plugin.armLook((Player) sender);
    }

    /** 处理世界黑名单 GUI。 */
    private void handleWorldBan(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        if (!hasAnyPermission(sender, "WorldListTrashCan.BanGui", "blworldtrashcan.admin")) {
            sender.sendMessage(message("command.no-world-ban-permission", "{prefix}&c你没有权限打开世界黑名单。"));
            return;
        }
        plugin.openWorldBan((Player) sender);
    }

    /** 处理公共垃圾桶黑名单 GUI。 */
    private void handleGlobalBan(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        if (!hasAnyPermission(sender, "WorldListTrashCan.GlobalBan", "blworldtrashcan.admin")) {
            sender.sendMessage(message("command.no-global-ban-permission", "{prefix}&c你没有权限打开公共垃圾桶黑名单。"));
            return;
        }
        plugin.openGlobalBan((Player) sender);
    }

    /** 后台测试打开指定玩家 GUI。 */
    private void handleDebugOpen(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /blwtc debugopen <玩家> <global|personal>");
            return;
        }
        Player player = plugin.getServer().getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage("§c玩家不在线: " + args[1]);
            return;
        }
        if ("global".equalsIgnoreCase(args[2])) {
            plugin.openGlobalTrash(player);
            sender.sendMessage("§a已为玩家打开公共垃圾桶: §f" + player.getName());
            return;
        }
        if ("personal".equalsIgnoreCase(args[2])) {
            plugin.openPersonalTrash(player);
            sender.sendMessage("§a已为玩家打开个人垃圾桶: §f" + player.getName());
            return;
        }
        sender.sendMessage("§c类型必须是 global 或 personal。");
    }

    /** 后台创建并登记测试世界垃圾桶。 */
    private void handleDebugWorldTrash(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /blwtc debugworldtrash <玩家>");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        if (player == null) {
            return;
        }
        boolean created = plugin.debugCreateWorldTrash(player);
        sender.sendMessage(created ? "§a已提交测试世界垃圾桶创建任务，请查看后台日志。" : "§c创建测试世界垃圾桶任务提交失败。");
    }

    /** 后台测试指定垃圾桶路由。 */
    private void handleDebugRoute(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage("§c用法: /blwtc debugroute <玩家> <world|personal|global> <Material> <数量>");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        TrashRoute route = parseRoute(args[2]);
        Material material = parseMaterial(args[3]);
        int amount = parseAmount(args[4]);
        if (player == null || route == null || material == null || amount <= 0) {
            sender.sendMessage("§c参数错误，请检查玩家、路由、Material 和数量。");
            return;
        }
        boolean routed = plugin.debugRoute(player, route, material, amount);
        sender.sendMessage(routed ? "§a已提交路由测试任务，请查看后台日志。" : "§c路由测试任务提交失败，请检查玩家状态。");
    }

    /** 后台生成测试掉落物。 */
    private void handleDebugDrop(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /blwtc debugdrop <玩家> <Material> <数量> [owner]");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        Material material = parseMaterial(args[2]);
        int amount = parseAmount(args[3]);
        boolean markOwner = args.length > 4 && ("owner".equalsIgnoreCase(args[4]) || "true".equalsIgnoreCase(args[4]));
        if (player == null || material == null || amount <= 0) {
            sender.sendMessage("§c参数错误，请检查玩家、Material 和数量。");
            return;
        }
        plugin.debugDrop(player, material, amount, markOwner);
        sender.sendMessage("§a已提交测试掉落物生成任务，请查看后台日志。");
    }

    /** 后台测试仙人掌、岩浆等损坏回收。 */
    private void handleDebugDamage(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /blwtc debugdamage <玩家> <Material> <数量>");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        Material material = parseMaterial(args[2]);
        int amount = parseAmount(args[3]);
        if (player == null || material == null || amount <= 0) {
            sender.sendMessage("§c参数错误，请检查玩家、Material 和数量。");
            return;
        }
        boolean recovered = plugin.debugDamageRecovery(player, material, amount);
        sender.sendMessage(recovered ? "§a已提交损坏回收测试任务，请查看后台日志。" : "§c损坏回收测试任务提交失败，请检查玩家状态。");
    }

    /** 后台输出不依赖在线玩家的垃圾桶库存摘要。 */
    private void handleDebugStock(CommandSender sender) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        sender.sendMessage("§a垃圾桶库存:");
        sender.sendMessage("§7- §f公共垃圾桶物品: §a" + plugin.getGlobalTrashStoredItemAmount()
                + " §7(堆叠 " + plugin.getGlobalTrashStoredStackCount() + ")");
        sender.sendMessage(message("command.stats.global-pages", "&7- &f公共垃圾桶页数: &a{pages}", "{pages}", String.valueOf(plugin.getGlobalTrashPageCount())));
        sender.sendMessage(message("command.stats.personal-loaded", "&7- &f已加载个人垃圾桶: &a{count}", "{count}", String.valueOf(plugin.getPersonalTrashInventoryCount())));
    }

    /** 后台输出测试摘要。 */
    private void handleDebugSummary(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /blwtc debugsummary <玩家>");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        if (player == null) {
            return;
        }
        for (String line : plugin.debugSummary(player)) {
            sender.sendMessage(line);
        }
    }

    /** 后台测试需要真实玩家对象的入口。 */
    private void handleDebugPlayer(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(message("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /blwtc debugplayer <玩家> <dropmode|look|ban|globalban>");
            return;
        }
        Player player = requireOnlinePlayer(sender, args[1]);
        if (player == null) {
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if ("dropmode".equals(action)) {
            plugin.toggleDropProtection(player);
            sender.sendMessage("§a已切换玩家防丢弃模式。");
            return;
        }
        if ("look".equals(action)) {
            plugin.armLook(player);
            sender.sendMessage("§a已触发玩家 look 查询。");
            return;
        }
        if ("ban".equals(action)) {
            plugin.openWorldBan(player);
            sender.sendMessage("§a已为玩家打开世界黑名单 GUI。");
            return;
        }
        if ("globalban".equals(action)) {
            plugin.openGlobalBan(player);
            sender.sendMessage("§a已为玩家打开公共黑名单 GUI。");
            return;
        }
        sender.sendMessage("§c类型必须是 dropmode、look、ban 或 globalban。");
    }

    /** 发送平台能力信息。 */
    private void sendPlatform(CommandSender sender) {
        sender.sendMessage(message("command.platform.header", "{prefix}&a当前平台: &f{platform}", "{platform}", plugin.getPlatform().id()));
        for (Capability capability : Capability.values()) {
            String state = plugin.getPlatform().capabilities().has(capability)
                    ? message("command.platform.enabled", "&a启用")
                    : message("command.platform.disabled", "&7禁用");
            sender.sendMessage(message("command.platform.line", "&7- &f{capability}&7: {state}",
                    "{capability}", capability.name().toLowerCase().replace('_', '-'),
                    "{state}", state));
        }
    }

    /** 要求命令发送者必须是玩家。 */
    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        sender.sendMessage(message("command.only-player", "{prefix}&c该命令只能由玩家执行。"));
        return false;
    }

    /** 判断发送者是否拥有管理权限，兼容旧插件 OP 管理语义。 */
    private boolean hasAdminPermission(CommandSender sender) {
        return sender != null && (sender.isOp() || sender.hasPermission("blworldtrashcan.admin"));
    }

    /** 判断发送者是否拥有任一权限，保留旧插件 OP 旁路。 */
    private boolean hasAnyPermission(CommandSender sender, String... permissions) {
        if (sender == null) {
            return false;
        }
        if (sender.isOp()) {
            return true;
        }
        for (String permission : permissions) {
            if (permission != null && sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }
    /** 获取在线玩家，没有时直接提示。 */
    private Player requireOnlinePlayer(CommandSender sender, String playerName) {
        Player player = plugin.getServer().getPlayerExact(playerName);
        if (player == null) {
            sender.sendMessage(message("command.player-offline", "{prefix}&c玩家不在线: {player}", "{player}", playerName));
            return null;
        }
        return player;
    }

    /** 解析 Material 名称。 */
    private Material parseMaterial(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
    }

    /** 解析测试路由类型。 */
    private TrashRoute parseRoute(String value) {
        String route = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("world".equals(route)) {
            return TrashRoute.WORLD_TRASH;
        }
        if ("personal".equals(route)) {
            return TrashRoute.PERSONAL_TRASH;
        }
        if ("global".equals(route)) {
            return TrashRoute.GLOBAL_TRASH;
        }
        return null;
    }

    /** 解析测试物品数量。 */
    private int parseAmount(String value) {
        int parsed = parseInt(value, 0);
        if (parsed <= 0) {
            return 0;
        }
        return Math.min(64, parsed);
    }

    /** 解析整数。 */
    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 按前缀过滤补全项。 */
    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }
}
