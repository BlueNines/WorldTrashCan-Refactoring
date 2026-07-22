package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.worldlisttrashcan.api.audit.AuditRegistration;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSink;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunContext;
import pixeltech.worldlisttrashcan.api.audit.WorldListTrashCanAuditBridge;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 清理审计服务的稳定主插件实现。 */
public final class DefaultWorldListTrashCanAuditBridge implements WorldListTrashCanAuditBridge {
    private static final long ERROR_LOG_INTERVAL_MILLIS = 30000L;

    private final Plugin plugin;
    private final ServerPlatform platform;
    private final AtomicReference<RegistrationState> registration = new AtomicReference<>();
    private final AtomicLong lastErrorLogMillis = new AtomicLong(0L);

    /** 创建稳定审计服务门面。 */
    public DefaultWorldListTrashCanAuditBridge(Plugin plugin, ServerPlatform platform) {
        this.plugin = plugin;
        this.platform = platform;
    }

    /** 返回当前 API 版本。 */
    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    /** 注册唯一审计消费者。 */
    @Override
    public AuditRegistration register(Plugin owner, CleanupAuditSink sink) {
        if (owner == null || sink == null) {
            throw new IllegalArgumentException("owner and sink cannot be null");
        }
        RegistrationState state = new RegistrationState(owner, sink);
        if (!registration.compareAndSet(null, state)) {
            throw new IllegalStateException("A cleanup audit consumer is already registered");
        }
        return new BridgeRegistration(this, state);
    }

    /** 在玩家所属合法线程执行附属插件回调。 */
    @Override
    public boolean executeForPlayer(final Plugin owner, UUID playerId, final Consumer<Player> action) {
        if (owner == null || playerId == null || action == null || !owner.isEnabled()) {
            return false;
        }
        return platform.executeForPlayer(playerId, new Consumer<Player>() {
            /** 在执行前再次确认附属插件仍然启用。 */
            @Override
            public void accept(Player player) {
                if (!owner.isEnabled()) {
                    return;
                }
                try {
                    action.accept(player);
                } catch (VirtualMachineError error) {
                    throw error;
                } catch (Throwable throwable) {
                    logFailure(owner, "玩家线程回调失败", throwable);
                }
            }
        });
    }

    /** 为主插件清理流程创建安全审计会话。 */
    public CleanupAuditSession beginRun(CleanupRunContext context) {
        RegistrationState state = registration.get();
        if (!isActive(state)) {
            return NoopCleanupAuditSession.INSTANCE;
        }
        CleanupAuditSession delegate;
        try {
            delegate = state.sink.beginRun(context);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            logFailure(state.owner, "创建审计会话失败", throwable);
            return NoopCleanupAuditSession.INSTANCE;
        }
        if (delegate == null) {
            logFailure(state.owner, "创建审计会话返回 null", null);
            return NoopCleanupAuditSession.INSTANCE;
        }
        SafeCleanupAuditSession session = new SafeCleanupAuditSession(this, state, delegate);
        state.sessions.add(session);
        if (!isActive(state)) {
            session.invalidate(false);
            return NoopCleanupAuditSession.INSTANCE;
        }
        return session;
    }

    /** 移除指定附属插件拥有的注册。 */
    public void removeOwner(Plugin owner, boolean callDiscard) {
        RegistrationState state = registration.get();
        if (state != null && state.owner == owner) {
            unregister(state, callDiscard);
        }
    }

    /** 关闭审计服务并释放所有附属对象引用。 */
    public void close() {
        RegistrationState state = registration.getAndSet(null);
        if (state != null) {
            invalidateSessions(state, false);
        }
    }

    /** 判断注册是否仍是当前活动消费者。 */
    private boolean isActive(RegistrationState state) {
        return state != null && registration.get() == state && state.owner.isEnabled();
    }

    /** 注销指定注册并使其所有活动会话失效。 */
    private void unregister(RegistrationState state, boolean callDiscard) {
        if (!registration.compareAndSet(state, null)) {
            return;
        }
        invalidateSessions(state, callDiscard);
    }

    /** 使指定注册的全部活动会话失效。 */
    private void invalidateSessions(RegistrationState state, boolean callDiscard) {
        for (SafeCleanupAuditSession session : state.sessions) {
            session.invalidate(callDiscard);
        }
        state.sessions.clear();
    }

    /** 从活动集合移除已结束会话。 */
    private void removeSession(RegistrationState state, SafeCleanupAuditSession session) {
        state.sessions.remove(session);
    }

    /** 限频记录附属插件异常。 */
    private void logFailure(Plugin owner, String message, Throwable throwable) {
        long now = System.currentTimeMillis();
        long previous = lastErrorLogMillis.get();
        if (now - previous < ERROR_LOG_INTERVAL_MILLIS || !lastErrorLogMillis.compareAndSet(previous, now)) {
            return;
        }
        String ownerName = owner == null ? "unknown" : owner.getName();
        plugin.getLogger().warning("[AddonAPI] " + ownerName + " " + message
                + (throwable == null || throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
    }

    /** 保存唯一消费者和其活动会话。 */
    private static final class RegistrationState {
        private final Plugin owner;
        private final CleanupAuditSink sink;
        private final Set<SafeCleanupAuditSession> sessions = ConcurrentHashMap.newKeySet();

        /** 创建注册状态。 */
        private RegistrationState(Plugin owner, CleanupAuditSink sink) {
            this.owner = owner;
            this.sink = sink;
        }
    }

    /** 可重复关闭的消费者注册句柄。 */
    private static final class BridgeRegistration implements AuditRegistration {
        private final DefaultWorldListTrashCanAuditBridge bridge;
        private final AtomicReference<RegistrationState> state;

        /** 创建注册句柄。 */
        private BridgeRegistration(DefaultWorldListTrashCanAuditBridge bridge, RegistrationState state) {
            this.bridge = bridge;
            this.state = new AtomicReference<>(state);
        }

        /** 注销消费者。 */
        @Override
        public void close() {
            RegistrationState value = state.getAndSet(null);
            if (value != null) {
                bridge.unregister(value, value.owner.isEnabled());
            }
        }
    }

    /** 隔离附属插件异常和生命周期的审计会话。 */
    private static final class SafeCleanupAuditSession implements CleanupAuditSession {
        private final DefaultWorldListTrashCanAuditBridge bridge;
        private final RegistrationState state;
        private final AtomicReference<CleanupAuditSession> delegate;
        private final AtomicBoolean terminal = new AtomicBoolean(false);

        /** 创建安全会话。 */
        private SafeCleanupAuditSession(DefaultWorldListTrashCanAuditBridge bridge, RegistrationState state,
                                        CleanupAuditSession delegate) {
            this.bridge = bridge;
            this.state = state;
            this.delegate = new AtomicReference<>(delegate);
        }

        /** 安全记录物品。 */
        @Override
        public void recordItem(org.bukkit.inventory.ItemStack itemStack) {
            if (terminal.get() || !bridge.isActive(state)) {
                return;
            }
            CleanupAuditSession value = delegate.get();
            if (value == null) {
                return;
            }
            try {
                value.recordItem(itemStack);
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable throwable) {
                bridge.logFailure(state.owner, "记录审计物品失败", throwable);
                invalidate(false);
            }
        }

        /** 安全完成会话。 */
        @Override
        public void complete(CleanupRunCompletion completion) {
            finish(completion, false);
        }

        /** 安全放弃会话。 */
        @Override
        public void discard() {
            finish(null, true);
        }

        /** 使会话失效并按需通知附属插件。 */
        private void invalidate(boolean callDiscard) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            CleanupAuditSession value = delegate.getAndSet(null);
            bridge.removeSession(state, this);
            if (callDiscard && value != null) {
                try {
                    value.discard();
                } catch (VirtualMachineError error) {
                    throw error;
                } catch (Throwable throwable) {
                    bridge.logFailure(state.owner, "放弃审计会话失败", throwable);
                }
            }
        }

        /** 结束会话并隔离附属插件异常。 */
        private void finish(CleanupRunCompletion completion, boolean discard) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            CleanupAuditSession value = delegate.getAndSet(null);
            bridge.removeSession(state, this);
            if (value == null || !bridge.isActive(state)) {
                return;
            }
            try {
                if (discard) {
                    value.discard();
                } else {
                    value.complete(completion);
                }
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable throwable) {
                bridge.logFailure(state.owner, discard ? "放弃审计会话失败" : "完成审计会话失败", throwable);
            }
        }
    }
}
