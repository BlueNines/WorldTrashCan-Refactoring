package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 覆盖立即扫描与延迟生成请求合并时的补排边界。 */
public final class DirtyChunkScheduleTest {
    /** 立即请求先执行后必须保留尚未成熟的生成请求。 */
    @Test
    public void immediateScanKeepsDelayedFollowUp() {
        AbstractModernItemStackingFeature.DirtyChunk immediate =
                new AbstractModernItemStackingFeature.DirtyChunk(1000L, 31000L);
        AbstractModernItemStackingFeature.DirtyChunk delayed =
                new AbstractModernItemStackingFeature.DirtyChunk(1500L, 31500L);

        AbstractModernItemStackingFeature.DirtyChunk merged = immediate.merge(delayed);

        assertEquals(1000L, merged.getReadyAtMillis());
        assertEquals(1500L, merged.getLatestReadyAtMillis());
        assertTrue(merged.needsFollowUp(1000L));
        assertEquals(1500L, merged.followUp().getReadyAtMillis());
        assertFalse(merged.followUp().needsFollowUp(1500L));
    }

    /** 多次延迟请求只保留最晚一次补排时间，不扩大条目数量。 */
    @Test
    public void burstRequestsKeepLatestFollowUpTime() {
        AbstractModernItemStackingFeature.DirtyChunk merged =
                new AbstractModernItemStackingFeature.DirtyChunk(1000L, 31000L)
                        .merge(new AbstractModernItemStackingFeature.DirtyChunk(1400L, 31400L))
                        .merge(new AbstractModernItemStackingFeature.DirtyChunk(1300L, 31300L));

        assertEquals(1000L, merged.getReadyAtMillis());
        assertEquals(1400L, merged.getLatestReadyAtMillis());
        assertTrue(merged.needsFollowUp(1200L));
    }
}
