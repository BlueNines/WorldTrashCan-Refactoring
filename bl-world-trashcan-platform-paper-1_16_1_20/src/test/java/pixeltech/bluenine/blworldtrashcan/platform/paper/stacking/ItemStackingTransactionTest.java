package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import java.util.logging.Logger;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证逻辑数量写入、合并和库存插入的事务边界。 */
public final class ItemStackingTransactionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 安装只覆盖 ItemStack 相似比较所需方法的 Bukkit 服务代理。 */
    @BeforeClass
    public static void installBukkitItemFactory() {
        Bukkit.setServer(serverProxy());
    }

    /** 剩余数量写入回读失败时必须恢复地面实体原数量。 */
    @Test
    public void remainingWriteFailureRestoresOriginalAmount() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);
        item.failNextLogicalWrite(70, 60);

        assertFalse(feature.setRemaining(item.proxy(), 80, 70));
        assertEquals(80, feature.getAmount(item.proxy()));
        assertEquals(64, item.stackAmount());
    }

    /** 实体移除失败时不得把地面数量视为已经扣除。 */
    @Test
    public void removalFailureKeepsOriginalAmount() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);
        item.setRemovalSucceeds(false);

        assertFalse(feature.setRemaining(item.proxy(), 80, 0));
        assertEquals(80, feature.getAmount(item.proxy()));
        assertTrue(item.isValid());
    }

    /** 实体成功移除时应完成全部数量扣减。 */
    @Test
    public void successfulRemovalConsumesEntireLogicalStack() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);

        assertTrue(feature.setRemaining(item.proxy(), 80, 0));
        assertFalse(item.isValid());
    }

    /** 达到逻辑上限时只转移可容纳数量，其余保留在来源实体。 */
    @Test
    public void partialMergeStopsAtConfiguredLimit() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(100, feature.getAmount(target.proxy()));
        assertEquals(10, feature.getAmount(source.proxy()));
        assertTrue(source.isValid());
    }

    /** 单 Material 独立上限必须优先于全局上限。 */
    @Test
    public void perMaterialLimitOverridesGlobalLimit() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  max-stack-size: 90\n");
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(90, feature.getAmount(target.proxy()));
        assertEquals(20, feature.getAmount(source.proxy()));
    }

    /** 禁用 Material 的普通原版合并事件不得被插件取消。 */
    @Test
    public void disabledMaterialLeavesVanillaMergeUntouched() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  enabled: false\n");
        ItemMergeEvent event = new ItemMergeEvent(new FakeItem(10).proxy(), new FakeItem(20).proxy());

        feature.onItemMerge(event);

        assertFalse(event.isCancelled());
    }

    /** 禁用前已经存在的逻辑实体仍需阻止原版误合并，等待预算化排空。 */
    @Test
    public void disabledMaterialProtectsExistingLogicalAmountUntilDrain() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  enabled: false\n");
        ItemMergeEvent event = new ItemMergeEvent(new FakeItem(80).proxy(), new FakeItem(20).proxy());

        feature.onItemMerge(event);

        assertTrue(event.isCancelled());
    }

    /** 目标写入异常值时必须同时保留目标和来源的原始数量。 */
    @Test
    public void targetWriteFailureRollsBackWithoutDuplication() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);
        target.failNextLogicalWrite(100, 90);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(30, feature.getAmount(source.proxy()));
    }

    /** 来源部分扣减失败时必须回滚目标和来源。 */
    @Test
    public void sourceWriteFailureRollsBackBothEntities() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(100);
        source.failNextLogicalWrite(80, 75);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(100, feature.getAmount(source.proxy()));
    }

    /** 来源移除失败时必须回滚已经增加的目标数量。 */
    @Test
    public void sourceRemovalFailureRollsBackTarget() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(20);
        source.setRemovalSucceeds(false);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(20, feature.getAmount(source.proxy()));
    }

    /** 空库存必须按原版上限把逻辑数量拆入多个槽位。 */
    @Test
    public void emptyInventoryAcceptsLogicalAmountAcrossSlots() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(80, acceptedAmount(receipt));
        assertEquals(64, inventory.amountAt(0));
        assertEquals(16, inventory.amountAt(1));
    }

    /** 只有一个空槽时最多接收一组，并保留其余地面数量。 */
    @Test
    public void partialInventoryAcceptsOnlyAvailableCapacity() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.fill(new ItemStack(Material.WOODEN_SWORD, 1));
        inventory.set(0, null);

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(64, acceptedAmount(receipt));
        assertEquals(64, inventory.amountAt(0));
    }

    /** 满库存不得接收任何数量。 */
    @Test
    public void fullInventoryAcceptsNothing() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.fill(new ItemStack(Material.WOODEN_SWORD, 1));

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(0, acceptedAmount(receipt));
    }

    /** 库存插入收据必须能精确恢复所有修改过的槽位。 */
    @Test
    public void inventoryReceiptRollsBackEveryChangedSlot() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.set(0, new ItemStack(Material.COBBLESTONE, 60));

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 20);
        invokeNoArg(receipt, "rollback");

        assertEquals(60, inventory.amountAt(0));
        assertNull(inventory.itemAt(1));
    }

    /** 创建使用临时数据目录的无调度测试实现。 */
    private TestFeature feature(int maxLogicalAmount) throws Exception {
        File dataFolder = temporaryFolder.newFolder();
        Plugin plugin = pluginProxy(dataFolder);
        ItemStackingConfig config = new ItemStackingConfig(maxLogicalAmount, 3D, 1.5D,
                5, 0, 8, 256, 1500, 4096, 30, false, "{name} x {amount}");
        return new TestFeature(plugin, config);
    }

    /** 创建带逐物品 YAML 的测试实现。 */
    private TestFeature feature(int maxLogicalAmount, String itemYaml) throws Exception {
        File dataFolder = temporaryFolder.newFolder();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("enabled: true\nstack:\n  max-logical-amount: " + maxLogicalAmount
                + "\ndisplay:\n  custom-name:\n    enabled: false\nitems:\n  "
                + itemYaml.replace("\n", "\n  "));
        ItemStackingConfig config = ItemStackingConfig.load(new BukkitConfigurationSource(yaml));
        return new TestFeature(pluginProxy(dataFolder), config);
    }

    /** 调用私有合并方法，直接覆盖事务结果。 */
    private void invokeMerge(TestFeature feature, Item target, Item source) throws Exception {
        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("mergeInto", Item.class, Item.class);
        method.setAccessible(true);
        method.invoke(feature, target, source);
    }

    /** 调用私有库存插入方法并返回事务收据。 */
    private Object invokeInventoryInsert(TestFeature feature, Inventory inventory,
                                         ItemStack sample, int requested) throws Exception {
        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod(
                "insertIntoInventory", Inventory.class, ItemStack.class, Integer.TYPE);
        method.setAccessible(true);
        return method.invoke(feature, inventory, sample, Integer.valueOf(requested));
    }

    /** 读取库存插入收据中的实际接收数量。 */
    private int acceptedAmount(Object receipt) throws Exception {
        return ((Integer) invokeNoArg(receipt, "getAcceptedAmount")).intValue();
    }

    /** 调用测试对象的私有无参方法。 */
    private Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** 创建只提供名称、类加载器和数据目录的插件代理。 */
    private Plugin pluginProxy(final File dataFolder) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    /** 返回堆叠构造阶段需要的最小插件信息。 */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        if ("getName".equals(method.getName())) {
                            return "WorldListTrashCan";
                        }
                        if ("getDataFolder".equals(method.getName())) {
                            return dataFolder;
                        }
                        if ("getClassLoader".equals(method.getName())) {
                            return getClass().getClassLoader();
                        }
                        if ("getLogger".equals(method.getName())) {
                            return Logger.getLogger("ItemStackingTransactionTest");
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    /** 创建只提供 ItemFactory 的 Bukkit 服务代理。 */
    private static Server serverProxy() {
        final ItemFactory itemFactory = (ItemFactory) Proxy.newProxyInstance(
                ItemStackingTransactionTest.class.getClassLoader(), new Class<?>[]{ItemFactory.class},
                (proxy, method, arguments) -> {
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(arguments[0] == null || arguments[1] == null
                                || arguments[0].equals(arguments[1]));
                    }
                    return defaultValue(method.getReturnType());
                });
        return (Server) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if ("getItemFactory".equals(method.getName())) {
                        return itemFactory;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("ItemStackingTransactionTest");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 返回代理方法所需的基本默认值。 */
    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }

    /** 无调度测试实现，只复用数量和事务逻辑。 */
    private static final class TestFeature extends AbstractModernItemStackingFeature {
        /** 创建测试实现。 */
        private TestFeature(Plugin plugin, final ItemStackingConfig config) {
            super(plugin, () -> config);
        }

        /** 测试不启动周期任务。 */
        @Override
        protected void startProcessor() {
        }

        /** 测试没有周期任务需要停止。 */
        @Override
        protected void stopProcessor() {
        }

        /** 测试不分派区块任务。 */
        @Override
        protected boolean dispatchChunk(StackingChunkKey key, long deadlineNanos) {
            return false;
        }

        /** 测试同步保存小型状态。 */
        @Override
        protected void saveStateAsync(Runnable runnable) {
            runnable.run();
        }
    }

    /** 可注入逻辑写入和移除失败的掉落物代理。 */
    private static final class FakeItem implements InvocationHandler {
        private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        private final Map<NamespacedKey, Object> pdcValues = new HashMap<>();
        private final PersistentDataContainer pdc = pdcProxy(pdcValues, this);
        private final World world = worldProxy();
        private final Item proxy;
        private ItemStack stack;
        private boolean valid = true;
        private boolean dead;
        private boolean removalSucceeds = true;
        private int failExpectedLogicalAmount = -1;
        private int failStoredLogicalAmount = -1;

        /** 创建指定逻辑数量的实体。 */
        private FakeItem(int logicalAmount) {
            stack = new ItemStack(Material.COBBLESTONE, Math.min(64, logicalAmount));
            proxy = (Item) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{Item.class}, this);
            if (logicalAmount > stack.getAmount()) {
                pdcValues.put(key("stack_amount"), Integer.valueOf(logicalAmount));
            }
        }

        /** 返回实体代理。 */
        private Item proxy() {
            return proxy;
        }

        /** 返回当前物理数量。 */
        private int stackAmount() {
            return stack.getAmount();
        }

        /** 判断实体当前是否有效。 */
        private boolean isValid() {
            return valid;
        }

        /** 设置 remove 是否真正让实体失效。 */
        private void setRemovalSucceeds(boolean removalSucceeds) {
            this.removalSucceeds = removalSucceeds;
        }

        /** 让下一次指定逻辑数量写入落成错误值。 */
        private void failNextLogicalWrite(int expectedAmount, int storedAmount) {
            this.failExpectedLogicalAmount = expectedAmount;
            this.failStoredLogicalAmount = storedAmount;
        }

        /** 实现测试需要的掉落物方法。 */
        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("isValid".equals(name)) {
                return Boolean.valueOf(valid);
            }
            if ("isDead".equals(name)) {
                return Boolean.valueOf(dead);
            }
            if ("getItemStack".equals(name)) {
                return stack.clone();
            }
            if ("setItemStack".equals(name)) {
                stack = ((ItemStack) arguments[0]).clone();
                return null;
            }
            if ("getPersistentDataContainer".equals(name)) {
                return pdc;
            }
            if ("getWorld".equals(name)) {
                return world;
            }
            if ("getLocation".equals(name)) {
                return new Location(world, 0D, 64D, 0D);
            }
            if ("getOwner".equals(name) || "getCustomName".equals(name)) {
                return null;
            }
            if ("isCustomNameVisible".equals(name) || "hasMetadata".equals(name)) {
                return Boolean.FALSE;
            }
            if ("remove".equals(name)) {
                if (removalSucceeds) {
                    valid = false;
                    dead = true;
                }
                return null;
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(ignoredProxy == arguments[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(ignoredProxy));
            }
            return defaultValue(method.getReturnType());
        }

        /** 在 PDC set 时注入一次错误逻辑数量。 */
        private Object filterPdcWrite(NamespacedKey namespacedKey, Object value) {
            if ("stack_amount".equals(namespacedKey.getKey())
                    && value instanceof Integer
                    && ((Integer) value).intValue() == failExpectedLogicalAmount) {
                int stored = failStoredLogicalAmount;
                failExpectedLogicalAmount = -1;
                failStoredLogicalAmount = -1;
                return Integer.valueOf(stored);
            }
            return value;
        }

        /** 创建固定世界代理。 */
        private static World worldProxy() {
            return (World) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, (proxy, method, arguments) -> {
                        if ("getUID".equals(method.getName())) {
                            return WORLD_ID;
                        }
                        if ("equals".equals(method.getName())) {
                            return Boolean.valueOf(proxy == arguments[0]);
                        }
                        if ("hashCode".equals(method.getName())) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        /** 创建与生产插件相同命名空间的键。 */
        private static NamespacedKey key(String value) {
            return new NamespacedKey("worldlisttrashcan", value);
        }
    }

    /** 可观察槽位变化的库存代理。 */
    private static final class FakeInventory implements InvocationHandler {
        private final ItemStack[] contents;
        private final Inventory proxy;

        /** 创建固定槽位数量的库存。 */
        private FakeInventory(int size) {
            contents = new ItemStack[size];
            proxy = (Inventory) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{Inventory.class}, this);
        }

        /** 返回库存代理。 */
        private Inventory proxy() {
            return proxy;
        }

        /** 使用物品副本填满全部槽位。 */
        private void fill(ItemStack sample) {
            for (int slot = 0; slot < contents.length; slot++) {
                set(slot, sample);
            }
        }

        /** 设置单个槽位。 */
        private void set(int slot, ItemStack itemStack) {
            contents[slot] = itemStack == null ? null : itemStack.clone();
        }

        /** 返回指定槽位物品。 */
        private ItemStack itemAt(int slot) {
            return contents[slot] == null ? null : contents[slot].clone();
        }

        /** 返回指定槽位数量。 */
        private int amountAt(int slot) {
            return contents[slot] == null ? 0 : contents[slot].getAmount();
        }

        /** 实现库存插入所需方法。 */
        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("getMaxStackSize".equals(name)) {
                return Integer.valueOf(64);
            }
            if ("getStorageContents".equals(name) || "getContents".equals(name)) {
                ItemStack[] copy = new ItemStack[contents.length];
                for (int slot = 0; slot < contents.length; slot++) {
                    copy[slot] = itemAt(slot);
                }
                return copy;
            }
            if ("getItem".equals(name)) {
                return itemAt(((Integer) arguments[0]).intValue());
            }
            if ("setItem".equals(name)) {
                set(((Integer) arguments[0]).intValue(), (ItemStack) arguments[1]);
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** 创建最小 PDC 代理并保留类型无关的测试值。 */
    private static PersistentDataContainer pdcProxy(final Map<NamespacedKey, Object> values,
                                                    final FakeItem owner) {
        return (PersistentDataContainer) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, new InvocationHandler() {
                    /** 实现数量事务需要的 PDC 读写方法。 */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        String name = method.getName();
                        if ("set".equals(name)) {
                            NamespacedKey key = (NamespacedKey) arguments[0];
                            values.put(key, owner.filterPdcWrite(key, arguments[2]));
                            return null;
                        }
                        if ("get".equals(name)) {
                            return values.get((NamespacedKey) arguments[0]);
                        }
                        if ("has".equals(name)) {
                            return Boolean.valueOf(values.containsKey((NamespacedKey) arguments[0]));
                        }
                        if ("remove".equals(name)) {
                            values.remove((NamespacedKey) arguments[0]);
                            return null;
                        }
                        if ("getKeys".equals(name)) {
                            return new HashSet<>(values.keySet());
                        }
                        if ("isEmpty".equals(name)) {
                            return Boolean.valueOf(values.isEmpty());
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }
}
