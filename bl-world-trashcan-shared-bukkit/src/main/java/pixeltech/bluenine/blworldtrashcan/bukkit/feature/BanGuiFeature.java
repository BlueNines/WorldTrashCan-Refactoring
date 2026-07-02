package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
        BanContext context = new BanContext(BanType.WORLD, world.getName());
        Inventory inventory = createInventory(context,
                message("ban-gui.world-title", "&8世界黑名单: {world}", "{world}", world.getName()));
        fillInventory(inventory, trashRouter.getWorldBannedMaterials(world));
        player.openInventory(inventory);
    }

    /** 打开公共垃圾桶黑名单 GUI。 */
    public void openGlobalBan(Player player) {
        BanContext context = new BanContext(BanType.GLOBAL, "");
        Inventory inventory = createInventory(context, message("ban-gui.global-title", "&8公共垃圾桶黑名单"));
        fillInventory(inventory, configSupplier.get().getTrashConfig().getGlobalTrashBannedMaterials());
        player.openInventory(inventory);
    }

    /** 创建带上下文 holder 的黑名单 GUI。 */
    private Inventory createInventory(BanContext context, String title) {
        BanHolder holder = new BanHolder(context);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        holder.setInventory(inventory);
        contexts.put(inventory, context);
        return inventory;
    }

    /** GUI 点击时用明确 token 语义编辑黑名单，避免原版光标物品关闭时未落入 Inventory。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        BanContext context = contextOf(topInventory);
        if (context == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < topInventory.getSize()) {
            removeToken(topInventory, rawSlot);
            return;
        }
        addToken(topInventory, event.getCurrentItem(), (Player) event.getWhoClicked());
    }

    /** GUI 关闭时保存黑名单。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        BanContext context = removeContext(topInventory);
        if (context == null || !(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Set<String> materials = collectMaterials(topInventory);
        if (context.type == BanType.WORLD) {
            saveWorldBan(player, context.worldName, materials);
            return;
        }
        saveGlobalBan(player, materials);
    }

    /** 从 Inventory holder 或兼容 map 读取上下文。 */
    private BanContext contextOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BanHolder) {
            return ((BanHolder) holder).context;
        }
        return contexts.get(inventory);
    }

    /** 移除 GUI 上下文，holder 作为 1.12 包装对象不稳定时的兜底。 */
    private BanContext removeContext(Inventory inventory) {
        BanContext context = contexts.remove(inventory);
        if (context != null) {
            return context;
        }
        return contextOf(inventory);
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

    /** 点击上方 GUI 物品时移除一个黑名单 token。 */
    private void removeToken(Inventory inventory, int slot) {
        ItemStack itemStack = inventory.getItem(slot);
        if (!isEmpty(itemStack)) {
            inventory.clear(slot);
        }
    }

    /** 点击玩家背包物品时复制一个 Material token 到 GUI，不消耗玩家物品。 */
    private void addToken(Inventory inventory, ItemStack clicked, Player player) {
        if (isEmpty(clicked)) {
            return;
        }
        Material material = clicked.getType();
        if (material == Material.AIR || containsMaterial(inventory, material)) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, new ItemStack(material));
                return;
            }
        }
        player.sendMessage(message("ban-gui.full", "&c黑名单 GUI 已满，无法继续添加。"));
    }

    /** 判断 GUI 中是否已经存在指定 Material token。 */
    private boolean containsMaterial(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!isEmpty(itemStack) && itemStack.getType() == material) {
                return true;
            }
        }
        return false;
    }

    /** 从 GUI 收集 Material 名称。 */
    private Set<String> collectMaterials(Inventory inventory) {
        Set<String> result = new LinkedHashSet<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!isEmpty(itemStack)) {
                result.add(itemStack.getType().name());
            }
        }
        return result;
    }

    /** 判断物品是否为空。 */
    private boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0;
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

    /** 黑名单 GUI 的 Bukkit holder，用于跨版本稳定绑定上下文。 */
    private static final class BanHolder implements InventoryHolder {
        private final BanContext context;
        private Inventory inventory;

        /** 创建带上下文的 holder。 */
        private BanHolder(BanContext context) {
            this.context = context;
        }

        /** 绑定 Bukkit 创建出的 Inventory。 */
        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        /** 返回当前 holder 关联的 Inventory。 */
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
