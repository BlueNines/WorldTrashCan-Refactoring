package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemDisplayNameResolver;

import java.util.List;

/** 地面掉落物逻辑堆叠的最小运行控制面。 */
public interface ItemStackingFeature extends Feature, ItemDisplayNameResolver {
    /** 返回供扫地读取实际数量的服务。 */
    ItemQuantityService quantities();

    /** 返回适合命令发送者阅读的状态行。 */
    List<String> statusLines();

    /** 请求进入排空模式；返回本次是否成功进入或继续排空。 */
    boolean requestDrain();

    /** 取消配置触发的排空并恢复新掉落物聚集；冲突阻断时返回 false。 */
    boolean resumeMerging();
}
