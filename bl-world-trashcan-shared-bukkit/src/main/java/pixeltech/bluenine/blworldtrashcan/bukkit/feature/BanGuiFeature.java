package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.SafeMaterialMatcher;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 世界垃圾桶和公共垃圾桶物品黑名单 GUI。 */
public final class BanGuiFeature implements Feature, Listener {
    private static final int INVENTORY_SIZE = 54;
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private final WorldTrashRouter trashRouter;
    private final BukkitMessageService messages;
    private final Runnable reloadCallback;
    private final Map<Inventory, BanContext> contexts = Collections.synchronizedMap(new IdentityHashMap<Inventory, BanContext>());
    private boolean registered;

    /** 创建黑名单 GUI 功能。 */
    public BanGuiFeature(Plugin plugin, Supplier<ConfigBundle> configSupplier, WorldTrashRouter trashRouter,
                         BukkitMessageService messages, Runnable reloadCallback) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.trashRouter = trashRouter;
        this.messages = messages;
        this.reloadCallback = reloadCallback;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "ban-gui";
    }

    /** 注册监听器。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    /** 重载时清理未关闭的上下文。 */
    @Override
    public void reload() {
        contexts.clear();
    }

    /** 取消注册监听器。 */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        contexts.clear();
        registered = false;
    }

    /** 打开当前世界的世界垃圾桶黑名单 GUI。 */
    public void openWorldBan(Player player) {
        World world = player.getWorld();
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE,
                message("ban-gui.world-title", "&8世界黑名单: {world}", "{world}", world.getName()));
        fillInventory(inventory, trashRouter.getWorldBannedMaterials(world));
        contexts.put(inventory, new BanContext(BanType.WORLD, world.getName()));
        player.openInventory(inventory);
    }

    /** 打开公共垃圾桶黑名单 GUI。 */
    public void openGlobalBan(Player player) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE,
                message("ban-gui.global-title", "&8公共垃圾桶黑名单"));
        fillInventory(inventory, configSupplier.get().getTrashConfig().getGlobalTrashBannedMaterials());
        contexts.put(inventory, new BanContext(BanType.GLOBAL, ""));
        player.openInventory(inventory);
    }

    /** GUI 关闭时保存黑名单。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        BanContext context = contexts.remove(event.getInventory());
        if (context == null || !(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Set<String> materials = collectMaterials(event.getInventory());
        if (context.type == BanType.WORLD) {
            saveWorldBan(player, context.worldName, materials);
            return;
        }
        saveGlobalBan(player, materials);
    }

    /** 保存世界黑名单。 */
    private void saveWorldBan(Player player, String worldName, Set<String> materials) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(message("ban-gui.world-missing", "&c世界不存在，无法保存黑名单: {world}", "{world}", worldName));
            return;
        }
        int defaultMax = configSupplier.get().getTrashConfig().getWorldTrash().getDefaultMaxCount();
        if (trashRouter.setWorldBannedMaterials(world, materials, defaultMax)) {
            player.sendMessage(message("ban-gui.world-save-success", "&a已保存世界垃圾桶黑名单，数量: &f{count}",
                    "{count}", String.valueOf(materials.size())));
            return;
        }
        player.sendMessage(message("ban-gui.world-save-fail", "&c保存世界垃圾桶黑名单失败，请查看后台日志。"));
    }

    /** 保存公共垃圾桶黑名单。 */
    private void saveGlobalBan(Player player, Set<String> materials) {
        File file = new File(plugin.getDataFolder(), "trash.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("global-trash.banned-materials", new ArrayList<>(materials));
        try {
            yaml.save(file);
            refreshAfterGlobalBanSave();
            player.sendMessage(message("ban-gui.global-save-success", "&a已保存公共垃圾桶黑名单，数量: &f{count} &7(已立即生效)",
                    "{count}", String.valueOf(materials.size())));
        } catch (IOException exception) {
            player.sendMessage(message("ban-gui.global-save-fail", "&c保存公共垃圾桶黑名单失败，请查看后台日志。"));
            plugin.getLogger().warning("[BanGui] 保存公共垃圾桶黑名单失败: " + exception.getMessage());
        }
    }

    /** 保存公共黑名单后刷新运行期配置。 */
    private void refreshAfterGlobalBanSave() {
        if (reloadCallback != null) {
            reloadCallback.run();
        }
    }

    /** 根据 Material 名称填充 GUI。 */
    private void fillInventory(Inventory inventory, Set<String> materials) {
        int slot = 0;
        for (String materialName : materials) {
            if (slot >= inventory.getSize()) {
                break;
            }
            Material material = SafeMaterialMatcher.match(materialName);
            if (material != null && material != Material.AIR) {
                inventory.setItem(slot, new ItemStack(material));
                slot++;
            }
        }
    }

    /** 从 GUI 收集 Material 名称。 */
    private Set<String> collectMaterials(Inventory inventory) {
        Set<String> result = new LinkedHashSet<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                result.add(itemStack.getType().name());
            }
        }
        return result;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return RichTextRenderer.color(text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }

    /** 黑名单类型。 */
    private enum BanType {
        WORLD,
        GLOBAL
    }

    /** 单个 GUI 的保存上下文。 */
    private static final class BanContext {
        private final BanType type;
        private final String worldName;

        /** 创建黑名单上下文。 */
        private BanContext(BanType type, String worldName) {
            this.type = type;
            this.worldName = worldName;
        }
    }
}
