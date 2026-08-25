package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Material;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;

/** 把逐 Material 配置压成 ordinal 数组，供实体热路径常量时间读取。 */
final class ItemStackingMaterialPolicy {
    private final boolean[] enabled;
    private final int[] maximumAmounts;
    private final String[] displayNames;

    /** 按当前服务端 Material 构建不可变运行快照。 */
    private ItemStackingMaterialPolicy(ItemStackingConfig config) {
        Material[] materials = Material.values();
        this.enabled = new boolean[materials.length];
        this.maximumAmounts = new int[materials.length];
        this.displayNames = new String[materials.length];
        for (Material material : materials) {
            ItemStackingConfig.ItemRule rule = config.getItemRule(material.name());
            int ordinal = material.ordinal();
            enabled[ordinal] = rule.isEnabled();
            maximumAmounts[ordinal] = rule.getMaxStackSize();
            displayNames[ordinal] = rule.getDisplayName();
        }
    }

    /** 从类型化配置创建运行快照。 */
    static ItemStackingMaterialPolicy from(ItemStackingConfig config) {
        return new ItemStackingMaterialPolicy(config);
    }

    /** 判断插件是否接管指定材质。 */
    boolean isEnabled(Material material) {
        int ordinal = ordinal(material);
        return ordinal >= 0 && enabled[ordinal];
    }

    /** 返回指定材质最终生效的逻辑上限。 */
    int maximumAmount(Material material) {
        int ordinal = ordinal(material);
        return ordinal < 0 ? 2 : maximumAmounts[ordinal];
    }

    /** 返回指定材质的独立显示名；null 表示使用内置语言。 */
    String displayName(Material material) {
        int ordinal = ordinal(material);
        return ordinal < 0 ? null : displayNames[ordinal];
    }

    /** 返回可安全访问数组的 Material ordinal。 */
    private int ordinal(Material material) {
        if (material == null) {
            return -1;
        }
        int ordinal = material.ordinal();
        return ordinal >= 0 && ordinal < enabled.length ? ordinal : -1;
    }
}
