package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import org.junit.Assert;
import org.junit.Test;
import org.bukkit.event.inventory.ClickType;

/** 验证公共垃圾桶动作前缀和内置变量替换。 */
public final class GlobalTrashActionTextTest {
    /** 验证玩家、世界和页码变量使用一基页码并限制边界。 */
    @Test
    public void replacesBuiltInVariables() {
        String actual = GlobalTrashTextResolver.replaceBuiltIns(
                "{player}|{uuid}|{world}|{page}|{max-page}|{previous-page}|{next-page}",
                "Alice", "uuid-1", "world_nether", 1, 5);
        Assert.assertEquals("Alice|uuid-1|world_nether|2|5|1|3", actual);
    }

    /** 验证 PAPI 风格文本不会被内置变量阶段破坏。 */
    @Test
    public void preservesPapiVariablesBeforePapiStage() {
        String actual = GlobalTrashTextResolver.replaceBuiltIns(
                "%player_level% {player}", "Alice", "uuid-1", "world", 0, 1);
        Assert.assertEquals("%player_level% Alice", actual);
    }

    /** 验证只接受 BLLevelManager 同语义的三种动作前缀。 */
    @Test
    public void validatesSupportedActionPrefixes() {
        Assert.assertTrue(GlobalTrashActionExecutor.isSupported("[console] say ok"));
        Assert.assertTrue(GlobalTrashActionExecutor.isSupported(" [COMMAND] wtc stats"));
        Assert.assertTrue(GlobalTrashActionExecutor.isSupported("[message] &aok"));
        Assert.assertFalse(GlobalTrashActionExecutor.isSupported("[player] wtc stats"));
        Assert.assertFalse(GlobalTrashActionExecutor.isSupported("say unsafe"));
    }

    /** 验证只有普通左右键会触发动作，额外事件类型一律拒绝。 */
    @Test
    public void acceptsOnlyNormalActionClicks() {
        Assert.assertTrue(GlobalTrashService.isNormalActionClick(ClickType.LEFT));
        Assert.assertTrue(GlobalTrashService.isNormalActionClick(ClickType.RIGHT));
        Assert.assertFalse(GlobalTrashService.isNormalActionClick(ClickType.SHIFT_LEFT));
        Assert.assertFalse(GlobalTrashService.isNormalActionClick(ClickType.NUMBER_KEY));
        Assert.assertFalse(GlobalTrashService.isNormalActionClick(ClickType.DOUBLE_CLICK));
        Assert.assertFalse(GlobalTrashService.isNormalActionClick(ClickType.DROP));
    }
}
