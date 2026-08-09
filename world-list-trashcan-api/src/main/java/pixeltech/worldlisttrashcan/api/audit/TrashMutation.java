package pixeltech.worldlisttrashcan.api.audit;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** 描述一次已经成功发生的虚拟垃圾桶数量变更。 */
public final class TrashMutation {
    private final TrashMutationType type;
    private final TrashMutationReason reason;
    private final CleanupItemDestination destination;
    private final ItemStack itemStack;
    private final String trackingKey;
    private final int amount;
    private final UUID actorUuid;
    private final String actorName;
    private final long occurredAtMillis;

    /** 创建不可变变更并复制传入物品。 */
    private TrashMutation(TrashMutationType type, TrashMutationReason reason,
                          CleanupItemDestination destination, ItemStack itemStack, String trackingKey, int amount,
                          UUID actorUuid, String actorName, long occurredAtMillis) {
        this.type = Objects.requireNonNull(type, "type");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.itemStack = itemStack == null ? null : itemStack.clone();
        this.trackingKey = trackingKey == null ? "" : trackingKey;
        this.amount = Math.max(0, amount);
        this.actorUuid = actorUuid;
        this.actorName = actorName == null ? "" : actorName;
        this.occurredAtMillis = occurredAtMillis;
    }

    /** 创建不进入可见审计的存入变更。 */
    public static TrashMutation untrackedDeposit(CleanupItemDestination destination, ItemStack itemStack,
                                                 String trackingKey, int amount,
                                                 TrashMutationReason reason, long occurredAtMillis) {
        if (itemStack == null || trackingKey == null || trackingKey.isEmpty() || amount <= 0) {
            throw new IllegalArgumentException("deposit item, trackingKey and amount must be valid");
        }
        return new TrashMutation(TrashMutationType.UNTRACKED_DEPOSIT, reason,
                destination, itemStack, trackingKey, amount, null, "", occurredAtMillis);
    }

    /** 创建玩家实际取出变更。 */
    public static TrashMutation take(CleanupItemDestination destination, ItemStack itemStack,
                                     String trackingKey, int amount,
                                     UUID actorUuid, String actorName, long occurredAtMillis) {
        if (itemStack == null || trackingKey == null || trackingKey.isEmpty()
                || amount <= 0 || actorUuid == null) {
            throw new IllegalArgumentException("take item, trackingKey, amount and actorUuid must be valid");
        }
        return new TrashMutation(TrashMutationType.TAKE, TrashMutationReason.PLAYER_TAKE,
                destination, itemStack, trackingKey, amount, actorUuid, actorName, occurredAtMillis);
    }

    /** 创建整个虚拟垃圾桶的清空变更。 */
    public static TrashMutation clear(CleanupItemDestination destination,
                                      TrashMutationReason reason, long occurredAtMillis) {
        return new TrashMutation(TrashMutationType.CLEAR, reason,
                destination, null, "", 0, null, "", occurredAtMillis);
    }

    /** 返回变更类型。 */
    public TrashMutationType getType() {
        return type;
    }

    /** 返回业务原因。 */
    public TrashMutationReason getReason() {
        return reason;
    }

    /** 返回变更目标。 */
    public CleanupItemDestination getDestination() {
        return destination;
    }

    /** 返回隔离复制的物品；清空事件返回 null。 */
    public ItemStack getItemStack() {
        return itemStack == null ? null : itemStack.clone();
    }

    /** 返回主插件存储模型提供的不透明追踪键；清空事件返回空字符串。 */
    public String getTrackingKey() {
        return trackingKey;
    }

    /** 返回实际变更数量。 */
    public int getAmount() {
        return amount;
    }

    /** 返回实际操作玩家 UUID。 */
    public UUID getActorUuid() {
        return actorUuid;
    }

    /** 返回操作当时的玩家名。 */
    public String getActorName() {
        return actorName;
    }

    /** 返回变更发生时间。 */
    public long getOccurredAtMillis() {
        return occurredAtMillis;
    }
}
