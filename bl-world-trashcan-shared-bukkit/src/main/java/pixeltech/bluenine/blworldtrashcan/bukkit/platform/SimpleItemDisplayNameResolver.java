package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/** 在没有现代本地化实现时提供稳定且可读的物品名称。 */
public final class SimpleItemDisplayNameResolver implements ItemDisplayNameResolver {
    private static final SimpleItemDisplayNameResolver INSTANCE = new SimpleItemDisplayNameResolver();

    /** 创建共享的无状态回退解析器。 */
    private SimpleItemDisplayNameResolver() {
    }

    /** 返回共享回退解析器。 */
    public static SimpleItemDisplayNameResolver getInstance() {
        return INSTANCE;
    }

    /** 优先返回物品自定义名，否则返回可读英文材质名。 */
    @Override
    public String resolve(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return "Item";
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return formatMaterialName(itemStack.getType().name());
    }

    /** 将 Bukkit 枚举材质名转换为可读英文名称。 */
    public static String formatMaterialName(String materialName) {
        if (materialName == null || materialName.trim().isEmpty()) {
            return "Item";
        }
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(materialName.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            if ("tnt".equals(word)) {
                result.append("TNT");
            } else {
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.length() == 0 ? materialName : result.toString();
    }
}
