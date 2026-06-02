package pixeltech.bluenine.blworldtrashcan.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 平台层从真实物品提取出的轻量快照，核心层只依赖这个对象做决策。 */
public final class ItemSnapshot {
    private final String materialKey;
    private final int amount;
    private final String displayName;
    private final List<String> lore;
    private final UUID ownerUuid;

    /** 创建物品快照。 */
    public ItemSnapshot(String materialKey, int amount, String displayName, List<String> lore, UUID ownerUuid) {
        this.materialKey = materialKey == null ? "" : materialKey;
        this.amount = Math.max(0, amount);
        this.displayName = displayName == null ? "" : displayName;
        this.lore = lore == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(lore));
        this.ownerUuid = ownerUuid;
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

    /** 返回带有补充所属玩家 UUID 的新快照，已有标记时保持原样。 */
    public ItemSnapshot withOwnerUuid(UUID fallbackOwnerUuid) {
        if (ownerUuid != null || fallbackOwnerUuid == null) {
            return this;
        }
        return new ItemSnapshot(materialKey, amount, displayName, lore, fallbackOwnerUuid);
    }
}
