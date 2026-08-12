package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.DefaultWorldListTrashCanAuditBridge;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProviderSelector;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination;
import pixeltech.worldlisttrashcan.api.audit.TrashMutation;
import pixeltech.worldlisttrashcan.api.audit.TrashMutationReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 个人垃圾桶服务，按玩家 UUID 隔离通用容器状态。 */
public final class PersonalTrashService {
    private final Plugin plugin;
    private final PaymentService paymentService;
    private final BukkitMessageService messages;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final ServerPlatform platform;
    private final DefaultWorldListTrashCanAuditBridge auditBridge;
    private final ItemIdentityProvider identityProvider;
    private final TrashContainerMenu containerMenu;
    private final Map<UUID, TrashContainerStore> stores = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTakeMillis = new ConcurrentHashMap<>();
    private TrashConfig.PersonalTrashConfig config;

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages) {
        this(plugin, config, paymentService, messages, null, null, null, null,
                CustomModelDataSupport.detect(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages,
                                ItemSnapshotMapper itemSnapshotMapper) {
        this(plugin, config, paymentService, messages, itemSnapshotMapper, null, null, null,
                CustomModelDataSupport.detect(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages,
                                ItemSnapshotMapper itemSnapshotMapper, ServerPlatform platform) {
        this(plugin, config, paymentService, messages, itemSnapshotMapper, platform, null, null,
                CustomModelDataSupport.detect(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建带审计变更分发器的个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages,
                                ItemSnapshotMapper itemSnapshotMapper, ServerPlatform platform,
                                DefaultWorldListTrashCanAuditBridge auditBridge) {
        this(plugin, config, paymentService, messages, itemSnapshotMapper, platform, auditBridge, null,
                CustomModelDataSupport.detect(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建带审计分发器和主插件物品身份实现的个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages,
                                ItemSnapshotMapper itemSnapshotMapper, ServerPlatform platform,
                                DefaultWorldListTrashCanAuditBridge auditBridge,
                                ItemIdentityProvider identityProvider) {
        this(plugin, config, paymentService, messages, itemSnapshotMapper, platform, auditBridge,
                identityProvider, CustomModelDataSupport.detect(plugin == null ? null : plugin.getLogger()));
    }

    /** 创建具备完整菜单外观能力的个人垃圾桶服务。 */
    public PersonalTrashService(Plugin plugin, TrashConfig.PersonalTrashConfig config,
                                PaymentService paymentService, BukkitMessageService messages,
                                ItemSnapshotMapper itemSnapshotMapper, ServerPlatform platform,
                                DefaultWorldListTrashCanAuditBridge auditBridge,
                                ItemIdentityProvider identityProvider,
                                CustomModelDataSupport customModelDataSupport) {
        this.plugin = plugin;
        this.paymentService = paymentService == null ? new NoPaymentService() : paymentService;
        this.messages = messages;
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.platform = platform;
        this.auditBridge = auditBridge;
        this.identityProvider = identityProvider == null
                ? new ItemIdentityProviderSelector().select(plugin) : identityProvider;
        this.containerMenu = new TrashContainerMenu(plugin, platform, itemSnapshotMapper,
                customModelDataSupport, new PersonalMenuPolicy());
        reload(config);
    }

    /** 重载个人垃圾桶配置并保留所有玩家运行期存量。 */
    public void reload(TrashConfig.PersonalTrashConfig nextConfig) {
        this.config = nextConfig;
        containerMenu.reload(nextConfig);
        int contentSlots = containerMenu.getContentSlotsPerPage();
        for (TrashContainerStore store : stores.values()) {
            store.configure(nextConfig, contentSlots);
        }
        plugin.getLogger().info("[PersonalTrash] mode=" + modeName()
                + ", identity=" + identityProvider.id()
                + ", loadedOwners=" + stores.size()
                + ", contentSlots=" + contentSlots
                + ", maxPages=" + (nextConfig == null ? 0 : nextConfig.getMaxPages())
                + ", autoClearWhenFull=" + (nextConfig != null && nextConfig.isAutoClearWhenFull()));
    }

    /** 判断个人垃圾桶是否启用。 */
    public boolean isEnabled() {
        return config != null && config.isEnabled()
                && !config.getLayout().getContentSlots().isEmpty();
    }

    /** 判断一次自动路由是否至少可以接收一个物品。 */
    public boolean hasSpace(UUID ownerUuid, ItemStack itemStack) {
        if (!isEnabled() || ownerUuid == null) {
            return false;
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack)) {
            return false;
        }
        return store(ownerUuid).canAccept(cleanItemStack, true, config.isAutoClearWhenFull());
    }

    /** 向个人垃圾桶完整放入一个非扫地自动路由物品。 */
    public boolean addItem(UUID ownerUuid, ItemStack itemStack) {
        return addAutomaticItem(ownerUuid, itemStack, false,
                TrashMutationReason.NON_CLEANUP_DEPOSIT).getStatus()
                == TrashWriteResult.Status.ACCEPTED_FULL;
    }

    /** 放入正式扫地物品，并允许紧凑模式部分接收。 */
    public TrashWriteResult addCleanupItem(UUID ownerUuid, ItemStack itemStack) {
        return addAutomaticItem(ownerUuid, itemStack, true,
                TrashMutationReason.NON_CLEANUP_DEPOSIT);
    }

    /** 自动路由写入；只有容器容量拒绝时才按配置清空并重试一次。 */
    private TrashWriteResult addAutomaticItem(UUID ownerUuid, ItemStack itemStack,
                                              boolean cleanupSource,
                                              TrashMutationReason reason) {
        if (!isEnabled() || ownerUuid == null) {
            return TrashWriteResult.rejected();
        }
        ItemStack cleanItemStack = sanitize(itemStack);
        if (InventorySlotUtil.isEmpty(cleanItemStack)) {
            return TrashWriteResult.rejected();
        }
        TrashContainerStore store = store(ownerUuid);
        TrashWriteResult result = config.isAutoClearWhenFull()
                ? store.addWithClearRetry(cleanItemStack, cleanupSource)
                : store.add(cleanItemStack, cleanupSource);
        if (result.isClearedBeforeWrite()) {
            recordMutation(TrashMutation.clear(personalDestination(ownerUuid),
                    TrashMutationReason.PERSONAL_AUTO_CLEAR, System.currentTimeMillis()));
            plugin.getLogger().info("[PersonalTrash] 个人垃圾桶容量不足，已按配置清空并仅重试一次: owner="
                    + ownerUuid);
        }
        if (!cleanupSource && result.getStatus() == TrashWriteResult.Status.ACCEPTED_FULL
                && hasAuditConsumer()) {
            recordMutation(TrashMutation.untrackedDeposit(personalDestination(ownerUuid),
                    cleanItemStack, result.getTrackingKey(), result.getAcceptedAmount(),
                    reason, System.currentTimeMillis()));
        }
        return result;
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
        if (player == null) {
            return;
        }
        if (!isEnabled()) {
            player.sendMessage(message(player, "personal-trash.disabled", "&c个人垃圾桶未启用。"));
            return;
        }
        containerMenu.open(player, store(player.getUniqueId()));
    }

    /** 处理个人垃圾桶点击。 */
    public boolean handleClick(InventoryClickEvent event) {
        return containerMenu.handleClick(event);
    }

    /** 阻止拖拽修改个人垃圾桶菜单。 */
    public boolean handleDrag(InventoryDragEvent event) {
        return containerMenu.handleDrag(event);
    }

    /** 关闭个人垃圾桶时释放菜单视图。 */
    public boolean handleClose(InventoryCloseEvent event) {
        return containerMenu.handleClose(event);
    }

    /** 玩家退出时释放个人桶菜单偏好和冷却。 */
    public void handleQuit(UUID playerId) {
        containerMenu.handleQuit(playerId);
        if (playerId != null) {
            lastTakeMillis.remove(playerId);
        }
    }

    /** 插件关闭时释放个人桶视图和会话缓存，运行期存量随插件生命周期结束。 */
    public void close() {
        containerMenu.close();
        lastTakeMillis.clear();
        stores.clear();
    }

    /** 返回已创建的个人垃圾桶状态数量。 */
    public int getLoadedInventoryCount() {
        return stores.size();
    }

    /** 返回指定玩家个人垃圾桶的物品总数量。 */
    public int getStoredItemAmount(UUID ownerUuid) {
        TrashContainerStore store = ownerUuid == null ? null : stores.get(ownerUuid);
        return store == null ? 0 : store.getStoredItemAmount();
    }

    /** 返回指定玩家个人垃圾桶当前模式的展示堆叠数量。 */
    public int getStoredStackCount(UUID ownerUuid) {
        TrashContainerStore store = ownerUuid == null ? null : stores.get(ownerUuid);
        return store == null ? 0 : store.getStoredStackCount();
    }

    /** 返回或创建一个玩家独立的容器状态。 */
    private TrashContainerStore store(UUID ownerUuid) {
        TrashContainerStore existing = stores.get(ownerUuid);
        if (existing != null) {
            return existing;
        }
        TrashContainerStore created = new TrashContainerStore(identityProvider,
                "personal:" + ownerUuid.toString());
        created.configure(config, containerMenu.getContentSlotsPerPage());
        TrashContainerStore raced = stores.putIfAbsent(ownerUuid, created);
        if (raced == null) {
            plugin.getLogger().fine("[PersonalTrash] 创建个人垃圾桶状态: " + ownerUuid);
            return created;
        }
        return raced;
    }

    /** 判断玩家是否拥有个人垃圾桶操作权限，并保留 OP 旁路。 */
    private boolean hasTrashPermission(Player player, String permission) {
        return player != null && (player.isOp() || player.hasPermission(permission));
    }

    /** 判断玩家是否仍在个人垃圾桶拿取冷却中。 */
    private boolean isCoolingDown(Player player) {
        long delay = config == null ? 0L : config.getTakeDelayMillis();
        if (delay <= 0L) {
            return false;
        }
        Long lastValue = lastTakeMillis.get(player.getUniqueId());
        long remain = (lastValue == null ? 0L : lastValue.longValue()) + delay
                - System.currentTimeMillis();
        if (remain <= 0L) {
            return false;
        }
        player.sendMessage(message(player, "personal-trash.take-cooldown",
                "&c个人垃圾桶拿取冷却剩余 {time} 秒。",
                "{time}", String.valueOf(Math.max(1L, remain / 100L) / 10D)));
        return true;
    }

    /** 清理插件内部物品标记后用于入库。 */
    private ItemStack sanitize(ItemStack itemStack) {
        return itemSnapshotMapper == null ? itemStack : itemSnapshotMapper.sanitizeForStorage(itemStack);
    }

    /** 创建包含当前可用名字的个人垃圾桶去向。 */
    private CleanupItemDestination personalDestination(UUID ownerUuid) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
        return CleanupItemDestination.personalTrash(ownerUuid,
                owner.getName() == null ? "" : owner.getName());
    }

    /** 在附属存在时转发变更；没有附属时保持空操作。 */
    private void recordMutation(TrashMutation mutation) {
        if (hasAuditConsumer()) {
            auditBridge.recordTrashMutation(mutation);
        }
    }

    /** 返回当前是否存在活动审计消费者。 */
    private boolean hasAuditConsumer() {
        return auditBridge != null && auditBridge.hasActiveConsumer();
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
            player.sendMessage(RichTextRenderer.color(player, text));
        }
    }

    /** 格式化个人垃圾桶提示中的物品列表。 */
    private String formatItemList(List<ItemStack> itemStacks) {
        List<NotificationEntry> entries = mergeEntries(itemStacks);
        int maxDisplay = config == null ? 3 : config.getNotifyMaxDisplayItems();
        int displayCount = Math.min(entries.size(), maxDisplay);
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < displayCount; index++) {
            parts.add(formatEntry(entries.get(index)));
        }
        if (entries.size() > maxDisplay) {
            parts.add(message("personal-trash.recycle.ellipsis", "&7..."));
        }
        String joined = join(parts, message("personal-trash.recycle.separator", "&7, "));
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

    /** 标题变化且菜单无人查看时才允许重建；保留给旧迁移单测。 */
    static boolean shouldRecreateInventory(String previousTitle, String nextTitle, boolean noViewers) {
        return noViewers && previousTitle != null && !previousTitle.equals(nextTitle);
    }

    /** 按原槽位迁移库存内容；保留给旧迁移单测。 */
    static void moveInventoryContents(Inventory source, Inventory target) {
        int size = Math.min(source.getSize(), target.getSize());
        for (int slot = 0; slot < size; slot++) {
            target.setItem(slot, source.getItem(slot));
        }
        source.clear();
    }

    /** 优先使用 Bukkit 已知的真实玩家名。 */
    private String resolveOwnerName(UUID ownerUuid, String fallbackName) {
        Player online = Bukkit.getPlayer(ownerUuid);
        if (online != null && online.getName() != null && !online.getName().trim().isEmpty()) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(ownerUuid);
        if (offline.getName() != null && !offline.getName().trim().isEmpty()) {
            return offline.getName();
        }
        return fallbackName == null || fallbackName.trim().isEmpty() ? "离线玩家" : fallbackName;
    }

    /** 返回当前个人桶显示模式名称。 */
    private String modeName() {
        return config == null || config.getMode() == null
                ? "disabled" : config.getMode().name().toLowerCase();
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? RichTextRenderer.color(replace(fallback, replacements))
                : messages.text(key, fallback, replacements);
    }

    /** 按玩家协议版本返回格式化消息。 */
    private String message(Player player, String key, String fallback, String... replacements) {
        return messages == null ? RichTextRenderer.color(player, replace(fallback, replacements))
                : messages.text(player, key, fallback, replacements);
    }

    /** 为没有消息服务的单元场景替换简单变量。 */
    private String replace(String value, String... replacements) {
        String result = value == null ? "" : value;
        if (replacements == null) {
            return result;
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            result = result.replace(replacements[index],
                    replacements[index + 1] == null ? "" : replacements[index + 1]);
        }
        return result;
    }

    /** 个人垃圾桶专属菜单策略。 */
    private final class PersonalMenuPolicy implements TrashContainerMenuPolicy {
        /** 返回个人桶后台日志作用域。 */
        @Override
        public String getLogName() {
            return "PersonalTrash";
        }

        /** 返回当前玩家自己的个人桶标题。 */
        @Override
        public String getTitle(Player player, int pageIndex, int maxPages) {
            return PersonalTrashService.this.message(player, "personal-trash.gui.title",
                    "&8{player} 的个人垃圾桶 {page}/{max}",
                    "{player}", resolveOwnerName(player.getUniqueId(), player.getName()),
                    "{page}", String.valueOf(pageIndex + 1),
                    "{max}", String.valueOf(maxPages));
        }

        /** 发送个人桶关闭提示。 */
        @Override
        public void sendDisabled(Player player) {
            player.sendMessage(PersonalTrashService.this.message(player,
                    "personal-trash.disabled", "&c个人垃圾桶未启用。"));
        }

        /** 判断个人桶取物权限。 */
        @Override
        public boolean canTake(Player player) {
            if (hasTrashPermission(player, "WorldListTrashCan.PersonalTrashTakeItem")) {
                return true;
            }
            player.sendMessage(PersonalTrashService.this.message(player,
                    "personal-trash.no-take-permission",
                    "&c你没有权限从个人垃圾桶取出物品。"));
            return false;
        }

        /** 执行个人桶冷却和 Vault 收费检查。 */
        @Override
        public boolean beforeTake(Player player, ItemStack itemStack, int requestedAmount) {
            if (isCoolingDown(player)) {
                return false;
            }
            double cost = config == null ? -1D : config.getTakeCost();
            if (cost <= 0D || paymentService.charge(player, cost)) {
                return true;
            }
            player.sendMessage(PersonalTrashService.this.message(player,
                    "personal-trash.not-enough-money",
                    "&c余额不足，无法取出该物品。"));
            return false;
        }

        /** 记录个人桶实际取物、冷却、付款提示和审计。 */
        @Override
        public void afterTake(Player player, ItemStack itemStack,
                              String trackingKey, int removedAmount) {
            lastTakeMillis.put(player.getUniqueId(), System.currentTimeMillis());
            double cost = config == null ? -1D : config.getTakeCost();
            if (cost > 0D) {
                player.sendMessage(PersonalTrashService.this.message(player,
                        "personal-trash.pay-success", "&a已支付 {cost}。",
                        "{cost}", paymentService.format(cost)));
            }
            if (hasAuditConsumer()) {
                recordMutation(TrashMutation.take(personalDestination(player.getUniqueId()), itemStack,
                        trackingKey, removedAmount, player.getUniqueId(), player.getName(),
                        System.currentTimeMillis()));
            }
        }

        /** 判断个人桶手动放入权限。 */
        @Override
        public boolean canManualPut(Player player, ItemStack itemStack) {
            return hasTrashPermission(player, "WorldListTrashCan.PersonalTrashPutItem");
        }

        /** 记录个人桶手动放入审计；手动放入永不触发自动清空。 */
        @Override
        public void afterManualPut(Player player, ItemStack itemStack,
                                   String trackingKey, int acceptedAmount) {
            if (hasAuditConsumer()) {
                recordMutation(TrashMutation.untrackedDeposit(personalDestination(player.getUniqueId()),
                        itemStack, trackingKey, acceptedAmount,
                        TrashMutationReason.MANUAL_DEPOSIT, System.currentTimeMillis()));
            }
        }

        /** 读取个人桶语言键。 */
        @Override
        public String message(String suffix, String fallback, String... replacements) {
            return PersonalTrashService.this.message("personal-trash." + suffix, fallback, replacements);
        }

        /** 发送个人桶背包空间不足提示。 */
        @Override
        public void onTakeInventoryFull(Player player) {
            player.sendMessage(PersonalTrashService.this.message(player,
                    "personal-trash.inventory-full",
                    "&c背包空间不足，无法取出该物品。"));
        }
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
