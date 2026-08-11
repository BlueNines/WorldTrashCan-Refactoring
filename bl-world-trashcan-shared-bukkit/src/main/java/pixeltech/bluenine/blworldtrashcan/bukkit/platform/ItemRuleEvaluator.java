package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.inventory.ItemStack;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.ItemMatchRules;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;

import java.util.Set;

/** 按可见元数据、PDC 和 Raw NBT 的固定短路顺序执行五类物品规则。 */
public final class ItemRuleEvaluator {
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final ReflectiveItemDataKeyInspector inspector;

    /** 创建并完成一次运行时能力探测。 */
    public ItemRuleEvaluator(ItemSnapshotMapper itemSnapshotMapper) {
        this(itemSnapshotMapper, new ReflectiveItemDataKeyInspector());
    }

    /** 创建使用指定 key 检查器的规则执行器。 */
    public ItemRuleEvaluator(ItemSnapshotMapper itemSnapshotMapper,
                             ReflectiveItemDataKeyInspector inspector) {
        this.itemSnapshotMapper = itemSnapshotMapper;
        this.inspector = inspector;
    }

    /** 判断物品是否命中任意一类规则；不需要的数据源不会被读取。 */
    public boolean matches(ItemMatchRules rules, ItemSnapshot snapshot, ItemStack itemStack) {
        if (rules == null || rules.isEmpty() || snapshot == null || itemStack == null) {
            return false;
        }
        if (rules.matchesVisible(snapshot)) {
            return true;
        }
        ItemStack inspected = sanitize(itemStack);
        if (rules.requiresPdcKeys() && rules.matchesPdcKeys(inspector.pdcKeys(inspected))) {
            return true;
        }
        return rules.requiresNbtKeys() && rules.matchesNbtKeys(inspector.nbtKeyPaths(inspected));
    }

    /** 返回去除插件内部旧 owner 标记后的 PDC key。 */
    public Set<String> pdcKeys(ItemStack itemStack) {
        return inspector.pdcKeys(sanitize(itemStack));
    }

    /** 返回去除插件内部旧 owner 标记后的 Raw NBT key 路径。 */
    public Set<String> nbtKeyPaths(ItemStack itemStack) {
        return inspector.nbtKeyPaths(sanitize(itemStack));
    }

    /** 判断 PDC 读取能力是否可用。 */
    public boolean isPdcReady() {
        return inspector.isPdcReady();
    }

    /** 判断 Raw NBT 读取能力是否可用。 */
    public boolean isNbtReady() {
        return inspector.isNbtReady();
    }

    /** 返回 PDC 探测失败原因。 */
    public String getPdcFailureReason() {
        return inspector.getPdcFailureReason();
    }

    /** 返回 Raw NBT 探测失败原因。 */
    public String getNbtFailureReason() {
        return inspector.getNbtFailureReason();
    }

    /** 清除插件自身旧标记，避免把它当作业务自定义数据。 */
    private ItemStack sanitize(ItemStack itemStack) {
        if (itemSnapshotMapper == null) {
            return itemStack;
        }
        return itemSnapshotMapper.sanitizeForStorage(itemStack);
    }
}
