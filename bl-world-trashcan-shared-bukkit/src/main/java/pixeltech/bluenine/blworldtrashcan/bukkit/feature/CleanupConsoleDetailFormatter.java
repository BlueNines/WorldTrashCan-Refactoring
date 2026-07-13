package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;

import java.util.ArrayList;
import java.util.List;

/** 把清理统计格式化为有界、可搜索的控制台明细。 */
public final class CleanupConsoleDetailFormatter {
    /** 阻止实例化格式化工具类。 */
    private CleanupConsoleDetailFormatter() {
    }

    /** 返回本轮清理的控制台明细行。 */
    public static List<String> format(NotifyConfig.ConsoleConfig config,
                                      CleanupFeature.CleanupStats stats, boolean partial) {
        CleanupFeature.CleanupStats.EntityRemovalSummary summary =
                stats.snapshotEntityRemovalSummary(config.getMaxEntries());
        List<String> lines = new ArrayList<>();
        lines.add("entities=" + summary.getTotalEntities()
                + ", items=" + summary.getTotalItems()
                + ", groups=" + summary.getTrackedGroups()
                + ", shown=" + summary.getEntries().size()
                + ", partial=" + partial);
        for (CleanupFeature.CleanupStats.EntityRemovalEntry entry : summary.getEntries()) {
            lines.add(singleLine(config.getEntityFormat()
                    .replace("{count}", String.valueOf(entry.getCount()))
                    .replace("{type}", entry.getType())
                    .replace("{name}", entry.getName())));
        }
        if (summary.getOthers() > 0) {
            lines.add(singleLine(config.getOthersFormat()
                    .replace("{count}", String.valueOf(summary.getOthers()))));
        }
        lines.add(singleLine(config.getItemsFormat()
                .replace("{count}", String.valueOf(summary.getTotalItems()))));
        return lines;
    }

    /** 把配置格式中的换行和控制字符压成单行空格。 */
    private static String singleLine(String value) {
        String text = value == null ? "" : value;
        StringBuilder result = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.append(character);
        }
        return result.toString();
    }
}
