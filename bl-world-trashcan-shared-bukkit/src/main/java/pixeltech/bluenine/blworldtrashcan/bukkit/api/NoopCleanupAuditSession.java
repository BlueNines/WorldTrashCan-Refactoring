package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.inventory.ItemStack;
import pixeltech.worldlisttrashcan.api.audit.CleanupAuditSession;
import pixeltech.worldlisttrashcan.api.audit.CleanupRunCompletion;

/** 未注册审计消费者时复用的零分配空会话。 */
public enum NoopCleanupAuditSession implements CleanupAuditSession {
    INSTANCE;

    /** 忽略物品、去向和追踪键记录。 */
    @Override
    public void recordItem(ItemStack itemStack, pixeltech.worldlisttrashcan.api.audit.CleanupItemDestination destination,
                           String trackingKey) {
    }

    /** 忽略完成通知。 */
    @Override
    public void complete(CleanupRunCompletion completion) {
    }

    /** 忽略放弃通知。 */
    @Override
    public void discard() {
    }
}
