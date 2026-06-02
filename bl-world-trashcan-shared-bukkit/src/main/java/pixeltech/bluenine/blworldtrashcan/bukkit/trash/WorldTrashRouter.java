package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.storage.TrashLocation;
import pixeltech.bluenine.blworldtrashcan.storage.WorldTrashData;
import pixeltech.bluenine.blworldtrashcan.storage.WorldTrashStorage;

import java.io.IOException;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 垃圾桶路由实现，统一处理世界、个人和公共垃圾桶。 */
public final class WorldTrashRouter implements TrashRouter {
    private final Plugin plugin;
    private final WorldTrashStorage storage;
    private final GlobalTrashService globalTrashService;
    private final PersonalTrashService personalTrashService;
    private final Map<String, WorldTrashData> worldData = new HashMap<>();
    private TrashConfig trashConfig;
    private int skippedUnloadedChunkAccesses;

    /** 创建世界垃圾桶路由器。 */
    public WorldTrashRouter(Plugin plugin, WorldTrashStorage storage, GlobalTrashService globalTrashService,
                            PersonalTrashService personalTrashService, TrashConfig trashConfig) {
        this.plugin = plugin;
        this.storage = storage;
        this.globalTrashService = globalTrashService;
        this.personalTrashService = personalTrashService;
        this.trashConfig = trashConfig;
        reload(trashConfig);
    }

    /** 判断世界垃圾桶是否可用。 */
    @Override
    public boolean hasWorldTrash(World world, ItemStack itemStack) {
        if (trashConfig == null || !trashConfig.getWorldTrash().isEnabled()) {
            return false;
        }
        WorldTrashData data = getData(world);
        if (data == null || data.getLocations().isEmpty()) {
            return false;
        }
        return !data.getBannedMaterials().contains(itemStack.getType().name());
    }

    /** 判断个人垃圾桶是否有容量。 */
    @Override
    public boolean hasPersonalTrash(UUID ownerUuid, ItemStack itemStack) {
        return personalTrashService != null && personalTrashService.hasSpace(ownerUuid, itemStack);
    }

    /** 判断公共垃圾桶是否有容量。 */
    @Override
    public boolean hasGlobalTrash(ItemStack itemStack) {
        return globalTrashService != null && globalTrashService.hasSpace(itemStack);
    }

    /** 尝试按路由存放物品。 */
    @Override
    public boolean route(World world, UUID ownerUuid, ItemStack itemStack, TrashRoute route) {
        if (route == TrashRoute.PERSONAL_TRASH) {
            return personalTrashService != null && personalTrashService.addItem(ownerUuid, itemStack);
        }
        if (route == TrashRoute.GLOBAL_TRASH) {
            return globalTrashService != null && globalTrashService.addItem(itemStack);
        }
        if (route != TrashRoute.WORLD_TRASH) {
            return false;
        }
        WorldTrashData data = getData(world);
        if (data == null) {
            return false;
        }
        for (TrashLocation location : data.getLocations()) {
            Inventory inventory = getInventory(location);
            if (inventory != null && InventorySlotUtil.add(inventory, itemStack, 0, inventory.getSize())) {
                return true;
            }
        }
        return false;
    }

    /** 重载存储中的世界垃圾桶数据。 */
    @Override
    public void reload() {
        reload(trashConfig);
    }

    /** 重载配置和存储中的世界垃圾桶数据。 */
    public void reload(TrashConfig nextTrashConfig) {
        this.trashConfig = nextTrashConfig;
        worldData.clear();
        skippedUnloadedChunkAccesses = 0;
        try {
            Collection<WorldTrashData> all = storage.loadAll();
            for (WorldTrashData data : all) {
                worldData.put(normalize(data.getWorldName()), data);
            }
            plugin.getLogger().info("[TrashRouter] 已加载 " + worldData.size() + " 个世界垃圾桶数据。");
            if (isAllowLoadUnloadedChunks()) {
                plugin.getLogger().warning("[TrashRouter] world-trash.allow-load-unloaded-chunks 已开启，清理任务可能同步加载未加载区块。");
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("[TrashRouter] 加载世界垃圾桶数据失败: " + exception.getMessage());
        }
    }

    /** 添加世界垃圾桶位置。 */
    public boolean addWorldTrash(Block block, int defaultMaxCount) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        WorldTrashData data = getOrCreateData(block.getWorld(), defaultMaxCount);
        if (data.getMaxTrashCanCount() > 0 && data.getLocations().size() >= data.getMaxTrashCanCount()) {
            return false;
        }
        Set<TrashLocation> locations = new HashSet<>(data.getLocations());
        locations.add(toLocation(block));
        return save(new WorldTrashData(data.getWorldName(), locations, data.getBannedMaterials(), data.getMaxTrashCanCount()));
    }

    /** 删除世界垃圾桶位置。 */
    public boolean removeWorldTrash(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        WorldTrashData data = getData(block.getWorld());
        if (data == null || data.getLocations().isEmpty()) {
            return false;
        }
        Set<TrashLocation> locations = new HashSet<>(data.getLocations());
        boolean removed = locations.remove(toLocation(block));
        if (!removed) {
            return false;
        }
        return save(new WorldTrashData(data.getWorldName(), locations, data.getBannedMaterials(), data.getMaxTrashCanCount()));
    }

    /** 判断方块是否登记为世界垃圾桶。 */
    public boolean isWorldTrashBlock(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        WorldTrashData data = getData(block.getWorld());
        return data != null && data.getLocations().contains(toLocation(block));
    }

    /** 设置世界最大垃圾桶数量。 */
    public int addMaxCount(World world, int delta, int defaultMaxCount) {
        WorldTrashData data = getOrCreateData(world, defaultMaxCount);
        int current = data.getMaxTrashCanCount() <= 0 ? defaultMaxCount : data.getMaxTrashCanCount();
        int next = Math.max(defaultMaxCount, current + delta);
        save(new WorldTrashData(data.getWorldName(), data.getLocations(), data.getBannedMaterials(), next));
        return next;
    }

    /** 返回单个世界的已登记垃圾桶数量。 */
    public int getWorldTrashCount(World world) {
        WorldTrashData data = getData(world);
        return data == null ? 0 : data.getLocations().size();
    }

    /** 返回单个世界垃圾桶内的物品总数量。 */
    public int getWorldTrashStoredItemAmount(World world) {
        WorldTrashData data = getData(world);
        if (data == null) {
            return 0;
        }
        int amount = 0;
        for (TrashLocation location : data.getLocations()) {
            Inventory inventory = getInventory(location);
            if (inventory != null) {
                amount += countItemAmount(inventory);
            }
        }
        return amount;
    }

    /** 返回单个世界垃圾桶内的堆叠数量。 */
    public int getWorldTrashStoredStackCount(World world) {
        WorldTrashData data = getData(world);
        if (data == null) {
            return 0;
        }
        int count = 0;
        for (TrashLocation location : data.getLocations()) {
            Inventory inventory = getInventory(location);
            if (inventory != null) {
                count += countStacks(inventory);
            }
        }
        return count;
    }

    /** 返回单世界物品黑名单。 */
    public Set<String> getWorldBannedMaterials(World world) {
        WorldTrashData data = getData(world);
        if (data == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(data.getBannedMaterials());
    }

    /** 返回因为未加载区块而跳过的容器访问次数。 */
    public int getSkippedUnloadedChunkAccesses() {
        return skippedUnloadedChunkAccesses;
    }

    /** 保存单世界物品黑名单。 */
    public boolean setWorldBannedMaterials(World world, Set<String> materials, int defaultMaxCount) {
        if (world == null) {
            return false;
        }
        WorldTrashData data = getOrCreateData(world, defaultMaxCount);
        Set<String> normalized = new LinkedHashSet<>();
        if (materials != null) {
            for (String material : materials) {
                if (material != null && !material.trim().isEmpty()) {
                    normalized.add(material.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return save(new WorldTrashData(data.getWorldName(), data.getLocations(), normalized, data.getMaxTrashCanCount()));
    }

    /** 获取单世界数据。 */
    private WorldTrashData getData(World world) {
        if (world == null) {
            return null;
        }
        return worldData.get(normalize(world.getName()));
    }

    /** 获取或创建单世界数据。 */
    private WorldTrashData getOrCreateData(World world, int defaultMaxCount) {
        WorldTrashData data = getData(world);
        if (data != null) {
            return data;
        }
        return new WorldTrashData(world.getName(), Collections.<TrashLocation>emptySet(),
                Collections.<String>emptySet(), Math.max(0, defaultMaxCount));
    }

    /** 保存并刷新内存缓存。 */
    private boolean save(WorldTrashData data) {
        try {
            storage.save(data);
            worldData.put(normalize(data.getWorldName()), data);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("[TrashRouter] 保存世界垃圾桶数据失败: " + exception.getMessage());
            return false;
        }
    }

    /** 转成存储位置。 */
    private TrashLocation toLocation(Block block) {
        return new TrashLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** 获取容器背包。 */
    private Inventory getInventory(TrashLocation location) {
        World world = Bukkit.getWorld(location.getWorldName());
        if (world == null) {
            return null;
        }
        if (!isAllowLoadUnloadedChunks() && !isChunkLoaded(world, location)) {
            skippedUnloadedChunkAccesses++;
            return null;
        }
        BlockState state = world.getBlockAt(location.getX(), location.getY(), location.getZ()).getState();
        if (state instanceof InventoryHolder) {
            return ((InventoryHolder) state).getInventory();
        }
        return null;
    }

    /** 判断世界垃圾桶配置是否允许加载未加载区块。 */
    private boolean isAllowLoadUnloadedChunks() {
        return trashConfig != null && trashConfig.getWorldTrash().isAllowLoadUnloadedChunks();
    }

    /** 判断位置所在区块是否已经加载。 */
    private boolean isChunkLoaded(World world, TrashLocation location) {
        return world.isChunkLoaded(location.getX() >> 4, location.getZ() >> 4);
    }

    /** 统计背包内物品总数量。 */
    private int countItemAmount(Inventory inventory) {
        int amount = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!InventorySlotUtil.isEmpty(itemStack)) {
                amount += itemStack.getAmount();
            }
        }
        return amount;
    }

    /** 统计背包内堆叠数量。 */
    private int countStacks(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!InventorySlotUtil.isEmpty(inventory.getItem(slot))) {
                count++;
            }
        }
        return count;
    }

    /** 标准化世界名。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
