package pixeltech.bluenine.blworldtrashcan.platform.paper;

import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

import java.util.EnumSet;

/** Paper 1.16-1.20 平台实现。 */
public final class PaperPlatform implements ServerPlatform {
    private final SchedulerAdapter scheduler;
    private final CapabilityReport capabilityReport;

    /** 创建 Paper 平台实现。 */
    public PaperPlatform(Plugin plugin) {
        this.scheduler = new BukkitSchedulerAdapter(plugin);
        this.capabilityReport = new CapabilityReport("paper-1.16-1.20", EnumSet.of(
                Capability.SCHEDULER_GLOBAL,
                Capability.INVENTORY_BASIC,
                Capability.ITEM_PDC_TAG,
                Capability.RGB_MESSAGE,
                Capability.CLICKABLE_MESSAGE,
                Capability.BOSS_BAR,
                Capability.SIGN_MODERN,
                Capability.ENTITY_MOVE_EVENT
        ));
    }

    /** 返回平台 ID。 */
    @Override
    public String id() {
        return capabilityReport.getPlatformId();
    }

    /** 返回能力报告。 */
    @Override
    public CapabilityReport capabilities() {
        return capabilityReport;
    }

    /** 返回平台调度器。 */
    @Override
    public SchedulerAdapter scheduler() {
        return scheduler;
    }
}
