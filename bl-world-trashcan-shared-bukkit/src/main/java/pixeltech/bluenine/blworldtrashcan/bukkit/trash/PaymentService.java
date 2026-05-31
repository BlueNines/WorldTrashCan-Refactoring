package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.entity.Player;

/** 个人垃圾桶扣费服务，Vault 不存在时使用空实现。 */
public interface PaymentService {
    /** 尝试向玩家扣费，金额小于等于 0 时应直接成功。 */
    boolean charge(Player player, double amount);

    /** 格式化金额。 */
    String format(double amount);
}

