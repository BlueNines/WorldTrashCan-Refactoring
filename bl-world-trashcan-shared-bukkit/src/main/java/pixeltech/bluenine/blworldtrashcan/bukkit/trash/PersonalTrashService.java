package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 个人垃圾桶 GUI 和路由服务。 */
public final class PersonalTrashService {
    private final Plugin plugin;
    private final PaymentService paymentService;
    private final BukkitMessageService messages;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final ServerPlatform platform;
    private final Map<UUID, Inventory> inventories = new HashMap<>();
    private TrashConfig.PersonalTrashConfig config;

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config, PaymentService paymentService,
                                BukkitMessageService messages) {
        this(plugin, config, paymentService, messages, null, null);
    }

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config, PaymentService paymentService,
                                BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper) {
        this(plugin, config, paymentService, messages, itemSnapshotMapper, null);
    }

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config, PaymentService paymentService,
                                BukkitMessageService messages, ItemSnapshotMapper itemSnapshotMapper,
                                ServerPlatform platform) {
        this.plugin = plugin;
        this.paymentService = paymentService;
        this.messages = messages;
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.platform = platform;
        this.config = config;
    }

    /** 重载个人垃圾桶配置。 */
    public void reload(TrashConfig.PersonalTrashConfig nextConfig) {
        this.config = nextConfig;
    }

    /** 判断个人垃圾桶是否启用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    /** 判断玩家是否有个人垃圾桶容量。 */
    public boolean hasSpace(UUID ownerUuid, ItemStack itemStack) {
        if (!isEnabled() || ownerUuid == null) {
            return false;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        Inventory inventory = inventory(ownerUuid, "离线玩家");
        return InventorySlotUtil.hasSpace(inventory, cleanItemStack, 0, inventory.getSize())
                || config.isAutoClearWhenFull();
    }

    /** 向个人垃圾桶放入物品。 */
    public boolean addItem(UUID ownerUuid, ItemStack itemStack) {
        if (!isEnabled() || ownerUuid == null) {
            return false;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        Inventory inventory = inventory(ownerUuid, "离线玩家");
        if (!InventorySlotUtil.hasSpace(inventory, cleanItemStack, 0, inventory.getSize()) && config.isAutoClearWhenFull()) {
            inventory.clear();
        }
        return InventorySlotUtil.add(inventory, cleanItemStack, 0, inventory.getSize());
    }

    /** 发送单个来源进入个人垃圾桶的提示。 */
    public void notifySingle(UUID ownerUuid, ItemStack itemStack) {
        if (!canNotify(ownerUuid) || InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        List<ItemStack> itemStacks = new ArrayList<>();
        itemStacks.add(itemStack.clone());
        sendToOwner(ownerUuid, message("personal-trash.recycle.single",
                "{prefix}&a已回收到个人垃圾桶: {items}",
                "{items}", formatItemList(itemStacks)));
    }

    /** 按玩家发送本轮批量进入个人垃圾桶的提示。 */
    public void notifyBatch(Map<UUID, List<ItemStack>> itemStacksByOwner) {
        if (!isNotifyEnabled() || itemStacksByOwner == null || itemStacksByOwner.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, List<ItemStack>> entry : itemStacksByOwner.entrySet()) {
            UUID ownerUuid = entry.getKey();
            List<ItemStack> itemStacks = entry.getValue();
            if (!canNotify(ownerUuid) || itemStacks == null || itemStacks.isEmpty()) {
                continue;
            }
            sendToOwner(ownerUuid, message("personal-trash.recycle.batch",
                    "{prefix}&a本次清理已回收到个人垃圾桶: {items}",
                    "{items}", formatItemList(itemStacks)));
        }
    }

    /** 打开玩家自己的个人垃圾桶。 */
    public void open(Player player) {
        if (!isEnabled()) {
            player.sendMessage(message("personal-trash.disabled", "&c个人垃圾桶未启用。"));
            return;
        }
        player.openInventory(inventory(player.getUniqueId(), player.getName()));
    }

    /** 处理个人垃圾桶点击。 */
    public boolean handleClick(InventoryClickEvent event) {
        if (!inventories.containsValue(event.getInventory())) {
            return false;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return true;
        }
        Player player = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < event.getInventory().getSize()) {
            takeItem(player, event.getInventory(), rawSlot);
            return true;
        }
        if (rawSlot >= event.getInventory().getSize()) {
            putFromPlayer(player, event);
        }
        return true;
    }

    /** 返回已创建的个人垃圾桶数量。 */
    public int getLoadedInventoryCount() {
        return inventories.size();
    }

    /** 返回指定玩家个人垃圾桶的物品总数量。 */
    public int getStoredItemAmount(UUID ownerUuid) {
        Inventory inventory = inventories.get(ownerUuid);
        if (inventory == null) {
            return 0;
        }
        int amount = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!InventorySlotUtil.isEmpty(itemStack)) {
                amount += itemStack.getAmount();
            }
        }
        return amount;
    }

    /** 返回指定玩家个人垃圾桶的堆叠数量。 */
    public int getStoredStackCount(UUID ownerUuid) {
        Inventory inventory = inventories.get(ownerUuid);
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!InventorySlotUtil.isEmpty(inventory.getItem(slot))) {
                count++;
            }
        }
        return count;
    }

    /** 取出个人垃圾桶物品。 */
    private void takeItem(Player player, Inventory inventory, int slot) {
        if (!hasTrashPermission(player, "blworldtrashcan.personal.take", "WorldListTrashCan.PersonalTrashTakeItem")) {
            player.sendMessage(message("personal-trash.no-take-permission", "&c你没有权限从个人垃圾桶取出物品。"));
            return;
        }
        ItemStack itemStack = inventory.getItem(slot);
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        double cost = config.getTakeCost();
        if (cost > 0D && !paymentService.charge(player, cost)) {
            player.sendMessage(message("personal-trash.not-enough-money", "&c余额不足，无法取出该物品。"));
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        if (leftovers.isEmpty()) {
            inventory.clear(slot);
            if (cost > 0D) {
                player.sendMessage(message("personal-trash.pay-success", "&a已支付 {cost}。",
                        "{cost}", paymentService.format(cost)));
            }
            return;
        }
        ItemStack remaining = leftovers.values().iterator().next();
        itemStack.setAmount(remaining.getAmount());
        inventory.setItem(slot, itemStack);
    }

    /** 玩家手动放入个人垃圾桶。 */
    private void putFromPlayer(Player player, InventoryClickEvent event) {
        if (!hasTrashPermission(player, "blworldtrashcan.personal.put", "WorldListTrashCan.PersonalTrashPutItem")) {
            return;
        }
        ItemStack itemStack = event.getCurrentItem();
        if (InventorySlotUtil.isEmpty(itemStack)) {
            return;
        }
        if (addItem(player.getUniqueId(), itemStack)) {
            itemStack.setAmount(0);
        }
    }

    /** 判断玩家是否拥有个人垃圾桶操作权限，保留旧插件 OP 旁路。 */
    private boolean hasTrashPermission(Player player, String modernPermission, String legacyPermission) {
        return player != null && (player.isOp() || player.hasPermission(modernPermission) || player.hasPermission(legacyPermission));
    }

    /** 清理插件内部物品标记后用于入库。 */
    private ItemStack sanitize(ItemStack itemStack) {
        return itemSnapshotMapper == null ? itemStack : itemSnapshotMapper.sanitizeForStorage(itemStack);
    }

    /** 判断是否允许给指定玩家发送个人垃圾桶提示。 */
    private boolean canNotify(UUID ownerUuid) {
        return ownerUuid != null && isNotifyEnabled();
    }

    /** 判断个人垃圾桶提示功能是否启用。 */
    private boolean isNotifyEnabled() {
        return isEnabled() && config.isNotifyWhenRouted();
    }

    /** 向在线拥有者发送消息。 */
    private void sendToOwner(UUID ownerUuid, String text) {
        if (platform != null) {
            platform.sendMessage(ownerUuid, text);
            return;
        }
        Player player = Bukkit.getPlayer(ownerUuid);
        if (player != null) {
            player.sendMessage(text);
        }
    }

    /** 格式化个人垃圾桶提示中的物品列表。 */
    private String formatItemList(List<ItemStack> itemStacks) {
        List<NotificationEntry> entries = mergeEntries(itemStacks);
        int maxDisplay = config == null ? 3 : config.getNotifyMaxDisplayItems();
        int displayCount = entries.size() > maxDisplay ? maxDisplay : entries.size();
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < displayCount; index++) {
            parts.add(formatEntry(entries.get(index)));
        }
        if (entries.size() > maxDisplay) {
            parts.add(message("personal-trash.recycle.ellipsis", "&7...", new String[0]));
        }
        String joined = join(parts, message("personal-trash.recycle.separator", "&7, ", new String[0]));
        return message("personal-trash.recycle.list", "&7[{items}&7]", "{items}", joined);
    }

    /** 合并同名物品条目，减少批量清理提示噪声。 */
    private List<NotificationEntry> mergeEntries(List<ItemStack> itemStacks) {
        Map<String, NotificationEntry> entries = new LinkedHashMap<>();
        for (ItemStack itemStack : itemStacks) {
            if (InventorySlotUtil.isEmpty(itemStack)) {
                continue;
            }
            String name = displayName(itemStack);
            NotificationEntry entry = entries.get(name);
            if (entry == null) {
                entry = new NotificationEntry(name);
                entries.put(name, entry);
            }
            entry.addAmount(itemStack.getAmount());
        }
        return new ArrayList<>(entries.values());
    }

    /** 格式化单个物品条目。 */
    private String formatEntry(NotificationEntry entry) {
        if (entry.getAmount() <= 1) {
            return message("personal-trash.recycle.item-single", "&f{name}",
                    "{name}", entry.getName());
        }
        return message("personal-trash.recycle.item", "&f{name}&7*&f{amount}",
                "{name}", entry.getName(), "{amount}", String.valueOf(entry.getAmount()));
    }

    /** 返回物品显示名，没有自定义名时使用 Material 名称。 */
    private String displayName(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return itemStack.getType().name();
    }

    /** 拼接字符串列表。 */
    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(separator);
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    /** 获取或创建个人垃圾桶。 */
    private Inventory inventory(UUID ownerUuid, String playerName) {
        Inventory inventory = inventories.get(ownerUuid);
        if (inventory != null) {
            return inventory;
        }
        Inventory created = Bukkit.createInventory(null, 54,
                message("personal-trash.gui.title", "&8{player} 的个人垃圾桶", "{player}", playerName));
        inventories.put(ownerUuid, created);
        plugin.getLogger().fine("[PersonalTrash] 创建个人垃圾桶: " + ownerUuid);
        return created;
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }

    /** 个人垃圾桶提示里的合并物品条目。 */
    private static final class NotificationEntry {
        private final String name;
        private int amount;

        /** 创建提示条目。 */
        private NotificationEntry(String name) {
            this.name = name;
        }

        /** 累加物品数量。 */
        private void addAmount(int delta) {
            amount += Math.max(1, delta);
        }

        /** 返回显示名。 */
        private String getName() {
            return name;
        }

        /** 返回总数量。 */
        private int getAmount() {
            return amount;
        }
    }
}
