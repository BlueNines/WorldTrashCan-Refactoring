package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** 验证紧凑模式完整 Lore 模板展开规则。 */
public final class TrashContainerMenuLoreTest {
    /** 验证模板顺序、空行、原 Lore 截断和省略行位置。 */
    @Test
    public void expandsOriginalLoreAtConfiguredPosition() {
        List<String> result = TrashContainerMenu.expandCompactLore(
                Arrays.asList("数量", "", "{content}", "操作"),
                Arrays.asList("原一", "原二", "原三", "原四"),
                2,
                "省略 {count} 行");

        assertEquals(Arrays.asList("数量", "", "原一", "原二", "省略 2 行", "操作"), result);
    }

    /** 验证没有原 Lore 时占位符展开为零行。 */
    @Test
    public void emptyOriginalLoreAddsNoContentLine() {
        List<String> result = TrashContainerMenu.expandCompactLore(
                Arrays.asList("数量", "{content}", "操作"),
                Collections.emptyList(),
                5,
                "省略 {count} 行");

        assertEquals(Arrays.asList("数量", "操作"), result);
    }

    /** 验证模板不含占位符时明确隐藏全部原 Lore。 */
    @Test
    public void missingPlaceholderHidesOriginalLore() {
        List<String> result = TrashContainerMenu.expandCompactLore(
                Arrays.asList("数量", "操作"),
                Arrays.asList("原一", "原二"),
                5,
                "省略 {count} 行");

        assertEquals(Arrays.asList("数量", "操作"), result);
    }

    /** 验证多个独立占位符只展开第一个，其余直接跳过。 */
    @Test
    public void duplicatePlaceholdersExpandOnlyOnce() {
        List<String> result = TrashContainerMenu.expandCompactLore(
                Arrays.asList("{content}", "中间", " {content} "),
                Collections.singletonList("原始"),
                -1,
                "省略 {count} 行");

        assertEquals(Arrays.asList("原始", "中间"), result);
    }

    /** 验证原 Lore 上限为零时只展开省略提示。 */
    @Test
    public void zeroLimitAddsOnlyOmittedLine() {
        List<String> result = TrashContainerMenu.expandCompactLore(
                Collections.singletonList("{content}"),
                Arrays.asList("原一", "原二"),
                0,
                "省略 {count} 行");

        assertEquals(Collections.singletonList("省略 2 行"), result);
    }
}
