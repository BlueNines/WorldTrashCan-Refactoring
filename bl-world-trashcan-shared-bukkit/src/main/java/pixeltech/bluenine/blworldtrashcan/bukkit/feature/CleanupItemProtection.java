package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import pixeltech.bluenine.blworldtrashcan.config.CleanupConfig;

/** 提供只在清理阶段执行的掉落物保护判断。 */
public final class CleanupItemProtection {
    private CleanupItemProtection() {
    }

    /** 判断掉落物携带的潜影盒物品是否有内嵌物品。 */
    public static boolean isFilledShulkerItem(ItemStack itemStack, CleanupConfig cleanupConfig) {
        if (itemStack == null || cleanupConfig == null
                || !cleanupConfig.getFilledShulkerBoxes().isEnabled()) {
            return false;
        }
        if (!isShulkerBoxItem(itemStack)) {
            return false;
        }
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (!(itemMeta instanceof BlockStateMeta)) {
            return false;
        }
        BlockState blockState;
        try {
            blockState = ((BlockStateMeta) itemMeta).getBlockState();
        } catch (RuntimeException ignored) {
            return true;
        }
        if (!(blockState instanceof Container)) {
            return false;
        }
        try {
            return hasContents(((Container) blockState).getInventory());
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** 判断掉落物携带的物品是否为跨版本命名的潜影盒。 */
    private static boolean isShulkerBoxItem(ItemStack itemStack) {
        String materialName = itemStack.getType().name();
        return "SHULKER_BOX".equals(materialName) || materialName.endsWith("_SHULKER_BOX");
    }

    /** 判断潜影盒库存中是否存在实际物品。 */
    private static boolean hasContents(Inventory inventory) {
        if (inventory == null) {
            return true;
        }
        ItemStack[] contents = inventory.getContents();
        if (contents == null) {
            return true;
        }
        for (ItemStack content : contents) {
            if (content != null && content.getAmount() > 0
                    && !"AIR".equals(content.getType().name())) {
                return true;
            }
        }
        return false;
    }
}
