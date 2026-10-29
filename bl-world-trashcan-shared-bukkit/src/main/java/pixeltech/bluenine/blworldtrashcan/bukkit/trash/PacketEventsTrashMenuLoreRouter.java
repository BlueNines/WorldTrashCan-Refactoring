package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 只整理本菜单出站物品包，不在网络线程访问 Bukkit 库存、世界或 PAPI。 */
final class PacketEventsTrashMenuLoreRouter extends TrashMenuLoreRouter implements Listener {
    private final Plugin plugin;
    private final Plugin dependency;
    private final String scope;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final PacketListenerAbstract listener;
    private volatile boolean active = true;
    private volatile boolean failed;
    private boolean warned;

    /** 在外置 PacketEvents 完成初始化后注册可选的末段展示处理器。 */
    PacketEventsTrashMenuLoreRouter(Plugin plugin, Plugin dependency, String scope) {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()
                || PacketEvents.getAPI().isTerminated()) {
            throw new IllegalStateException("PacketEvents API 尚未初始化或已终止");
        }
        try {
            ItemStack.class.getMethod("getComponentOr", ComponentType.class, Object.class);
            ItemLore.EMPTY.getLines();
            AdventureSerializer.toLegacyFormat(AdventureSerializer.fromLegacyFormat(""));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("缺少兼容的 PacketEvents 物品展示 API", exception);
        }
        this.plugin = plugin;
        this.dependency = dependency;
        this.scope = scope;
        this.listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            /** 仅处理当前菜单窗口的出站更新，MONITOR 阶段不修改数据包。 */
            @Override
            public void onPacketSend(PacketSendEvent event) {
                handlePacket(event);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        try {
            PacketEvents.getAPI().getEventManager().registerListener(listener);
        } catch (RuntimeException | LinkageError exception) {
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    /** 返回适配器当前是否仍能整理物品包。 */
    @Override
    boolean isPacketRoute() {
        return active && !failed;
    }

    /** 为当前页分配至多 54 个槽位的会话，不保留玩家或业务物品引用。 */
    @Override
    void beginView(UUID playerId, Object token, int size) {
        if (isPacketRoute()) {
            views.put(playerId, new View(token, size));
        }
    }

    /** 在玩家线程完成颜色规范化后发布不可变尾部，网络回调只读取快照。 */
    @Override
    void updateSlot(UUID playerId, Object token, int slot, List<String> tail) {
        View view = views.get(playerId);
        if (!isPacketRoute() || view == null || view.token != token || slot < 0 || slot >= view.tails.length()) {
            return;
        }
        try {
            List<String> canonical = new ArrayList<>(tail.size());
            for (String line : tail) {
                canonical.add(AdventureSerializer.toLegacyFormat(AdventureSerializer.fromLegacyFormat(line)));
            }
            view.tails.set(slot, Collections.unmodifiableList(canonical));
        } catch (RuntimeException | LinkageError exception) {
            warnOnce(exception);
        }
    }

    /** 旧页面关闭事件不能删除刚发布的新页面。 */
    @Override
    void closeView(UUID playerId, Object token) {
        View view = views.get(playerId);
        if (view != null && view.token == token) {
            views.remove(playerId, view);
        }
    }

    /** 重载时立即丢弃旧窗口的全部展示快照。 */
    @Override
    void clearViews() {
        views.clear();
    }

    /** 外置前置被停用时停止发包整理，Bukkit 完整展示仍可继续使用。 */
    @EventHandler
    public void onDependencyDisable(PluginDisableEvent event) {
        if (event.getPlugin() == dependency) {
            close();
        }
    }

    /** 停止监听并释放所有会话；重复调用无副作用。 */
    @Override
    synchronized void close() {
        if (!active) {
            return;
        }
        active = false;
        views.clear();
        HandlerList.unregisterAll(this);
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (RuntimeException | LinkageError exception) {
            warnOnce(exception);
        }
    }

    /** 先按包类型和玩家会话短路，异常时只警告一次并保留完整 Bukkit 展示。 */
    private void handlePacket(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!isPacketRoute() || event.isCancelled() || (type != PacketType.Play.Server.OPEN_WINDOW
                && type != PacketType.Play.Server.CLOSE_WINDOW && type != PacketType.Play.Server.WINDOW_ITEMS
                && type != PacketType.Play.Server.SET_SLOT) || event.getUser() == null) {
            return;
        }
        UUID playerId = event.getUser().getUUID();
        if (playerId == null) {
            return;
        }
        View view = views.get(playerId);
        if (view == null) {
            return;
        }
        try {
            if (type == PacketType.Play.Server.OPEN_WINDOW) {
                view.windowId = new WrapperPlayServerOpenWindow(event).getContainerId();
            } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
                if (view.windowId == new WrapperPlayServerCloseWindow(event).getWindowId()) {
                    views.remove(playerId, view);
                }
            } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                updateWindow(event, view);
            } else {
                updateSingleSlot(event, view);
            }
        } catch (RuntimeException | LinkageError exception) {
            warnOnce(exception);
        }
    }

    /** 只复制确有改动的顶部内容槽，不处理玩家背包和携带光标。 */
    private void updateWindow(PacketSendEvent event, View view) {
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        if (view.windowId < 1 || packet.getWindowId() != view.windowId) {
            return;
        }
        List<ItemStack> items = packet.getItems();
        List<ItemStack> changed = null;
        for (int slot = 0; slot < Math.min(items.size(), view.tails.length()); slot++) {
            ItemStack original = items.get(slot);
            ItemStack rendered = render(original, view.tails.get(slot), event.getServerVersion());
            if (rendered != original) {
                if (changed == null) {
                    changed = new ArrayList<>(items);
                }
                changed.set(slot, rendered);
            }
        }
        if (changed != null) {
            packet.setItems(changed);
            event.markForReEncode(true);
        }
    }

    /** 取出后只整理实际更新的单个顶部内容槽。 */
    private void updateSingleSlot(PacketSendEvent event, View view) {
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        int slot = packet.getSlot();
        if (view.windowId < 1 || packet.getWindowId() != view.windowId
                || slot < 0 || slot >= view.tails.length()) {
            return;
        }
        ItemStack original = packet.getItem();
        ItemStack rendered = render(original, view.tails.get(slot), event.getServerVersion());
        if (rendered != original) {
            packet.setItem(rendered);
            event.markForReEncode(true);
        }
    }

    /** 根据包内实际 Lore 数据选择组件或旧 NBT 路径，仅在需要移动时复制物品。 */
    static ItemStack render(ItemStack item, List<String> tail, ServerVersion version) {
        if (item == null || item.isEmpty() || tail == null || tail.isEmpty()) {
            return item;
        }
        NBTCompound tag = item.getNBT();
        NBTCompound display = tag == null ? null : tag.getCompoundTagOrNull("display");
        NBTList<NBTString> legacyLore = display == null ? null : display.getStringListTagOrNull("Lore");
        if (legacyLore != null) {
            List<NBTString> lines = legacyLore.getTags();
            boolean json = version.isNewerThanOrEquals(ServerVersion.V_1_14);
            List<NBTString> reordered = moveTail(lines, tail, line -> canonicalLegacyLine(line, json));
            if (reordered == lines) {
                return item;
            }
            ItemStack copy = item.copy();
            NBTList<NBTString> lore = NBTList.createStringList();
            for (NBTString line : reordered) {
                lore.addTag(line);
            }
            copy.getNBT().getCompoundTagOrThrow("display").setTag("Lore", lore);
            return copy;
        }
        ItemLore lore = item.getComponentOr(ComponentTypes.LORE, ItemLore.EMPTY);
        List<Component> lines = lore.getLines();
        List<Component> reordered = moveTail(lines, tail, AdventureSerializer::toLegacyFormat);
        if (reordered == lines) {
            return item;
        }
        ItemStack copy = item.copy();
        copy.setComponent(ComponentTypes.LORE, new ItemLore(reordered));
        return copy;
    }

    /** 旧协议 Lore 为传统颜色文本，1.14 起 NBT Lore 为 JSON；不改写原行编码。 */
    private static String canonicalLegacyLine(NBTString line, boolean json) {
        Component component = json ? AdventureSerializer.parseComponent(line.getValue())
                : AdventureSerializer.fromLegacyFormat(line.getValue());
        return AdventureSerializer.toLegacyFormat(component);
    }

    /** 网络回调失败仅保留一次告警，避免第三方不兼容造成持续刷屏。 */
    private synchronized void warnOnce(Throwable exception) {
        if (!warned) {
            warned = true;
            failed = true;
            views.clear();
            plugin.getLogger().warning("[" + scope + "] PacketEvents Lore 路由已降级为 Bukkit 展示: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /** 单页只保留会话令牌、窗口编号和不可变提示文本。 */
    private static final class View {
        private final Object token;
        private final AtomicReferenceArray<List<String>> tails;
        private volatile int windowId = -1;

        /** 建立有界槽位快照；令牌只用于和 Bukkit 关闭事件核对身份。 */
        private View(Object token, int size) {
            this.token = token;
            this.tails = new AtomicReferenceArray<>(size);
        }
    }
}
