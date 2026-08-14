package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemRuleEvaluator;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.ProtectionConfig;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.EntityNameCodec;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 防丢弃、查询、聊天限频和简单优化功能。 */
public final class ProtectionFeature implements Feature, Listener {
    /** 防止异常 Lore 让 look 一次性刷屏。 */
    private static final int MAX_LOOK_LORE_LINES = 50;
    private static final int MAX_LOOK_DATA_KEYS = 50;
    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Supplier<ConfigBundle> configSupplier;
    private final BukkitMessageService messages;
    private final ItemRuleEvaluator itemRuleEvaluator;
    private final Set<UUID> dropProtectedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> lookPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> trackedArrowIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastChatMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCommandMillis = new ConcurrentHashMap<>();
    private boolean registered;

    /** 创建保护功能。 */
    public ProtectionFeature(Plugin plugin, ServerPlatform platform, Supplier<ConfigBundle> configSupplier,
                             BukkitMessageService messages) {
        this.plugin = plugin;
        this.platform = platform;
        this.configSupplier = configSupplier;
        this.messages = messages;
        this.itemRuleEvaluator = new ItemRuleEvaluator(platform.itemSnapshotMapper());
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "protections";
    }

    /** 注册监听器。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    /** 重载时清理运行态缓存。 */
    @Override
    public void reload() {
        lastChatMillis.clear();
        lastCommandMillis.clear();
        trackedArrowIds.clear();
    }

    /** 取消注册监听器。 */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        registered = false;
        dropProtectedPlayers.clear();
        lookPlayers.clear();
        trackedArrowIds.clear();
        lastChatMillis.clear();
        lastCommandMillis.clear();
    }

    /** 切换玩家防丢弃模式。 */
    public boolean toggleDropProtection(Player player) {
        UUID uuid = player.getUniqueId();
        if (dropProtectedPlayers.remove(uuid)) {
            player.sendMessage(message("protection.drop-mode-off", "&a已关闭防丢弃模式。"));
            return false;
        }
        dropProtectedPlayers.add(uuid);
        player.sendMessage(message("protection.drop-mode-on", "&a已开启防丢弃模式。"));
        return true;
    }

    /** 让玩家进入下一次右键实体查询模式。 */
    public void armLook(Player player) {
        lookPlayers.add(player.getUniqueId());
        sendChunkEntitySummary(player);
        sendHandItem(player);
        player.sendMessage(message("protection.look-prompt", "&a请右键一个实体以查询实体名称。"));
    }

    /** 防丢弃模式下取消玩家丢弃。 */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ProtectionConfig config = configSupplier.get().getProtectionConfig();
        if (!config.isDropProtectionEnabled()) {
            return;
        }
        if (dropProtectedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(message("protection.drop-blocked", "&c防丢弃模式已阻止本次丢弃。"));
        }
    }

    /** 处理聊天防刷屏。 */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ProtectionConfig.RateLimitConfig config = configSupplier.get().getProtectionConfig().getChatRateLimit();
        if (!shouldLimit(player, config, lastChatMillis)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(RichTextRenderer.color(player, config.getMessage()));
        runConfiguredCommand(player, config.getCommand());
    }

    /** 处理命令防刷屏。 */
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        ProtectionConfig.CommandRateLimitConfig config = configSupplier.get().getProtectionConfig().getCommandRateLimit();
        if (config.isWhitelisted(event.getMessage())) {
            return;
        }
        if (!shouldLimit(player, config, lastCommandMillis)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(RichTextRenderer.color(player, config.getMessage()));
        runConfiguredCommand(player, config.getCommand());
    }

    /** 玩家离线时清理运行态。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        dropProtectedPlayers.remove(uuid);
        lookPlayers.remove(uuid);
        lastChatMillis.remove(uuid);
        lastCommandMillis.remove(uuid);
    }

    /** 记录无法拾取的箭矢。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!configSupplier.get().getProtectionConfig().isRemoveUnpickableArrow()) {
            return;
        }
        if (!(event.getProjectile() instanceof Arrow)) {
            return;
        }
        Entity shooter = event.getEntity();
        if (shooter instanceof Skeleton || bowHasInfinity(event.getBow())) {
            trackedArrowIds.add(event.getProjectile().getEntityId());
        }
    }

    /** 箭矢命中后清理不可拾取箭矢。 */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!configSupplier.get().getProtectionConfig().isRemoveUnpickableArrow()) {
            return;
        }
        if (!(event.getEntity() instanceof Arrow)) {
            return;
        }
        Arrow arrow = (Arrow) event.getEntity();
        if (trackedArrowIds.remove(arrow.getEntityId()) || isUnpickable(arrow)) {
            arrow.remove();
        }
    }

    /** 阻止实体踩踏农田。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        if (configSupplier.get().getProtectionConfig().isPreventFarmlandTrampling() && isFarmland(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 阻止玩家踩踏农田。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL
                && configSupplier.get().getProtectionConfig().isPreventFarmlandTrampling()
                && isFarmland(event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    /** 处理玩家右键实体查询。 */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!lookPlayers.remove(player.getUniqueId())) {
            return;
        }
        Entity entity = event.getRightClicked();
        String name = entity.getName();
        sendSuggestMessage(player, "protection.entity-result", "&a实体: &f{name} &7({type})", name,
                "{name}", name, "{type}", entity.getType().name());
        String customName = entity.getCustomName();
        if (customName == null || customName.isEmpty()) {
            player.sendMessage(message("protection.entity-custom-name-none",
                    "&8自定义名称: 未设置"));
            return;
        }
        String configName = EntityNameCodec.toConfigText(customName);
        String plainName = EntityNameCodec.stripColors(customName);
        sendSuggestMessage(player, "protection.entity-custom-name",
                "&a自定义名称（配置格式）: &f{name}", configName,
                "{name}", RichTextRenderer.escapeLiteralAmpersands(configName));
        sendSuggestMessage(player, "protection.entity-plain-name",
                "&a自定义名称（去除颜色）: &f{name}", plainName, "{name}", plainName);
    }

    /** 判断玩家当前操作是否应该被限频。 */
    private boolean shouldLimit(Player player, ProtectionConfig.RateLimitConfig config, Map<UUID, Long> cache) {
        if (!config.isEnabled() || player.isOp()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = cache.put(player.getUniqueId(), now);
        return last != null && now - last < config.getIntervalMillis();
    }

    /** 执行配置里的控制台命令。 */
    private void runConfiguredCommand(final Player player, String command) {
        final String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return;
        }
        platform.scheduler().runLater(new Runnable() {
            /** 在主线程执行配置命令。 */
            @Override
            public void run() {
                String finalCommand = normalized.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            }
        }, 1L);
    }

    /** 发送当前区块实体汇总。 */
    private void sendChunkEntitySummary(Player player) {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        for (Entity entity : player.getLocation().getChunk().getEntities()) {
            String name = entity.getName();
            counts.put(name, counts.containsKey(name) ? counts.get(name) + 1 : 1);
        }
        player.sendMessage(message("protection.chunk-entities-title", "&a当前区块实体:"));
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String text = "- " + entry.getKey() + (entry.getValue() > 1 ? " *" + entry.getValue() : "");
            sendSuggestMessage(player, "protection.chunk-entity-line", "&a{text}", entry.getKey(),
                    "{text}", text);
        }
    }

    /** 发送玩家手持物品的材质、显示名和 Lore。 */
    private void sendHandItem(Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        String material = itemStack == null ? "AIR" : itemStack.getType().name();
        sendSuggestMessage(player, "protection.hand-item", "&a手持物品: &f{material}", material,
                "{material}", material);
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.getDisplayName() != null
                && !meta.getDisplayName().trim().isEmpty()) {
            String displayName = meta.getDisplayName();
            sendSuggestMessage(player, "protection.hand-item-name", "&a物品显示名: &f{name}", displayName,
                    "{name}", displayName);
        }
        List<String> lore = meta == null ? null : meta.getLore();
        if (lore != null && !lore.isEmpty()) {
            player.sendMessage(message("protection.hand-item-lore-title", "&a物品 Lore:"));
            int shown = Math.min(MAX_LOOK_LORE_LINES, lore.size());
            for (int index = 0; index < shown; index++) {
                String line = lore.get(index) == null ? "" : lore.get(index);
                sendSuggestMessage(player, "protection.hand-item-lore-line", "&7- &f{line}", line,
                        "{line}", line);
            }
            if (lore.size() > shown) {
                player.sendMessage(message("protection.hand-item-lore-truncated",
                        "&8...已省略 {count} 行 Lore...", "{count}", String.valueOf(lore.size() - shown)));
            }
        }
        sendItemDataKeys(player, itemStack);
    }

    /** 向玩家展示 PDC key 和 Raw NBT 路径，不输出任何值。 */
    private void sendItemDataKeys(Player player, ItemStack itemStack) {
        if (!itemRuleEvaluator.isPdcReady()) {
            player.sendMessage(message("protection.hand-item-pdc-unavailable", "&8PDC: 当前版本不支持"));
        } else {
            sendDataKeyList(player, "protection.hand-item-pdc-title", "&a物品 PDC Keys:",
                    itemRuleEvaluator.pdcKeys(itemStack));
        }
        if (!itemRuleEvaluator.isNbtReady()) {
            player.sendMessage(message("protection.hand-item-nbt-unavailable", "&cRaw NBT key 读取不可用"));
        } else {
            sendDataKeyList(player, "protection.hand-item-nbt-title", "&a物品 Raw NBT Keys:",
                    itemRuleEvaluator.nbtKeyPaths(itemStack));
        }
    }

    /** 限量输出一类数据 key，并允许点击填入聊天框。 */
    private void sendDataKeyList(Player player, String titleKey, String titleFallback, Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        player.sendMessage(message(titleKey, titleFallback));
        int shown = 0;
        for (String key : keys) {
            if (shown >= MAX_LOOK_DATA_KEYS) {
                break;
            }
            sendSuggestMessage(player, "protection.hand-item-data-key-line", "&7- &f{key}", key,
                    "{key}", key);
            shown++;
        }
        if (keys.size() > shown) {
            player.sendMessage(message("protection.hand-item-data-key-truncated",
                    "&8...已省略 {count} 个 key...", "{count}", String.valueOf(keys.size() - shown)));
        }
    }

    /** 从原始颜色文本直接编译可点击组件，避免现代 Paper 拒绝旧式 RGB 字符串。 */
    private void sendSuggestMessage(Player player, String key, String fallback, String suggest,
                                    String... replacements) {
        String raw = messages == null ? replaceTokens(fallback, replacements)
                : messages.rawText(key, fallback, replacements);
        String safeSuggest = EntityNameCodec.toConfigText(suggest);
        player.spigot().sendMessage(RichTextRenderer.suggest(player, raw, safeSuggest));
    }

    /** 在消息服务不可用时完成最小变量替换。 */
    private String replaceTokens(String raw, String... replacements) {
        String result = raw == null ? "" : raw;
        if (replacements == null) {
            return result;
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            result = result.replace(replacements[index], replacements[index + 1]);
        }
        return result;
    }

    /** 判断弓是否带无限附魔。 */
    private boolean bowHasInfinity(ItemStack bow) {
        return bow != null && bow.containsEnchantment(Enchantment.ARROW_INFINITE);
    }

    /** 判断箭矢是否不可拾取。 */
    private boolean isUnpickable(Arrow arrow) {
        try {
            Object pickupStatus = arrow.getClass().getMethod("getPickupStatus").invoke(arrow);
            return pickupStatus != null && !"ALLOWED".equals(String.valueOf(pickupStatus));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /** 判断方块是否是农田。 */
    private boolean isFarmland(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        String name = type.name();
        return "SOIL".equals(name) || "FARMLAND".equals(name) || "LEGACY_SOIL".equals(name);
    }

    /** 转换颜色代码。 */
    private String color(String text) {
        return RichTextRenderer.color(text);
    }

    /** 返回格式化消息。 */
    private String message(String key, String fallback, String... replacements) {
        return messages == null ? color(fallback) : messages.text(key, fallback, replacements);
    }
}
