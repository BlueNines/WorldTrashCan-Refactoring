package pixeltech.bluenine.blworldtrashcan.core.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 当前平台能力报告，用于启动日志、README 和测试断言。 */
public final class CapabilityReport {
    private final String platformId;
    private final EnumSet<Capability> enabled;

    /** 创建能力报告。 */
    public CapabilityReport(String platformId, Set<Capability> enabled) {
        this.platformId = platformId;
        this.enabled = enabled.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(enabled);
    }

    /** 返回平台标识。 */
    public String getPlatformId() {
        return platformId;
    }

    /** 判断能力是否启用。 */
    public boolean has(Capability capability) {
        return enabled.contains(capability);
    }

    /** 返回不可变能力集合。 */
    public Set<Capability> getEnabled() {
        return Collections.unmodifiableSet(enabled);
    }
}
