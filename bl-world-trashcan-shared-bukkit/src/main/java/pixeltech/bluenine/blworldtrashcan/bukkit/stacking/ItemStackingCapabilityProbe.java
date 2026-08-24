package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只在服主请求启用时反射核对逻辑堆叠所需 API。 */
public final class ItemStackingCapabilityProbe {
    /** 执行不依赖服务端名称和版本号的能力检测。 */
    public Result probe(Plugin plugin, boolean foliaRuntime) {
        List<String> missing = new ArrayList<>();
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> holder = load(loader, "org.bukkit.persistence.PersistentDataHolder", missing);
        Class<?> container = load(loader, "org.bukkit.persistence.PersistentDataContainer", missing);
        Class<?> dataType = load(loader, "org.bukkit.persistence.PersistentDataType", missing);
        Class<?> namespacedKey = load(loader, "org.bukkit.NamespacedKey", missing);
        Class<?> item = load(loader, "org.bukkit.entity.Item", missing);
        Class<?> itemMergeEvent = load(loader, "org.bukkit.event.entity.ItemMergeEvent", missing);
        Class<?> entityPickupEvent = load(loader, "org.bukkit.event.entity.EntityPickupItemEvent", missing);
        Class<?> inventoryPickupEvent = load(loader, "org.bukkit.event.inventory.InventoryPickupItemEvent", missing);
        Class<?> chunk = load(loader, "org.bukkit.Chunk", missing);
        Class<?> bukkit = load(loader, "org.bukkit.Bukkit", missing);
        Class<?> world = load(loader, "org.bukkit.World", missing);
        load(loader, "org.bukkit.event.entity.ItemSpawnEvent", missing);
        load(loader, "org.bukkit.event.world.ChunkLoadEvent", missing);
        load(loader, "org.bukkit.event.world.ChunkUnloadEvent", missing);
        requireMethod(holder, "getPersistentDataContainer", missing);
        requireMethod(container, "getKeys", missing);
        requireField(dataType, "INTEGER", missing);
        requireField(dataType, "STRING", missing);
        requireConstructor(namespacedKey, new Class<?>[]{Plugin.class, String.class}, missing);
        requireMethod(item, "getPersistentDataContainer", missing);
        requireMethod(item, "getOwner", missing);
        requireMethod(item, "setOwner", new Class<?>[]{java.util.UUID.class}, missing);
        requireMethod(item, "getTicksLived", missing);
        requireMethod(itemMergeEvent, "getTarget", missing);
        requireMethod(entityPickupEvent, "getItem", missing);
        requireMethod(inventoryPickupEvent, "getItem", missing);
        requireMethod(chunk, "getEntities", missing);
        if (foliaRuntime) {
            requireRuntimeClass(loader, "io.papermc.paper.threadedregions.scheduler.RegionScheduler", missing);
            requireRuntimeClass(loader, "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler", missing);
            requireRuntimeClass(loader, "io.papermc.paper.threadedregions.scheduler.AsyncScheduler", missing);
            requireRuntimeClass(loader, "io.papermc.paper.threadedregions.scheduler.EntityScheduler", missing);
            requireMethod(item, "getScheduler", missing);
            requireMethod(bukkit, "isOwnedByCurrentRegion",
                    new Class<?>[]{world, int.class, int.class}, missing);
        }
        return new Result(missing);
    }

    /** 加载指定类并记录缺失项。 */
    private static Class<?> load(ClassLoader loader, String name, List<String> missing) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError error) {
            missing.add("class:" + name);
            return null;
        }
    }

    /** 核对运行时类。 */
    private static void requireRuntimeClass(ClassLoader loader, String name, List<String> missing) {
        load(loader, name, missing);
    }

    /** 核对无参方法。 */
    private static void requireMethod(Class<?> owner, String name, List<String> missing) {
        requireMethod(owner, name, new Class<?>[0], missing);
    }

    /** 核对指定签名方法。 */
    private static void requireMethod(Class<?> owner, String name, Class<?>[] parameters, List<String> missing) {
        if (owner == null) {
            return;
        }
        try {
            owner.getMethod(name, parameters);
        } catch (NoSuchMethodException | SecurityException error) {
            missing.add("method:" + owner.getName() + "#" + name);
        }
    }

    /** 核对静态字段。 */
    private static void requireField(Class<?> owner, String name, List<String> missing) {
        if (owner == null) {
            return;
        }
        try {
            owner.getField(name);
        } catch (NoSuchFieldException | SecurityException error) {
            missing.add("field:" + owner.getName() + "#" + name);
        }
    }

    /** 核对构造方法。 */
    private static void requireConstructor(Class<?> owner, Class<?>[] parameters, List<String> missing) {
        if (owner == null) {
            return;
        }
        try {
            owner.getConstructor(parameters);
        } catch (NoSuchMethodException | SecurityException error) {
            missing.add("constructor:" + owner.getName());
        }
    }

    /** 能力检测不可变结果。 */
    public static final class Result {
        private final List<String> missing;

        /** 保存能力缺失项。 */
        private Result(List<String> missing) {
            this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
        }

        /** 判断全部能力是否可用。 */
        public boolean isSupported() {
            return missing.isEmpty();
        }

        /** 返回能力缺失项。 */
        public List<String> getMissing() {
            return missing;
        }
    }
}
