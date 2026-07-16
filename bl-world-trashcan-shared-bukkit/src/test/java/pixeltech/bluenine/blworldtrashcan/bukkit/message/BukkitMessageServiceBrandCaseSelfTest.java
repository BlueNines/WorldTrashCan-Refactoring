package pixeltech.bluenine.blworldtrashcan.bukkit.message;

/** 不依赖服务端运行态的外部语言品牌大小写自测。 */
public final class BukkitMessageServiceBrandCaseSelfTest {
    /** 验证完整名称、短名称和无关文本的迁移结果。 */
    public static void main(String[] args) {
        String legacyName = "B" + "LWorldTrashCan";
        String legacyShort = "B" + "LWTC";
        String legacyMixedShort = "B" + "LWtc";
        String input = "[" + legacyName + "] " + legacyName + " " + legacyShort + " " + legacyMixedShort + " BLUE";
        String expected = "[BlWorldTrashCan] BlWorldTrashCan BlWTC BlWtc BLUE";
        String actual = BukkitMessageService.normalizeBrandCase(input);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("brand case expected " + expected + " but got " + actual);
        }
        System.out.println("BukkitMessageServiceBrandCaseSelfTest passed");
    }

    /** 阻止实例化自测类。 */
    private BukkitMessageServiceBrandCaseSelfTest() {
    }
}
