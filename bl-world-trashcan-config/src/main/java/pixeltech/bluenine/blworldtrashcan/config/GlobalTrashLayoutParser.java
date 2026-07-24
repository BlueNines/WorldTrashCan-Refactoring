package pixeltech.bluenine.blworldtrashcan.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 读取并校验公共垃圾桶的小型字符布局。 */
public final class GlobalTrashLayoutParser {
    private static final String LAYOUT_PATH = "global-trash.gui.layout";

    /** 读取布局；缺少新布局时兼容现有固定按钮配置。 */
    public TrashConfig.GlobalTrashLayoutConfig parse(ConfigurationSource source,
                                                      int legacyBackModelId,
                                                      int legacyNextModelId,
                                                      int legacyBackgroundModelId) {
        List<String> rows = source.getStringList(LAYOUT_PATH + ".position");
        if (rows == null || rows.isEmpty()) {
            return TrashConfig.GlobalTrashLayoutConfig.defaultLayout(
                    legacyBackModelId, legacyNextModelId, legacyBackgroundModelId, null);
        }
        List<String> errors = validateRows(rows);
        Set<Character> symbols = collectSymbols(rows);
        Map<Character, TrashConfig.GlobalTrashItemConfig> items = parseItems(source, symbols, errors);
        validateItems(symbols, items, errors);
        if (!errors.isEmpty()) {
            return TrashConfig.GlobalTrashLayoutConfig.defaultLayout(
                    legacyBackModelId, legacyNextModelId, legacyBackgroundModelId, joinErrors(errors));
        }
        return new TrashConfig.GlobalTrashLayoutConfig(rows, items, null);
    }

    /** 校验布局行数、宽度和字符范围。 */
    private List<String> validateRows(List<String> rows) {
        List<String> errors = new ArrayList<>();
        if (rows.size() < 1 || rows.size() > 6) {
            errors.add("global-trash.gui.layout.position 只能配置 1-6 行，当前为 " + rows.size() + " 行");
        }
        for (int index = 0; index < rows.size(); index++) {
            String row = rows.get(index);
            if (row == null || row.length() != 9) {
                errors.add("global-trash.gui.layout.position[" + index + "] 必须正好包含 9 个字符");
                continue;
            }
            for (int column = 0; column < row.length(); column++) {
                char symbol = row.charAt(column);
                if (!isSupportedSymbol(symbol)) {
                    errors.add("布局字符 '" + symbol + "' 不受支持，只允许英文字母、数字或下划线");
                }
            }
        }
        return errors;
    }

    /** 收集布局实际引用的字符。 */
    private Set<Character> collectSymbols(List<String> rows) {
        Set<Character> symbols = new LinkedHashSet<>();
        for (String row : rows) {
            if (row == null) {
                continue;
            }
            for (int index = 0; index < row.length(); index++) {
                symbols.add(Character.valueOf(row.charAt(index)));
            }
        }
        return symbols;
    }

    /** 读取布局字符对应的物品定义。 */
    private Map<Character, TrashConfig.GlobalTrashItemConfig> parseItems(
            ConfigurationSource source, Set<Character> symbols, List<String> errors) {
        Map<Character, TrashConfig.GlobalTrashItemConfig> items = new LinkedHashMap<>();
        for (Character symbolValue : symbols) {
            char symbol = symbolValue.charValue();
            String path = LAYOUT_PATH + ".items." + symbol;
            TrashConfig.GlobalTrashItemType type = parseType(source.getString(path + ".type", ""));
            if (type == null) {
                errors.add(path + ".type 无效，可用值为 content、previous-page、next-page、background、actions");
                continue;
            }
            List<String> materials = source.getStringList(path + ".material");
            if (materials == null || materials.isEmpty()) {
                materials = defaultMaterials(type);
            }
            String name = source.contains(path + ".name") ? source.getString(path + ".name", "") : null;
            List<String> lore = source.getStringList(path + ".lore");
            List<String> actions = source.getStringList(path + ".actions");
            Character unavailableItem = parseUnavailableItem(
                    source.getString(path + ".unavailable-item", ""), path, errors);
            items.put(symbolValue, new TrashConfig.GlobalTrashItemConfig(
                    symbol, type, source.getInt(path + ".model-id", -1), materials,
                    name, lore, actions, unavailableItem));
        }
        return items;
    }

    /** 校验内容槽和不可用按钮替代项。 */
    private void validateItems(Set<Character> symbols,
                               Map<Character, TrashConfig.GlobalTrashItemConfig> items,
                               List<String> errors) {
        boolean hasContent = false;
        for (Character symbol : symbols) {
            TrashConfig.GlobalTrashItemConfig item = items.get(symbol);
            if (item == null) {
                continue;
            }
            if (item.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                hasContent = true;
            }
            Character unavailable = item.getUnavailableItem();
            if (unavailable == null) {
                continue;
            }
            if (item.getType() != TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE
                    && item.getType() != TrashConfig.GlobalTrashItemType.NEXT_PAGE) {
                errors.add("布局字符 '" + symbol + "' 只有翻页按钮可以配置 unavailable-item");
            }
            TrashConfig.GlobalTrashItemConfig fallback = items.get(unavailable);
            if (fallback == null) {
                errors.add("布局字符 '" + symbol + "' 的 unavailable-item 引用了不存在的字符 '" + unavailable + "'");
            } else if (fallback.getType() == TrashConfig.GlobalTrashItemType.CONTENT) {
                errors.add("布局字符 '" + symbol + "' 的 unavailable-item 不能引用 content 槽 '" + unavailable + "'");
            }
        }
        if (!hasContent) {
            errors.add("公共垃圾桶布局至少需要一个 type: content 的内容槽");
        }
        validateUnavailableCycles(items, errors);
    }

    /** 检查 unavailable-item 引用链是否形成循环。 */
    private void validateUnavailableCycles(Map<Character, TrashConfig.GlobalTrashItemConfig> items,
                                           List<String> errors) {
        for (Character start : items.keySet()) {
            Set<Character> visited = new LinkedHashSet<>();
            Character current = start;
            while (current != null && visited.add(current)) {
                TrashConfig.GlobalTrashItemConfig item = items.get(current);
                current = item == null ? null : item.getUnavailableItem();
            }
            if (current != null) {
                errors.add("布局字符 '" + start + "' 的 unavailable-item 引用形成循环");
            }
        }
    }

    /** 解析布局物品类型。 */
    private TrashConfig.GlobalTrashItemType parseType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("content".equals(normalized)) {
            return TrashConfig.GlobalTrashItemType.CONTENT;
        }
        if ("previous-page".equals(normalized)) {
            return TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE;
        }
        if ("next-page".equals(normalized)) {
            return TrashConfig.GlobalTrashItemType.NEXT_PAGE;
        }
        if ("background".equals(normalized)) {
            return TrashConfig.GlobalTrashItemType.BACKGROUND;
        }
        if ("actions".equals(normalized)) {
            return TrashConfig.GlobalTrashItemType.ACTIONS;
        }
        return null;
    }

    /** 解析按钮不可用时显示的布局字符。 */
    private Character parseUnavailableItem(String value, String path, List<String> errors) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() != 1 || !isSupportedSymbol(normalized.charAt(0))) {
            errors.add(path + ".unavailable-item 必须是单个受支持的布局字符");
            return null;
        }
        return Character.valueOf(normalized.charAt(0));
    }

    /** 返回指定类型的默认材质候选。 */
    private List<String> defaultMaterials(TrashConfig.GlobalTrashItemType type) {
        if (type == TrashConfig.GlobalTrashItemType.PREVIOUS_PAGE
                || type == TrashConfig.GlobalTrashItemType.NEXT_PAGE) {
            return Collections.singletonList("ARROW");
        }
        if (type == TrashConfig.GlobalTrashItemType.BACKGROUND) {
            List<String> result = new ArrayList<>();
            result.add("BLACK_STAINED_GLASS_PANE");
            result.add("STAINED_GLASS_PANE");
            result.add("LEGACY_STAINED_GLASS_PANE");
            result.add("GRAY_STAINED_GLASS_PANE");
            result.add("GLASS_PANE");
            result.add("THIN_GLASS");
            return result;
        }
        if (type == TrashConfig.GlobalTrashItemType.ACTIONS) {
            return Collections.singletonList("BOOK");
        }
        return Collections.emptyList();
    }

    /** 判断字符是否可以安全用于 YAML 路径。 */
    private boolean isSupportedSymbol(char symbol) {
        return symbol == '_' || symbol >= '0' && symbol <= '9'
                || symbol >= 'a' && symbol <= 'z' || symbol >= 'A' && symbol <= 'Z';
    }

    /** 合并多条校验错误。 */
    private String joinErrors(List<String> errors) {
        StringBuilder builder = new StringBuilder();
        for (String error : errors) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(error);
        }
        return builder.toString();
    }
}
