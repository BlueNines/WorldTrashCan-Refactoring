package pixeltech.bluenine.blworldtrashcan.bukkit.stacking;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** 以实体身份、材质和数量保护掉落物路由提交。 */
public final class DroppedItemCommitGuard {
    /** 禁止实例化工具类。 */
    private DroppedItemCommitGuard() {
    }

    /** 捕获仍可处理的掉落物状态；实体已失效或数量非法时返回 null。 */
    public static State capture(Item item, ItemQuantityService quantities) {
        if (item == null) {
            return null;
        }
        try {
            if (quantities != null && quantities.isReserved(item)) {
                return null;
            }
            if (!item.isValid() || item.isDead()) {
                return null;
            }
            ItemStack itemStack = item.getItemStack();
            if (!isUsableStack(itemStack)) {
                return null;
            }
            int amount = quantities == null ? itemStack.getAmount() : quantities.getAmount(item);
            if (amount <= 0) {
                return null;
            }
            UUID entityId = item.getUniqueId();
            return entityId == null ? null : new State(entityId, itemStack.clone(), amount);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 判断实体当前是否仍与捕获状态完全一致。 */
    public static boolean isCurrent(Item item, State expected, ItemQuantityService quantities) {
        if (item == null || expected == null) {
            return false;
        }
        try {
            if (quantities != null && quantities.isReserved(item)) {
                return false;
            }
            if (!item.isValid() || item.isDead()) {
                return false;
            }
            if (!expected.entityId.equals(item.getUniqueId())) {
                return false;
            }
            ItemStack current = item.getItemStack();
            if (!isUsableStack(current)) {
                return false;
            }
            int amount = quantities == null ? current.getAmount() : quantities.getAmount(item);
            return amount == expected.amount && expected.hasSameType(current);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 仅在来源仍等于捕获状态时提交剩余数量，并确认最终实体状态。 */
    public static boolean commitRemaining(Item item, State expected, int remainingAmount,
                                          ItemQuantityService quantities) {
        if (expected == null || remainingAmount < 0 || remainingAmount > expected.amount
                || !isCurrent(item, expected, quantities)) {
            return false;
        }
        try {
            if (quantities != null) {
                if (!quantities.setRemaining(item, expected.amount, remainingAmount)) {
                    return false;
                }
                return remainingAmount == 0
                        ? !isLive(item) : hasRemaining(item, expected, remainingAmount, quantities);
            }
            if (remainingAmount == 0) {
                item.remove();
                return !isLive(item);
            }
            ItemStack remaining = item.getItemStack().clone();
            remaining.setAmount(remainingAmount);
            item.setItemStack(remaining);
            return hasRemaining(item, expected, remainingAmount, null);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 判断部分提交后的实体身份和剩余数量是否正确。 */
    private static boolean hasRemaining(Item item, State expected, int remainingAmount,
                                        ItemQuantityService quantities) {
        if (item == null || expected == null) {
            return false;
        }
        try {
            if (!item.isValid() || item.isDead()) {
                return false;
            }
            ItemStack current = item.getItemStack();
            if (!isUsableStack(current)) {
                return false;
            }
            int amount = quantities == null ? current.getAmount() : quantities.getAmount(item);
            return expected.entityId.equals(item.getUniqueId())
                    && amount == remainingAmount && expected.hasSameType(current);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 判断 Bukkit 实体当前仍可安全读取和修改。 */
    public static boolean isLive(Item item) {
        if (item == null) {
            return false;
        }
        try {
            if (!item.isValid() || item.isDead()) {
                return false;
            }
            return isUsableStack(item.getItemStack());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 判断物理 ItemStack 是否仍可由掉落实体承载。 */
    private static boolean isUsableStack(ItemStack itemStack) {
        return itemStack != null && itemStack.getType() != Material.AIR && itemStack.getAmount() > 0;
    }

    /** 一次路由尝试使用的只读来源状态。 */
    public static final class State {
        private final UUID entityId;
        private final ItemStack sample;
        private final int amount;

        /** 创建来源状态。 */
        private State(UUID entityId, ItemStack sample, int amount) {
            this.entityId = entityId;
            this.sample = sample;
            this.amount = amount;
        }

        /** 返回捕获时的实际数量。 */
        public int getAmount() {
            return amount;
        }

        /** 返回只读物品样品；调用方不得修改。 */
        public ItemStack getSample() {
            return sample;
        }

        /** 判断另一份物品是否仍是相同材质。 */
        public boolean hasSameType(ItemStack itemStack) {
            return itemStack != null && sample.getType() == itemStack.getType();
        }
    }
}
