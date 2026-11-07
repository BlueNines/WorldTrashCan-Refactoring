package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.config.TrashConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** 验证共享库存的菜单校准只修改操作者视图，且不改变原槽位。 */
public final class TrashContainerMenuRefreshTest {
    /** 取出首格后，当前页末尾失效的同类堆叠也必须立即清空。 */
    @Test
    public void stackedWithdrawalClearsEveryInvalidReferenceOnPage() throws Exception {
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED);
        fixture.add(Material.STONE, 192);
        fixture.add(Material.DIRT, 7);
        View view = fixture.view();
        fixture.store.remove(view.holder.getSnapshot().getReference(0, 0).getEntryId(), 64);

        fixture.sync(view);

        assertEquals(64, view.items[0].getAmount());
        assertEquals(64, view.items[1].getAmount());
        assertNull(view.items[2]);
        assertEquals(Material.DIRT, view.items[3].getType());
        assertEquals(1, view.writes);
        assertEquals(135L, fixture.store.getStoredItemAmount());
    }

    /** 部分堆叠扣减后须同时更新首格数量、清空尾格，不能移动后续物品。 */
    @Test
    public void stackedPartialWithdrawalUpdatesAmountWithoutCompactingSlots() throws Exception {
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED);
        fixture.add(Material.STONE, 65);
        fixture.add(Material.DIRT, 7);
        View view = fixture.view();
        fixture.store.remove(view.holder.getSnapshot().getReference(0, 0).getEntryId(), 64);

        fixture.sync(view);

        assertEquals(1, view.items[0].getAmount());
        assertNull(view.items[1]);
        assertEquals(Material.DIRT, view.items[2].getType());
        assertEquals(2, view.writes);
    }

    /** A 的操作不能刷新 B；B 自己操作时才校准其旧页面。 */
    @Test
    public void idleViewerRemainsUnchangedUntilOwnRefresh() throws Exception {
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED);
        fixture.add(Material.STONE, 192);
        View first = fixture.view();
        View second = fixture.view();
        fixture.store.remove(first.holder.getSnapshot().getReference(0, 0).getEntryId(), 64);

        fixture.sync(first);

        assertNull(first.items[2]);
        assertNotNull(second.items[2]);
        assertEquals(0, second.writes);
        fixture.sync(second);
        assertNull(second.items[2]);
        assertEquals(1, second.writes);
    }

    /** 紧凑模式同样只校准本人，已被其他人取空的条目不再保留残影。 */
    @Test
    public void compactViewClearsStaleEntriesWithoutLoadingNewIdentities() throws Exception {
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.COMPACT);
        fixture.add(Material.STONE, 3);
        fixture.add(Material.DIRT, 7);
        View first = fixture.view();
        View second = fixture.view();
        fixture.store.remove(first.holder.getSnapshot().getReference(0, 0).getEntryId(), 3);
        fixture.add(Material.GOLD_INGOT, 4);

        fixture.sync(first);

        assertNull(first.items[0]);
        assertEquals(Material.DIRT, first.items[1].getType());
        assertNull(first.items[2]);
        assertNotNull(second.items[0]);
        assertEquals(0, second.writes);
        assertEquals(2, first.holder.getSnapshot().getReferenceCount());
    }

    /** 没变化的槽位不重复写入，避免重发整页物品。 */
    @Test
    public void unchangedViewDoesNotWriteSlots() throws Exception {
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED);
        fixture.add(Material.STONE, 128);
        View view = fixture.view();

        fixture.sync(view);

        assertEquals(0, view.writes);
    }

    /** 同一视图只排队一次，另一玩家或新页面拥有独立刷新标记。 */
    @Test
    public void pendingRefreshIsBoundToOneView() {
        TrashContainerViewHolder first = new TrashContainerViewHolder(null, UUID.randomUUID(), 0, null, null);
        TrashContainerViewHolder second = new TrashContainerViewHolder(null, UUID.randomUUID(), 0, null, null);
        assertTrue(first.queueContentSync());
        assertFalse(first.queueContentSync());
        assertTrue(second.queueContentSync());
        first.clearContentSyncPending();
        assertTrue(first.queueContentSync());
        assertFalse(second.queueContentSync());
    }

    /** 生产调度入口必须合并同一视图的请求，关闭后旧回调不得写库存。 */
    @Test
    public void scheduledRefreshCoalescesAndIgnoresClosedView() throws Exception {
        List<Consumer<Player>> callbacks = new ArrayList<>();
        ServerPlatform platform = proxy(ServerPlatform.class, (object, method, arguments) -> {
            if ("executeForPlayer".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                Consumer<Player> callback = (Consumer<Player>) arguments[1];
                callbacks.add(callback);
                return true;
            }
            return null;
        });
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED, platform);
        fixture.add(Material.STONE, 192);
        View view = fixture.view();
        fixture.schedule(view);
        fixture.schedule(view);
        assertEquals(1, callbacks.size());
        callbacks.get(0).accept(null);
        assertEquals(0, view.writes);
        fixture.schedule(view);
        assertEquals(2, callbacks.size());
    }

    /** 平台拒绝调度后必须释放合并标记，否则后续操作永远无法再刷新。 */
    @Test
    public void rejectedSchedulingCanBeRetried() throws Exception {
        int[] attempts = {0};
        ServerPlatform platform = proxy(ServerPlatform.class, (object, method, arguments) -> {
            if ("executeForPlayer".equals(method.getName())) {
                attempts[0]++;
                return false;
            }
            return null;
        });
        Fixture fixture = new Fixture(TrashConfig.GlobalTrashMode.STACKED, platform);
        View view = fixture.view();
        fixture.schedule(view);
        fixture.schedule(view);
        assertEquals(2, attempts[0]);
        assertEquals(0, view.writes);
    }

    /** 提供不依赖运行服务端的真实菜单和存储组合。 */
    private static final class Fixture {
        private final TrashContainerStore store;
        private final TrashContainerMenu menu;

        /** 配置真实菜单，仅替代 Bukkit 外部服务。 */
        private Fixture(TrashConfig.GlobalTrashMode mode) throws Exception {
            this(mode, null);
        }

        /** 使用可控的平台调度器验证生产排队入口。 */
        private Fixture(TrashConfig.GlobalTrashMode mode, ServerPlatform platform) throws Exception {
            TrashConfig.PersonalTrashConfig config = new TrashConfig.PersonalTrashConfig(true, 0, true,
                    TrashConfig.GlobalTrashLayoutConfig.defaultLayout(-1, -1, -1, null),
                    mode, TrashConfig.CompactGlobalTrashConfig.defaults(),
                    new TrashConfig.StackedGlobalTrashConfig(2), true, false, -1D,
                    TrashConfig.DamageRecoveryMode.DISABLED, 6, true, 3);
            store = new TrashContainerStore(new ItemIdentityProvider() {
                /** 返回测试身份能力名称。 */
                @Override
                public String id() { return "material"; }

                /** 忽略数量，只按材质合并测试条目。 */
                @Override
                public String key(ItemStack item) { return item.getType().name(); }
            }, "global");
            store.configure(config, 45);
            PluginManager manager = proxy(PluginManager.class, (object, method, arguments) ->
                    method.getReturnType() == boolean.class ? false : null);
            Server server = proxy(Server.class, (object, method, arguments) ->
                    "getPluginManager".equals(method.getName()) ? manager : null);
            Plugin plugin = proxy(Plugin.class, (object, method, arguments) ->
                    "getServer".equals(method.getName()) ? server : Logger.getLogger("menu-refresh"));
            TrashContainerMenuPolicy policy = proxy(TrashContainerMenuPolicy.class,
                    (object, method, arguments) -> "test");
            menu = new TrashContainerMenu(plugin, platform, null, null, policy);
            setField("config", config);
            setField("layout", config.getLayout());
        }

        /** 添加保留轻量物品替身类型的真实库存条目。 */
        private void add(Material material, int amount) {
            store.add(new PlainItem(material, amount), true);
        }

        /** 为每名玩家建立独立库存和固定引用快照。 */
        private View view() {
            TrashContainerStore.ViewSnapshot snapshot = store.createViewSnapshot(TrashConfig.GlobalTrashSortType.INSERTION);
            TrashContainerViewHolder holder = new TrashContainerViewHolder(menu, UUID.randomUUID(), 0, store, snapshot);
            View view = new View(holder);
            for (int index = 0; index < 45; index++) {
                TrashContainerStore.DisplayItem display = store.getDisplayItem(snapshot.getReference(0, index));
                if (display != null) {
                    view.items[index] = display.getSample();
                    view.items[index].setAmount(display.getDisplayAmount());
                }
            }
            return view;
        }

        /** 调用生产校准方法，验证写入范围和数量。 */
        private void sync(View view) throws Exception {
            Method method = TrashContainerMenu.class.getDeclaredMethod("syncContentPage", Player.class, TrashContainerViewHolder.class);
            method.setAccessible(true);
            method.invoke(menu, null, view.holder);
        }

        /** 通过生产调度入口排队，不直接执行页面校准。 */
        private void schedule(View view) throws Exception {
            Method method = TrashContainerMenu.class.getDeclaredMethod("scheduleContentSync", TrashContainerViewHolder.class);
            method.setAccessible(true);
            method.invoke(menu, view.holder);
        }

        /** 注入已解析配置，避免单测启动 Bukkit 全局单例。 */
        private void setField(String name, Object value) throws Exception {
            Field field = TrashContainerMenu.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(menu, value);
        }
    }

    /** 记录一个玩家视图的内容与实际写入次数。 */
    private static final class View {
        private final TrashContainerViewHolder holder;
        private final ItemStack[] items = new ItemStack[54];
        private int writes;

        /** 创建只实现菜单校准所需操作的库存替身。 */
        private View(TrashContainerViewHolder holder) {
            this.holder = holder;
            holder.bind(proxy(Inventory.class, (object, method, arguments) -> {
                if ("getItem".equals(method.getName())) {
                    return items[(Integer) arguments[0]];
                }
                if ("setItem".equals(method.getName())) {
                    items[(Integer) arguments[0]] = (ItemStack) arguments[1];
                    writes++;
                }
                return null;
            }));
        }
    }

    /** 无元数据物品替身，不依赖 Bukkit ItemFactory。 */
    private static final class PlainItem extends ItemStack {
        /** 创建按原版最大堆叠数展示的简单物品。 */
        private PlainItem(Material material, int amount) { super(material, amount); }

        /** 测试物品没有元数据。 */
        @Override
        public ItemMeta getItemMeta() { return null; }

        /** 对本测试范围内的普通物品按材质和数量比较。 */
        @Override
        public boolean equals(Object other) {
            return other instanceof ItemStack && getType() == ((ItemStack) other).getType()
                    && getAmount() == ((ItemStack) other).getAmount();
        }

        /** 与普通测试物品的相等规则保持一致。 */
        @Override
        public int hashCode() { return 31 * getType().hashCode() + getAmount(); }
    }

    /** 创建显式接口的轻量 Bukkit 服务替身。 */
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
