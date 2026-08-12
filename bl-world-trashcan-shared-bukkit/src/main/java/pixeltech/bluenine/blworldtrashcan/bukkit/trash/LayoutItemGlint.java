package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** 为配置生成的 GUI 展示物提供跨版本附魔光效。 */
final class LayoutItemGlint {
    private final Logger logger;
    private final Method nativeOverrideMethod;
    private final Enchantment fallbackEnchantment;
    private final AtomicBoolean nativeFailureLogged = new AtomicBoolean();

    /** 在服务启动时探测当前 Bukkit 是否支持纯光效覆盖。 */
    LayoutItemGlint(Logger logger) {
        this(logger, findFallbackEnchantment());
    }

    /** 创建可注入旧版降级附魔的测试实例。 */
    LayoutItemGlint(Logger logger, Enchantment fallbackEnchantment) {
        this.logger = logger;
        this.nativeOverrideMethod = findNativeOverrideMethod();
        this.fallbackEnchantment = fallbackEnchantment;
    }

    /** 给展示物应用光效；高版本使用纯光效，旧版本自动降级为隐藏附魔。 */
    void apply(ItemMeta meta) {
        if (meta == null) {
            return;
        }
        if (applyNativeOverride(meta)) {
            return;
        }
        applyHiddenEnchantment(meta);
    }

    /** 返回启动时确定的光效实现名称。 */
    String getModeName() {
        return nativeOverrideMethod == null ? "hidden-enchantment" : "bukkit-glint-override";
    }

    /** 探测 1.20.5 及以上 Bukkit 的原生纯光效 API。 */
    private Method findNativeOverrideMethod() {
        try {
            return ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        } catch (LinkageError ignored) {
            return null;
        }
    }

    /** 尝试调用原生纯光效 API，失败时允许当前物品降级。 */
    private boolean applyNativeOverride(ItemMeta meta) {
        if (nativeOverrideMethod == null) {
            return false;
        }
        try {
            nativeOverrideMethod.invoke(meta, Boolean.TRUE);
            return true;
        } catch (ReflectiveOperationException exception) {
            logNativeFailure(exception);
            return false;
        } catch (RuntimeException exception) {
            logNativeFailure(exception);
            return false;
        } catch (LinkageError error) {
            logNativeFailure(error);
            return false;
        }
    }

    /** 使用旧版 Bukkit 全版本可用的隐藏附魔生成光效。 */
    private void applyHiddenEnchantment(ItemMeta meta) {
        if (fallbackEnchantment == null) {
            return;
        }
        meta.addEnchant(fallbackEnchantment, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    /** 启动时从 Bukkit 注册表解析跨版本可用的降级附魔。 */
    private static Enchantment findFallbackEnchantment() {
        Enchantment enchantment = Enchantment.getByName("DURABILITY");
        if (enchantment == null) {
            enchantment = Enchantment.getByName("UNBREAKING");
        }
        return enchantment;
    }

    /** 原生 API 异常时只记录一次，避免玩家反复打开菜单刷屏。 */
    private void logNativeFailure(Throwable throwable) {
        if (logger == null || !nativeFailureLogged.compareAndSet(false, true)) {
            return;
        }
        logger.warning("[GlobalTrash] Bukkit 纯光效 API 调用失败，已降级为隐藏附魔: "
                + throwable.getClass().getSimpleName());
    }
}
