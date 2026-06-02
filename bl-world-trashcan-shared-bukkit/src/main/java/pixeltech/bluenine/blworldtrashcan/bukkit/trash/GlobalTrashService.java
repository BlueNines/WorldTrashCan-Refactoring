package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 公共垃圾桶 GUI 和路由服务。 */
public final class GlobalTrashService {
    private static final int CONTENT_SIZE = 45;
    private static final int INVENTORY_SIZE = 54;
    private final Plugin plugin;
    private final BukkitMessageService messages;
    private final List<Inventory> pages = new ArrayList<>();
    private final Map<UUID, Long> lastTakeMillis = new HashMap<>();
    private TrashConfig.GlobalTrashConfig config;
    private ItemStack backItem;
    private ItemStack nextItem;
    private ItemStack backgroundItem;

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config, BukkitMessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        reload(config);
    }

    /** 重载配置并重建分页。 */
    public void reload(TrashConfig.GlobalTrashConfig nextConfig) {
        List<ItemStack> oldItems = snapshotContent();
        this.config = nextConfig;
        int backModelId = nextConfig == null ? -1 : nextConfig.getBackItemModelId();
        int nextModelId = nextConfig == null ? -1 : nextConfig.getNextItemModelId();
        int backgroundModelId = nextConfig == null ? -1 : nextConfig.getBackgroundItemModelId();
        this.backItem = createItem(Material.ARROW, message("global-trash.gui.back", "&a上一页"), backModelId);
        this.nextItem = createItem(Material.ARROW, message("global-trash.gui.next", "&a下一页"), nextModelId);
        this.backgroundItem = createItem(matchMaterial(
                "BLACK_STAINED_GLASS_PANE",
                "STAINED_GLASS_PANE",
                "LEGACY_STAINED_GLASS_PANE",
                "GRAY_STAINED_GLASS_PANE",
                "GLASS_PANE",
                "THIN_GLASS"
        ), " ", backgroundModelId);
        pages.clear();
        int maxPages = nextConfig == null ? 1 : nextConfig.getMaxPages();
        for (int index = 0; index < maxPages; index++) {
            pages.add(createPage(index, maxPages));
        }
        refill(oldItems);
    }

    /** 判断公共垃圾桶是否可用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled() && !pages.isEmpty();
    }

    /** 判断是否有容量放入指定物品。 */
    public boolean hasSpace(ItemStack itemStack) {
        if (!isEnabled()) {
            return false;
        }
        if (itemStack != null && config.isBannedMaterial(itemStack.getType().name())) {
            return false;
        }
        for (Inventory page : pages) {
            if (InventorySlotUtil.hasSpace(page, itemStack, 0, CONTENT_SIZE)) {
                return true;
            }
        }
        return false;
    }

    /** 向公共垃圾桶放入物品。 */
    public boolean addItem(ItemStack itemStack) {
        if (!hasSpace(itemStack)) {
            return false;
        }
        for (Inventory page : pages) {
            if (InventorySlotUtil.add(page, itemStack, 0, CONTENT_SIZE)) {
                return true;
            }
        }
        return false;
    }

    /** 打开公共垃圾桶首页。 */
    public void open(Player player) {
        if (!isEnabled()) {
            player.sendMessage(message("global-trash.disabled", "&c公共垃圾桶未启用。"));
            return;
        }
        player.openInventory(pages.get(0));
    }

    /** 处理公共垃圾桶点击。 */
    public boolean handleClick(InventoryClickEvent event) {
        int pageIndex = pages.indexOf(event.getInventory());
        if (pageIndex < 0) {
            return false;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return true;
        }
        Player player = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        if (rawSlot == 46 && pageIndex > 0) {
            player.openInventory(pages.get(pageIndex - 1));
            return true;
        }
        if (rawSlot == 52 && pageIndex < pages.size() - 1) {
            player.openInventory(pages.get(pageIndex + 1));
            return true;
        }
        if (rawSlot >= 0 && rawSlot < CONTENT_SIZE) {
            takeItem(player, event.getInventory(), rawSlot);
            return true;
        }
        if (rawSlot >= INVENTORY_SIZE && config.isAllowPlayerPut()) {
            putFromPlayer(player, event);
        }
        return true;
    }

    /** 清空所有内容槽位。 */
    public void clearContent() {
        for (Inventory page : pages) {
            for (int slot = 0; slot < CONTENT_SIZE; slot++) {
                page.clear(slot);
            }
        }
    }

    /** 返回公共垃圾桶页数。 */
    public int getPageCount() {
        return pages.size();
    }

    /** 返回公共垃圾桶内容物品总数量。 */
    public int getStoredItemAmount() {
        int amount = 0;
        for (Inventory page : pages) {
            for (int slot = 0; slot < CONTENT_SIZE; slot++) {
                ItemStack itemStack = page.getItem(slot);
                if (!InventorySlotUtil.isEmpty(itemStack)) {
                    amount += itemStack.getAmount();
                }
            }
        }
        return amount;
    }

    /** 返回公共垃圾桶内容堆叠数量。 */
    public int getStoredStackCount() {
        int count = 0;
        for (Inventory page : pages) {
            for (int slot = 0; slot < CONTENT_SIZE; slot++) {
                if (!InventorySlotUtil.isEmpty(page.getItem(slot))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 玩家从公共垃圾桶取出物品。 */
    private void takeItem(Player player, Inventory inventory, int slot) {
        if (!hasTrashPermission(player, "blworldtrashcan.global.take", "WorldListTrashCan.GlobalTrashTakeItem")) {
            player.sendMessage(message("global-trash.no-take-permission", "&c你没有权限从公共垃圾桶取出物品。"));
            return;
        }
        if (isCoolingDown(player)) {
            return;
        }
        ItemStack itemStack = inventory.getItem(slot);
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        lastTakeMillis.put(player.getUniqueId(), System.currentTimeMillis());
        if (leftovers.isEmpty()) {
            logGlobalTrash(player, "-global", itemStack, itemStack.getAmount());
            inventory.clear(slot);
            return;
        }
        ItemStack remaining = leftovers.values().iterator().next();
        int moved = itemStack.getAmount() - remaining.getAmount();
        if (moved > 0) {
            logGlobalTrash(player, "-global", itemStack, moved);
        }
        itemStack.setAmount(remaining.getAmount());
        inventory.setItem(slot, itemStack);
    }

    /** 玩家手动把背包物品放入公共垃圾桶。 */
    private void putFromPlayer(Player player, InventoryClickEvent event) {
        if (!hasTrashPermission(player, "blworldtrashcan.global.put", "WorldListTrashCan.GlobalTrashPutItem")) {
            return;
        }
        ItemStack itemStack = event.getCurrentItem();
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        int amount = itemStack.getAmount();
        if (addItem(itemStack)) {
            logGlobalTrash(player, "+global", itemStack, amount);
            itemStack.setAmount(0);
        }
    }

    /** 判断玩家是否拥有公共垃圾桶操作权限，保留旧插件 OP 旁路。 */
    private boolean hasTrashPermission(Player player, String modernPermission, String legacyPermission) {
        return player != null && (player.isOp() || player.hasPermission(modernPermission) || player.hasPermission(legacyPermission));
    }

    /** 判断玩家是否处于取出冷却。 */
    private boolean isCoolingDown(Player player) {
        long delay = config.getTakeDelayMillis();
        if (delay <= 0L) {
            return false;
        }
        long last = lastTakeMillis.containsKey(player.getUniqueId()) ? lastTakeMillis.get(player.getUniqueId()) : 0L;
        long remain = last + delay - System.currentTimeMillis();
        if (remain <= 0L) {
            return false;
        }
        player.sendMessage(message("global-trash.take-cooldown", "&c公共垃圾桶拿取冷却剩余 {time} 秒。",
                "{time}", String.valueOf(Math.max(1L, remain / 100L) / 10D)));
        return true;
    }

    /** 创建单页 GUI。 */
    private Inventory createPage(int pageIndex, int maxPages) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE,
                message("global-trash.gui.title", "&8公共垃圾桶 {page}/{max}",
                        "{page}", String.valueOf(pageIndex + 1), "{max}", String.valueOf(maxPages)));
        for (int slot = CONTENT_SIZE; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, backgroundItem);
        }
        if (pageIndex > 0) {
            inventory.setItem(46, backItem);
        }
        if (pageIndex < maxPages - 1) {
            inventory.setItem(52, nextItem);
        }
        return inventory;
    }

    /** 快照旧分页中的物品。 */
    private List<ItemStack> snapshotContent() {
        List<ItemStack> result = new ArrayList<>();
        for (Inventory page : pages) {
            for (int slot = 0; slot < CONTENT_SIZE; slot++) {
                ItemStack itemStack = page.getItem(slot);
                if (!InventorySlotUtil.isEmpty(itemStack)) {
                    result.add(itemStack.clone());
                }
            }
        }
        return result;
    }

    /** 重载后把旧内容尽量放回新分页。 */
    private void refill(List<ItemStack> items) {
        for (ItemStack itemStack : items) {
            if (!addItem(itemStack)) {
                plugin.getLogger().warning("[GlobalTrash] 重载后公共垃圾桶容量不足，丢弃未能放回的物品: " + itemStack.getType());
            }
        }
    }

    /** 创建 GUI 物品。 */
    private ItemStack createItem(Material material, String name, int modelId) {
        ItemStack itemStack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            applyCustomModelData(meta, modelId);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    /** 尝试设置 CustomModelData，旧版本没有该 API 时自动忽略。 */
    private void applyCustomModelData(ItemMeta meta, int modelId) {
        if (meta == null || modelId < 0) {
            return;
        }
        try {
            Method method = meta.getClass().getMethod("setCustomModelData", Integer.class);
            method.invoke(meta, Integer.valueOf(modelId));
        } catch (ReflectiveOperationException ignored) {
            // 1.12 没有 CustomModelData，保持旧版本可加载。
        } catch (RuntimeException ignored) {
            // 反射目标来自服务端实现，异常时只跳过外观字段，不影响 GUI 可用性。
        }
    }

    /** 写入公共垃圾桶操作日志。 */
    private void logGlobalTrash(Player player, String action, ItemStack itemStack, int amount) {
        if (config == null || !config.isLogEnabled() || itemStack == null || amount <= 0) {
            return;
        }
        String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = time + " " + player.getName() + " " + action + " " + itemStack.getType().name() + "x" + amount;
        Path logDir = plugin.getDataFolder().toPath().resolve("logs");
        Path logFile = logDir.resolve("global-trash-" + day + ".log");
        try {
            Files.createDirectories(logDir);
            Files.write(logFile, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            plugin.getLogger().warning("[GlobalTrash] 写入公共垃圾桶日志失败: " + exception.getMessage());
        }
    }

    /** 按顺序匹配跨版本物品类型。 */
    private Material matchMaterial(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                return material;
            }
        }
        return Material.STONE;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }
}
