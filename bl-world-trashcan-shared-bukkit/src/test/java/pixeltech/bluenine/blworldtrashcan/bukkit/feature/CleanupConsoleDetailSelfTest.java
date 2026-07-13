package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;
import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.List;

/** 不依赖 JUnit 的控制台清理明细自测。 */
public final class CleanupConsoleDetailSelfTest {
    /** 执行控制台清理明细自测。 */
    public static void main(String[] args) {
        CleanupFeature.CleanupStats stats = new CleanupFeature.CleanupStats();
        addEntities(stats, 3, "ARMOR_STAND", "Armor Stand", "&c神话最强怪");
        addEntities(stats, 2, "ARMOR_STAND", "Armor Stand", "&6神话最强怪");
        addEntities(stats, 4, "SHEEP", "Sheep", "");
        addEntities(stats, 1, "PIG", " \n ", "&c");
        stats.addItemsRouted(64, TrashRoute.GLOBAL_TRASH);
        stats.addItemsRemoved(33);

        CleanupFeature.CleanupStats.EntityRemovalSummary summary = stats.snapshotEntityRemovalSummary(2);
        assertEquals("total entities", 10, summary.getTotalEntities());
        assertEquals("actual item amount", 97, summary.getTotalItems());
        assertEquals("shown entries", 2, summary.getEntries().size());
        assertEntry("custom name priority", summary.getEntries().get(0), "神话最强怪", "armor_stand", 5);
        assertEntry("getName fallback", summary.getEntries().get(1), "Sheep", "sheep", 4);
        assertEquals("others", 1, summary.getOthers());

        NotifyConfig.ConsoleConfig config = new NotifyConfig.ConsoleConfig(
                true, true, 2,
                "{name}_{type}: {count}", "items: {count}", "others: {count}");
        List<String> lines = CleanupConsoleDetailFormatter.format(config, stats, false);
        assertContains("summary line", lines, "entities=10, items=97, groups=3, shown=2, partial=false");
        assertContains("custom entity line", lines, "神话最强怪_armor_stand: 5");
        assertContains("fallback entity line", lines, "Sheep_sheep: 4");
        assertContains("others line", lines, "others: 1");
        assertContains("items line", lines, "items: 97");

        NotifyConfig.ConsoleConfig limited = new NotifyConfig.ConsoleConfig(
                true, true, 999,
                "{name}_{type}: {count}", "items: {count}", "others: {count}");
        assertEquals("max entries hard limit", 100, limited.getMaxEntries());
        System.out.println("CleanupConsoleDetailSelfTest passed");
    }

    /** 向统计中加入指定数量的实体快照。 */
    private static void addEntities(CleanupFeature.CleanupStats stats, int amount,
                                    String type, String name, String customName) {
        for (int index = 0; index < amount; index++) {
            stats.addEntitiesRemoved(new EntitySnapshot(type, name, customName,
                    true, false, false, false));
        }
    }

    /** 断言单个实体明细内容。 */
    private static void assertEntry(String label, CleanupFeature.CleanupStats.EntityRemovalEntry entry,
                                    String name, String type, int count) {
        if (!name.equals(entry.getName()) || !type.equals(entry.getType()) || count != entry.getCount()) {
            throw new IllegalStateException(label + " expected " + name + "_" + type + ": " + count
                    + " but got " + entry.getName() + "_" + entry.getType() + ": " + entry.getCount());
        }
    }

    /** 断言整数相等。 */
    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException(label + " expected " + expected + " but got " + actual);
        }
    }

    /** 断言列表包含指定文本。 */
    private static void assertContains(String label, List<String> values, String expected) {
        if (!values.contains(expected)) {
            throw new IllegalStateException(label + " missing " + expected + " in " + values);
        }
    }

    /** 阻止实例化测试类。 */
    private CleanupConsoleDetailSelfTest() {
    }
}
