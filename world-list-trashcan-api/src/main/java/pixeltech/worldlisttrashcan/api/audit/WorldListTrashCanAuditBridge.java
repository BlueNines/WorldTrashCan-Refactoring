package pixeltech.worldlisttrashcan.api.audit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/** 主插件向清理审计附属插件提供的稳定服务门面。 */
public interface WorldListTrashCanAuditBridge {
    int API_VERSION = 1;

    /** 返回当前清理审计 API 版本。 */
    int getApiVersion();

    /** 注册唯一的清理审计消费者。 */
    AuditRegistration register(Plugin owner, CleanupAuditSink sink);

    /** 在玩家所属合法线程执行回调；无法调度时返回 false。 */
    boolean executeForPlayer(Plugin owner, UUID playerId, Consumer<Player> action);
}
