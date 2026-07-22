package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

import java.util.UUID;
import java.util.function.Consumer;

/** Bukkit 侧平台入口，每个版本产物提供自己的实现。 */
public interface ServerPlatform {
    /** 返回平台标识。 */
    String id();

    /** 返回当前平台能力报告。 */
    CapabilityReport capabilities();

    /** 返回调度适配器。 */
    SchedulerAdapter scheduler();

    /** 返回物品快照映射器。 */
    ItemSnapshotMapper itemSnapshotMapper();

    /** 返回实体快照映射器。 */
    EntitySnapshotMapper entitySnapshotMapper();

    /** 返回告示牌所依附的容器方块；无法解析时返回 null。 */
    Block getAttachedContainerBlock(Block signBlock);

    /** 向指定在线玩家发送消息；玩家不在线时静默跳过。 */
    void sendMessage(UUID playerUuid, String message);

    /** 在玩家所属合法线程执行回调；无法调度时返回 false。 */
    boolean executeForPlayer(UUID playerUuid, Consumer<Player> action);
}
