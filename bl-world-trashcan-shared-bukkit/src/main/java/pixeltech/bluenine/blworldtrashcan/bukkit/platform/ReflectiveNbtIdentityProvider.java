package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 通过当前服务端 CraftItemStack/NBT 方法读取完整物品标签的身份实现。 */
public final class ReflectiveNbtIdentityProvider implements ItemIdentityProvider {
    private final Method asNmsCopy;
    private final Method saveMethod;
    private final Constructor<?> compoundConstructor;
    private final Method removeMethod;
    private final String failureReason;

    /** 探测当前服务端的 CraftItemStack 和 NBT 方法，不触发其他版本类初始化。 */
    public ReflectiveNbtIdentityProvider() {
        Method copy = null;
        Method save = null;
        Constructor<?> compound = null;
        Method remove = null;
        String failure = null;
        try {
            ClassLoader serverLoader = Bukkit.getServer() == null
                    ? ReflectiveNbtIdentityProvider.class.getClassLoader()
                    : Bukkit.getServer().getClass().getClassLoader();
            String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftItemStack = loadClass(craftPackage + ".inventory.CraftItemStack", serverLoader);
            copy = findAsNmsCopy(craftItemStack);
            if (copy == null) {
                throw new NoSuchMethodException("CraftItemStack.asNMSCopy(ItemStack)");
            }
            ItemStack sample = new ItemStack(Material.STONE, 1);
            Object nmsItem = copy.invoke(null, sample);
            if (nmsItem == null) {
                throw new IllegalStateException("asNMSCopy returned null");
            }
            save = findSaveMethod(nmsItem.getClass());
            if (save == null) {
                throw new NoSuchMethodException("NMS ItemStack.save(CompoundTag)");
            }
            Class<?> compoundType = save.getParameterTypes()[0];
            compound = compoundType.getDeclaredConstructor();
            makeAccessible(compound);
            Object compoundValue = compound.newInstance();
            Object saved = save.invoke(nmsItem, compoundValue);
            Object actualCompound = saved == null ? compoundValue : saved;
            remove = findRemoveMethod(actualCompound.getClass());
            if (remove == null) {
                throw new NoSuchMethodException("NBT compound remove(String)");
            }
            if (!selfTest(copy, save, compound, remove)) {
                throw new IllegalStateException("NBT identity self-test failed");
            }
        } catch (ReflectiveOperationException exception) {
            failure = rootMessage(exception);
        } catch (RuntimeException exception) {
            failure = rootMessage(exception);
        } catch (LinkageError error) {
            failure = rootMessage(error);
        }
        this.asNmsCopy = copy;
        this.saveMethod = save;
        this.compoundConstructor = compound;
        this.removeMethod = remove;
        this.failureReason = failure;
    }

    /** 返回实现名称。 */
    @Override
    public String id() {
        return "raw-nbt";
    }

    /** 判断反射适配器和启动期自检是否成功。 */
    public boolean isReady() {
        return failureReason == null && asNmsCopy != null && saveMethod != null
                && compoundConstructor != null && removeMethod != null;
    }

    /** 返回降级原因，不包含物品 NBT。 */
    public String getFailureReason() {
        return failureReason == null ? "" : failureReason;
    }

    /** 读取数量归一化后的完整 NBT 文本作为身份键。 */
    @Override
    public String key(ItemStack itemStack) {
        if (!isReady() || itemStack == null || itemStack.getType() == null || itemStack.getAmount() <= 0) {
            return null;
        }
        try {
            ItemStack normalized = itemStack.clone();
            normalized.setAmount(1);
            Object nmsItem = asNmsCopy.invoke(null, normalized);
            Object compound = compoundConstructor.newInstance();
            Object saved = saveMethod.invoke(nmsItem, compound);
            Object actualCompound = saved == null ? compound : saved;
            removeCount(actualCompound, "Count");
            removeCount(actualCompound, "count");
            return "nbt-v1|" + String.valueOf(actualCompound);
        } catch (ReflectiveOperationException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        } catch (LinkageError error) {
            return null;
        }
    }

    /** 加载服务端类，必要时回退到插件类加载器。 */
    private Class<?> loadClass(String className, ClassLoader serverLoader) throws ClassNotFoundException {
        try {
            return Class.forName(className, false, serverLoader);
        } catch (ClassNotFoundException exception) {
            ClassLoader pluginLoader = ReflectiveNbtIdentityProvider.class.getClassLoader();
            if (pluginLoader == serverLoader) {
                throw exception;
            }
            return Class.forName(className, false, pluginLoader);
        }
    }

    /** 查找静态 CraftItemStack 转换方法。 */
    private Method findAsNmsCopy(Class<?> craftItemStack) {
        for (Method method : craftItemStack.getMethods()) {
            if (!"asNMSCopy".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 1
                    || !ItemStack.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            makeAccessible(method);
            return method;
        }
        return null;
    }

    /** 查找保存 NBT 的单参数方法。 */
    private Method findSaveMethod(Class<?> nmsItemClass) {
        for (Method method : nmsItemClass.getMethods()) {
            if (!"save".equals(method.getName()) || method.getParameterTypes().length != 1
                    || method.getReturnType() == Void.TYPE) {
                continue;
            }
            makeAccessible(method);
            return method;
        }
        return null;
    }

    /** 查找删除 NBT 键的方法。 */
    private Method findRemoveMethod(Class<?> compoundClass) {
        for (Method method : compoundClass.getMethods()) {
            if ("remove".equals(method.getName()) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == String.class) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    /** 运行数量、名称和 Lore 的最小身份自检。 */
    private boolean selfTest(Method copy, Method save, Constructor<?> compound, Method remove) {
        ItemStack one = new ItemStack(Material.STONE, 1);
        ItemStack sixtyFour = new ItemStack(Material.STONE, 64);
        String oneKey = keyWith(one, copy, save, compound, remove);
        String sixtyFourKey = keyWith(sixtyFour, copy, save, compound, remove);
        if (oneKey == null || !oneKey.equals(sixtyFourKey)) {
            return false;
        }
        ItemStack named = one.clone();
        ItemMeta namedMeta = named.getItemMeta();
        if (namedMeta == null) {
            return false;
        }
        namedMeta.setDisplayName("identity-test");
        named.setItemMeta(namedMeta);
        if (oneKey.equals(keyWith(named, copy, save, compound, remove))) {
            return false;
        }
        ItemStack withLore = one.clone();
        ItemMeta loreMeta = withLore.getItemMeta();
        if (loreMeta == null) {
            return false;
        }
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("identity-lore");
        loreMeta.setLore(lore);
        withLore.setItemMeta(loreMeta);
        return !oneKey.equals(keyWith(withLore, copy, save, compound, remove));
    }

    /** 使用指定反射成员生成一次测试键。 */
    private String keyWith(ItemStack itemStack, Method copy, Method save,
                           Constructor<?> compound, Method remove) {
        try {
            ItemStack normalized = itemStack.clone();
            normalized.setAmount(1);
            Object nmsItem = copy.invoke(null, normalized);
            Object compoundValue = compound.newInstance();
            Object saved = save.invoke(nmsItem, compoundValue);
            Object actualCompound = saved == null ? compoundValue : saved;
            remove.invoke(actualCompound, "Count");
            remove.invoke(actualCompound, "count");
            return String.valueOf(actualCompound);
        } catch (ReflectiveOperationException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 尝试删除一个数量字段，兼容大小写不同的 NBT 写法。 */
    private void removeCount(Object compound, String key) throws ReflectiveOperationException {
        removeMethod.invoke(compound, key);
    }

    /** 尽量放开反射访问权限，失败时交由实际调用结果判断。 */
    private void makeAccessible(java.lang.reflect.AccessibleObject object) {
        try {
            object.setAccessible(true);
        } catch (RuntimeException ignored) {
            // Java 模块限制下仍可能允许调用公开成员。
        }
    }

    /** 取出异常链中最短的诊断信息。 */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
