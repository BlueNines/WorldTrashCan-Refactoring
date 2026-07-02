package pixeltech.bluenine.blworldtrashcan.plugin.universal;

import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.NoPaymentService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PaymentService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Vault 经济扣费服务，使用反射避免 Vault 缺失时触发类加载失败。 */
public final class UniversalVaultPaymentService implements PaymentService {
    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";
    private final Object economy;
    private final Method withdrawPlayerMethod;
    private final Method formatMethod;

    /** 创建 Vault 扣费服务。 */
    private UniversalVaultPaymentService(Object economy, Method withdrawPlayerMethod, Method formatMethod) {
        this.economy = economy;
        this.withdrawPlayerMethod = withdrawPlayerMethod;
        this.formatMethod = formatMethod;
    }

    /** 从服务器服务管理器中解析 Vault 经济服务。 */
    public static PaymentService create(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("[Vault] 未检测到 Vault，个人垃圾桶扣费自动关闭。");
            return new NoPaymentService();
        }
        try {
            Class<?> economyClass = Class.forName(ECONOMY_CLASS);
            RegisteredServiceProvider<?> provider = plugin.getServer().getServicesManager().getRegistration(economyClass);
            if (provider == null || provider.getProvider() == null) {
                plugin.getLogger().info("[Vault] 未检测到 Economy 服务，个人垃圾桶扣费自动关闭。");
                return new NoPaymentService();
            }
            Object economy = provider.getProvider();
            Method withdrawPlayer = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Method format = economyClass.getMethod("format", double.class);
            plugin.getLogger().info("[Vault] 已连接经济服务: " + readEconomyName(economyClass, economy));
            return new UniversalVaultPaymentService(economy, withdrawPlayer, format);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("[Vault] Vault API 不可用，个人垃圾桶扣费自动关闭: " + exception.getMessage());
            return new NoPaymentService();
        }
    }

    /** 向玩家扣费。 */
    @Override
    public boolean charge(Player player, double amount) {
        if (amount <= 0D) {
            return true;
        }
        try {
            Object response = withdrawPlayerMethod.invoke(economy, player, Double.valueOf(amount));
            if (response == null) {
                return false;
            }
            Method success = response.getClass().getMethod("transactionSuccess");
            Object result = success.invoke(response);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    /** 格式化金额。 */
    @Override
    public String format(double amount) {
        try {
            Object value = formatMethod.invoke(economy, Double.valueOf(amount));
            return value == null ? String.valueOf(amount) : String.valueOf(value);
        } catch (ReflectiveOperationException exception) {
            return String.valueOf(amount);
        }
    }

    /** 读取 Vault 经济服务名称。 */
    private static String readEconomyName(Class<?> economyClass, Object economy) throws ReflectiveOperationException {
        try {
            Object name = economyClass.getMethod("getName").invoke(economy);
            return name == null ? "unknown" : String.valueOf(name);
        } catch (InvocationTargetException exception) {
            return "unknown";
        }
    }
}
