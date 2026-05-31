package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.entity.Player;

/** 没有 Vault 时的扣费服务。 */
public final class NoPaymentService implements PaymentService {
    /** 不扣费，直接放行。 */
    @Override
    public boolean charge(Player player, double amount) {
        return true;
    }

    /** 返回简单金额文本。 */
    @Override
    public String format(double amount) {
        return String.valueOf(amount);
    }
}

