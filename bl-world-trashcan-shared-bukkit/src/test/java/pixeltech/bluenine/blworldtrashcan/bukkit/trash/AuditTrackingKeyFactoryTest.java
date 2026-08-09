package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.Assert;
import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;

/** 验证个人虚拟垃圾桶的追踪键稳定、定长且忽略物品数量。 */
public final class AuditTrackingKeyFactoryTest {
    /** 相同身份必须得到相同键，不同身份必须得到不同键。 */
    @Test
    public void createsStableOpaqueKeys() {
        AuditTrackingKeyFactory factory = new AuditTrackingKeyFactory(new TestIdentityProvider());

        String oneStone = factory.create(new ItemStack(Material.STONE, 1));
        String sixtyFourStone = factory.create(new ItemStack(Material.STONE, 64));
        String dirt = factory.create(new ItemStack(Material.DIRT, 1));

        Assert.assertEquals(oneStone, sixtyFourStone);
        Assert.assertNotEquals(oneStone, dirt);
        Assert.assertTrue(oneStone.startsWith("item:test:"));
        Assert.assertEquals("item:test:".length() + 64, oneStone.length());
    }

    /** 只按材质生成测试身份。 */
    private static final class TestIdentityProvider implements ItemIdentityProvider {
        /** 返回测试身份实现名称。 */
        @Override
        public String id() {
            return "test";
        }

        /** 返回忽略数量的材质身份。 */
        @Override
        public String key(ItemStack itemStack) {
            return itemStack == null ? null : itemStack.getType().name();
        }
    }
}
