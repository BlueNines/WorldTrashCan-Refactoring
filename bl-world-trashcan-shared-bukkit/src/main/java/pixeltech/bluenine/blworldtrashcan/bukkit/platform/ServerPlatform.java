package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.block.Block;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

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
}
