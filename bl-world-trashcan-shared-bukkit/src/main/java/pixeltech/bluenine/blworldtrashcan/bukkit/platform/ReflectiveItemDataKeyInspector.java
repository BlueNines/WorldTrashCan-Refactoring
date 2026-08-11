package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 通过运行时能力探测读取 ItemStack 的 PDC key 和 Raw NBT key 路径。 */
public final class ReflectiveItemDataKeyInspector {
    private static final int MAX_NBT_DEPTH = 16;
    private final Method pdcContainerMethod;
    private final Method pdcKeysMethod;
    private final Method asNmsCopy;
    private final Method saveMethod;
    private final Constructor<?> compoundConstructor;
    private final Method itemMetaAsStringMethod;
    private final Method parseCompoundMethod;
    private final Method compoundKeysMethod;
    private final Method compoundGetMethod;
    private final boolean legacyNbtReady;
    private final boolean stringNbtReady;
    private final String pdcFailureReason;
    private final String nbtFailureReason;

    /** 探测当前运行时，不按服务端名称或版本字符串选择实现。 */
    public ReflectiveItemDataKeyInspector() {
        Method pdcContainer = null;
        Method pdcKeys = null;
        String pdcFailure = null;
        try {
            ItemMeta meta = new ItemStack(Material.STONE, 1).getItemMeta();
            if (meta == null) {
                throw new IllegalStateException("sample ItemMeta is null");
            }
            ClassLoader apiLoader = meta.getClass().getClassLoader();
            Class<?> persistentDataHolder = loadClass(
                    "org.bukkit.persistence.PersistentDataHolder", apiLoader);
            Class<?> persistentDataContainer = loadClass(
                    "org.bukkit.persistence.PersistentDataContainer", apiLoader);
            pdcContainer = persistentDataHolder.getMethod("getPersistentDataContainer");
            Object container = pdcContainer.invoke(meta);
            if (container == null) {
                throw new IllegalStateException("sample PDC is null");
            }
            pdcKeys = persistentDataContainer.getMethod("getKeys");
            Object keys = pdcKeys.invoke(container);
            if (!(keys instanceof Collection)) {
                throw new IllegalStateException("PDC getKeys is not a collection");
            }
        } catch (ReflectiveOperationException exception) {
            pdcFailure = rootMessage(exception);
        } catch (RuntimeException exception) {
            pdcFailure = rootMessage(exception);
        } catch (LinkageError error) {
            pdcFailure = rootMessage(error);
        }
        this.pdcContainerMethod = pdcContainer;
        this.pdcKeysMethod = pdcKeys;
        this.pdcFailureReason = pdcFailure;

        Method copy = null;
        Method save = null;
        Constructor<?> compound = null;
        Method metaAsString = null;
        Method parseCompound = null;
        Method keys = null;
        Method get = null;
        boolean legacyReady = false;
        boolean stringReady = false;
        String nbtFailure = null;
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                throw new IllegalStateException("Bukkit server is not ready");
            }
            ClassLoader serverLoader = server.getClass().getClassLoader();
            String craftPackage = server.getClass().getPackage().getName();
            Class<?> craftItemStack = loadClass(craftPackage + ".inventory.CraftItemStack", serverLoader);
            copy = findAsNmsCopy(craftItemStack);
            if (copy == null) {
                throw new NoSuchMethodException("CraftItemStack.asNMSCopy(ItemStack)");
            }
            Object nmsItem = copy.invoke(null, new ItemStack(Material.STONE, 1));
            if (nmsItem == null) {
                throw new IllegalStateException("asNMSCopy returned null");
            }
            save = findSaveMethod(nmsItem.getClass());
            if (save == null) {
                throw new NoSuchMethodException("NMS ItemStack save method");
            }
            Class<?> compoundType = save.getParameterTypes()[0];
            compound = compoundType.getDeclaredConstructor();
            makeAccessible(compound);
            Object compoundValue = compound.newInstance();
            Object saved = save.invoke(nmsItem, compoundValue);
            Object actualCompound = saved == null ? compoundValue : saved;
            keys = findCompoundKeysMethod(actualCompound.getClass());
            get = findCompoundGetMethod(actualCompound.getClass());
            if (keys == null || get == null) {
                throw new NoSuchMethodException("NBT compound keys/get method");
            }
            Object sampleKeys = keys.invoke(actualCompound);
            if (!(sampleKeys instanceof Collection)) {
                throw new IllegalStateException("NBT key method is not a collection");
            }
            legacyReady = true;
        } catch (ReflectiveOperationException exception) {
            nbtFailure = "NMS save: " + rootMessage(exception);
        } catch (RuntimeException exception) {
            nbtFailure = "NMS save: " + rootMessage(exception);
        } catch (LinkageError error) {
            nbtFailure = "NMS save: " + rootMessage(error);
        }
        if (!legacyReady) {
            try {
                Object server = Bukkit.getServer();
                if (server == null) {
                    throw new IllegalStateException("Bukkit server is not ready");
                }
                ClassLoader serverLoader = server.getClass().getClassLoader();
                String craftPackage = server.getClass().getPackage().getName();
                ItemMeta sampleMeta = new ItemStack(Material.STONE, 1).getItemMeta();
                if (sampleMeta == null) {
                    throw new IllegalStateException("sample ItemMeta is null");
                }
                metaAsString = findItemMetaAsStringMethod(serverLoader);
                if (metaAsString == null) {
                    throw new NoSuchMethodException("ItemMeta.getAsString()");
                }
                String sampleSnbt = String.valueOf(metaAsString.invoke(sampleMeta));
                parseCompound = findParseCompoundMethod(craftPackage, serverLoader);
                if (parseCompound == null) {
                    throw new NoSuchMethodException("server SNBT compound parser");
                }
                Object actualCompound = parseCompound.invoke(null, sampleSnbt);
                if (actualCompound == null) {
                    throw new IllegalStateException("SNBT parser returned null");
                }
                keys = findCompoundKeysMethod(actualCompound.getClass());
                get = findCompoundGetMethod(actualCompound.getClass());
                if (keys == null || get == null) {
                    throw new NoSuchMethodException("NBT compound keys/get method");
                }
                Object sampleKeys = keys.invoke(actualCompound);
                if (!(sampleKeys instanceof Collection)) {
                    throw new IllegalStateException("NBT key method is not a collection");
                }
                stringReady = true;
                nbtFailure = null;
            } catch (ReflectiveOperationException exception) {
                nbtFailure = appendFailure(nbtFailure, "ItemMeta SNBT: " + rootMessage(exception));
            } catch (RuntimeException exception) {
                nbtFailure = appendFailure(nbtFailure, "ItemMeta SNBT: " + rootMessage(exception));
            } catch (LinkageError error) {
                nbtFailure = appendFailure(nbtFailure, "ItemMeta SNBT: " + rootMessage(error));
            }
        }
        this.asNmsCopy = copy;
        this.saveMethod = save;
        this.compoundConstructor = compound;
        this.itemMetaAsStringMethod = metaAsString;
        this.parseCompoundMethod = parseCompound;
        this.compoundKeysMethod = keys;
        this.compoundGetMethod = get;
        this.legacyNbtReady = legacyReady;
        this.stringNbtReady = stringReady;
        this.nbtFailureReason = nbtFailure;
    }

    /** 判断当前运行时是否支持 PDC key 读取。 */
    public boolean isPdcReady() {
        return pdcFailureReason == null && pdcContainerMethod != null && pdcKeysMethod != null;
    }

    /** 判断当前运行时是否支持 Raw NBT key 路径读取。 */
    public boolean isNbtReady() {
        return nbtFailureReason == null && (legacyNbtReady || stringNbtReady)
                && compoundKeysMethod != null && compoundGetMethod != null;
    }

    /** 返回 PDC 探测失败原因；可用时返回空字符串。 */
    public String getPdcFailureReason() {
        return pdcFailureReason == null ? "" : pdcFailureReason;
    }

    /** 返回 Raw NBT 探测失败原因；可用时返回空字符串。 */
    public String getNbtFailureReason() {
        return nbtFailureReason == null ? "" : nbtFailureReason;
    }

    /** 读取 ItemMeta 上的 PDC NamespacedKey，不包含插件内部旧 owner key。 */
    public Set<String> pdcKeys(ItemStack itemStack) {
        if (!isPdcReady() || itemStack == null || itemStack.getType() == Material.AIR) {
            return Collections.emptySet();
        }
        try {
            ItemMeta meta = itemStack.getItemMeta();
            if (meta == null) {
                return Collections.emptySet();
            }
            Object container = pdcContainerMethod.invoke(meta);
            Object rawKeys = pdcKeysMethod.invoke(container);
            if (!(rawKeys instanceof Collection)) {
                return Collections.emptySet();
            }
            Set<String> result = new LinkedHashSet<>();
            for (Object key : (Collection<?>) rawKeys) {
                String value = String.valueOf(key);
                if (!isInternalOwnerKey(value)) {
                    result.add(value);
                }
            }
            return result.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(result);
        } catch (ReflectiveOperationException exception) {
            return Collections.emptySet();
        } catch (RuntimeException exception) {
            return Collections.emptySet();
        } catch (LinkageError error) {
            return Collections.emptySet();
        }
    }

    /** 读取数量无关的 Raw NBT/Data Components key 路径，不读取或输出值。 */
    public Set<String> nbtKeyPaths(ItemStack itemStack) {
        if (!isNbtReady() || itemStack == null || itemStack.getType() == Material.AIR) {
            return Collections.emptySet();
        }
        try {
            ItemStack normalized = itemStack.clone();
            normalized.setAmount(1);
            Object actualCompound = legacyNbtReady
                    ? serializeLegacyNbt(normalized)
                    : parseItemMetaSnbt(normalized);
            if (actualCompound == null) {
                return Collections.emptySet();
            }
            Set<String> result = new LinkedHashSet<>();
            collectCompoundPaths(actualCompound, "", 0, true, result);
            return result.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(result);
        } catch (ReflectiveOperationException exception) {
            return Collections.emptySet();
        } catch (RuntimeException exception) {
            return Collections.emptySet();
        } catch (LinkageError error) {
            return Collections.emptySet();
        }
    }

    /** 使用旧式 NMS ItemStack#save(Compound) 生成结构化 NBT。 */
    private Object serializeLegacyNbt(ItemStack itemStack) throws ReflectiveOperationException {
        Object nmsItem = asNmsCopy.invoke(null, itemStack);
        Object compoundValue = compoundConstructor.newInstance();
        Object saved = saveMethod.invoke(nmsItem, compoundValue);
        return saved == null ? compoundValue : saved;
    }

    /** 使用 ItemMeta#getAsString 与服务端 SNBT 解析器生成结构化数据。 */
    private Object parseItemMetaSnbt(ItemStack itemStack) throws ReflectiveOperationException {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String snbt = String.valueOf(itemMetaAsStringMethod.invoke(meta));
        return parseCompoundMethod.invoke(null, snbt);
    }

    /** 递归读取 Compound key；父路径只有存在非空子节点时才写入结果。 */
    private boolean collectCompoundPaths(Object compound, String prefix, int depth,
                                         boolean root, Set<String> result)
            throws ReflectiveOperationException {
        if (compound == null || depth > MAX_NBT_DEPTH
                || !compoundKeysMethod.getDeclaringClass().isInstance(compound)) {
            return false;
        }
        Object rawKeys = compoundKeysMethod.invoke(compound);
        if (!(rawKeys instanceof Collection)) {
            return false;
        }
        boolean found = false;
        for (Object rawKey : (Collection<?>) rawKeys) {
            String key = String.valueOf(rawKey);
            if (root && isBaseItemKey(key)) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object child = compoundGetMethod.invoke(compound, key);
            if (child != null && compoundKeysMethod.getDeclaringClass().isInstance(child)) {
                int before = result.size();
                boolean childFound = collectCompoundPaths(child, path, depth + 1, false, result);
                if (childFound || result.size() > before) {
                    if (!isInternalOwnerPath(path)) {
                        result.add(path);
                    }
                    found = true;
                }
                continue;
            }
            if (!isInternalOwnerPath(path)) {
                result.add(path);
                found = true;
            }
        }
        return found;
    }

    /** 判断根 ItemStack 序列化中的基础字段。 */
    private boolean isBaseItemKey(String key) {
        return "id".equalsIgnoreCase(key) || "count".equalsIgnoreCase(key);
    }

    /** 判断 PDC key 是否是插件旧版 ItemStack owner 标记。 */
    private boolean isInternalOwnerKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.endsWith(":player_uuid")
                && (normalized.startsWith("worldlisttrashcan:")
                || normalized.startsWith("blworldtrashcan:"));
    }

    /** 判断 Raw NBT 路径是否指向插件内部 owner 标记。 */
    private boolean isInternalOwnerPath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".worldlisttrashcan:player_uuid")
                || normalized.endsWith(".blworldtrashcan:player_uuid");
    }

    /** 加载服务端类，必要时回退插件类加载器。 */
    private Class<?> loadClass(String className, ClassLoader serverLoader) throws ClassNotFoundException {
        try {
            return Class.forName(className, false, serverLoader);
        } catch (ClassNotFoundException exception) {
            ClassLoader pluginLoader = ReflectiveItemDataKeyInspector.class.getClassLoader();
            if (pluginLoader == serverLoader) {
                throw exception;
            }
            return Class.forName(className, false, pluginLoader);
        }
    }

    /** 查找静态 CraftItemStack 转换方法。 */
    private Method findAsNmsCopy(Class<?> craftItemStack) {
        for (Method method : craftItemStack.getMethods()) {
            if ("asNMSCopy".equals(method.getName()) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 1
                    && ItemStack.class.isAssignableFrom(method.getParameterTypes()[0])) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    /** 查找保存 ItemStack 的单参数方法。 */
    private Method findSaveMethod(Class<?> nmsItemClass) {
        for (Method method : nmsItemClass.getMethods()) {
            if ("save".equals(method.getName()) && method.getParameterTypes().length == 1
                    && method.getReturnType() != Void.TYPE) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    /** 查找返回 Compound key 集合的方法。 */
    private Method findCompoundKeysMethod(Class<?> compoundClass) {
        Method fallback = null;
        for (Method method : compoundClass.getMethods()) {
            if (method.getParameterTypes().length != 0
                    || !Collection.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if ("getAllKeys".equals(method.getName()) || "getKeys".equals(method.getName())
                    || "keySet".equals(method.getName())) {
                makeAccessible(method);
                return method;
            }
            fallback = method;
        }
        makeAccessible(fallback);
        return fallback;
    }

    /** 查找返回完整 ItemMeta SNBT 的无参数方法。 */
    private Method findItemMetaAsStringMethod(ClassLoader serverLoader) {
        Class<?> itemMetaClass;
        try {
            itemMetaClass = loadClass("org.bukkit.inventory.meta.ItemMeta", serverLoader);
        } catch (ClassNotFoundException exception) {
            return null;
        }
        for (Method method : itemMetaClass.getMethods()) {
            if ("getAsString".equals(method.getName()) && method.getParameterTypes().length == 0
                    && method.getReturnType() == String.class) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    /** 查找当前运行时的静态 SNBT Compound 解析方法。 */
    private Method findParseCompoundMethod(String craftPackage, ClassLoader serverLoader)
            throws ClassNotFoundException {
        Class<?> parserClass;
        try {
            parserClass = loadClass("net.minecraft.nbt.TagParser", serverLoader);
        } catch (ClassNotFoundException modernMissing) {
            String versionedNms = craftPackage.replace("org.bukkit.craftbukkit", "net.minecraft.server");
            parserClass = loadClass(versionedNms + ".MojangsonParser", serverLoader);
        }
        Method fallback = null;
        for (Method method : parserClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != String.class || method.getReturnType() == Void.TYPE) {
                continue;
            }
            String name = method.getName();
            if ("parseCompoundFully".equals(name) || "parseTag".equals(name) || "parse".equals(name)) {
                makeAccessible(method);
                return method;
            }
            fallback = method;
        }
        makeAccessible(fallback);
        return fallback;
    }

    /** 查找按 key 返回 NBT Tag 的方法。 */
    private Method findCompoundGetMethod(Class<?> compoundClass) {
        Method fallback = null;
        for (Method method : compoundClass.getMethods()) {
            if (method.getParameterTypes().length != 1 || method.getParameterTypes()[0] != String.class
                    || method.getReturnType() == Void.TYPE || method.getReturnType().isPrimitive()) {
                continue;
            }
            if ("get".equals(method.getName())) {
                makeAccessible(method);
                return method;
            }
            String returnName = method.getReturnType().getSimpleName().toLowerCase(Locale.ROOT);
            if (returnName.contains("tag") || returnName.contains("nbt")) {
                fallback = method;
            }
        }
        makeAccessible(fallback);
        return fallback;
    }

    /** 尽量放开反射访问限制。 */
    private void makeAccessible(java.lang.reflect.AccessibleObject object) {
        if (object == null) {
            return;
        }
        try {
            object.setAccessible(true);
        } catch (RuntimeException ignored) {
            // 公开成员在模块限制下通常仍可直接调用。
        }
    }

    /** 返回异常链末端的简短诊断。 */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 合并两条能力探测失败原因。 */
    private static String appendFailure(String current, String next) {
        return current == null || current.isEmpty() ? next : current + "; " + next;
    }
}
