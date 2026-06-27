package pixeltech.bluenine.blworldtrashcan.bukkit.feature.entitylimit;

import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 不依赖 JUnit 的低占用实体密度扫描自测。 */
public final class LowOverheadEntityLimitEngineSelfTest {
    /** 执行低占用实体密度扫描自测。 */
    public static void main(String[] args) {
        EntityLimitConfig config = createConfig(true);
        LowOverheadEntityLimitEngine engine = new LowOverheadEntityLimitEngine();
        LowOverheadEntityLimitEngine.ChunkKey key =
                new LowOverheadEntityLimitEngine.ChunkKey("world", 0, 0);
        engine.applySnapshot(new LowOverheadEntityLimitEngine.ChunkSnapshot(key, true, createCowRecords(12)), config);

        List<LowOverheadEntityLimitEngine.RemovalCandidate> candidates =
                engine.pollCandidates(20, config.getScanConfig());
        assertEquals("remove-count should control candidates per dense group", 5, candidates.size());
        assertFalse("disabled gather limit should stop queued removals",
                engine.shouldRemove(candidates.get(0), createConfig(false)));
        assertCrossChunkDenseGroupDoesNotCreateSecondCandidateBatch(config);

        for (int index = 0; index < 2; index++) {
            LowOverheadEntityLimitEngine.RemovalCandidate candidate = candidates.get(index);
            assertTrue("candidate before threshold trim should be removable", engine.shouldRemove(candidate, config));
            engine.finishCandidate(candidate, true, true);
        }

        for (int index = 2; index < candidates.size(); index++) {
            LowOverheadEntityLimitEngine.RemovalCandidate candidate = candidates.get(index);
            assertTrue("candidate after threshold trim should still be removable", engine.shouldRemove(candidate, config));
            engine.finishCandidate(candidate, true, true);
        }

        System.out.println("LowOverheadEntityLimitEngineSelfTest passed");
    }

    /** 断言跨 chunk 的同一密集群不会重复创建第二组候选。 */
    private static void assertCrossChunkDenseGroupDoesNotCreateSecondCandidateBatch(EntityLimitConfig config) {
        LowOverheadEntityLimitEngine engine = new LowOverheadEntityLimitEngine();
        engine.applySnapshot(new LowOverheadEntityLimitEngine.ChunkSnapshot(
                new LowOverheadEntityLimitEngine.ChunkKey("world", 0, 0),
                true,
                createCowRecords("chunk-a-cow-", 0, 14D, 12)
        ), config);
        engine.applySnapshot(new LowOverheadEntityLimitEngine.ChunkSnapshot(
                new LowOverheadEntityLimitEngine.ChunkKey("world", 1, 0),
                true,
                createCowRecords("chunk-b-cow-", 1, 16D, 12)
        ), config);
        List<LowOverheadEntityLimitEngine.RemovalCandidate> candidates =
                engine.pollCandidates(20, config.getScanConfig());
        assertEquals("cross chunk dense group should share one remove-count batch", 5, candidates.size());
    }

    /** 创建测试用实体限制配置。 */
    private static EntityLimitConfig createConfig(boolean gatherEnabled) {
        Map<String, EntityLimitConfig.GatherRule> rules = new HashMap<>();
        rules.put("COW", new EntityLimitConfig.GatherRule(10, 8, 5));
        return new EntityLimitConfig(
                new EntityLimitConfig.WorldLimitConfig(false, Collections.<String>emptySet(),
                        Collections.<String, Integer>emptyMap()),
                new EntityLimitConfig.GatherLimitConfig(gatherEnabled, true, Collections.<String>emptySet(), rules),
                EntityLimitConfig.ScanConfig.defaults()
        );
    }

    /** 创建同一区域内的牛实体快照。 */
    private static List<LowOverheadEntityLimitEngine.EntityRecord> createCowRecords(int count) {
        return createCowRecords("cow-", 0, 0D, count);
    }

    /** 创建指定 chunk 附近的牛实体快照。 */
    private static List<LowOverheadEntityLimitEngine.EntityRecord> createCowRecords(String prefix, int chunkX,
                                                                                   double startX, int count) {
        List<LowOverheadEntityLimitEngine.EntityRecord> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            records.add(new LowOverheadEntityLimitEngine.EntityRecord(
                    UUID.nameUUIDFromBytes((prefix + index).getBytes(StandardCharsets.UTF_8)),
                    "world",
                    "COW",
                    chunkX,
                    0,
                    startX + index * 0.2D,
                    64D,
                    0D
            ));
        }
        return records;
    }

    /** 断言布尔值为 true。 */
    private static void assertTrue(String name, boolean actual) {
        if (!actual) {
            throw new IllegalStateException(name + " expected true but got false");
        }
    }

    /** 断言布尔值为 false。 */
    private static void assertFalse(String name, boolean actual) {
        if (actual) {
            throw new IllegalStateException(name + " expected false but got true");
        }
    }

    /** 断言整数相等。 */
    private static void assertEquals(String name, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException(name + " expected " + expected + " but got " + actual);
        }
    }

    /** 阻止实例化测试类。 */
    private LowOverheadEntityLimitEngineSelfTest() {
    }
}
