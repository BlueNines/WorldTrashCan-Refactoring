package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 覆盖掉落物堆叠启停生命周期的关键边界。 */
public final class ItemStackingLifecycleTest {
    /** 配置关闭后的排空完成不得重新允许聚集。 */
    @Test
    public void completedDrainKeepsMergingBlocked() {
        ItemStackingLifecycle lifecycle = new ItemStackingLifecycle(false);

        lifecycle.requestDrain();
        lifecycle.completeDrain();

        assertFalse(lifecycle.isDraining());
        assertTrue(lifecycle.isMergeBlocked());
    }

    /** 重启继承的排空状态可在总开关重新开启时恢复。 */
    @Test
    public void persistedDrainCanResumeWhenEnabledAgain() {
        ItemStackingLifecycle lifecycle = new ItemStackingLifecycle(true);

        assertTrue(lifecycle.isDraining());
        assertTrue(lifecycle.resumeMerging());
        assertFalse(lifecycle.isDraining());
        assertFalse(lifecycle.isMergeBlocked());
    }

    /** 第三方冲突被发现后当前实例不得恢复聚集。 */
    @Test
    public void conflictCannotBeOverriddenByReload() {
        ItemStackingLifecycle lifecycle = new ItemStackingLifecycle(false);

        lifecycle.blockForConflict();

        assertFalse(lifecycle.resumeMerging());
        assertTrue(lifecycle.isDraining());
        assertTrue(lifecycle.isMergeBlocked());
        assertTrue(lifecycle.isConflictBlocked());
    }
}
