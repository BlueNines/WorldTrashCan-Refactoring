package pixeltech.bluenine.blworldtrashcan.plugin;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.NoPaymentService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PaymentService;

/** Vault 经济扣费服务。 */
public final class VaultPaymentService implements PaymentService {
    private final Economy economy;

    /** 创建 Vault 扣费服务。 */
    private VaultPaymentService(Economy economy) {
        this.economy = economy;
    }

    /** 从服务器服务管理器中解析 Vault 经济服务。 */
    public static PaymentService create(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("[Vault] 未检测到 Vault，个人垃圾桶扣费自动关闭。");
            return new NoPaymentService();
        }
        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            plugin.getLogger().info("[Vault] 未检测到 Economy 服务，个人垃圾桶扣费自动关闭。");
            return new NoPaymentService();
        }
        plugin.getLogger().info("[Vault] 已连接经济服务: " + provider.getProvider().getName());
        return new VaultPaymentService(provider.getProvider());
    }

    /** 向玩家扣费。 */
    @Override
    public boolean charge(Player player, double amount) {
        if (amount <= 0D) {
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    /** 格式化金额。 */
    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}

