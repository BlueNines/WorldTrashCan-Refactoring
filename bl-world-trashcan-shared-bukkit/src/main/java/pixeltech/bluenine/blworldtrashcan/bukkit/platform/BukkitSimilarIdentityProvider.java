package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 使用 Bukkit ItemStack 序列化结果生成便携物品身份键。 */
public final class BukkitSimilarIdentityProvider implements ItemIdentityProvider {
    /** 返回实现名称。 */
    @Override
    public String id() {
        return "bukkit-similar";
    }

    /** 生成忽略数量但保留 Bukkit 可表达元数据的身份键。 */
    @Override
    public String key(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == null || itemStack.getAmount() <= 0) {
            return null;
        }
        try {
            ItemStack normalized = itemStack.clone();
            normalized.setAmount(1);
            return "bukkit-v1|" + canonical(normalized.serialize());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 将 Bukkit 序列化对象按稳定顺序转换为文本。 */
    private String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof ConfigurationSerializable) {
            return "serializable(" + value.getClass().getName() + ")"
                    + canonical(((ConfigurationSerializable) value).serialize());
        }
        if (value instanceof Map<?, ?>) {
            return canonicalMap((Map<?, ?>) value);
        }
        if (value instanceof Iterable<?>) {
            StringBuilder builder = new StringBuilder("list[");
            for (Object item : (Iterable<?>) value) {
                builder.append(canonical(item)).append(';');
            }
            return builder.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder("array[");
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                builder.append(canonical(Array.get(value, index))).append(';');
            }
            return builder.append(']').toString();
        }
        return escape(value.getClass().getName()) + ':' + escape(String.valueOf(value));
    }

    /** 将映射按键名排序，避免第三方序列化映射顺序造成重复身份。 */
    private String canonicalMap(Map<?, ?> map) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<?, ?>>() {
            /** 按键文本排序。 */
            @Override
            public int compare(Map.Entry<?, ?> left, Map.Entry<?, ?> right) {
                return String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey()));
            }
        });
        StringBuilder builder = new StringBuilder("map{");
        for (Map.Entry<?, ?> entry : entries) {
            builder.append(escape(String.valueOf(entry.getKey())))
                    .append('=')
                    .append(canonical(entry.getValue()))
                    .append(';');
        }
        return builder.append('}').toString();
    }

    /** 转义身份键中的结构控制字符。 */
    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace(";", "\\;")
                .replace("=", "\\=");
    }
}
