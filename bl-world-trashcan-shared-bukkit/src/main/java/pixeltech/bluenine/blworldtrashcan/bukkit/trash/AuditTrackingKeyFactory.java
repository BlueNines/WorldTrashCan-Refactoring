package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSimilarIdentityProvider;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 仅在审计消费者存在时为非模型垃圾桶生成短小的不透明物品追踪键。 */
final class AuditTrackingKeyFactory {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ItemIdentityProvider primary;
    private final ItemIdentityProvider fallback = new BukkitSimilarIdentityProvider();

    /** 创建复用主插件既定物品身份策略的追踪键工厂。 */
    AuditTrackingKeyFactory(ItemIdentityProvider primary) {
        this.primary = primary == null ? fallback : primary;
    }

    /** 返回不包含原始 NBT 的固定长度追踪键，无法识别时返回空字符串。 */
    String create(ItemStack itemStack) {
        String identity = primary.key(itemStack);
        String providerId = primary.id();
        if (identity == null && primary != fallback) {
            identity = fallback.key(itemStack);
            providerId = fallback.id();
        }
        return identity == null ? "" : "item:" + providerId + ':' + sha256(identity);
    }

    /** 计算身份文本的固定长度 SHA-256。 */
    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[hash.length * 2];
            for (int index = 0; index < hash.length; index++) {
                int current = hash[index] & 0xff;
                result[index * 2] = HEX[current >>> 4];
                result[index * 2 + 1] = HEX[current & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
