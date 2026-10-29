package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperatorModernImpl;
import io.github.retrooper.packetevents.netty.buffer.ByteBufOperatorModernImpl;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import io.netty.buffer.Unpooled;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 使用真实 PacketEvents 物品结构验证跨协议整理和原物品隔离，不启动服务端。 */
public final class PacketEventsTrashMenuLoreRouterTest {
    private PacketEventsAPI<?> previousApi;
    private ServerVersion version;
    private final List<PacketEventsTrashMenuLoreRouter> routes = new ArrayList<>();

    /** 仅提供物品协议结构需要的服务端版本，不模拟网络或 Bukkit 事件。 */
    @Before
    public void installProtocolContext() {
        previousApi = PacketEvents.getAPI();
        version = ServerVersion.V_1_21_4;
        PacketEvents.setAPI(new PacketEventsAPI<Object>() {
            /** 协议数据测试无需启动网络。 */
            @Override public void load() { }
            /** 测试上下文始终可读取物品注册表。 */
            @Override public boolean isLoaded() { return true; }
            /** 协议数据测试无需初始化插件。 */
            @Override public void init() { }
            /** 声明物品序列化上下文已准备。 */
            @Override public boolean isInitialized() { return true; }
            /** 测试结束由外部恢复全局上下文。 */
            @Override public void terminate() { }
            /** 测试过程中不终止协议上下文。 */
            @Override public boolean isTerminated() { return false; }
            /** 不创建 Bukkit 宿主。 */
            @Override public Object getPlugin() { return null; }
            /** 使物品注册表读取当前测试协议。 */
            @Override public ServerManager getServerManager() { return () -> version; }
            /** 不提供协议连接。 */
            @Override public ProtocolManager getProtocolManager() { return null; }
            /** 不创建在线玩家。 */
            @Override public PlayerManager getPlayerManager() { return null; }
            /** 使用真实内存缓冲区解码物品注册表，但不创建网络线程或连接。 */
            @Override public NettyManager getNettyManager() { return BUFFERS; }
            /** 不安装网络注入器。 */
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    private static final NettyManager BUFFERS = new NettyManager() {
        private final ByteBufOperator operator = new ByteBufOperatorModernImpl();
        private final ByteBufAllocationOperator allocator = new ByteBufAllocationOperatorModernImpl();

        /** 测试不建立网络连接。 */
        @Override public ChannelOperator getChannelOperator() { return null; }
        /** 返回依赖库自己的缓冲区读取实现。 */
        @Override public ByteBufOperator getByteBufOperator() { return operator; }
        /** 返回依赖库自己的堆内存分配实现。 */
        @Override public ByteBufAllocationOperator getByteBufAllocationOperator() { return allocator; }
    };

    /** 不将测试的全局 PacketEvents 上下文泄漏到其它用例。 */
    @After
    public void restoreProtocolContext() {
        for (PacketEventsTrashMenuLoreRouter route : routes) {
            route.close();
        }
        PacketEvents.setAPI(previousApi);
    }

    /** 1.12.2 使用传统颜色 NBT，复制后原物品 Lore、私有标签和数量不变。 */
    @Test
    public void reordersLegacyNbtWithoutMutatingSource() {
        version = ServerVersion.V_1_12_2;
        assertNbtRouting(false);
    }

    /** 1.16.5 使用 JSON NBT，RGB 与第三方富文本 JSON 保持原始编码。 */
    @Test
    public void reordersJsonNbtWithoutMutatingSource() {
        version = ServerVersion.V_1_16_5;
        assertNbtRouting(true);
    }

    /** 组件协议重排时保留第三方字体、悬停和原行对象，不能修改输入物品。 */
    @Test
    public void reordersComponentsAndPreservesRichText() {
        Component original = Component.text("原始");
        Component hint = AdventureSerializer.fromLegacyFormat("\u00a7x\u00a73\u00a78\u00a7B\u00a7D\u00a7F\u00a78左键取出 1");
        Component dynamic = Component.text("动态槽位").font(Key.key("example", "slots"))
                .hoverEvent(Component.text("详情"));
        List<Component> sourceLines = Arrays.asList(original, hint, dynamic);
        ItemStack source = ItemStack.builder().type(ItemTypes.DIAMOND).amount(1)
                .component(ComponentTypes.LORE, new ItemLore(sourceLines)).build();
        List<String> tail = Collections.singletonList(AdventureSerializer.toLegacyFormat(hint));

        ItemStack rendered = PacketEventsTrashMenuLoreRouter.render(source, tail, version);

        assertNotSame(source, rendered);
        List<Component> lines = rendered.getComponentOr(ComponentTypes.LORE, ItemLore.EMPTY).getLines();
        assertEquals(Arrays.asList(original, dynamic, hint), lines);
        assertSame(dynamic, lines.get(1));
        assertEquals(sourceLines, source.getComponentOr(ComponentTypes.LORE, ItemLore.EMPTY).getLines());
        assertSame(rendered, PacketEventsTrashMenuLoreRouter.render(rendered, tail, version));
        assertEquals(1, rendered.getAmount());
    }

    /** 无提示、无物品、空物品、未匹配或已在底部时不复制任何包内物品。 */
    @Test
    public void irrelevantAndAlreadyOrderedItemsAreUntouched() {
        List<String> tail = Collections.singletonList("操作");
        assertSame(null, PacketEventsTrashMenuLoreRouter.render(null, tail, version));
        assertSame(ItemStack.EMPTY, PacketEventsTrashMenuLoreRouter.render(ItemStack.EMPTY, tail, version));
        ItemStack noLore = ItemStack.builder().type(ItemTypes.DIRT).amount(1).build();
        assertSame(noLore, PacketEventsTrashMenuLoreRouter.render(noLore, tail, version));
        ItemStack ordered = ItemStack.builder().type(ItemTypes.DIRT).amount(1)
                .component(ComponentTypes.LORE, new ItemLore(Arrays.asList(Component.text("原始"),
                        Component.text("操作")))).build();
        assertSame(ordered, PacketEventsTrashMenuLoreRouter.render(ordered, tail, version));
        assertSame(ordered, PacketEventsTrashMenuLoreRouter.render(ordered, null, version));
        assertSame(ordered, PacketEventsTrashMenuLoreRouter.render(ordered, Collections.emptyList(), version));
        assertSame(ordered, PacketEventsTrashMenuLoreRouter.render(ordered,
                Collections.singletonList("不匹配"), version));
    }

    /** 真正经过出站监听器，全窗口只整理顶部槽位，玩家背包与光标保持原对象。 */
    @Test
    public void fullWindowOnlyReordersPublishedTopSlots() throws Exception {
        PacketEventsTrashMenuLoreRouter route = route();
        User user = user();
        Object token = new Object();
        route.beginView(user.getUUID(), token, 1);
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        open(user, 7);
        ItemStack sample = componentSample();
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(7, 0,
                Arrays.asList(sample, sample), sample);

        PacketSendEvent event = send(user, PacketType.Play.Server.WINDOW_ITEMS, packet);
        WrapperPlayServerWindowItems observed = new WrapperPlayServerWindowItems(event);

        assertTrue(event.needsReEncode());
        assertMoved(observed.getItems().get(0));
        assertSame(sample, observed.getItems().get(1));
        assertSame(sample, observed.getCarriedItem().get());
        assertEquals(Arrays.asList(Component.text("原始"), Component.text("操作"), Component.text("动态")),
                sample.getComponentOr(ComponentTypes.LORE, ItemLore.EMPTY).getLines());
    }

    /** 单槽更新匹配正确窗口和会话；其它窗口、背包、光标及未发布槽全部跳过。 */
    @Test
    public void singleSlotRejectsForeignWindowsAndOutOfRangeSlots() throws Exception {
        PacketEventsTrashMenuLoreRouter route = route();
        User user = user();
        Object token = new Object();
        route.beginView(user.getUUID(), token, 1);
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        open(user, 8);
        for (int[] target : new int[][]{{0, 0}, {-1, -1}, {9, 0}, {8, 1}, {8, -1}}) {
            ItemStack source = componentSample();
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(target[0], 0, target[1], source);
            PacketSendEvent event = send(user, PacketType.Play.Server.SET_SLOT, packet);
            assertFalse(event.needsReEncode());
            assertSame(source, packet.getItem());
        }
        PacketSendEvent correct = send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(8, 1, 0, componentSample()));
        assertTrue(correct.needsReEncode());
        assertMoved(new WrapperPlayServerSetSlot(correct).getItem());
    }

    /** 翻页时关闭旧令牌不能释放新页面，旧窗口更新则不再处理。 */
    @Test
    public void oldPageCloseCannotRemoveNewPage() throws Exception {
        PacketEventsTrashMenuLoreRouter route = route();
        User user = user();
        Object old = new Object();
        Object current = new Object();
        route.beginView(user.getUUID(), old, 1);
        open(user, 9);
        route.beginView(user.getUUID(), current, 1);
        route.updateSlot(user.getUUID(), current, 0, Collections.singletonList("操作"));
        route.updateSlot(user.getUUID(), old, 0, Collections.singletonList("错误旧提示"));
        route.closeView(user.getUUID(), old);
        send(user, PacketType.Play.Server.CLOSE_WINDOW, new WrapperPlayServerCloseWindow(9));
        open(user, 10);
        PacketSendEvent event = send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(10, 0, 0, componentSample()));
        assertTrue(event.needsReEncode());
        assertMoved(new WrapperPlayServerSetSlot(event).getItem());
    }

    /** 清空与 reload 释放全部旧快照后，监听仍可用于下一次打开。 */
    @Test
    public void clearViewsAllowsLaterMenuWhileCloseUnregisters() throws Exception {
        PacketEventsTrashMenuLoreRouter route = route();
        User user = user();
        Object token = new Object();
        route.beginView(user.getUUID(), token, 1);
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        open(user, 11);
        route.clearViews();
        assertFalse(send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(11, 0, 0, componentSample())).needsReEncode());
        route.beginView(user.getUUID(), token, 1);
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        open(user, 12);
        assertTrue(send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(12, 0, 0, componentSample())).needsReEncode());
        route.close();
        route.close();
        assertFalse(route.isPacketRoute());
        assertFalse(send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(12, 0, 0, componentSample())).needsReEncode());
    }

    /** 关闭当前窗口和内容槽归零后不能再整理无关物品。 */
    @Test
    public void emptiedSlotsAndClosedViewsStopRouting() throws Exception {
        PacketEventsTrashMenuLoreRouter route = route();
        User user = user();
        Object token = new Object();
        route.beginView(user.getUUID(), token, 1);
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        open(user, 13);
        route.updateSlot(user.getUUID(), token, 0, Collections.emptyList());
        assertFalse(send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(13, 0, 0, componentSample())).needsReEncode());
        route.updateSlot(user.getUUID(), token, 0, Collections.singletonList("操作"));
        send(user, PacketType.Play.Server.CLOSE_WINDOW, new WrapperPlayServerCloseWindow(13));
        assertFalse(send(user, PacketType.Play.Server.SET_SLOT,
                new WrapperPlayServerSetSlot(13, 0, 0, componentSample())).needsReEncode());
    }

    /** 前置停用只关闭展示路由，停止后不再接受新快照。 */
    @Test
    public void dependencyDisableStopsOptionalListener() throws Exception {
        Plugin dependency = owner();
        PacketEventsTrashMenuLoreRouter route = new PacketEventsTrashMenuLoreRouter(owner(), dependency, "GlobalTrash");
        routes.add(route);
        assertTrue(route.isPacketRoute());
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        try {
            serverField.set(null, owner().getServer());
            route.onDependencyDisable(new PluginDisableEvent(dependency));
        } finally {
            serverField.set(null, previousServer);
        }
        assertFalse(route.isPacketRoute());
    }

    /** 创建真实出站监听，仅 Bukkit 注册宿主使用最小代理。 */
    private PacketEventsTrashMenuLoreRouter route() {
        PacketEventsTrashMenuLoreRouter route = new PacketEventsTrashMenuLoreRouter(owner(), owner(), "GlobalTrash");
        routes.add(route);
        return route;
    }

    /** 代理禁止所有不需要的 Bukkit 调用，避免网络路径悄悄访问玩家或世界。 */
    private Plugin owner() {
        ClassLoader loader = getClass().getClassLoader();
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(loader, new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("registerEvents".equals(method.getName())) { return null; }
                    throw new AssertionError("Unexpected Bukkit manager access: " + method.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(loader, new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if ("getPluginManager".equals(method.getName())) { return manager; }
                    if ("isPrimaryThread".equals(method.getName())) { return true; }
                    throw new AssertionError("Unexpected Bukkit server access: " + method.getName());
                });
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if ("getServer".equals(method.getName())) { return server; }
            if ("getLogger".equals(method.getName())) { return Logger.getLogger("LoreTest"); }
            throw new AssertionError("Unexpected Bukkit plugin access: " + method.getName());
        });
    }

    /** 建立没有通道和 Bukkit Player 的协议用户，网络回调不能依赖两者。 */
    private User user() {
        return new User(null, ConnectionState.PLAY, version.toClientVersion(),
                new UserProfile(UUID.randomUUID(), "LoreTest"));
    }

    /** 发布一个菜单窗口。 */
    private void open(User user, int window) throws Exception {
        send(user, PacketType.Play.Server.OPEN_WINDOW,
                new WrapperPlayServerOpenWindow(window, 0, Component.text("Trash")));
    }

    /** 通过真实事件管理器处理真实 wrapper，不建立网络连接或客户端。 */
    private PacketSendEvent send(User user, PacketTypeCommon type, PacketWrapper<?> wrapper) throws Exception {
        PacketSendEvent event = new PacketSendEvent(0, type, version, null, user, null, Unpooled.EMPTY_BUFFER) { };
        event.setLastUsedWrapper(wrapper);
        event.markForReEncode(false);
        PacketEvents.getAPI().getEventManager().callEvent(event);
        return event;
    }

    /** 创建自身提示后面含动态行的组件样品。 */
    private ItemStack componentSample() {
        return ItemStack.builder().type(ItemTypes.DIAMOND).amount(1).component(ComponentTypes.LORE,
                new ItemLore(Arrays.asList(Component.text("原始"), Component.text("操作"), Component.text("动态")))).build();
    }

    /** 核对最终提示在动态 Lore 之后。 */
    private void assertMoved(ItemStack sample) {
        assertEquals(Arrays.asList(Component.text("原始"), Component.text("动态"), Component.text("操作")),
                sample.getComponentOr(ComponentTypes.LORE, ItemLore.EMPTY).getLines());
    }

    /** 对两种 NBT Lore 编码使用同一组顺序、幂等和深复制断言。 */
    private void assertNbtRouting(boolean json) {
        String hint = json ? "\u00a7x\u00a73\u00a78\u00a7B\u00a7D\u00a7F\u00a78操作" : "\u00a7b操作";
        NBTList<NBTString> lines = NBTList.createStringList();
        for (String line : Arrays.asList("原始", hint, "动态槽位")) {
            lines.addTag(new NBTString(json
                    ? AdventureSerializer.toJson(AdventureSerializer.fromLegacyFormat(line)) : line));
        }
        NBTCompound display = new NBTCompound();
        display.setTag("Lore", lines);
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("display", display);
        nbt.setTag("custom-owner", new NBTString("unchanged"));
        ItemStack source = ItemStack.builder().type(ItemTypes.DIAMOND).amount(1).nbt(nbt).build();
        List<String> tail = Collections.singletonList(AdventureSerializer.toLegacyFormat(
                AdventureSerializer.fromLegacyFormat(hint)));

        ItemStack rendered = PacketEventsTrashMenuLoreRouter.render(source, tail, version);

        assertNotSame(source, rendered);
        List<NBTString> result = rendered.getNBT().getCompoundTagOrThrow("display")
                .getStringListTagOrNull("Lore").getTags();
        assertEquals(Arrays.asList(lines.getTags().get(0), lines.getTags().get(2), lines.getTags().get(1)), result);
        assertEquals(Arrays.asList("原始", json ? tail.get(0) : hint, "动态槽位"), lines.getTags().stream().map(line -> json
                ? AdventureSerializer.toLegacyFormat(AdventureSerializer.parseComponent(line.getValue()))
                : line.getValue()).collect(Collectors.toList()));
        assertEquals("unchanged", rendered.getNBT().getStringTagValueOrNull("custom-owner"));
        assertEquals(1, rendered.getAmount());
        assertSame(rendered, PacketEventsTrashMenuLoreRouter.render(rendered, tail, version));
        assertEquals(lines, source.getNBT().getCompoundTagOrThrow("display").getStringListTagOrNull("Lore"));
    }
}
