package pixeltech.bluenine.blworldtrashcan.config;

import org.junit.Test;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.CustomItemRoutingSettings;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.DefaultCleanupPolicy;
import pixeltech.bluenine.blworldtrashcan.core.cleanup.ItemMatchRules;
import pixeltech.bluenine.blworldtrashcan.core.model.ItemSnapshot;
import pixeltech.bluenine.blworldtrashcan.core.trash.RejectedCleanupAction;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/** 验证自定义物品路由、公共桶白名单与新旧配置兼容。 */
public final class CustomItemRoutingConfigTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 验证旧配置缺少新节点时全部保持关闭。 */
    @Test
    public void newFeaturesDefaultToDisabled() {
        ConfigBundle bundle = load(new MapConfigurationSource(), new MapConfigurationSource());

        assertFalse(bundle.getCleanupConfig().getSettings().getCustomItemRouting().isEnabled());
        assertFalse(bundle.getTrashConfig().getGlobalTrash().getAdmissionWhitelist().isEnabled());
    }

    /** 验证三个 ignored 列表迁移后仍会与旧顶层节点合并去重。 */
    @Test
    public void ignoredRulesMergeCurrentAndLegacyPaths() {
        MapConfigurationSource cleanup = new MapConfigurationSource();
        cleanup.put("custom-data-items.ignored-materials", Arrays.asList("DIAMOND", "EMERALD"));
        cleanup.put("ignored-materials", Arrays.asList("DIAMOND", "GOLD_INGOT"));
        cleanup.put("custom-data-items.ignored-name-fragments", Collections.singletonList("*protected*"));
        cleanup.put("ignored-name-fragments", Collections.singletonList("legacy-name"));
        cleanup.put("custom-data-items.ignored-lore-fragments", Collections.singletonList("*secure-lore*"));
        cleanup.put("ignored-lore-fragments", Collections.singletonList("legacy-lore"));

        CleanupConfig config = load(cleanup, new MapConfigurationSource()).getCleanupConfig();
        CleanupSettings settings = config.getSettings();

        assertTrue(config.isLegacyItemProtectionConfigured());
        assertTrue(settings.isIgnoredMaterial("DIAMOND"));
        assertTrue(settings.isIgnoredMaterial("EMERALD"));
        assertTrue(settings.isIgnoredMaterial("GOLD_INGOT"));
        assertTrue(settings.matchesIgnoredName("a protected item"));
        assertTrue(settings.matchesIgnoredName("contains legacy-name marker"));
        assertTrue(settings.matchesIgnoredLore(Collections.singletonList("secure-lore marker")));
        assertTrue(settings.matchesIgnoredLore(Collections.singletonList("contains legacy-lore marker")));
    }

    /** 验证正式 name/lore key 节点和短期旧别名会合并为一份规则。 */
    @Test
    public void routingMergesNameAndLoreAliases() {
        MapConfigurationSource cleanup = new MapConfigurationSource();
        cleanup.put("custom-data-items.routing.enabled", true);
        cleanup.put("custom-data-items.routing.detection.name-key-patterns",
                Collections.singletonList("current-name"));
        cleanup.put("custom-data-items.routing.detection.name-patterns",
                Collections.singletonList("legacy-name"));
        cleanup.put("custom-data-items.routing.detection.lore-key-patterns",
                Collections.singletonList("current-lore"));
        cleanup.put("custom-data-items.routing.detection.lore-patterns",
                Collections.singletonList("legacy-lore"));

        ItemMatchRules rules = load(cleanup, new MapConfigurationSource()).getCleanupConfig()
                .getSettings().getCustomItemRouting().getRules();

        assertTrue(rules.matchesVisible(item("contains current-name", "")));
        assertTrue(rules.matchesVisible(item("contains legacy-name", "")));
        assertTrue(rules.matchesVisible(item("", "contains current-lore")));
        assertTrue(rules.matchesVisible(item("", "contains legacy-lore")));
    }

    /** 验证五类规则是 OR，名称和 Lore 为包含语义，其余普通文本为精确语义。 */
    @Test
    public void fiveRuleTypesUseDocumentedMatchingSemantics() {
        ItemMatchRules rules = new ItemMatchRules(
                set("DIAMOND", "*_SWORD"), set("relic"), set("bound item"),
                set("plugin:custom"), set("tag.CustomData"));

        assertTrue(rules.matchesVisible(new ItemSnapshot("DIAMOND", 1, "", noLore(), null)));
        assertTrue(rules.matchesVisible(new ItemSnapshot("NETHERITE_SWORD", 1, "", noLore(), null)));
        assertFalse(rules.matchesVisible(new ItemSnapshot("DIAMOND_BLOCK", 1, "", noLore(), null)));
        assertTrue(rules.matchesVisible(item("Ancient Relic Blade", "")));
        assertTrue(rules.matchesVisible(item("", "Soul-bound item marker")));
        assertTrue(rules.matchesPdcKeys(set("plugin:custom")));
        assertFalse(rules.matchesPdcKeys(set("plugin:custom_extra")));
        assertTrue(rules.matchesNbtKeys(set("tag.CustomData")));
        assertFalse(rules.matchesNbtKeys(set("tag.CustomData.child")));
    }

    /** 验证公共桶白名单读取五类节点和拒绝动作。 */
    @Test
    public void globalAdmissionWhitelistLoadsAllSettings() {
        MapConfigurationSource trash = new MapConfigurationSource();
        trash.put("global-trash.admission-whitelist.enabled", true);
        trash.put("global-trash.admission-whitelist.material-patterns", Collections.singletonList("*_INGOT"));
        trash.put("global-trash.admission-whitelist.name-key-patterns", Collections.singletonList("allowed-name"));
        trash.put("global-trash.admission-whitelist.lore-key-patterns", Collections.singletonList("allowed-lore"));
        trash.put("global-trash.admission-whitelist.pdc-key-patterns", Collections.singletonList("allowed:pdc"));
        trash.put("global-trash.admission-whitelist.nbt-key-patterns", Collections.singletonList("tag.allowed"));
        trash.put("global-trash.admission-whitelist.rejected-cleanup-action", "direct-remove");

        TrashConfig.GlobalTrashAdmissionWhitelistConfig whitelist = load(
                new MapConfigurationSource(), trash).getTrashConfig().getGlobalTrash().getAdmissionWhitelist();

        assertTrue(whitelist.isEnabled());
        assertEquals(RejectedCleanupAction.DIRECT_REMOVE, whitelist.getRejectedCleanupAction());
        assertTrue(whitelist.getRules().matchesVisible(
                new ItemSnapshot("GOLD_INGOT", 1, "", noLore(), null)));
        assertTrue(whitelist.getRules().matchesVisible(item("contains allowed-name", "")));
        assertTrue(whitelist.getRules().matchesVisible(item("", "contains allowed-lore")));
        assertTrue(whitelist.getRules().matchesPdcKeys(set("allowed:pdc")));
        assertTrue(whitelist.getRules().matchesNbtKeys(set("tag.allowed")));
    }

    /** 验证绝对保护和强制直删世界高于自定义路由。 */
    @Test
    public void routingPriorityProtectsIgnoredItemsBeforeDirectWorlds() {
        CleanupSettings settings = settings(CustomItemRoutingSettings.Mode.DIRECT_REMOVE,
                CustomItemRoutingSettings.UnavailableAction.DIRECT_REMOVE,
                set("DIAMOND"));
        DefaultCleanupPolicy policy = new DefaultCleanupPolicy(settings);
        ItemSnapshot ignored = routedItem("DIAMOND", OWNER_ID, null);
        ItemSnapshot normal = routedItem("STONE", OWNER_ID, null);

        assertEquals(TrashRoute.SKIP,
                policy.decideItem(ignored, true, true, true, true).getRoute());
        assertEquals(TrashRoute.REMOVE,
                policy.decideItem(normal, true, true, true, true).getRoute());
    }

    /** 验证 personal-only 对已知物主、未知物主和不可用动作的处理。 */
    @Test
    public void personalOnlyRequiresKnownOwnerAndAvailableTrash() {
        DefaultCleanupPolicy keepPolicy = new DefaultCleanupPolicy(settings(
                CustomItemRoutingSettings.Mode.PERSONAL_ONLY,
                CustomItemRoutingSettings.UnavailableAction.KEEP_GROUND,
                Collections.<String>emptySet()));

        assertEquals(TrashRoute.PERSONAL_TRASH, keepPolicy.decideItem(
                routedItem("STONE", OWNER_ID, null), false, true, true).getRoute());
        assertEquals(TrashRoute.SKIP, keepPolicy.decideItem(
                routedItem("STONE", null, null), false, true, true).getRoute());
        assertEquals(TrashRoute.SKIP, keepPolicy.decideItem(
                routedItem("STONE", OWNER_ID, null), false, false, true).getRoute());

        DefaultCleanupPolicy removePolicy = new DefaultCleanupPolicy(settings(
                CustomItemRoutingSettings.Mode.PERSONAL_ONLY,
                CustomItemRoutingSettings.UnavailableAction.DIRECT_REMOVE,
                Collections.<String>emptySet()));
        assertEquals(TrashRoute.REMOVE, removePolicy.decideItem(
                routedItem("STONE", null, null), false, false, true).getRoute());
    }

    /** 验证公共桶白名单拒绝后的保留与直删动作。 */
    @Test
    public void admissionRejectionAppliesAfterExistingTrashRoutes() {
        DefaultCleanupPolicy policy = new DefaultCleanupPolicy(settings(
                CustomItemRoutingSettings.Mode.PERSONAL_ONLY,
                CustomItemRoutingSettings.UnavailableAction.KEEP_GROUND,
                Collections.<String>emptySet(), false));

        assertEquals(TrashRoute.WORLD_TRASH, policy.decideItem(
                plainItem(RejectedCleanupAction.KEEP_GROUND), true, false, false).getRoute());
        assertEquals(TrashRoute.SKIP, policy.decideItem(
                plainItem(RejectedCleanupAction.KEEP_GROUND), false, false, false).getRoute());
        assertEquals(TrashRoute.REMOVE, policy.decideItem(
                plainItem(RejectedCleanupAction.DIRECT_REMOVE), false, false, false).getRoute());
    }

    /** 创建默认启用自定义路由的核心设置。 */
    private CleanupSettings settings(CustomItemRoutingSettings.Mode mode,
                                     CustomItemRoutingSettings.UnavailableAction unavailable,
                                     Set<String> ignoredMaterials) {
        return settings(mode, unavailable, ignoredMaterials, true);
    }

    /** 创建可控制自定义路由开关的核心设置。 */
    private CleanupSettings settings(CustomItemRoutingSettings.Mode mode,
                                     CustomItemRoutingSettings.UnavailableAction unavailable,
                                     Set<String> ignoredMaterials, boolean routingEnabled) {
        return new CleanupSettings(ignoredMaterials, Collections.<String>emptySet(),
                Collections.<String>emptySet(), true, true, true, false, true,
                false, false, true, true, Collections.<String>emptySet(),
                Collections.<String>emptySet(), new CustomItemRoutingSettings(
                routingEnabled, ItemMatchRules.empty(), mode, unavailable));
    }

    /** 创建已命中新路由规则的物品快照。 */
    private ItemSnapshot routedItem(String material, UUID owner, RejectedCleanupAction rejectedAction) {
        return new ItemSnapshot(material, 1, "", noLore(), owner, true, rejectedAction);
    }

    /** 创建未命中新路由、仅携带公共桶拒绝动作的物品快照。 */
    private ItemSnapshot plainItem(RejectedCleanupAction rejectedAction) {
        return new ItemSnapshot("STONE", 1, "", noLore(), null, false, rejectedAction);
    }

    /** 创建包含指定显示名和单行 Lore 的物品快照。 */
    private ItemSnapshot item(String name, String lore) {
        List<String> lines = lore == null || lore.isEmpty() ? noLore() : Collections.singletonList(lore);
        return new ItemSnapshot("STONE", 1, name, lines, null);
    }

    /** 返回空 Lore。 */
    private List<String> noLore() {
        return Collections.emptyList();
    }

    /** 创建字符串集合。 */
    private Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /** 使用指定 cleanup.yml 和 trash.yml 数据加载完整配置。 */
    private ConfigBundle load(ConfigurationSource cleanup, ConfigurationSource trash) {
        ConfigurationSource empty = new MapConfigurationSource();
        return new ConfigBundleLoader().load(empty, cleanup, trash, empty, empty);
    }

    /** 基于 Map 的最小配置来源。 */
    private static final class MapConfigurationSource implements ConfigurationSource {
        private final Map<String, Object> values = new HashMap<>();

        /** 写入测试配置。 */
        private void put(String path, Object value) {
            values.put(path, value);
        }

        /** 判断路径是否存在。 */
        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }

        /** 读取字符串。 */
        @Override
        public String getString(String path, String fallback) {
            Object value = values.get(path);
            return value == null ? fallback : String.valueOf(value);
        }

        /** 读取布尔值。 */
        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object value = values.get(path);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
        }

        /** 读取整数。 */
        @Override
        public int getInt(String path, int fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        /** 读取小数。 */
        @Override
        public double getDouble(String path, double fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        /** 读取字符串列表。 */
        @Override
        public List<String> getStringList(String path) {
            Object value = values.get(path);
            if (!(value instanceof List<?>)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                result.add(String.valueOf(entry));
            }
            return result;
        }

        /** 读取映射列表。 */
        @Override
        public List<Map<?, ?>> getMapList(String path) {
            return Collections.emptyList();
        }
    }
}
