package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.logging.DebugOutput;
import pixeltech.bluenine.blworldtrashcan.bukkit.stacking.ItemQuantityService;
import pixeltech.bluenine.blworldtrashcan.bukkit.stacking.ItemStackingFeature;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Paper 与 Folia 共用的现代掉落物数量规则和事件处理。 */
public abstract class AbstractModernItemStackingFeature implements ItemStackingFeature, ItemQuantityService, Listener {
    private static final int MAX_DRAIN_STACKS_PER_ENTITY = 16;
    private static final int MAX_PENDING_PICKUPS = 4096;
    private static final String WORLD_TRASH_TRANSFER_METADATA = "worldlisttrashcan_world_trash_transfer";
    private static final Method PDC_COPY_TO_METHOD = findPdcCopyToMethod();
    private static final Set<String> CONFLICT_PLUGIN_NAMES = new HashSet<>();
    static {
        CONFLICT_PLUGIN_NAMES.add("rosestacker");
        CONFLICT_PLUGIN_NAMES.add("wildstacker");
        CONFLICT_PLUGIN_NAMES.add("ultimatestacker");
        CONFLICT_PLUGIN_NAMES.add("stackmob");
    }
    private final Plugin plugin;
    private final Supplier<ItemStackingConfig> configSupplier;
    private final NamespacedKey amountKey;
    private final NamespacedKey displayOwnedKey;
    private final NamespacedKey originalNameKey;
    private final NamespacedKey originalVisibleKey;
    private final NamespacedKey trackedOwnerKey;
    private final NamespacedKey damageRecoveryOwnerKey;
    private final NamespacedKey damageRecoveryUntilKey;
    private final Map<StackingChunkKey, DirtyChunk> dirtyChunks = new ConcurrentHashMap<>();
    private final Map<UUID, PickupPreparation> pendingPickups = new ConcurrentHashMap<>();
    private final Set<UUID> pendingPickupEntities = ConcurrentHashMap.newKeySet();
    private final Set<StackingChunkKey> knownStackChunks = ConcurrentHashMap.newKeySet();
    private final ItemStackingStateStore stateStore;
    private final AtomicBoolean stateSaveQueued = new AtomicBoolean(false);
    private final AtomicBoolean stateSaveDirty = new AtomicBoolean(false);
    private final AtomicLong mergedItems = new AtomicLong();
    private final AtomicLong mergeCandidates = new AtomicLong();
    private final AtomicLong mergeEvents = new AtomicLong();
    private final AtomicLong mergeAttempts = new AtomicLong();
    private final AtomicLong mergeDistanceMatches = new AtomicLong();
    private final AtomicLong mergeSuccesses = new AtomicLong();
    private final AtomicLong mergeWriteReadbackFailures = new AtomicLong();
    private final AtomicLong mergeRemoveReadbackFailures = new AtomicLong();
    private final AtomicLong spawnEvents = new AtomicLong();
    private final AtomicLong playerPickedItems = new AtomicLong();
    private final AtomicLong inventoryPickedItems = new AtomicLong();
    private final AtomicLong droppedQueueRequests = new AtomicLong();
    private final AtomicLong processRuns = new AtomicLong();
    private final AtomicLong scannedItems = new AtomicLong();
    private final AtomicLong delayedItems = new AtomicLong();
    private final AtomicLong dispatchedChunks = new AtomicLong();
    private volatile ItemStackingConfig config;
    private volatile ItemStackingMaterialPolicy materialPolicy;
    private volatile ItemStackingItemNameResolver itemNameResolver;
    private DebugOutput debugOutput = DebugOutput.disabled();
    private volatile boolean activeState;
    private volatile boolean enabled;
    private final ItemStackingLifecycle lifecycle;

    /** 创建现代掉落物堆叠基础实现。 */
    protected AbstractModernItemStackingFeature(Plugin plugin, Supplier<ItemStackingConfig> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.config = configSupplier.get();
        this.materialPolicy = ItemStackingMaterialPolicy.from(this.config);
        this.itemNameResolver = ItemStackingItemNameResolver.load(plugin, this.config);
        this.amountKey = new NamespacedKey(plugin, "stack_amount");
        this.displayOwnedKey = new NamespacedKey(plugin, "stack_display_owned");
        this.originalNameKey = new NamespacedKey(plugin, "stack_original_name");
        this.originalVisibleKey = new NamespacedKey(plugin, "stack_original_name_visible");
        this.trackedOwnerKey = new NamespacedKey(plugin, "player_uuid");
        this.damageRecoveryOwnerKey = new NamespacedKey(plugin, "damage_recovery_owner");
        this.damageRecoveryUntilKey = new NamespacedKey(plugin, "damage_recovery_until");
        this.stateStore = new ItemStackingStateStore(new File(plugin.getDataFolder(),
                "data/item-stacking-state.yml"));
        ItemStackingStateStore.State state = stateStore.load();
        this.activeState = state.isActive();
        this.lifecycle = new ItemStackingLifecycle(state.isDraining());
        this.knownStackChunks.addAll(state.getChunks());
    }

    /** 返回功能 ID。 */
    @Override
    public final String id() {
        return "item-stacking";
    }

    /** 注册事件并启动有预算的 dirty-chunk 处理器。 */
    @Override
    public final void enable() {
        if (enabled) {
            return;
        }
        String conflict = findEnabledConflict();
        if (conflict != null) {
            lifecycle.blockForConflict();
            activeState = true;
            plugin.getLogger().severe("[ItemStacking] 检测到冲突插件 " + conflict
                    + "，仅启动旧逻辑数量的安全排空，不会产生新聚集。");
        }
        enabled = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        enqueueInitiallyLoadedChunks();
        startProcessor();
        plugin.getLogger().info("[ItemStacking] 已启用能力驱动的地面掉落物逻辑堆叠"
                + (lifecycle.isDraining() ? "，正在继续上次排空" : "") + "。");
    }

    /** 重新读取独立配置，但不丢弃已有逻辑数量。 */
    @Override
    public final void reload() {
        this.config = configSupplier.get();
        this.materialPolicy = ItemStackingMaterialPolicy.from(this.config);
        this.itemNameResolver = ItemStackingItemNameResolver.load(plugin, this.config);
        if (enabled) {
            stopProcessor();
            startProcessor();
        }
        enqueueInitiallyLoadedChunks();
    }

    /** 停止监听和任务并保存状态。 */
    @Override
    public final void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        HandlerList.unregisterAll(this);
        clearPendingPickups();
        stopProcessor();
        dirtyChunks.clear();
        saveStateNow();
    }

    /** 更新逻辑堆叠模块的调试输出开关。 */
    public final void setDebugOutput(DebugOutput debugOutput) {
        this.debugOutput = debugOutput == null ? DebugOutput.disabled() : debugOutput;
    }

    /** 返回当前数量服务。 */
    @Override
    public final ItemQuantityService quantities() {
        return this;
    }

    /** 使用与地面悬浮名称相同的优先级解析任意物品显示名。 */
    @Override
    public final String resolve(ItemStack itemStack) {
        return itemNameResolver.resolve(itemStack);
    }

    /** 返回掉落物代表的实际数量并修复明显坏数据。 */
    @Override
    public final int getAmount(Item item) {
        if (!usable(item)) {
            return 0;
        }
        ItemStack stack = item.getItemStack();
        int physical = stack == null ? 0 : Math.max(0, stack.getAmount());
        Integer stored = item.getPersistentDataContainer().get(amountKey, PersistentDataType.INTEGER);
        if (stored == null) {
            return physical;
        }
        if (stored.intValue() < physical || stored.intValue() <= 0) {
            item.getPersistentDataContainer().remove(amountKey);
            restoreDisplay(item);
            return physical;
        }
        return stored.intValue();
    }

    /** 使用预期数量保护扣减，剩余为零时移除实体。 */
    @Override
    public final boolean setRemaining(Item item, int expectedAmount, int remainingAmount) {
        if (isPickupPending(item) || !usable(item) || getAmount(item) != expectedAmount || remainingAmount < 0) {
            return false;
        }
        if (remainingAmount == 0) {
            StackingChunkKey previousChunk = chunkKey(item);
            item.remove();
            if (item.isValid()) {
                return false;
            }
            enqueue(previousChunk, 0L);
            return true;
        }
        writeAmount(item, remainingAmount);
        if (!usable(item) || getAmount(item) != remainingAmount) {
            restoreLogicalAmount(item, expectedAmount);
            return false;
        }
        enqueue(item, 0L);
        return true;
    }

    /** 向扫地和其它数量消费者暴露短期原版拾取占用。 */
    @Override
    public final boolean isReserved(Item item) {
        return isPickupPending(item);
    }

    /** 把实际数量拆成原版合法物品快照。 */
    @Override
    public final void forEachStack(ItemStack sample, int amount, ItemStackConsumer consumer) {
        if (sample == null || consumer == null || amount <= 0) {
            return;
        }
        int max = Math.max(1, sample.getMaxStackSize());
        int remaining = amount;
        while (remaining > 0) {
            ItemStack copy = sample.clone();
            int moved = Math.min(max, remaining);
            copy.setAmount(moved);
            consumer.accept(copy);
            remaining -= moved;
        }
    }

    /** 返回命令可读的运行状态。 */
    @Override
    public final List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§b掉落物逻辑堆叠状态:");
        lines.add("§7- §f运行: §a" + enabled + " §7/ 排空: §e" + lifecycle.isDraining()
                + " §7/ 新合并阻断: §e" + lifecycle.isMergeBlocked());
        lines.add("§7- §f冲突阻断: §e" + lifecycle.isConflictBlocked());
        lines.add("§7- §f拾取事务: §a" + pendingPickups.size()
                + " §7/ 保护实体: §a" + pendingPickupEntities.size());
        lines.add("§7- §f活动数据标记: §a" + activeState);
        lines.add("§7- §fdirty chunk: §a" + dirtyChunks.size()
                + " §7/ 已知逻辑区块: §a" + knownStackChunks.size());
        lines.add("§7- §f已合并数量: §a" + mergedItems.get()
                + " §7/ 玩家拾取: §a" + playerPickedItems.get()
                + " §7/ 容器拾取: §a" + inventoryPickedItems.get());
        lines.add("§7- §f合并候选: §a" + mergeCandidates.get()
                + " §7/ 原版事件: §a" + mergeEvents.get()
                + " §7/ 尝试: §a" + mergeAttempts.get()
                + " §7/ 距离命中: §a" + mergeDistanceMatches.get()
                + " §7/ 成功: §a" + mergeSuccesses.get());
        lines.add("§7- §f写入回读失败: §a" + mergeWriteReadbackFailures.get()
                + " §7/ 移除回读失败: §a" + mergeRemoveReadbackFailures.get());
        lines.add("§7- §f扫描轮数: §a" + processRuns.get()
                + " §7/ 扫描物品: §a" + scannedItems.get()
                + " §7/ 延迟跳过: §a" + delayedItems.get()
                + " §7/ 调度区块: §a" + dispatchedChunks.get());
        lines.add("§7- §f队列丢弃请求: §a" + droppedQueueRequests.get());
        return lines;
    }

    /** 进入排空模式并提交所有已加载区块。 */
    @Override
    public final boolean requestDrain() {
        if (!enabled && !activeState && knownStackChunks.isEmpty()) {
            return false;
        }
        lifecycle.requestDrain();
        activeState = true;
        if (enabled) {
            enqueueInitiallyLoadedChunks();
            for (StackingChunkKey key : knownStackChunks) {
                enqueue(key, 0L);
            }
        }
        saveStateNow();
        plugin.getLogger().warning("[ItemStacking] 已进入排空模式；不会强制加载区块，未加载区块将在自然加载后处理。");
        return true;
    }

    /** 在没有同类插件冲突时恢复新掉落物聚集。 */
    @Override
    public final boolean resumeMerging() {
        if (!lifecycle.resumeMerging()) {
            return false;
        }
        if (!enabled) {
            enable();
        } else {
            enqueueInitiallyLoadedChunks();
        }
        saveStateNow();
        plugin.getLogger().info("[ItemStacking] 已恢复新掉落物的逻辑聚集。");
        return true;
    }

    /** 掉落物生成后只标记 dirty chunk。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onItemSpawn(ItemSpawnEvent event) {
        if (lifecycle.isMergeBlocked() || lifecycle.isConflictBlocked()) {
            return;
        }
        // ItemSpawnEvent 可能早于实体进入区块索引，此时 isValid() 仍为 false；只记录坐标并延后扫描。
        Item item = event.getEntity();
        if (!materialPolicy.isEnabled(item.getItemStack().getType())) {
            return;
        }
        spawnEvents.incrementAndGet();
        Location location = item.getLocation();
        enqueue(new StackingChunkKey(item.getWorld().getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4),
                Math.max(1, config.getMergeDelayTicks()));
    }

    /** 接管包含逻辑数量的原版合并事件。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onItemMerge(ItemMergeEvent event) {
        mergeEvents.incrementAndGet();
        Item source = event.getEntity();
        Item target = event.getTarget();
        if (isWorldTrashTransfer(source) || isWorldTrashTransfer(target)
                || isPickupPending(source) || isPickupPending(target)) {
            event.setCancelled(true);
            return;
        }
        if (!materialPolicy.isEnabled(source.getItemStack().getType())
                || !materialPolicy.isEnabled(target.getItemStack().getType())) {
            if (isManaged(source) || isManaged(target)) {
                event.setCancelled(true);
                enqueue(source, 0L);
                enqueue(target, 0L);
            }
            return;
        }
        if (!sameOwner(source, target) || !sameOriginalDisplay(source, target)
                || !sameRecoveryMetadata(source, target)
                || hasExternalEntityData(source) || hasExternalEntityData(target)) {
            event.setCancelled(true);
            return;
        }
        boolean managed = isManaged(source) || isManaged(target);
        if (lifecycle.isMergeBlocked() || lifecycle.isDraining() || lifecycle.isConflictBlocked()) {
            if (managed) {
                event.setCancelled(true);
            }
            return;
        }
        int waitTicks = Math.max(remainingMergeDelayTicks(source), remainingMergeDelayTicks(target));
        if (waitTicks > 0) {
            event.setCancelled(true);
            enqueue(source, waitTicks);
            enqueue(target, waitTicks);
            return;
        }
        // 事件回调只阻止原版合并；实际写入和移除延后到统一扫描，避免服务端事件收尾恢复实体。
        event.setCancelled(true);
        enqueue(target, 0L);
        enqueue(source, 0L);
    }

    /** 玩家或其它实体拾取逻辑堆叠时按实际交接数量处理。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onEntityPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (isWorldTrashTransfer(item)) {
            event.setCancelled(true);
            return;
        }
        if (isPickupPending(item)) {
            event.setCancelled(true);
            return;
        }
        int actual = getAmount(item);
        if (!isManaged(item)) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            int physical = physicalAmount(item);
            if (actual <= physical) {
                return;
            }
            // 原版实体只能理解物理 ItemStack；事务期间只暴露合法物理数量，收尾时再写回逻辑数量。
            PickupPreparation preparation = prepareVanillaPickup(item, actual, physical,
                    event);
            if (preparation == null || !startPickupPreparation(preparation)) {
                event.setCancelled(true);
            }
            return;
        }
        Player player = (Player) event.getEntity();
        UUID owner = item.getOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        InventoryInsertion insertion = insertIntoInventory(player.getInventory(), item.getItemStack(), actual);
        if (insertion.getAcceptedAmount() > 0
                && setRemaining(item, actual, actual - insertion.getAcceptedAmount())) {
            playerPickedItems.addAndGet(insertion.getAcceptedAmount());
            playPickupFeedback(player, item);
        } else {
            insertion.rollback();
        }
    }

    /** 在所有插件完成拾取事件后确认或回滚非玩家拾取拆分。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public final void onEntityPickupMonitor(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (item == null) {
            return;
        }
        UUID itemId = item.getUniqueId();
        if (itemId == null) {
            return;
        }
        PickupPreparation preparation = pendingPickups.get(itemId);
        if (preparation == null) {
            return;
        }
        if (preparation.event == event) {
            preparation.cancelled = event.isCancelled();
        }
    }

    /** 保留漏斗原版吸取、比较器更新和冷却，仅保护超出物理堆叠的逻辑余量。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        if (isWorldTrashTransfer(item) || isPickupPending(item)) {
            event.setCancelled(true);
            return;
        }
        int actual = getAmount(item);
        if (!isManaged(item)) {
            return;
        }
        if (!hasInventorySpace(event.getInventory(), item.getItemStack())) {
            event.setCancelled(true);
            return;
        }
        PickupPreparation preparation = prepareInventoryPickup(item, actual, event);
        if (preparation == null || !startPickupPreparation(preparation)) {
            event.setCancelled(true);
        }
    }

    /** 记录所有插件完成漏斗事件派发后的取消结果，不把 MONITOR 当作吸取完成。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public final void onInventoryPickupMonitor(InventoryPickupItemEvent event) {
        PickupPreparation preparation = pendingPickups.get(event.getItem().getUniqueId());
        if (preparation != null && preparation.event == event) {
            preparation.cancelled = event.isCancelled();
        }
    }

    /** 区块加载后恢复 PDC 数量显示或继续排空。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onChunkLoad(ChunkLoadEvent event) {
        enqueue(new StackingChunkKey(event.getWorld().getUID(),
                event.getChunk().getX(), event.getChunk().getZ()), 0L);
    }

    /** 区块卸载前记录其中仍存在插件管理数据的坐标。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onChunkUnload(ChunkUnloadEvent event) {
        StackingChunkKey key = new StackingChunkKey(event.getWorld().getUID(),
                event.getChunk().getX(), event.getChunk().getZ());
        if (containsManagedStack(event.getChunk())) {
            rememberLogicalChunk(key);
        }
        dirtyChunks.remove(key);
    }

    /** 短期拾取事务不允许自然消失；其它消失请求标记区块核对状态。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onItemDespawn(ItemDespawnEvent event) {
        if (isPickupPending(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        enqueue(event.getEntity(), 0L);
    }

    /** 同类堆叠插件运行期后加载时立即停止新聚集并进入排空。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public final void onConflictPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (!CONFLICT_PLUGIN_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        plugin.getLogger().severe("[ItemStacking] 运行中检测到冲突插件 " + name
                + "，已停止新聚集并进入安全排空。请勿同时启用两个掉落物堆叠实现。");
        lifecycle.blockForConflict();
        requestDrain();
    }

    /** 返回当前已经启用的同类堆叠插件名。 */
    private String findEnabledConflict() {
        for (Plugin candidate : plugin.getServer().getPluginManager().getPlugins()) {
            if (candidate != null && candidate.isEnabled()
                    && CONFLICT_PLUGIN_NAMES.contains(candidate.getName().toLowerCase(java.util.Locale.ROOT))) {
                return candidate.getName();
            }
        }
        return null;
    }

    /** 启动平台专用处理器。 */
    protected abstract void startProcessor();

    /** 停止平台专用处理器。 */
    protected abstract void stopProcessor();

    /** 提交一个区块到平台合法线程执行。 */
    protected abstract boolean dispatchChunk(StackingChunkKey key, long deadlineNanos);

    /** 在源掉落实体所属的合法线程中延迟执行拾取收尾。 */
    protected abstract boolean schedulePickupReconciliation(Item item, Runnable runnable, Runnable retired);

    /** 判断当前平台是否允许在本次任务线程读取指定区块。 */
    protected boolean canReadChunk(World world, int chunkX, int chunkZ) {
        return true;
    }

    /** 判断拾取收尾当前线程能否读取该实体；普通平台的主线程拥有全部实体。 */
    protected boolean canAccessPickupItem(Item item) {
        return true;
    }

    /** 异步保存状态快照。 */
    protected abstract void saveStateAsync(Runnable runnable);

    /** 返回插件实例。 */
    protected final Plugin plugin() {
        return plugin;
    }

    /** 返回当前配置快照。 */
    protected final ItemStackingConfig config() {
        return config;
    }

    /** 按数量和时间预算取出并分派 dirty chunk。 */
    protected final void processDirtyQueue() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = System.nanoTime() + config.getTimeBudgetMicros() * 1000L;
        int dispatched = 0;
        Iterator<Map.Entry<StackingChunkKey, DirtyChunk>> iterator = dirtyChunks.entrySet().iterator();
        while (iterator.hasNext() && dispatched < config.getMaxChunksPerRun()
                && System.nanoTime() < deadline) {
            Map.Entry<StackingChunkKey, DirtyChunk> entry = iterator.next();
            DirtyChunk dirty = entry.getValue();
            if (dirty.expiresAtMillis < now) {
                dirtyChunks.remove(entry.getKey(), dirty);
                continue;
            }
            if (dirty.readyAtMillis > now || !dirtyChunks.remove(entry.getKey(), dirty)) {
                continue;
            }
            if (dirty.needsFollowUp(now)) {
                enqueueDirty(entry.getKey(), dirty.followUp());
            }
            if (dispatchChunk(entry.getKey(), deadline)) {
                dispatchedChunks.incrementAndGet();
                dispatched++;
            }
        }
    }

    /** 在合法线程处理单个已加载区块。 */
    protected final void processChunk(World world, int chunkX, int chunkZ, boolean includeNeighbours,
                                      long deadlineNanos) {
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        StackingChunkKey center = new StackingChunkKey(world.getUID(), chunkX, chunkZ);
        ItemCollection collection = collectItems(world, chunkX, chunkZ, includeNeighbours, deadlineNanos);
        List<Item> items = collection.items;
        processRuns.incrementAndGet();
        scannedItems.addAndGet(items.size());
        boolean incompleteScan = !collection.centerComplete;
        if (incompleteScan) {
            enqueue(center, config.getProcessIntervalTicks());
        }
        if (lifecycle.isDraining()) {
            for (Item item : items) {
                if (System.nanoTime() >= deadlineNanos) {
                    enqueue(center, config.getProcessIntervalTicks());
                    break;
                }
                drainItem(item);
            }
        } else if (!lifecycle.isMergeBlocked() && !lifecycle.isConflictBlocked()) {
            mergeGroups(items, deadlineNanos);
        }
        rememberManagedItemChunks(items);
        if (containsManagedInChunk(items, center) || incompleteScan) {
            rememberLogicalChunk(center);
        } else if (knownStackChunks.remove(center)) {
            queueStateSave();
        }
        clearInactiveStateIfPossible();
        completeDrainIfPossible();
    }

    /** 把实体所在区块加入去重队列。 */
    protected final void enqueue(Item item, long delayTicks) {
        if (!usable(item)) {
            return;
        }
        Location location = item.getLocation();
        enqueue(new StackingChunkKey(item.getWorld().getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4), delayTicks);
    }

    /** 把坐标加入有界去重队列。 */
    protected final void enqueue(StackingChunkKey key, long delayTicks) {
        if (key == null || !enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        DirtyChunk next = new DirtyChunk(now + Math.max(0L, delayTicks) * 50L,
                now + config.getQueueTtlSeconds() * 1000L);
        enqueueDirty(key, next);
    }

    /** 合并或新增一个已经计算好时间边界的队列请求。 */
    private void enqueueDirty(StackingChunkKey key, DirtyChunk next) {
        while (true) {
            DirtyChunk current = dirtyChunks.get(key);
            if (current != null) {
                DirtyChunk merged = current.merge(next);
                if (dirtyChunks.replace(key, current, merged)) {
                    return;
                }
                continue;
            }
            if (dirtyChunks.size() >= config.getMaxQueuedChunks()) {
                droppedQueueRequests.incrementAndGet();
                return;
            }
            if (dirtyChunks.putIfAbsent(key, next) == null) {
                return;
            }
        }
    }

    /** 返回状态文件是否要求在总开关关闭后继续加载数量解释器。 */
    public static boolean hasActiveState(File pluginDataFolder) {
        if (pluginDataFolder == null) {
            return false;
        }
        File file = new File(pluginDataFolder, "data/item-stacking-state.yml");
        if (!file.isFile()) {
            return false;
        }
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                .getBoolean("active", false);
    }

    /** 收集中心区块或普通端相邻区块中的掉落物。 */
    private ItemCollection collectItems(World world, int chunkX, int chunkZ, boolean neighbours,
                                        long deadlineNanos) {
        List<Item> result = new ArrayList<>();
        boolean centerComplete = collectChunkItems(world, chunkX, chunkZ, result, deadlineNanos);
        if (!neighbours || result.size() >= config.getMaxItemsPerChunk()) {
            return new ItemCollection(result, centerComplete);
        }
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                if ((x == chunkX && z == chunkZ) || System.nanoTime() >= deadlineNanos
                        || result.size() >= config.getMaxItemsPerChunk()) {
                    continue;
                }
                if (!canReadChunk(world, x, z)) {
                    continue;
                }
                collectChunkItems(world, x, z, result, deadlineNanos);
            }
        }
        return new ItemCollection(result, centerComplete);
    }

    /** 从单个已加载区块收集本轮可处理物品，并返回本次是否扫描完整。 */
    private boolean collectChunkItems(World world, int chunkX, int chunkZ, List<Item> result,
                                      long deadlineNanos) {
        if (!world.isChunkLoaded(chunkX, chunkZ) || System.nanoTime() >= deadlineNanos) {
            return false;
        }
        Entity[] entities = world.getChunkAt(chunkX, chunkZ).getEntities();
        if (lifecycle.isDraining()) {
            return collectEntityArray(entities, result, deadlineNanos, true);
        }
        return collectEntityArray(entities, result, deadlineNanos, false);
    }

    /** 按模式从实体数组抽取有效掉落物，并返回是否看完全部实体。 */
    private boolean collectEntityArray(Entity[] entities, List<Item> result,
                                       long deadlineNanos, boolean logicalOnly) {
        for (Entity entity : entities) {
            if (System.nanoTime() >= deadlineNanos || result.size() >= config.getMaxItemsPerChunk()) {
                return false;
            }
            if (!(entity instanceof Item)) {
                continue;
            }
            Item item = (Item) entity;
            if (isPickupPending(item) || !usable(item) || (logicalOnly && !isManaged(item))
                    || (!logicalOnly && !isManaged(item)
                    && !materialPolicy.isEnabled(item.getItemStack().getType()))) {
                continue;
            }
            result.add(item);
        }
        return true;
    }

    /** 使用物品身份、owner 和空间网格执行附近聚集。 */
    private void mergeGroups(List<Item> items, long deadlineNanos) {
        Map<MergeKey, Map<SpatialCell, Deque<Item>>> groups = new HashMap<>();
        for (Item item : items) {
            if (System.nanoTime() >= deadlineNanos) {
                enqueue(item, config.getProcessIntervalTicks());
                return;
            }
            if (isWorldTrashTransfer(item)) {
                continue;
            }
            if (rebalanceToMaterialPolicy(item)) {
                continue;
            }
            int waitTicks = remainingMergeDelayTicks(item);
            if (waitTicks > 0) {
                delayedItems.incrementAndGet();
                enqueue(item, waitTicks);
                continue;
            }
            refreshManagedDisplay(item);
            if (hasExternalEntityData(item)) {
                continue;
            }
            MergeKey key = MergeKey.from(item, trackedOwner(item), recoveryMetadata(item),
                    originalDisplayName(item), originalDisplayVisible(item));
            if (key == null) {
                continue;
            }
            mergeCandidates.incrementAndGet();
            Map<SpatialCell, Deque<Item>> cells = groups.get(key);
            if (cells == null) {
                cells = new HashMap<>();
                groups.put(key, cells);
            }
            SpatialCell sourceCell = SpatialCell.from(item.getLocation(), config);
            mergeIntoNearbyTargets(item, sourceCell, cells, deadlineNanos);
            if (!usable(item)) {
                continue;
            }
            if (getAmount(item) < materialPolicy.maximumAmount(item.getItemStack().getType())) {
                Deque<Item> targets = cells.get(sourceCell);
                if (targets == null) {
                    targets = new ArrayDeque<>();
                    cells.put(sourceCell, targets);
                }
                targets.addLast(item);
            }
        }
    }

    /** 把来源物品尽可能转移到相邻空间格中的未满目标。 */
    private void mergeIntoNearbyTargets(Item source, SpatialCell sourceCell,
                                        Map<SpatialCell, Deque<Item>> cells, long deadlineNanos) {
        for (int offsetX = -1; offsetX <= 1 && usable(source); offsetX++) {
            for (int offsetY = -1; offsetY <= 1 && usable(source); offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1 && usable(source); offsetZ++) {
                    if (System.nanoTime() >= deadlineNanos) {
                        enqueue(source, config.getProcessIntervalTicks());
                        return;
                    }
                    SpatialCell neighbour = sourceCell.offset(offsetX, offsetY, offsetZ);
                    Deque<Item> targets = cells.get(neighbour);
                    if (targets != null) {
                        mergeIntoTargetQueue(source, targets);
                        if (targets.isEmpty()) {
                            cells.remove(neighbour);
                        }
                    }
                }
            }
        }
    }

    /** 清理失效或已满目标，并把来源转移到仍有容量的目标。 */
    private void mergeIntoTargetQueue(Item source, Deque<Item> targets) {
        Iterator<Item> iterator = targets.iterator();
        while (iterator.hasNext() && usable(source)) {
            Item target = iterator.next();
            if (!usable(target) || getAmount(target)
                    >= materialPolicy.maximumAmount(target.getItemStack().getType())) {
                iterator.remove();
                continue;
            }
            if (withinRadius(target, source)) {
                mergeDistanceMatches.incrementAndGet();
                mergeInto(target, source);
            }
            if (!usable(target) || getAmount(target)
                    >= materialPolicy.maximumAmount(target.getItemStack().getType())) {
                iterator.remove();
            }
        }
    }

    /** 把来源可接收的数量转移到目标。 */
    private void mergeInto(Item target, Item source) {
        mergeAttempts.incrementAndGet();
        if (!usable(target) || !usable(source) || target.equals(source)
                || isWorldTrashTransfer(target) || isWorldTrashTransfer(source)
                || !sameOwner(target, source) || !sameOriginalDisplay(target, source)
                || !sameRecoveryMetadata(target, source)
                || !target.getItemStack().isSimilar(source.getItemStack())) {
            return;
        }
        int targetAmount = getAmount(target);
        int sourceAmount = getAmount(source);
        int capacity = materialPolicy.maximumAmount(target.getItemStack().getType()) - targetAmount;
        int moved = Math.min(Math.max(0, capacity), sourceAmount);
        if (moved <= 0) {
            return;
        }
        writeAmount(target, targetAmount + moved);
        if (getAmount(target) != targetAmount + moved) {
            mergeWriteReadbackFailures.incrementAndGet();
            restoreLogicalAmount(target, targetAmount);
            return;
        }
        if (moved >= sourceAmount) {
            StackingChunkKey previousChunk = chunkKey(source);
            source.remove();
            if (source.isValid()) {
                mergeRemoveReadbackFailures.incrementAndGet();
                restoreLogicalAmount(target, targetAmount);
                return;
            }
            enqueue(previousChunk, 0L);
        } else {
            writeAmount(source, sourceAmount - moved);
            if (!usable(source) || getAmount(source) != sourceAmount - moved) {
                mergeWriteReadbackFailures.incrementAndGet();
                restoreLogicalAmount(target, targetAmount);
                restoreLogicalAmount(source, sourceAmount);
                return;
            }
        }
        mergedItems.addAndGet(moved);
        mergeSuccesses.incrementAndGet();
    }

    /** 在合并事务失败时恢复实体原来的逻辑数量。 */
    private boolean restoreLogicalAmount(Item item, int amount) {
        if (!usable(item) || amount <= 0) {
            return false;
        }
        writeAmount(item, amount);
        return usable(item) && getAmount(item) == amount;
    }

    /** 把一个逻辑实体按预算拆回原版实体并返回是否仍需继续。 */
    private boolean drainItem(Item item) {
        if (isWorldTrashTransfer(item)) {
            return true;
        }
        int actual = getAmount(item);
        int physical = physicalAmount(item);
        if (actual <= physical) {
            restoreDisplay(item);
            return false;
        }
        ItemStack sample = item.getItemStack().clone();
        sample.setAmount(1);
        int max = Math.max(1, sample.getMaxStackSize());
        int remaining = actual;
        int spawned = 0;
        while (remaining > max && spawned < MAX_DRAIN_STACKS_PER_ENTITY) {
            ItemStack split = sample.clone();
            split.setAmount(max);
            Item dropped = item.getWorld().dropItem(item.getLocation(), split);
            dropped.setVelocity(item.getVelocity());
            dropped.setPickupDelay(item.getPickupDelay());
            dropped.setOwner(item.getOwner());
            String trackedOwner = trackedOwner(item);
            if (trackedOwner != null) {
                dropped.getPersistentDataContainer().set(trackedOwnerKey, PersistentDataType.STRING, trackedOwner);
            }
            copyRecoveryMetadata(item, dropped);
            dropped.setCustomName(originalDisplayName(item));
            dropped.setCustomNameVisible(originalDisplayVisible(item));
            remaining -= max;
            spawned++;
        }
        if (remaining <= max) {
            writePhysicalAmount(item, remaining);
            restoreDisplay(item);
            return false;
        }
        writeAmount(item, remaining);
        enqueue(item, config.getProcessIntervalTicks());
        return true;
    }

    /** 按逐物品启用状态和上限修正实体；返回 true 表示本轮不再参与合并。 */
    private boolean rebalanceToMaterialPolicy(Item item) {
        if (!usable(item)) {
            return true;
        }
        Material material = item.getItemStack().getType();
        if (!materialPolicy.isEnabled(material)) {
            if (isManaged(item)) {
                drainItem(item);
            }
            return true;
        }
        int maximum = materialPolicy.maximumAmount(material);
        if (getAmount(item) <= maximum) {
            return false;
        }
        splitAboveMaterialMaximum(item, maximum);
        return true;
    }

    /** 按单轮实体预算把超出逐物品上限的数量无损拆开。 */
    private void splitAboveMaterialMaximum(Item item, int maximum) {
        int remaining = getAmount(item);
        int spawned = 0;
        while (usable(item) && remaining > maximum && spawned < MAX_DRAIN_STACKS_PER_ENTITY) {
            int previous = remaining;
            int next = remaining - maximum;
            Item split = spawnPolicySplit(item, maximum);
            if (split == null) {
                return;
            }
            writeAmount(item, next);
            if (!usable(item) || getAmount(item) != next) {
                split.remove();
                restoreLogicalAmount(item, previous);
                return;
            }
            remaining = next;
            spawned++;
        }
        if (usable(item) && remaining > maximum) {
            enqueue(item, config.getProcessIntervalTicks());
        }
    }

    /** 在原实体附近生成一个继承归属和原始名称的拆分实体。 */
    private Item spawnPolicySplit(Item source, int amount) {
        ItemStack sample = source.getItemStack().clone();
        sample.setAmount(Math.min(Math.max(1, sample.getMaxStackSize()), amount));
        String owner = trackedOwner(source);
        String originalName = originalDisplayName(source);
        boolean originalVisible = originalDisplayVisible(source);
        Item split = null;
        try {
            split = source.getWorld().dropItem(source.getLocation(), sample);
            if (split == null) {
                return null;
            }
            // 新旧 API 对实体 PDC 的复制方法不同，必须运行时探测，避免旧端链接失败。
            if (!copyEntityData(source, split)) {
                split.remove();
                return null;
            }
            split.setVelocity(source.getVelocity());
            split.setPickupDelay(source.getPickupDelay());
            split.setOwner(source.getOwner());
            if (owner != null) {
                split.getPersistentDataContainer().set(trackedOwnerKey, PersistentDataType.STRING, owner);
            }
            copyRecoveryMetadata(source, split);
            split.setCustomName(originalName);
            split.setCustomNameVisible(originalVisible);
            writeAmount(split, amount);
            if (usable(split) && getAmount(split) == amount) {
                return split;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[ItemStacking] 按逐物品上限拆分掉落物失败: "
                    + exception.getMessage());
        }
        if (split != null && split.isValid()) {
            split.remove();
        }
        return null;
    }

    /** 满库存直接保留来源，避免每次失败吸取都生成一个临时余量实体。 */
    private boolean hasInventorySpace(Inventory inventory, ItemStack sample) {
        int maximum = Math.min(sample.getMaxStackSize(), inventory.getMaxStackSize());
        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType() == Material.AIR
                    || (current.getAmount() < maximum && current.isSimilar(sample))) {
                return true;
            }
        }
        return false;
    }

    /** 为漏斗暴露合法物理堆叠，并在原版可能移除来源之前保存超额余量。 */
    private PickupPreparation prepareInventoryPickup(Item item, int actualAmount,
                                                      InventoryPickupItemEvent event) {
        int physical = physicalAmount(item);
        if (!usable(item) || actualAmount < physical || physical <= 0
                || pendingPickups.size() >= MAX_PENDING_PICKUPS) {
            return null;
        }
        Item remainder = actualAmount > physical ? spawnPolicySplit(item, actualAmount - physical) : null;
        if (actualAmount > physical && remainder == null) {
            return null;
        }
        PickupPreparation preparation = new PickupPreparation(item, remainder, actualAmount,
                physical, -1, event);
        writePhysicalAmount(item, physical);
        restoreDisplay(item);
        if (usable(item) && physicalAmount(item) == physical
                && getAmount(item) == physical && !isManaged(item)) {
            return preparation;
        }
        rollbackVanillaPickup(preparation);
        return null;
    }

    /** 注册有界短期事务并使用存活余量实体承接 Folia 的下一 tick 收尾。 */
    private boolean startPickupPreparation(final PickupPreparation preparation) {
        final UUID sourceId = preparation.source.getUniqueId();
        if (sourceId == null || pendingPickups.size() >= MAX_PENDING_PICKUPS) {
            rollbackVanillaPickup(preparation);
            return false;
        }
        pendingPickups.put(sourceId, preparation);
        pendingPickupEntities.add(sourceId);
        addPendingPickupEntity(preparation.remainder);
        Item anchor = preparation.event instanceof InventoryPickupItemEvent && preparation.remainder != null
                ? preparation.remainder : preparation.source;
        Runnable completion = new Runnable() {
            /** 原版吸取结束或实体 retired 后，只消费一次对应事务。 */
            @Override
            public void run() {
                reconcileVanillaPickup(sourceId);
            }
        };
        if (schedulePickupReconciliation(anchor, completion, completion)) {
            return true;
        }
        pendingPickups.remove(sourceId, preparation);
        rollbackVanillaPickup(preparation);
        releasePickupProtection(preparation);
        return false;
    }

    /** 为非玩家实体准备原版可识别的物理数量，并按事件结果保留逻辑余量。 */
    private PickupPreparation prepareVanillaPickup(Item item, int actualAmount, int physicalAmount,
                                                   EntityPickupItemEvent event) {
        int expectedRemaining = event == null ? -1 : event.getRemaining();
        if (!usable(item) || actualAmount <= physicalAmount || physicalAmount <= 0
                || expectedRemaining < 0 || expectedRemaining > physicalAmount) {
            return null;
        }
        Item remainder = null;
        if (expectedRemaining == 0) {
            int logicalRemainder = actualAmount - physicalAmount;
            if (logicalRemainder <= 0) {
                return null;
            }
            remainder = spawnPolicySplit(item, logicalRemainder);
            if (remainder == null) {
                return null;
            }
        }
        writePhysicalAmount(item, physicalAmount);
        restoreDisplay(item);
        if (usable(item) && physicalAmount(item) == physicalAmount
                && getAmount(item) == physicalAmount && !isManaged(item)) {
            int predictedLogicalRemaining = actualAmount + expectedRemaining - physicalAmount;
            if (remainder == null && predictedLogicalRemaining > 0) {
                updateDisplay(item, predictedLogicalRemaining);
            }
            return new PickupPreparation(item, remainder, actualAmount, physicalAmount,
                    expectedRemaining, event);
        }
        if (remainder != null && remainder.isValid()) {
            remainder.remove();
        }
        if (usable(item)) {
            restoreLogicalAmount(item, actualAmount);
        }
        return null;
    }

    /** 回滚被其它插件取消的非玩家拾取准备，始终保持总数量不丢失。 */
    private void rollbackVanillaPickup(PickupPreparation preparation) {
        Item source = preparation.source;
        Item remainder = preparation.remainder;
        if (!usable(source)) {
            if (usable(remainder) && restoreLogicalAmount(remainder, preparation.originalAmount)) {
                plugin.getLogger().warning("[ItemStacking] 非玩家拾取被取消时源实体已失效，已将原逻辑数量转移到剩余实体: "
                        + remainder.getUniqueId());
            } else {
                plugin.getLogger().warning("[ItemStacking] 非玩家拾取被取消但源实体无法恢复，原版事件可能已移除物品: "
                        + (source == null ? "null" : source.getUniqueId()));
            }
            return;
        }
        if (remainder == null) {
            if (!restoreLogicalAmount(source, preparation.originalAmount)) {
                plugin.getLogger().warning("[ItemStacking] 非玩家拾取被取消但无法恢复原逻辑数量: "
                        + source.getUniqueId());
            }
            return;
        }
        if (!remainder.isValid()) {
            if (!restoreLogicalAmount(source, preparation.originalAmount)) {
                plugin.getLogger().warning("[ItemStacking] 非玩家拾取被取消但无法恢复原逻辑数量: "
                        + source.getUniqueId());
            }
            return;
        }
        if (!restoreLogicalAmount(source, preparation.originalAmount)) {
            // 源实体仍是合法的物理数量，剩余实体继续保存逻辑余量，不能删除剩余实体。
            return;
        }
        remainder.remove();
        if (remainder.isValid()) {
            // 删除失败时恢复为“物理数量 + 逻辑余量”，避免源实体和剩余实体重复计算。
            writePhysicalAmount(source, preparation.physicalAmount);
            restoreDisplay(source);
            plugin.getLogger().warning("[ItemStacking] 非玩家拾取被取消且剩余实体无法移除，已保留拆分后的无损状态: "
                    + source.getUniqueId());
        }
    }

    /** 在原版拾取完成后按事件结果校正源实体，并释放事务保护。 */
    private void reconcileVanillaPickup(UUID sourceId) {
        final PickupPreparation preparation = pendingPickups.get(sourceId);
        if (preparation == null) {
            return;
        }
        if (preparation.event instanceof InventoryPickupItemEvent && preparation.source.isValid()
                && !canAccessPickupItem(preparation.source)) {
            // 余量跟随自己的 region，源实体可能已移动；最多转交一次到源实体所属线程。
            if (preparation.handoffQueued.compareAndSet(false, true)) {
                Runnable completion = new Runnable() {
                    /** 在源实体所属 region 重新核对原版物理余量。 */
                    @Override
                    public void run() {
                        reconcileVanillaPickup(preparation.source.getUniqueId());
                    }
                };
                if (schedulePickupReconciliation(preparation.source, completion, completion)) {
                    return;
                }
            }
            if (pendingPickups.remove(sourceId, preparation)) {
                releasePickupProtection(preparation);
                plugin.getLogger().warning("[ItemStacking] 漏斗收尾无法转交源 region，已保留原版物理余量与逻辑余量: " + sourceId);
            }
            return;
        }
        if (!pendingPickups.remove(sourceId, preparation)) {
            return;
        }
        try {
            if (preparation.event instanceof InventoryPickupItemEvent && preparation.remainder != null
                    && preparation.remainder.isValid() && !canAccessPickupItem(preparation.remainder)) {
                // 两个实体分属不同 region 时不强行合并；它们已经分别保存正确的真实数量。
                if (!preparation.cancelled && !preparation.event.isCancelled()) {
                    int observed = isLiveItem(preparation.source) ? physicalAmount(preparation.source) : 0;
                    inventoryPickedItems.addAndGet(Math.max(0, preparation.physicalAmount - observed));
                }
                return;
            }
            if (preparation.cancelled || preparation.event.isCancelled()) {
                rollbackVanillaPickup(preparation);
                return;
            }
            if (preparation.event instanceof InventoryPickupItemEvent) {
                reconcileInventoryPickup(preparation);
                return;
            }
            Item source = preparation.source;
            if (!isLiveItem(source)) {
                // 源实体只有在事件剩余为零时才会被原版移除，此时余量实体保存逻辑数量。
                if (preparation.remainder == null) {
                    plugin.getLogger().warning("[ItemStacking] 非玩家拾取收尾发现源实体意外失效，无法确认逻辑余量: "
                            + source.getUniqueId());
                }
                return;
            }
            int observedPhysical = physicalAmount(source);
            if (preparation.remainder != null) {
                // 余量实体已经保存了原逻辑余量；源实体若暂时仍有效，只能保留当前物理数量，不能再次写入逻辑标记。
                // 否则原版延迟移除源实体时会同时留下“源逻辑数量 + 余量实体”的重复数量。
                if (observedPhysical <= 0) {
                    StackingChunkKey previousChunk = chunkKey(source);
                    source.remove();
                    if (!source.isValid()) {
                        enqueue(previousChunk, 0L);
                    }
                } else {
                    writePhysicalAmount(source, observedPhysical);
                    restoreDisplay(source);
                }
                return;
            }
            if (observedPhysical < 0 || observedPhysical > preparation.physicalAmount) {
                // 物理数量上升代表事务窗口有外部变更；按净变化计算，避免吞掉外部增加的物品。
                int logicalRemaining = preparation.originalAmount
                        + observedPhysical - preparation.physicalAmount;
                if (logicalRemaining > 0 && writeLogicalPickupResult(source, logicalRemaining)) {
                    plugin.getLogger().warning("[ItemStacking] 非玩家拾取收尾发现物理数量异常变化，已按净变化保留数量: "
                            + source.getUniqueId() + ", prepared=" + preparation.physicalAmount
                            + ", actual=" + observedPhysical + ", logical=" + logicalRemaining);
                } else {
                    plugin.getLogger().warning("[ItemStacking] 非玩家拾取收尾发现非法物理数量，无法安全对账: "
                            + source.getUniqueId() + ", prepared=" + preparation.physicalAmount
                            + ", actual=" + observedPhysical);
                }
                return;
            }
            int vanillaRemaining = observedPhysical;
            if (observedPhysical == preparation.physicalAmount
                    && preparation.expectedRemaining < observedPhysical) {
                // 某些实现的事件收尾可能暂时没有反映到 Bukkit 包装层，使用事件结果兜底校正。
                vanillaRemaining = preparation.expectedRemaining;
            }
            int logicalRemaining = preparation.originalAmount + vanillaRemaining
                    - preparation.physicalAmount;
            if (logicalRemaining <= 0) {
                StackingChunkKey previousChunk = chunkKey(source);
                source.remove();
                if (source.isValid()) {
                    plugin.getLogger().warning("[ItemStacking] 非玩家拾取应移除源实体但移除失败: "
                            + source.getUniqueId());
                } else {
                    enqueue(previousChunk, 0L);
                }
                return;
            }
            if (!writeLogicalPickupResult(source, logicalRemaining)) {
                plugin.getLogger().warning("[ItemStacking] 非玩家拾取收尾无法校正源实体物理数量: "
                        + source.getUniqueId() + ", expectedLogical=" + logicalRemaining
                        + ", actual=" + physicalAmount(source));
            }
        } finally {
            releasePickupProtection(preparation);
        }
    }

    /** 读取漏斗原版实际扣减，并把未吸取的物理数量与受保护余量无损合并。 */
    private void reconcileInventoryPickup(PickupPreparation preparation) {
        Item source = preparation.source;
        Item remainder = preparation.remainder;
        int observed = isLiveItem(source) ? Math.max(0, physicalAmount(source)) : 0;
        int accepted = Math.max(0, preparation.physicalAmount - observed);
        inventoryPickedItems.addAndGet(accepted);
        if (observed == 0) {
            if (isLiveItem(source)) {
                source.remove();
            }
            if (usable(remainder)) {
                enqueue(remainder, 0L);
            }
            return;
        }
        if (remainder == null) {
            if (writeLogicalPickupResult(source, observed)) {
                enqueue(source, 0L);
            }
            return;
        }
        if (!usable(remainder)) {
            plugin.getLogger().warning("[ItemStacking] 漏斗吸取收尾时受保护余量已失效: " + source.getUniqueId());
            return;
        }
        int logicalRemaining = getAmount(remainder) + observed;
        if (writeLogicalPickupResult(source, logicalRemaining)) {
            remainder.remove();
            if (remainder.isValid()) {
                // 余量移除失败时恢复拆分状态，防止重复保存同一批余量。
                writePhysicalAmount(source, observed);
                restoreDisplay(source);
            }
        } else {
            // 写回失败时仍保留“原版未吸取数量 + 逻辑余量”的两个真实来源。
            writePhysicalAmount(source, observed);
            restoreDisplay(source);
        }
        enqueue(source, 0L);
        if (usable(remainder)) {
            enqueue(remainder, 0L);
        }
    }

    /** 判断掉落实体仍可读取和修改，即使它当前物理数量暂时为零。 */
    private boolean isLiveItem(Item item) {
        return item != null && item.isValid() && !item.isDead()
                && item.getItemStack() != null
                && item.getItemStack().getType() != Material.AIR;
    }

    /** 判断掉落实体是否正处于等待原版拾取收尾的事务中。 */
    private boolean isPickupPending(Item item) {
        UUID itemId = item == null ? null : item.getUniqueId();
        return itemId != null && pendingPickupEntities.contains(itemId);
    }

    /** 释放源实体和拆分实体的拾取事务保护。 */
    private void releasePickupProtection(PickupPreparation preparation) {
        pendingPickupEntities.remove(preparation.source.getUniqueId());
        removePendingPickupEntity(preparation.remainder);
    }

    /** 将存在的拾取事务实体加入保护集合。 */
    private void addPendingPickupEntity(Item item) {
        if (item != null && item.getUniqueId() != null) {
            pendingPickupEntities.add(item.getUniqueId());
        }
    }

    /** 将存在的拾取事务实体移出保护集合。 */
    private void removePendingPickupEntity(Item item) {
        if (item != null && item.getUniqueId() != null) {
            pendingPickupEntities.remove(item.getUniqueId());
        }
    }

    /** 插件关闭时清理所有待处理事务，避免跨生命周期泄漏引用。 */
    private void clearPendingPickups() {
        for (PickupPreparation preparation : new ArrayList<>(pendingPickups.values())) {
            if (preparation.cancelled || preparation.event.isCancelled()) {
                // 事件已经被取消，原版不会发放奖励，必须撤销本次临时拆分。
                rollbackVanillaPickup(preparation);
            } else {
                // MONITOR 只代表事件派发完成，不代表原版已经扣减物理 ItemStack；
                // 关闭时不能提前按 getRemaining() 对账，否则原版随后还会再次扣减。
                // 没有预先拆分余量的事务，要先物化逻辑余量，再解除保护，避免关闭时丢失 PDC 数量。
                preserveAcceptedPickupOnDisable(preparation);
                pendingPickups.remove(preparation.source.getUniqueId(), preparation);
                releasePickupProtection(preparation);
            }
        }
        pendingPickups.clear();
        pendingPickupEntities.clear();
    }

    /** 插件关闭前把尚未拆分的逻辑余量放到独立实体，保持原版后续收尾安全。 */
    private void preserveAcceptedPickupOnDisable(PickupPreparation preparation) {
        if (preparation.remainder != null) {
            return;
        }
        Item source = preparation.source;
        int logicalRemainder = preparation.originalAmount - preparation.physicalAmount;
        if (logicalRemainder <= 0) {
            return;
        }
        if (!usable(source)) {
            plugin.getLogger().warning("[ItemStacking] 插件关闭时无法物化非玩家拾取逻辑余量: "
                    + (source == null ? "null" : source.getUniqueId())
                    + ", remainder=" + logicalRemainder);
            return;
        }
        Item remainder = spawnPolicySplit(source, logicalRemainder);
        if (remainder != null) {
            // 源实体只保留当前物理数量，不能继续显示拾取事务期间的预测逻辑数量。
            restoreDisplay(source);
            return;
        }

        // 生成实体失败时把当前物理源合并回逻辑数量，至少保证数量不被静默吞掉。
        int observedPhysical = physicalAmount(source);
        int logicalAmount = preparation.originalAmount + observedPhysical - preparation.physicalAmount;
        if (logicalAmount > 0 && writeLogicalPickupResult(source, logicalAmount)) {
            plugin.getLogger().warning("[ItemStacking] 插件关闭时无法生成逻辑余量实体，已回退为单实体保存: "
                    + source.getUniqueId() + ", logical=" + logicalAmount);
            return;
        }
        plugin.getLogger().severe("[ItemStacking] 插件关闭时无法安全保存非玩家拾取逻辑数量，需立即检查掉落实体: "
                + source.getUniqueId());
    }

    /** 返回当前运行时是否提供实体 PDC 的复制方法。 */
    private static Method findPdcCopyToMethod() {
        try {
            return PersistentDataContainer.class.getMethod("copyTo",
                    PersistentDataContainer.class, Boolean.TYPE);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /** 跨 Bukkit/Paper 版本复制实体 PDC；能力不存在时仅允许无外部 PDC 的安全拆分。 */
    private boolean copyEntityData(Item source, Item target) {
        if (PDC_COPY_TO_METHOD != null) {
            try {
                PDC_COPY_TO_METHOD.invoke(source.getPersistentDataContainer(),
                        target.getPersistentDataContainer(), Boolean.TRUE);
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                if (hasExternalEntityData(source)) {
                    debugOutput.trace(() -> "[ItemStacking] 当前端无法复制外部实体 PDC，已放弃拆分: "
                            + exception.getClass().getSimpleName());
                    return false;
                }
            }
        }
        return !hasExternalEntityData(source);
    }

    /** 记录一次等待事件最终结果的拾取事务。 */
    private final class PickupPreparation {
        private final Item source;
        private final Item remainder;
        private final int originalAmount;
        private final int physicalAmount;
        private final int expectedRemaining;
        private final Cancellable event;
        private volatile boolean cancelled;
        private final AtomicBoolean handoffQueued = new AtomicBoolean(false);

        /** 创建拾取事务记录。 */
        private PickupPreparation(Item source, Item remainder, int originalAmount, int physicalAmount,
                                  int expectedRemaining, Cancellable event) {
            this.source = source;
            this.remainder = remainder;
            this.originalAmount = originalAmount;
            this.physicalAmount = physicalAmount;
            this.expectedRemaining = expectedRemaining;
            this.event = event;
        }

    }

    /** 写入实际数量、物理展示数量和状态标记。 */
    private void writeAmount(Item item, int actualAmount) {
        if (!usable(item) || actualAmount <= 0) {
            return;
        }
        ItemStack stack = item.getItemStack();
        int max = Math.max(1, stack.getMaxStackSize());
        int physical = Math.min(max, actualAmount);
        stack.setAmount(physical);
        item.setItemStack(stack);
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (actualAmount > physical) {
            pdc.set(amountKey, PersistentDataType.INTEGER, Integer.valueOf(actualAmount));
        } else {
            pdc.remove(amountKey);
        }
        updateDisplay(item, actualAmount);
        if (isManaged(item)) {
            markActive(item);
        }
    }

    /** 写入不超过原版上限的数量并移除逻辑标记。 */
    private void writePhysicalAmount(Item item, int amount) {
        if (!usable(item) || amount <= 0) {
            return;
        }
        ItemStack stack = item.getItemStack();
        stack.setAmount(amount);
        // 事件收尾前若物理数量已经相同，不替换服务端内部 ItemStack 引用。
        if (physicalAmount(item) != amount) {
            item.setItemStack(stack);
        }
        item.getPersistentDataContainer().remove(amountKey);
    }

    /** 将非玩家拾取结果写回逻辑实体，物理数量始终保持原版合法范围。 */
    private boolean writeLogicalPickupResult(Item item, int logicalAmount) {
        if (!isLiveItem(item) || logicalAmount <= 0) {
            return false;
        }
        writeAmount(item, logicalAmount);
        return usable(item) && getAmount(item) == logicalAmount;
    }

    /** 更新或恢复掉落物悬浮名称。 */
    private void updateDisplay(Item item, int actualAmount) {
        if (!config.isDisplayNameEnabled() || actualAmount <= 1) {
            restoreDisplay(item);
            return;
        }
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (!pdc.has(displayOwnedKey, PersistentDataType.BYTE)) {
            String original = item.getCustomName();
            if (original != null) {
                pdc.set(originalNameKey, PersistentDataType.STRING, original);
            }
            pdc.set(originalVisibleKey, PersistentDataType.BYTE,
                    Byte.valueOf(item.isCustomNameVisible() ? (byte) 1 : (byte) 0));
            pdc.set(displayOwnedKey, PersistentDataType.BYTE, Byte.valueOf((byte) 1));
        }
        String display = config.getDisplayNameFormat()
                .replace("{name}", displayItemName(item.getItemStack()))
                .replace("{amount}", String.valueOf(actualAmount));
        item.setCustomName(RichTextRenderer.color(display));
        item.setCustomNameVisible(true);
    }

    /** 恢复接管悬浮名称前的状态。 */
    private void restoreDisplay(Item item) {
        if (!usable(item)) {
            return;
        }
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (!pdc.has(displayOwnedKey, PersistentDataType.BYTE)) {
            return;
        }
        String original = pdc.get(originalNameKey, PersistentDataType.STRING);
        Byte visible = pdc.get(originalVisibleKey, PersistentDataType.BYTE);
        item.setCustomName(original);
        item.setCustomNameVisible(visible != null && visible.byteValue() != 0);
        pdc.remove(displayOwnedKey);
        pdc.remove(originalNameKey);
        pdc.remove(originalVisibleKey);
    }

    /** 按重载后的显示配置刷新已有受管实体，不接管普通原版掉落物。 */
    private void refreshManagedDisplay(Item item) {
        if (isManaged(item)) {
            updateDisplay(item, getAmount(item));
        }
    }

    /** 返回物品用于悬浮名称的名称。 */
    private String displayItemName(ItemStack stack) {
        return resolve(stack);
    }

    /** 尽可能向库存插入数量并返回可精确回滚的槽位收据。 */
    private InventoryInsertion insertIntoInventory(Inventory inventory, ItemStack sample, int requested) {
        if (inventory == null || sample == null || requested <= 0) {
            return InventoryInsertion.empty();
        }
        List<SlotChange> changes = new ArrayList<>();
        int inventoryMax = inventory.getMaxStackSize();
        int max = Math.max(1, Math.min(sample.getMaxStackSize(),
                inventoryMax <= 0 ? sample.getMaxStackSize() : inventoryMax));
        int remaining = requested;
        int storageSize = inventory.getStorageContents().length;
        for (int slot = 0; slot < storageSize && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current != null && current.isSimilar(sample) && current.getAmount() < max) {
                int moved = Math.min(remaining, max - current.getAmount());
                changes.add(new SlotChange(slot, current.clone()));
                ItemStack updated = current.clone();
                updated.setAmount(current.getAmount() + moved);
                inventory.setItem(slot, updated);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < storageSize && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR || current.getAmount() <= 0) {
                int moved = Math.min(remaining, max);
                changes.add(new SlotChange(slot, null));
                ItemStack inserted = sample.clone();
                inserted.setAmount(moved);
                inventory.setItem(slot, inserted);
                remaining -= moved;
            }
        }
        return new InventoryInsertion(inventory, requested - remaining, changes);
    }

    /** 播放最基本的拾取反馈。 */
    private void playPickupFeedback(Player player, Item item) {
        try {
            player.playSound(player.getLocation(), "entity.item.pickup", 0.2F, 1.8F);
        } catch (RuntimeException ignored) {
            debugOutput.trace(() -> "[ItemStacking] 当前端无法播放逻辑拾取音效: " + item.getUniqueId());
        }
    }

    /** 判断两个掉落物 owner 是否一致。 */
    private boolean sameOwner(Item first, Item second) {
        UUID firstOwner = first.getOwner();
        UUID secondOwner = second.getOwner();
        boolean sameBukkitOwner = firstOwner == null ? secondOwner == null : firstOwner.equals(secondOwner);
        String firstTrackedOwner = trackedOwner(first);
        String secondTrackedOwner = trackedOwner(second);
        boolean sameTrackedOwner = firstTrackedOwner == null
                ? secondTrackedOwner == null : firstTrackedOwner.equals(secondTrackedOwner);
        return sameBukkitOwner && sameTrackedOwner;
    }

    /** 返回主插件用于个人垃圾桶路由的实体归属标记。 */
    private String trackedOwner(Item item) {
        return item == null ? null
                : item.getPersistentDataContainer().get(trackedOwnerKey, PersistentDataType.STRING);
    }

    /** 返回尚未过期的损坏回收元数据；过期数据会立即从实体移除。 */
    private RecoveryMetadata recoveryMetadata(Item item) {
        if (item == null) {
            return null;
        }
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        String owner = pdc.get(damageRecoveryOwnerKey, PersistentDataType.STRING);
        Long expiresAt = pdc.get(damageRecoveryUntilKey, PersistentDataType.LONG);
        if (owner == null || expiresAt == null) {
            if (owner != null || expiresAt != null) {
                clearRecoveryMetadata(item);
            }
            return null;
        }
        if (expiresAt.longValue() <= System.currentTimeMillis() || !isUuid(owner)) {
            clearRecoveryMetadata(item);
            return null;
        }
        return new RecoveryMetadata(owner, expiresAt.longValue());
    }

    /** 判断两个掉落物的损坏回收归属和到期时间是否完全一致。 */
    private boolean sameRecoveryMetadata(Item first, Item second) {
        RecoveryMetadata firstMetadata = recoveryMetadata(first);
        RecoveryMetadata secondMetadata = recoveryMetadata(second);
        return firstMetadata == null ? secondMetadata == null : firstMetadata.equals(secondMetadata);
    }

    /** 把损坏回收元数据复制到拆分后的新实体。 */
    private void copyRecoveryMetadata(Item source, Item target) {
        RecoveryMetadata metadata = recoveryMetadata(source);
        if (metadata == null) {
            clearRecoveryMetadata(target);
            return;
        }
        PersistentDataContainer pdc = target.getPersistentDataContainer();
        pdc.set(damageRecoveryOwnerKey, PersistentDataType.STRING, metadata.owner);
        pdc.set(damageRecoveryUntilKey, PersistentDataType.LONG, Long.valueOf(metadata.expiresAtMillis));
    }

    /** 清理实体上的损坏回收元数据。 */
    private void clearRecoveryMetadata(Item item) {
        if (item == null) {
            return;
        }
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.remove(damageRecoveryOwnerKey);
        pdc.remove(damageRecoveryUntilKey);
    }

    /** 校验 PDC 中保存的归属确实是一个 UUID，避免脏数据参与路由。 */
    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 表示一个实体当前可用于损坏回收的临时归属。 */
    private static final class RecoveryMetadata {
        private final String owner;
        private final long expiresAtMillis;

        /** 创建损坏回收元数据。 */
        private RecoveryMetadata(String owner, long expiresAtMillis) {
            this.owner = owner;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** 比较损坏回收元数据。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RecoveryMetadata)) {
                return false;
            }
            RecoveryMetadata metadata = (RecoveryMetadata) other;
            return expiresAtMillis == metadata.expiresAtMillis && owner.equals(metadata.owner);
        }

        /** 返回损坏回收元数据哈希。 */
        @Override
        public int hashCode() {
            int result = owner.hashCode();
            return 31 * result + (int) (expiresAtMillis ^ (expiresAtMillis >>> 32));
        }
    }

    /** 判断插件接管悬浮名称前的实体名称和可见状态是否一致。 */
    private boolean sameOriginalDisplay(Item first, Item second) {
        String firstName = originalDisplayName(first);
        String secondName = originalDisplayName(second);
        boolean sameName = firstName == null ? secondName == null : firstName.equals(secondName);
        return sameName && originalDisplayVisible(first) == originalDisplayVisible(second);
    }

    /** 返回插件接管前的实体自定义名称。 */
    private String originalDisplayName(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        return pdc.has(displayOwnedKey, PersistentDataType.BYTE)
                ? pdc.get(originalNameKey, PersistentDataType.STRING) : item.getCustomName();
    }

    /** 返回插件接管前的实体名称可见状态。 */
    private boolean originalDisplayVisible(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (!pdc.has(displayOwnedKey, PersistentDataType.BYTE)) {
            return item.isCustomNameVisible();
        }
        Byte visible = pdc.get(originalVisibleKey, PersistentDataType.BYTE);
        return visible != null && visible.byteValue() != 0;
    }

    /** 判断实体 PDC 是否包含其它插件数据；这类实体保持原样，不参与逻辑聚集。 */
    private boolean hasExternalEntityData(Item item) {
        if (item == null) {
            return false;
        }
        for (NamespacedKey key : item.getPersistentDataContainer().getKeys()) {
            if (!amountKey.equals(key) && !displayOwnedKey.equals(key)
                    && !originalNameKey.equals(key) && !originalVisibleKey.equals(key)
                    && !trackedOwnerKey.equals(key) && !damageRecoveryOwnerKey.equals(key)
                    && !damageRecoveryUntilKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 判断两个实体距离是否在配置范围内。 */
    private boolean withinRadius(Item first, Item second) {
        if (!first.getWorld().equals(second.getWorld())) {
            return false;
        }
        Location a = first.getLocation();
        Location b = second.getLocation();
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double dy = Math.abs(a.getY() - b.getY());
        double radius = config.getHorizontalRadius();
        return dx * dx + dz * dz <= radius * radius && dy <= config.getVerticalRadius();
    }

    /** 返回实体当前物理 ItemStack 数量。 */
    private int physicalAmount(Item item) {
        return item == null || item.getItemStack() == null ? 0 : Math.max(0, item.getItemStack().getAmount());
    }

    /** 返回掉落物距离允许主动聚集还需等待的 tick。 */
    private int remainingMergeDelayTicks(Item item) {
        if (!usable(item)) {
            return 0;
        }
        return Math.max(0, config.getMergeDelayTicks() - item.getTicksLived());
    }

    /** 判断掉落物仍可安全操作。 */
    private boolean usable(Item item) {
        return item != null && item.isValid() && !item.isDead() && item.getItemStack() != null
                && item.getItemStack().getType() != Material.AIR && item.getItemStack().getAmount() > 0;
    }

    /** 判断掉落物是否正在由 Folia 世界垃圾桶事务占用。 */
    private boolean isWorldTrashTransfer(Item item) {
        return item != null && item.hasMetadata(WORLD_TRASH_TRANSFER_METADATA);
    }

    /** 判断区块内是否仍有插件需要在关闭时收口的数据。 */
    private boolean containsManagedStack(Chunk chunk) {
        if (chunk == null) {
            return false;
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item && isManaged((Item) entity)) {
                return true;
            }
        }
        return false;
    }

    /** 记录收集结果中每个受管理实体真实所在的区块。 */
    private void rememberManagedItemChunks(List<Item> items) {
        for (Item item : items) {
            if (!usable(item) || !isManaged(item)) {
                continue;
            }
            Location location = item.getLocation();
            rememberLogicalChunk(new StackingChunkKey(item.getWorld().getUID(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4));
        }
    }

    /** 判断指定中心区块内是否仍有插件管理实体。 */
    private boolean containsManagedInChunk(List<Item> items, StackingChunkKey key) {
        for (Item item : items) {
            if (!usable(item) || !isManaged(item)) {
                continue;
            }
            Location location = item.getLocation();
            if (item.getWorld().getUID().equals(key.getWorldUuid())
                    && (location.getBlockX() >> 4) == key.getChunkX()
                    && (location.getBlockZ() >> 4) == key.getChunkZ()) {
                return true;
            }
        }
        return false;
    }

    /** 判断实体是否带有逻辑数量或插件接管的悬浮显示。 */
    private boolean isManaged(Item item) {
        if (!usable(item)) {
            return false;
        }
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        return pdc.has(amountKey, PersistentDataType.INTEGER)
                || pdc.has(displayOwnedKey, PersistentDataType.BYTE);
    }

    /** 启动时把所有已加载区块加入队列，不加载任何新区块。 */
    private void enqueueInitiallyLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                enqueue(new StackingChunkKey(world.getUID(), chunk.getX(), chunk.getZ()), 0L);
            }
        }
    }

    /** 标记首次产生逻辑堆叠并同步落盘活动标记。 */
    private void markActive(Item item) {
        StackingChunkKey key = chunkKey(item);
        rememberLogicalChunk(key);
        if (!activeState) {
            activeState = true;
            saveStateNow();
        }
    }

    /** 记录可能包含逻辑实体的区块。 */
    private void rememberLogicalChunk(StackingChunkKey key) {
        if (key != null && knownStackChunks.add(key)) {
            queueStateSave();
        }
    }

    /** 返回物品当前区块键，供实体移除前保留坐标。 */
    private StackingChunkKey chunkKey(Item item) {
        if (item == null || item.getWorld() == null) {
            return null;
        }
        Location location = item.getLocation();
        return new StackingChunkKey(item.getWorld().getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** 正常运行时在最后一个逻辑区块消失后清理活动标记。 */
    private void clearInactiveStateIfPossible() {
        if (lifecycle.isDraining() || !knownStackChunks.isEmpty() || !activeState) {
            return;
        }
        activeState = false;
        queueStateSave();
    }

    /** 全部已知区块排空后清理活动标记。 */
    private void completeDrainIfPossible() {
        if (!lifecycle.isDraining() || !knownStackChunks.isEmpty()) {
            return;
        }
        lifecycle.completeDrain();
        activeState = false;
        dirtyChunks.clear();
        saveStateNow();
        disable();
        plugin.getLogger().info("[ItemStacking] 逻辑堆叠排空完成；监听器和周期任务已停止。");
    }

    /** 合并短时间内的状态保存请求。 */
    private void queueStateSave() {
        stateSaveDirty.set(true);
        if (!stateSaveQueued.compareAndSet(false, true)) {
            return;
        }
        saveStateAsync(new Runnable() {
            /** 持续写入最新隔离快照，直到没有合并期间的新变化。 */
            @Override
            public void run() {
                boolean saved = false;
                try {
                    while (stateSaveDirty.getAndSet(false)) {
                        stateStore.save(activeState, lifecycle.isDraining(), new HashSet<>(knownStackChunks));
                    }
                    saved = true;
                } catch (IOException error) {
                    stateSaveDirty.set(true);
                    plugin.getLogger().warning("[ItemStacking] 保存逻辑堆叠状态失败: " + error.getMessage());
                } finally {
                    stateSaveQueued.set(false);
                    if (saved && stateSaveDirty.get()) {
                        queueStateSave();
                    }
                }
            }
        });
    }

    /** 立即保存关键状态变化。 */
    private void saveStateNow() {
        try {
            stateStore.save(activeState, lifecycle.isDraining(), new HashSet<>(knownStackChunks));
        } catch (IOException error) {
            plugin.getLogger().warning("[ItemStacking] 保存关键状态失败: " + error.getMessage());
        }
    }

    /** dirty chunk 的就绪和过期时间。 */
    static final class DirtyChunk {
        private final long readyAtMillis;
        private final long latestReadyAtMillis;
        private final long expiresAtMillis;

        /** 保存任务时间边界。 */
        DirtyChunk(long readyAtMillis, long expiresAtMillis) {
            this(readyAtMillis, readyAtMillis, expiresAtMillis);
        }

        /** 保存合并后最早与最晚请求时间。 */
        private DirtyChunk(long readyAtMillis, long latestReadyAtMillis, long expiresAtMillis) {
            this.readyAtMillis = readyAtMillis;
            this.latestReadyAtMillis = latestReadyAtMillis;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** 合并重复请求，保留最早执行和最晚补排时间并延长有效期。 */
        DirtyChunk merge(DirtyChunk other) {
            return new DirtyChunk(Math.min(readyAtMillis, other.readyAtMillis),
                    Math.max(latestReadyAtMillis, other.latestReadyAtMillis),
                    Math.max(expiresAtMillis, other.expiresAtMillis));
        }

        /** 判断本轮执行后是否仍有尚未到期的延迟请求。 */
        boolean needsFollowUp(long nowMillis) {
            return latestReadyAtMillis > nowMillis && expiresAtMillis >= latestReadyAtMillis;
        }

        /** 创建只包含最后一次延迟请求的补排条目。 */
        DirtyChunk followUp() {
            return new DirtyChunk(latestReadyAtMillis, latestReadyAtMillis, expiresAtMillis);
        }

        /** 返回最早可执行时间，供回归测试核对。 */
        long getReadyAtMillis() {
            return readyAtMillis;
        }

        /** 返回最后一次延迟请求时间，供回归测试核对。 */
        long getLatestReadyAtMillis() {
            return latestReadyAtMillis;
        }
    }

    /** 单轮物品收集结果及中心区块完整性。 */
    private static final class ItemCollection {
        private final List<Item> items;
        private final boolean centerComplete;

        /** 保存本轮物品列表和中心区块是否看完。 */
        private ItemCollection(List<Item> items, boolean centerComplete) {
            this.items = items;
            this.centerComplete = centerComplete;
        }
    }

    /** 可比较的物品身份与 owner 分组键。 */
    private static final class MergeKey {
        private final ItemStack sample;
        private final UUID owner;
        private final String trackedOwner;
        private final RecoveryMetadata recoveryMetadata;
        private final String entityName;
        private final boolean entityNameVisible;
        private final int hash;

        /** 创建分组键。 */
        private MergeKey(ItemStack sample, UUID owner, String trackedOwner,
                         RecoveryMetadata recoveryMetadata, String entityName,
                         boolean entityNameVisible) {
            this.sample = sample;
            this.owner = owner;
            this.trackedOwner = trackedOwner;
            this.recoveryMetadata = recoveryMetadata;
            this.entityName = entityName;
            this.entityNameVisible = entityNameVisible;
            int result = 31 * sample.getType().hashCode() + (owner == null ? 0 : owner.hashCode());
            result = 31 * result + (trackedOwner == null ? 0 : trackedOwner.hashCode());
            result = 31 * result + (recoveryMetadata == null ? 0 : recoveryMetadata.hashCode());
            result = 31 * result + (entityName == null ? 0 : entityName.hashCode());
            this.hash = 31 * result + (entityNameVisible ? 1 : 0);
        }

        /** 从有效掉落物创建分组键。 */
        private static MergeKey from(Item item, String trackedOwner,
                                     RecoveryMetadata recoveryMetadata, String entityName,
                                     boolean entityNameVisible) {
            if (item == null || item.getItemStack() == null) {
                return null;
            }
            ItemStack sample = item.getItemStack().clone();
            sample.setAmount(1);
            return new MergeKey(sample, item.getOwner(), trackedOwner, recoveryMetadata, entityName,
                    entityNameVisible);
        }

        /** 使用 ItemStack 相似语义和 owner 判断相等。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MergeKey)) {
                return false;
            }
            MergeKey key = (MergeKey) other;
            boolean sameOwner = owner == null ? key.owner == null : owner.equals(key.owner);
            boolean sameTrackedOwner = trackedOwner == null
                    ? key.trackedOwner == null : trackedOwner.equals(key.trackedOwner);
            boolean sameRecoveryMetadata = recoveryMetadata == null
                    ? key.recoveryMetadata == null : recoveryMetadata.equals(key.recoveryMetadata);
            boolean sameName = entityName == null ? key.entityName == null : entityName.equals(key.entityName);
            return sameOwner && sameTrackedOwner && sameRecoveryMetadata && sameName
                    && entityNameVisible == key.entityNameVisible
                    && sample.isSimilar(key.sample);
        }

        /** 返回预计算哈希。 */
        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** 合并半径对应的三维空间格。 */
    private static final class SpatialCell {
        private final int x;
        private final int y;
        private final int z;

        /** 创建空间格坐标。 */
        private SpatialCell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 按当前合并半径把实体位置映射到空间格。 */
        private static SpatialCell from(Location location, ItemStackingConfig config) {
            return new SpatialCell(
                    floorCell(location.getX(), config.getHorizontalRadius()),
                    floorCell(location.getY(), config.getVerticalRadius()),
                    floorCell(location.getZ(), config.getHorizontalRadius()));
        }

        /** 返回指定偏移的相邻空间格。 */
        private SpatialCell offset(int offsetX, int offsetY, int offsetZ) {
            return new SpatialCell(x + offsetX, y + offsetY, z + offsetZ);
        }

        /** 对负坐标也使用向下取整的稳定格坐标。 */
        private static int floorCell(double coordinate, double cellSize) {
            return (int) Math.floor(coordinate / cellSize);
        }

        /** 比较空间格坐标。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SpatialCell)) {
                return false;
            }
            SpatialCell cell = (SpatialCell) other;
            return x == cell.x && y == cell.y && z == cell.z;
        }

        /** 返回空间格哈希。 */
        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            return 31 * result + z;
        }
    }

    /** 单个库存槽位修改前的快照。 */
    private static final class SlotChange {
        private final int slot;
        private final ItemStack previous;

        /** 保存槽位和修改前内容。 */
        private SlotChange(int slot, ItemStack previous) {
            this.slot = slot;
            this.previous = previous;
        }
    }

    /** 一次库存插入及其精确回滚信息。 */
    private static final class InventoryInsertion {
        private final Inventory inventory;
        private final int acceptedAmount;
        private final List<SlotChange> changes;

        /** 创建库存插入收据。 */
        private InventoryInsertion(Inventory inventory, int acceptedAmount, List<SlotChange> changes) {
            this.inventory = inventory;
            this.acceptedAmount = acceptedAmount;
            this.changes = changes;
        }

        /** 返回不包含修改的空收据。 */
        private static InventoryInsertion empty() {
            return new InventoryInsertion(null, 0, Collections.<SlotChange>emptyList());
        }

        /** 返回实际插入数量。 */
        private int getAcceptedAmount() {
            return acceptedAmount;
        }

        /** 恢复本次插入修改过的全部槽位。 */
        private void rollback() {
            if (inventory == null || changes.isEmpty()) {
                return;
            }
            for (int index = changes.size() - 1; index >= 0; index--) {
                SlotChange change = changes.get(index);
                inventory.setItem(change.slot, change.previous == null ? null : change.previous.clone());
            }
        }
    }
}
