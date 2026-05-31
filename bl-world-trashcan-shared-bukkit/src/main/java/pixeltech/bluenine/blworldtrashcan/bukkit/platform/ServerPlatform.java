package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

/** Bukkit 侧平台入口，每个版本产物提供自己的实现。 */
public interface ServerPlatform {
    /** 返回平台标识。 */
    String id();

    /** 返回当前平台能力报告。 */
    CapabilityReport capabilities();

    /** 返回调度适配器。 */
    SchedulerAdapter scheduler();
}
