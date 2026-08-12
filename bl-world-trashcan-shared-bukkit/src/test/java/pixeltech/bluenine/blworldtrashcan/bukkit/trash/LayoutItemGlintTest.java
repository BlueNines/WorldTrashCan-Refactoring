package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;

/** 验证旧版 Bukkit 的隐藏附魔光效降级。 */
public final class LayoutItemGlintTest {
    /** 1.12 API 下应添加安全附魔并隐藏附魔描述。 */
    @Test
    public void appliesHiddenEnchantmentOnLegacyApi() {
        LegacyMetaHandler handler = new LegacyMetaHandler();
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class}, handler);
        LayoutItemGlint glint = new LayoutItemGlint(null, new TestEnchantment());

        glint.apply(meta);

        Assert.assertEquals("hidden-enchantment", glint.getModeName());
        Assert.assertNotNull(handler.enchantment);
        Assert.assertEquals(1, handler.enchantmentLevel);
        Assert.assertTrue(handler.flags.contains(ItemFlag.HIDE_ENCHANTS));
    }

    /** 提供不依赖 Bukkit 服务端注册表的测试附魔。 */
    private static final class TestEnchantment extends Enchantment {
        /** 使用不会注册到服务端的测试 ID。 */
        private TestEnchantment() {
            super(126);
        }

        /** 返回测试名称。 */
        @Override
        public String getName() {
            return "WTC_TEST_GLINT";
        }

        /** 返回测试最大等级。 */
        @Override
        public int getMaxLevel() {
            return 1;
        }

        /** 返回测试起始等级。 */
        @Override
        public int getStartLevel() {
            return 1;
        }

        /** 返回测试适用目标。 */
        @Override
        public EnchantmentTarget getItemTarget() {
            return EnchantmentTarget.ALL;
        }

        /** 测试附魔不是宝藏附魔。 */
        @Override
        public boolean isTreasure() {
            return false;
        }

        /** 测试附魔不是诅咒。 */
        @Override
        public boolean isCursed() {
            return false;
        }

        /** 测试附魔不与其它附魔冲突。 */
        @Override
        public boolean conflictsWith(Enchantment other) {
            return false;
        }

        /** 测试附魔允许放在任意物品上。 */
        @Override
        public boolean canEnchantItem(ItemStack item) {
            return true;
        }
    }

    /** 记录测试关心的 ItemMeta 调用，其余方法返回基础默认值。 */
    private static final class LegacyMetaHandler implements InvocationHandler {
        private Enchantment enchantment;
        private int enchantmentLevel;
        private final EnumSet<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);

        /** 捕获附魔与物品标志调用。 */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("addEnchant".equals(method.getName())) {
                enchantment = (Enchantment) args[0];
                enchantmentLevel = ((Integer) args[1]).intValue();
                return Boolean.TRUE;
            }
            if ("addItemFlags".equals(method.getName())) {
                ItemFlag[] values = (ItemFlag[]) args[0];
                for (ItemFlag value : values) {
                    flags.add(value);
                }
                return null;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class) {
                return Integer.valueOf(0);
            }
            return null;
        }
    }
}
