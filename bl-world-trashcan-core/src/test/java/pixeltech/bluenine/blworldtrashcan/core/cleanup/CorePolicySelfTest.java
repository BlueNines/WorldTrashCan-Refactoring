package pixeltech.bluenine.blworldtrashcan.core.cleanup;

import pixeltech.bluenine.blworldtrashcan.core.model.EntitySnapshot;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoutingDecision;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

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
                true,
                false,
                true,
                false,
                true,
                true,
                true,
                new HashSet<>(Collections.singletonList("VILLAGER")),
                new HashSet<>(Arrays.asList(
                        "FLAMMPFEIL*", "SADDLED_BLACKLIST", "OWNED_BLACKLIST",
                        "PRIMED_TNT", "ARMOR_STAND"))
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
        assertRoute("legacy name fragment remains substring match", policy.decideItem(
                new ItemSnapshot("DIRT", 1, "special altar item", Collections.<String>emptyList(), null),
                false, false, true), TrashRoute.SKIP);
        assertRoute("legacy lore fragment remains substring match", policy.decideItem(
                new ItemSnapshot("DIRT", 1, "", Arrays.asList("QuickShop shopLocation: world,1,2,3"), null),
                false, false, true), TrashRoute.SKIP);
        CleanupSettings wildcardSettings = new CleanupSettings(
                Collections.<String>emptySet(),
                new HashSet<>(Collections.singletonList("*altar*")),
                new HashSet<>(Collections.singletonList("*shoplocation:*")),
                true,
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        DefaultCleanupPolicy wildcardPolicy = new DefaultCleanupPolicy(wildcardSettings);
        assertRoute("wildcard name", wildcardPolicy.decideItem(
                new ItemSnapshot("DIRT", 1, "special ALTAR item", Collections.<String>emptyList(), null),
                false, false, true), TrashRoute.SKIP);
        assertRoute("wildcard lore", wildcardPolicy.decideItem(
                new ItemSnapshot("DIRT", 1, "", Arrays.asList("QuickShop shopLocation: world,1,2,3"), null),
                false, false, true), TrashRoute.SKIP);
        assertRoute("wildcard mismatch", wildcardPolicy.decideItem(
                new ItemSnapshot("DIRT", 1, "ordinary item", Arrays.asList("ordinary lore"), null),
                false, false, true), TrashRoute.GLOBAL_TRASH);
        assertEntity("monster remove", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "", true, true, false, false)),
                EntityCleanupAction.REMOVE);
        assertEntity("named skip", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "Boss", true, true, false, false)),
                EntityCleanupAction.SKIP);
        assertEntity("boat skip", policy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "", true, true, false, true)),
                EntityCleanupAction.SKIP);
        assertEntity("saddled blacklist skip", policy.decideEntity(
                new EntitySnapshot("SADDLED_BLACKLIST", "Saddled", "", true, false, false,
                        false, true, false)),
                EntityCleanupAction.SKIP);
        assertEntity("owned blacklist skip", policy.decideEntity(
                new EntitySnapshot("OWNED_BLACKLIST", "Owned", "", true, false, false,
                        false, false, true)),
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
        assertEntity("projectile shooter is not tameable owner", policy.decideEntity(
                new EntitySnapshot("ARROW", "Arrow", "", false, false, true,
                        false, false, false)),
                EntityCleanupAction.REMOVE);
        assertEntity("tnt source is not tameable owner", policy.decideEntity(
                new EntitySnapshot("PRIMED_TNT", "Primed TNT", "", false, false, false,
                        false, false, false)),
                EntityCleanupAction.REMOVE);
        assertEntity("owner metadata is not tameable owner", policy.decideEntity(
                new EntitySnapshot("ARMOR_STAND", "Metadata Pet", "", true, false, false,
                        false, false, false)),
                EntityCleanupAction.REMOVE);
        assertRoute("dropped item owner still routes", policy.decideItem(
                new ItemSnapshot("COBBLESTONE", 8, "", Collections.<String>emptyList(),
                        UUID.fromString("00000000-0000-0000-0000-000000000001")),
                false, false, true), TrashRoute.GLOBAL_TRASH);
        CleanupSettings disabledEntitySettings = new CleanupSettings(
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                true,
                true,
                Collections.<String>emptySet(),
                new HashSet<>(Collections.singletonList("ZOMBIE"))
        );
        DefaultCleanupPolicy disabledEntityPolicy = new DefaultCleanupPolicy(disabledEntitySettings);
        assertEntity("entity cleanup disabled", disabledEntityPolicy.decideEntity(
                new EntitySnapshot("ZOMBIE", "Zombie", "", true, true, false, false)),
                EntityCleanupAction.SKIP);
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
