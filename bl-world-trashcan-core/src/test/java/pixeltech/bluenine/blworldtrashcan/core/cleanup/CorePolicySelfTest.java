package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** 不依赖 JUnit 的核心策略自测，方便在没有 Maven 的环境中直接运行。 */
public final class CorePolicySelfTest {
    /** 执行核心策略自测。 */
    public static void main(String[] args) {
        CleanupSettings settings = new CleanupSettings(
                new HashSet<>(Collections.singletonList("DIAMOND")),
                new HashSet<>(Collections.singletonList("ALTAR")),
                new HashSet<>(Collections.singletonList("shopLocation")),
                true,
                true,
                false,
                true,
                false,
                true,
                new HashSet<>(Collections.singletonList("VILLAGER")),
                new HashSet<>(Collections.singletonList("FLAMMPFEIL*"))
        );
        DefaultCleanupPolicy policy = new DefaultCleanupPolicy(settings);
        assertRoute("ignored material", policy.decideItem(
                new ItemSnapshot("DIAMOND", 1, "", Collections.<String>emptyList(), null),
                true, true, true), TrashRoute.SKIP);
        assertRoute("world trash first", policy.decideItem(
                new ItemSnapshot("DIRT", 32, "", Collections.<String>emptyList(), null),
                true, true, true), TrashRoute.WORLD_TRASH);
        assertRoute("ignored lore", policy.decideItem(
                new ItemSnapshot("DIRT", 1, "", Arrays.asList("shopLocation: world,1,2,3"), null),
                true, true, true), TrashRoute.SKIP);
        assertEntity("monster remove", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "", true, true, false, false)),
                EntityCleanupAction.REMOVE);
        assertEntity("named skip", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "Boss", true, true, false, false)),
                EntityCleanupAction.SKIP);
        assertEntity("boat skip", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "", true, true, false, true)),
                EntityCleanupAction.SKIP);
        assertEntity("whitelist skip", policy.decideEntity(
                new EntitySnapshot("VILLAGER", "Villager", "", true, false, false, false)),
                EntityCleanupAction.SKIP);
        assertEntity("blacklist remove", policy.decideEntity(
                new EntitySnapshot("FLAMMPFEIL.SLASHBLADE_BLADESTAND", "BladeStand", "", false, false, false, false)),
                EntityCleanupAction.REMOVE);
        assertEntity("experience orb remove", policy.decideEntity(
                new EntitySnapshot("EXPERIENCE_ORB", "Experience Orb", "", false, false, false, false)),
                EntityCleanupAction.REMOVE);
        System.out.println("CorePolicySelfTest passed");
    }

    /** 断言物品路由结果。 */
    private static void assertRoute(String name, TrashRoutingDecision decision, TrashRoute expected) {
        if (decision.getRoute() != expected) {
            throw new IllegalStateException(name + " expected " + expected + " but got " + decision.getRoute());
        }
    }

    /** 断言实体清理结果。 */
    private static void assertEntity(String name, EntityCleanupDecision decision, EntityCleanupAction expected) {
        if (decision.getAction() != expected) {
            throw new IllegalStateException(name + " expected " + expected + " but got " + decision.getAction());
        }
    }

    /** 阻止实例化测试类。 */
    private CorePolicySelfTest() {
    }
}
