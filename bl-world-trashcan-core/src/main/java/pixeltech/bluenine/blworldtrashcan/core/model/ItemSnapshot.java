package pixeltech.bluenine.blworldtrashcan.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;

/** 平台层从真实物品提取出的轻量快照，核心层只依赖这个对象做决策。 */
public final class ItemSnapshot {
    private final String materialKey;
    private final int amount;
    private final String displayName;
    private final List<String> lore;
    private final UUID ownerUuid;
    private final boolean customRoutingMatched;
    private final boolean globalTrashAvailabilityEvaluated;
    private final boolean globalTrashAvailable;
    private final RejectedCleanupAction globalWhitelistRejectedAction;

    /** 创建物品快照。 */
    public ItemSnapshot(String materialKey, int amount, String displayName, List<String> lore, UUID ownerUuid) {
        this(materialKey, amount, displayName, lore, ownerUuid, false, false, false, null);
    }

    /** 创建包含路由识别结果的物品快照。 */
    public ItemSnapshot(String materialKey, int amount, String displayName, List<String> lore, UUID ownerUuid,
                        boolean customRoutingMatched,
                        RejectedCleanupAction globalWhitelistRejectedAction) {
        this(materialKey, amount, displayName, lore, ownerUuid, customRoutingMatched,
                false, false, globalWhitelistRejectedAction);
    }

    /** 创建包含自定义路由和公共桶检查结果的完整物品快照。 */
    public ItemSnapshot(String materialKey, int amount, String displayName, List<String> lore, UUID ownerUuid,
                        boolean customRoutingMatched, boolean globalTrashAvailabilityEvaluated,
                        boolean globalTrashAvailable,
                        RejectedCleanupAction globalWhitelistRejectedAction) {
        this.materialKey = materialKey == null ? "" : materialKey;
        this.amount = Math.max(0, amount);
        this.displayName = displayName == null ? "" : displayName;
        this.lore = lore == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(lore));
        this.ownerUuid = ownerUuid;
        this.customRoutingMatched = customRoutingMatched;
        this.globalTrashAvailabilityEvaluated = globalTrashAvailabilityEvaluated;
        this.globalTrashAvailable = globalTrashAvailable;
        this.globalWhitelistRejectedAction = globalWhitelistRejectedAction;
    }

    /** 返回平台标准化后的物品类型。 */
    public String getMaterialKey() {
        return materialKey;
    }

    /** 返回物品数量。 */
    public int getAmount() {
        return amount;
    }

    /** 返回显示名，未设置时为空字符串。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 返回 lore，未设置时为空列表。 */
    public List<String> getLore() {
        return lore;
    }

    /** 返回标记的物品所属玩家 UUID，未标记时为 null。 */
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** 判断物品是否命中新自定义路由规则。 */
    public boolean isCustomRoutingMatched() {
        return customRoutingMatched;
    }

    /** 返回公共桶白名单拒绝动作；未拒绝时为 null。 */
    public RejectedCleanupAction getGlobalWhitelistRejectedAction() {
        return globalWhitelistRejectedAction;
    }

    /** 判断公共桶可用性是否已经在本物品快照中计算。 */
    public boolean isGlobalTrashAvailabilityEvaluated() {
        return globalTrashAvailabilityEvaluated;
    }

    /** 返回快照生成时公共桶是否允许并能接收物品。 */
    public boolean isGlobalTrashAvailable() {
        return globalTrashAvailable;
    }

    /** 返回带有补充所属玩家 UUID 的新快照，已有标记时保持原样。 */
    public ItemSnapshot withOwnerUuid(UUID fallbackOwnerUuid) {
        if (ownerUuid != null || fallbackOwnerUuid == null) {
            return this;
        }
        return new ItemSnapshot(materialKey, amount, displayName, lore, fallbackOwnerUuid,
                customRoutingMatched, globalTrashAvailabilityEvaluated, globalTrashAvailable,
                globalWhitelistRejectedAction);
    }

    /** 返回带有本轮自定义路由和公共桶准入结果的新快照。 */
    public ItemSnapshot withRoutingMetadata(boolean matched,
                                            boolean evaluated,
                                            boolean globalAvailable,
                                            RejectedCleanupAction rejectedAction) {
        if (customRoutingMatched == matched && globalTrashAvailabilityEvaluated == evaluated
                && globalTrashAvailable == globalAvailable
                && globalWhitelistRejectedAction == rejectedAction) {
            return this;
        }
        return new ItemSnapshot(materialKey, amount, displayName, lore, ownerUuid, matched,
                evaluated, globalAvailable, rejectedAction);
    }
}
