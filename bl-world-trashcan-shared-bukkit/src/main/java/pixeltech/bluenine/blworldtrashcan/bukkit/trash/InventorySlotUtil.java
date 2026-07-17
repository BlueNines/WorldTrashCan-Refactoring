package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 背包槽位放入工具，保证路由时先判断容量再修改，避免半写入。 */
final class InventorySlotUtil {
    /** 判断指定槽位范围是否能完整放入物品。 */
    static boolean hasSpace(Inventory inventory, ItemStack itemStack, int start, int endExclusive) {
        if (inventory == null || isEmpty(itemStack)) {
            return false;
        }
        int remaining = itemStack.getAmount();
        int maxStack = Math.max(1, itemStack.getMaxStackSize());
        for (int slot = start; slot < endExclusive && slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (isEmpty(current)) {
                remaining -= maxStack;
            } else if (current.isSimilar(itemStack)) {
                remaining -= Math.max(0, maxStack - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 判断指定离散槽位是否能完整放入物品。 */
    static boolean hasSpace(Inventory inventory, ItemStack itemStack, List<Integer> slots) {
        if (inventory == null || isEmpty(itemStack) || slots == null || slots.isEmpty()) {
            return false;
        }
        int remaining = itemStack.getAmount();
        int maxStack = Math.max(1, itemStack.getMaxStackSize());
        for (Integer slotValue : slots) {
            int slot = slotValue == null ? -1 : slotValue.intValue();
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (isEmpty(current)) {
                remaining -= maxStack;
            } else if (current.isSimilar(itemStack)) {
                remaining -= Math.max(0, maxStack - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 判断 Inventory 的可存储槽位是否能完整放入物品。 */
    static boolean hasStorageSpace(Inventory inventory, ItemStack itemStack) {
        if (inventory == null || isEmpty(itemStack)) {
            return false;
        }
        int remaining = itemStack.getAmount();
        int maxStack = Math.max(1, itemStack.getMaxStackSize());
        ItemStack[] contents = inventory.getStorageContents();
        for (ItemStack current : contents) {
            if (isEmpty(current)) {
                remaining -= maxStack;
            } else if (current.isSimilar(itemStack)) {
                remaining -= Math.max(0, maxStack - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 完整放入物品；调用前应先用 hasSpace 判断。 */
    static boolean add(Inventory inventory, ItemStack itemStack, int start, int endExclusive) {
        if (!hasSpace(inventory, itemStack, start, endExclusive)) {
            return false;
        }
        int remaining = itemStack.getAmount();
        int maxStack = Math.max(1, itemStack.getMaxStackSize());
        for (int slot = start; slot < endExclusive && slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (!isEmpty(current) && current.isSimilar(itemStack) && current.getAmount() < maxStack) {
                int moved = Math.min(remaining, maxStack - current.getAmount());
                current.setAmount(current.getAmount() + moved);
                inventory.setItem(slot, current);
                remaining -= moved;
            }
        }
        for (int slot = start; slot < endExclusive && slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (isEmpty(current)) {
                int moved = Math.min(remaining, maxStack);
                ItemStack copy = itemStack.clone();
                copy.setAmount(moved);
                inventory.setItem(slot, copy);
                remaining -= moved;
            }
        }
        return remaining <= 0;
    }

    /** 把物品完整放入指定离散槽位；调用前会先验证容量。 */
    static boolean add(Inventory inventory, ItemStack itemStack, List<Integer> slots) {
        if (!hasSpace(inventory, itemStack, slots)) {
            return false;
        }
        int remaining = itemStack.getAmount();
        int maxStack = Math.max(1, itemStack.getMaxStackSize());
        for (Integer slotValue : slots) {
            int slot = slotValue == null ? -1 : slotValue.intValue();
            if (slot < 0 || slot >= inventory.getSize() || remaining <= 0) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (!isEmpty(current) && current.isSimilar(itemStack) && current.getAmount() < maxStack) {
                int moved = Math.min(remaining, maxStack - current.getAmount());
                current.setAmount(current.getAmount() + moved);
                inventory.setItem(slot, current);
                remaining -= moved;
            }
        }
        for (Integer slotValue : slots) {
            int slot = slotValue == null ? -1 : slotValue.intValue();
            if (slot < 0 || slot >= inventory.getSize() || remaining <= 0) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (isEmpty(current)) {
                int moved = Math.min(remaining, maxStack);
                ItemStack copy = itemStack.clone();
                copy.setAmount(moved);
                inventory.setItem(slot, copy);
                remaining -= moved;
            }
        }
        return remaining <= 0;
    }

    /** 判断物品是否为空。 */
    static boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0;
    }

    /** 阻止实例化工具类。 */
    private InventorySlotUtil() {
    }
}
