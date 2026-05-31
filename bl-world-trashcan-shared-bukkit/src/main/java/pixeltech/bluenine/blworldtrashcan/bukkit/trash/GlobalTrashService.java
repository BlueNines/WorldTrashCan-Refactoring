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
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.io.IOException;
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
    private final List<Inventory> pages = new ArrayList<>();
    private final Map<UUID, Long> lastTakeMillis = new HashMap<>();
    private TrashConfig.GlobalTrashConfig config;
    private ItemStack backItem;
    private ItemStack nextItem;
    private ItemStack backgroundItem;

    /** 创建公共垃圾桶服务。 */
    public GlobalTrashService(Plugin plugin, TrashConfig.GlobalTrashConfig config) {
        this.plugin = plugin;
        reload(config);
    }

    /** 重载配置并重建分页。 */
    public void reload(TrashConfig.GlobalTrashConfig nextConfig) {
        List<ItemStack> oldItems = snapshotContent();
        this.config = nextConfig;
        this.backItem = createItem(Material.ARROW, "&a上一页");
        this.nextItem = createItem(Material.ARROW, "&a下一页");
        this.backgroundItem = createItem(matchMaterial("BLACK_STAINED_GLASS_PANE", Material.STAINED_GLASS_PANE), " ");
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
            player.sendMessage(color("&c公共垃圾桶未启用。"));
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
        if (!player.hasPermission("blworldtrashcan.global.take") && !player.hasPermission("WorldListTrashCan.GlobalTrashTakeItem")) {
            player.sendMessage(color("&c你没有权限从公共垃圾桶取出物品。"));
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
        if (!player.hasPermission("blworldtrashcan.global.put") && !player.hasPermission("WorldListTrashCan.GlobalTrashPutItem")) {
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
        player.sendMessage(color("&c公共垃圾桶拿取冷却剩余 " + Math.max(1L, remain / 100L) / 10D + " 秒。"));
        return true;
    }

    /** 创建单页 GUI。 */
    private Inventory createPage(int pageIndex, int maxPages) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE, color("&8公共垃圾桶 " + (pageIndex + 1) + "/" + maxPages));
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
    private ItemStack createItem(Material material, String name) {
        ItemStack itemStack = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            itemStack.setItemMeta(meta);
        }
        return itemStack;
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

    /** 匹配物品类型。 */
    private Material matchMaterial(String name, Material fallback) {
        Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
