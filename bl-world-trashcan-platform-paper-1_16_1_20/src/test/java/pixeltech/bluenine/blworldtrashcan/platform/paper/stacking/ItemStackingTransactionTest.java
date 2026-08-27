package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import java.util.logging.Logger;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pixeltech.bluenine.blworldtrashcan.config.ItemStackingConfig;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityPickupItemEvent;

/** 验证逻辑数量写入、合并和库存插入的事务边界。 */
public final class ItemStackingTransactionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 安装只覆盖 ItemStack 相似比较所需方法的 Bukkit 服务代理。 */
    @BeforeClass
    public static void installBukkitItemFactory() {
        Bukkit.setServer(serverProxy());
    }

    /** 剩余数量写入回读失败时必须恢复地面实体原数量。 */
    @Test
    public void remainingWriteFailureRestoresOriginalAmount() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);
        item.failNextLogicalWrite(70, 60);

        assertFalse(feature.setRemaining(item.proxy(), 80, 70));
        assertEquals(80, feature.getAmount(item.proxy()));
        assertEquals(64, item.stackAmount());
    }

    /** 实体移除失败时不得把地面数量视为已经扣除。 */
    @Test
    public void removalFailureKeepsOriginalAmount() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);
        item.setRemovalSucceeds(false);

        assertFalse(feature.setRemaining(item.proxy(), 80, 0));
        assertEquals(80, feature.getAmount(item.proxy()));
        assertTrue(item.isValid());
    }

    /** 实体成功移除时应完成全部数量扣减。 */
    @Test
    public void successfulRemovalConsumesEntireLogicalStack() throws Exception {
        TestFeature feature = feature(100);
        FakeItem item = new FakeItem(80);

        assertTrue(feature.setRemaining(item.proxy(), 80, 0));
        assertFalse(item.isValid());
    }

    /** 达到逻辑上限时只转移可容纳数量，其余保留在来源实体。 */
    @Test
    public void partialMergeStopsAtConfiguredLimit() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(100, feature.getAmount(target.proxy()));
        assertEquals(10, feature.getAmount(source.proxy()));
        assertTrue(source.isValid());
    }

    /** 单 Material 独立上限必须优先于全局上限。 */
    @Test
    public void perMaterialLimitOverridesGlobalLimit() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  max-stack-size: 90\n");
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(90, feature.getAmount(target.proxy()));
        assertEquals(20, feature.getAmount(source.proxy()));
    }

    /** 禁用 Material 的普通原版合并事件不得被插件取消。 */
    @Test
    public void disabledMaterialLeavesVanillaMergeUntouched() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  enabled: false\n");
        ItemMergeEvent event = new ItemMergeEvent(new FakeItem(10).proxy(), new FakeItem(20).proxy());

        feature.onItemMerge(event);

        assertFalse(event.isCancelled());
    }

    /** 禁用前已经存在的逻辑实体仍需阻止原版误合并，等待预算化排空。 */
    @Test
    public void disabledMaterialProtectsExistingLogicalAmountUntilDrain() throws Exception {
        TestFeature feature = feature(1000, "COBBLESTONE:\n  enabled: false\n");
        ItemMergeEvent event = new ItemMergeEvent(new FakeItem(80).proxy(), new FakeItem(20).proxy());

        feature.onItemMerge(event);

        assertTrue(event.isCancelled());
    }

    /** 目标写入异常值时必须同时保留目标和来源的原始数量。 */
    @Test
    public void targetWriteFailureRollsBackWithoutDuplication() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(30);
        target.failNextLogicalWrite(100, 90);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(30, feature.getAmount(source.proxy()));
    }

    /** 来源部分扣减失败时必须回滚目标和来源。 */
    @Test
    public void sourceWriteFailureRollsBackBothEntities() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(100);
        source.failNextLogicalWrite(80, 75);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(100, feature.getAmount(source.proxy()));
    }

    /** 来源移除失败时必须回滚已经增加的目标数量。 */
    @Test
    public void sourceRemovalFailureRollsBackTarget() throws Exception {
        TestFeature feature = feature(100);
        FakeItem target = new FakeItem(80);
        FakeItem source = new FakeItem(20);
        source.setRemovalSucceeds(false);

        invokeMerge(feature, target.proxy(), source.proxy());

        assertEquals(80, feature.getAmount(target.proxy()));
        assertEquals(20, feature.getAmount(source.proxy()));
    }

    /** 空库存必须按原版上限把逻辑数量拆入多个槽位。 */
    @Test
    public void emptyInventoryAcceptsLogicalAmountAcrossSlots() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(80, acceptedAmount(receipt));
        assertEquals(64, inventory.amountAt(0));
        assertEquals(16, inventory.amountAt(1));
    }

    /** 只有一个空槽时最多接收一组，并保留其余地面数量。 */
    @Test
    public void partialInventoryAcceptsOnlyAvailableCapacity() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.fill(new ItemStack(Material.WOODEN_SWORD, 1));
        inventory.set(0, null);

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(64, acceptedAmount(receipt));
        assertEquals(64, inventory.amountAt(0));
    }

    /** 满库存不得接收任何数量。 */
    @Test
    public void fullInventoryAcceptsNothing() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.fill(new ItemStack(Material.WOODEN_SWORD, 1));

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 80);

        assertEquals(0, acceptedAmount(receipt));
    }

    /** 库存插入收据必须能精确恢复所有修改过的槽位。 */
    @Test
    public void inventoryReceiptRollsBackEveryChangedSlot() throws Exception {
        TestFeature feature = feature(100);
        FakeInventory inventory = new FakeInventory(36);
        inventory.set(0, new ItemStack(Material.COBBLESTONE, 60));

        Object receipt = invokeInventoryInsert(feature, inventory.proxy(), new ItemStack(Material.COBBLESTONE, 64), 20);
        invokeNoArg(receipt, "rollback");

        assertEquals(60, inventory.amountAt(0));
        assertNull(inventory.itemAt(1));
    }

    /** 非玩家拾取逻辑堆叠时只交给原版一组，并保留剩余逻辑数量。 */
    @Test
    public void nonPlayerPickupPreservesLogicalRemainder() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);

        assertFalse(event.isCancelled());
        assertEquals(64, item.stackAmount());
        assertEquals(64, feature.getAmount(item.proxy()));
        assertEquals(1, world.spawnedItems().size());
        FakeItem remainder = world.spawnedItems().get(0);
        assertEquals(64, remainder.stackAmount());
        assertEquals(960, feature.getAmount(remainder.proxy()));

        feature.runPendingPickupTask();

        assertFalse(item.isValid());
        assertEquals(960, feature.getAmount(remainder.proxy()));
    }

    /** 非玩家拾取事件随后被其它插件取消时必须恢复原逻辑实体。 */
    @Test
    public void cancelledNonPlayerPickupRollsBackPreparation() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        event.setCancelled(true);
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertEquals(1024, feature.getAmount(item.proxy()));
        assertEquals(64, item.stackAmount());
        assertFalse(remainder.isValid());
    }

    /** 未被取消的非玩家拾取不得在监视阶段重复处理或删除逻辑余量。 */
    @Test
    public void acceptedNonPlayerPickupKeepsPreparationRemainder() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertFalse(item.isValid());
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertTrue(remainder.isValid());
    }

    /** 金锭原版拾取只消耗一枚时，逻辑总量必须准确减少一枚。 */
    @Test
    public void acceptedGoldPickupPreservesVanillaRemainingAmount() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);

        // 模拟 Paper 在事件收尾后的 Piglin.take(1) 和 ItemStack.setItem(63)。
        item.setPhysicalAmount(63);
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertFalse(event.isCancelled());
        assertEquals(63, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1023, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
    }

    /** 原版一次移除整个物理堆时，逻辑余量实体必须单独保留。 */
    @Test
    public void acceptedFullPhysicalPickupKeepsLogicalRemainder() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_NUGGET, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);

        // 模拟原版完整消费源实体。
        item.proxy().remove();
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertFalse(event.isCancelled());
        assertFalse(item.isValid());
        assertEquals(960, feature.getAmount(remainder.proxy()));
    }

    /** 连续原版拾取不能重复使用逻辑数量，每次都只从当前物理源消费。 */
    @Test
    public void consecutiveVanillaPickupsRemainLossless() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);

        EntityPickupItemEvent first = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);
        feature.onEntityPickup(first);
        FakeItem remainder = world.spawnedItems().get(0);
        item.setPhysicalAmount(63);
        feature.onEntityPickupMonitor(first);
        feature.runPendingPickupTask();

        EntityPickupItemEvent second = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 62);
        feature.onEntityPickup(second);
        feature.onEntityPickupMonitor(second);
        item.setPhysicalAmount(62);

        assertFalse(second.isCancelled());
        assertEquals(62, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1022, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
    }

    /** 原版收尾前再次触发同一掉落实体的拾取必须取消，避免重复发放奖励。 */
    @Test
    public void pickupIsBlockedUntilReconciliation() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent first = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);
        feature.onEntityPickup(first);

        EntityPickupItemEvent second = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);
        feature.onEntityPickup(second);

        assertTrue(second.isCancelled());
        assertEquals(1, world.spawnedItems().size());

        item.setPhysicalAmount(63);
        feature.onEntityPickupMonitor(first);
        feature.runPendingPickupTask();
    }

    /** 后续被拦截的重复事件不得覆盖首个拾取事务的成功结果。 */
    @Test
    public void duplicatePickupEventCannotCancelOriginalPreparation() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent first = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(first);
        FakeItem remainder = world.spawnedItems().get(0);

        EntityPickupItemEvent duplicate = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);
        feature.onEntityPickup(duplicate);
        assertTrue(duplicate.isCancelled());

        // 先收到重复事件的 MONITOR，再收到首个事件的 MONITOR，模拟插件链路的异常顺序。
        feature.onEntityPickupMonitor(duplicate);
        item.setPhysicalAmount(63);
        feature.onEntityPickupMonitor(first);
        feature.runPendingPickupTask();

        assertTrue(item.isValid());
        assertEquals(63, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1023, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
    }

    /** 拾取收尾调度失败时必须回滚拆分，不能丢失或复制数量。 */
    @Test
    public void failedPickupSchedulingRollsBackPreparation() throws Exception {
        TestFeature feature = feature(1024);
        feature.pickupSchedulingSucceeds = false;
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);

        assertTrue(event.isCancelled());
        assertEquals(1024, feature.getAmount(item.proxy()));
        assertEquals(64, item.stackAmount());
        assertEquals(1, world.spawnedItems().size());
        assertFalse(world.spawnedItems().get(0).isValid());
    }

    /** Folia 实体调度器触发 retired 回调时也必须完成同一笔收尾事务。 */
    @Test
    public void retiredPickupCallbackReconcilesPreparation() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        feature.runPendingPickupRetiredTask();

        assertFalse(item.isValid());
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertTrue(remainder.isValid());
    }

    /** 插件在事件尚未完成派发时关闭，必须保留物理源和逻辑余量的无损状态。 */
    @Test
    public void clearingUnobservedPickupKeepsLosslessPreparation() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("clearPendingPickups");
        method.setAccessible(true);
        method.invoke(feature);

        assertEquals(64, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1024, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
        assertEquals(64, item.stackAmount());
        assertTrue(remainder.isValid());
    }

    /** 插件关闭时已接受的非玩家拾取只能按真实物理变化收尾，不能回滚已发放奖励。 */
    @Test
    public void clearingAcceptedPickupKeepsConsumedAmount() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        item.setPhysicalAmount(63);
        feature.onEntityPickupMonitor(event);

        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("clearPendingPickups");
        method.setAccessible(true);
        method.invoke(feature);

        assertEquals(63, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1023, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
        assertTrue(item.isValid());
        assertTrue(remainder.isValid());
    }

    /** 关闭时 MONITOR 已执行但原版尚未扣减，不能提前按事件剩余量扣除一次。 */
    @Test
    public void clearingObservedButNotAppliedPickupDoesNotDoubleConsume() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        // MONITOR 已经看到事件，但模拟原版仍未真正修改源 ItemStack。
        feature.onEntityPickupMonitor(event);

        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("clearPendingPickups");
        method.setAccessible(true);
        method.invoke(feature);

        assertEquals(64, item.stackAmount());
        assertEquals(64, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
        assertEquals(1024, feature.getAmount(item.proxy()) + feature.getAmount(remainder.proxy()));
        assertTrue(item.isValid());
        assertTrue(remainder.isValid());
    }

    /** 插件关闭时已取消的非玩家拾取必须完整撤销临时拆分。 */
    @Test
    public void clearingCancelledPickupRestoresOriginalAmount() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        event.setCancelled(true);

        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("clearPendingPickups");
        method.setAccessible(true);
        method.invoke(feature);

        assertEquals(1024, feature.getAmount(item.proxy()));
        assertEquals(64, item.stackAmount());
        assertFalse(remainder.isValid());
    }

    /** 收尾时物理数量异常增加不得被事务覆盖或误算成数量扣减。 */
    @Test
    public void increasedPhysicalAmountIsPreserved() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        FakeItem remainder = world.spawnedItems().get(0);
        item.setPhysicalAmount(65);
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertTrue(item.isValid());
        assertEquals(65, item.stackAmount());
        assertEquals(65, feature.getAmount(item.proxy()));
        assertEquals(960, feature.getAmount(remainder.proxy()));
    }

    /** 原版没有及时回写 Bukkit 数量时，收尾任务必须按事件结果校正物理数量。 */
    @Test
    public void reconciliationCorrectsStalePhysicalAmount() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 63);

        feature.onEntityPickup(event);
        feature.onEntityPickupMonitor(event);
        feature.runPendingPickupTask();

        assertEquals(63, item.stackAmount());
        assertEquals(63, feature.getAmount(item.proxy()));
    }

    /** 事件提供非法剩余数量时必须拒绝交给原版，避免形成不可守恒事务。 */
    @Test
    public void invalidVanillaRemainingCancelsWithoutSplitting() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 65);

        feature.onEntityPickup(event);

        assertTrue(event.isCancelled());
        assertTrue(world.spawnedItems().isEmpty());
        assertEquals(1024, feature.getAmount(item.proxy()));
    }

    /** 非玩家拾取拆分失败时必须取消事件并保留原逻辑数量。 */
    @Test
    public void nonPlayerPickupFailureKeepsOriginalLogicalStack() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        world.failSpawn = true;
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 1024);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);

        assertTrue(event.isCancelled());
        assertEquals(64, item.stackAmount());
        assertEquals(1024, feature.getAmount(item.proxy()));
        assertTrue(world.spawnedItems().isEmpty());
    }

    /** 非逻辑堆叠物品的非玩家拾取必须保持原版事件状态。 */
    @Test
    public void physicalNonPlayerPickupRemainsVanilla() throws Exception {
        TestFeature feature = feature(1024);
        SpawnTrackingWorld world = new SpawnTrackingWorld();
        FakeItem item = new FakeItem(world.proxy(), Material.GOLD_INGOT, 64);
        EntityPickupItemEvent event = new EntityPickupItemEvent(
                livingEntityProxy(), item.proxy(), 0);

        feature.onEntityPickup(event);

        assertFalse(event.isCancelled());
        assertEquals(64, feature.getAmount(item.proxy()));
        assertTrue(world.spawnedItems().isEmpty());
    }

    /** 创建只覆盖拾取事件构造所需方法的生物实体代理。 */
    private static LivingEntity livingEntityProxy() {
        return (LivingEntity) Proxy.newProxyInstance(
                ItemStackingTransactionTest.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    /** 创建使用临时数据目录的无调度测试实现。 */
    private TestFeature feature(int maxLogicalAmount) throws Exception {
        File dataFolder = temporaryFolder.newFolder();
        Plugin plugin = pluginProxy(dataFolder);
        ItemStackingConfig config = new ItemStackingConfig(maxLogicalAmount, 3D, 1.5D,
                5, 0, 8, 256, 1500, 4096, 30, false, "{name} x {amount}");
        return new TestFeature(plugin, config);
    }

    /** 创建带逐物品 YAML 的测试实现。 */
    private TestFeature feature(int maxLogicalAmount, String itemYaml) throws Exception {
        File dataFolder = temporaryFolder.newFolder();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("enabled: true\nstack:\n  max-logical-amount: " + maxLogicalAmount
                + "\ndisplay:\n  custom-name:\n    enabled: false\nitems:\n  "
                + itemYaml.replace("\n", "\n  "));
        ItemStackingConfig config = ItemStackingConfig.load(new BukkitConfigurationSource(yaml));
        return new TestFeature(pluginProxy(dataFolder), config);
    }

    /** 调用私有合并方法，直接覆盖事务结果。 */
    private void invokeMerge(TestFeature feature, Item target, Item source) throws Exception {
        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod("mergeInto", Item.class, Item.class);
        method.setAccessible(true);
        method.invoke(feature, target, source);
    }

    /** 调用私有库存插入方法并返回事务收据。 */
    private Object invokeInventoryInsert(TestFeature feature, Inventory inventory,
                                         ItemStack sample, int requested) throws Exception {
        Method method = AbstractModernItemStackingFeature.class.getDeclaredMethod(
                "insertIntoInventory", Inventory.class, ItemStack.class, Integer.TYPE);
        method.setAccessible(true);
        return method.invoke(feature, inventory, sample, Integer.valueOf(requested));
    }

    /** 读取库存插入收据中的实际接收数量。 */
    private int acceptedAmount(Object receipt) throws Exception {
        return ((Integer) invokeNoArg(receipt, "getAcceptedAmount")).intValue();
    }

    /** 调用测试对象的私有无参方法。 */
    private Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** 创建只提供名称、类加载器和数据目录的插件代理。 */
    private Plugin pluginProxy(final File dataFolder) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                new InvocationHandler() {
                    /** 返回堆叠构造阶段需要的最小插件信息。 */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        if ("getName".equals(method.getName())) {
                            return "WorldListTrashCan";
                        }
                        if ("getDataFolder".equals(method.getName())) {
                            return dataFolder;
                        }
                        if ("getClassLoader".equals(method.getName())) {
                            return getClass().getClassLoader();
                        }
                        if ("getLogger".equals(method.getName())) {
                            return Logger.getLogger("ItemStackingTransactionTest");
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    /** 创建只提供 ItemFactory 的 Bukkit 服务代理。 */
    private static Server serverProxy() {
        final ItemFactory itemFactory = (ItemFactory) Proxy.newProxyInstance(
                ItemStackingTransactionTest.class.getClassLoader(), new Class<?>[]{ItemFactory.class},
                (proxy, method, arguments) -> {
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(arguments[0] == null || arguments[1] == null
                                || arguments[0].equals(arguments[1]));
                    }
                    return defaultValue(method.getReturnType());
                });
        return (Server) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if ("getItemFactory".equals(method.getName())) {
                        return itemFactory;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("ItemStackingTransactionTest");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 返回代理方法所需的基本默认值。 */
    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }

    /** 无调度测试实现，只复用数量和事务逻辑。 */
    private static final class TestFeature extends AbstractModernItemStackingFeature {
        /** 创建测试实现。 */
        private TestFeature(Plugin plugin, final ItemStackingConfig config) {
            super(plugin, () -> config);
        }

        /** 测试不启动周期任务。 */
        @Override
        protected void startProcessor() {
        }

        /** 测试没有周期任务需要停止。 */
        @Override
        protected void stopProcessor() {
        }

        /** 测试不分派区块任务。 */
        @Override
        protected boolean dispatchChunk(StackingChunkKey key, long deadlineNanos) {
            return false;
        }

        /** 记录测试中的延迟拾取收尾，供测试显式推进。 */
        @Override
        protected boolean schedulePickupReconciliation(Item item, Runnable runnable, Runnable retired) {
            pendingPickupTask = runnable;
            pendingPickupRetiredTask = retired;
            return pickupSchedulingSucceeds;
        }

        /** 执行测试中的下一 tick 拾取收尾。 */
        private void runPendingPickupTask() {
            if (pendingPickupTask != null) {
                Runnable task = pendingPickupTask;
                pendingPickupTask = null;
                pendingPickupRetiredTask = null;
                task.run();
            }
        }

        /** 执行测试中的实体 retired 拾取收尾。 */
        private void runPendingPickupRetiredTask() {
            if (pendingPickupRetiredTask != null) {
                Runnable retired = pendingPickupRetiredTask;
                pendingPickupTask = null;
                pendingPickupRetiredTask = null;
                retired.run();
            }
        }

        private Runnable pendingPickupTask;
        private Runnable pendingPickupRetiredTask;
        private boolean pickupSchedulingSucceeds = true;

        /** 测试同步保存小型状态。 */
        @Override
        protected void saveStateAsync(Runnable runnable) {
            runnable.run();
        }
    }

    /** 记录测试中的掉落物生成，并可注入生成失败。 */
    private static final class SpawnTrackingWorld implements InvocationHandler {
        private final World proxy;
        private final List<FakeItem> spawned = new ArrayList<>();
        private boolean failSpawn;

        /** 创建可生成测试掉落物的世界代理。 */
        private SpawnTrackingWorld() {
            proxy = (World) Proxy.newProxyInstance(
                    ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, this);
        }

        /** 返回世界代理。 */
        private World proxy() {
            return proxy;
        }

        /** 返回测试中生成的掉落物。 */
        private List<FakeItem> spawnedItems() {
            return spawned;
        }

        /** 实现测试世界的 UID 和掉落物生成。 */
        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("getUID".equals(name)) {
                return FakeItem.WORLD_ID;
            }
            if ("dropItem".equals(name)) {
                if (failSpawn) {
                    return null;
                }
                FakeItem item = new FakeItem(proxy,
                        ((ItemStack) arguments[1]).getType(),
                        ((ItemStack) arguments[1]).getAmount());
                spawned.add(item);
                return item.proxy();
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(ignoredProxy == arguments[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(ignoredProxy));
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** 可注入逻辑写入和移除失败的掉落物代理。 */
    private static final class FakeItem implements InvocationHandler {
        private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        private final Map<NamespacedKey, Object> pdcValues = new HashMap<>();
        private final PersistentDataContainer pdc = pdcProxy(pdcValues, this);
        private final World world;
        private final UUID uniqueId = UUID.randomUUID();
        private final Item proxy;
        private ItemStack stack;
        private boolean valid = true;
        private boolean dead;
        private boolean removalSucceeds = true;
        private int failExpectedLogicalAmount = -1;
        private int failStoredLogicalAmount = -1;

        /** 创建指定逻辑数量的实体。 */
        private FakeItem(int logicalAmount) {
            this(worldProxy(), Material.COBBLESTONE, logicalAmount);
        }

        /** 创建指定世界、材质和逻辑数量的实体。 */
        private FakeItem(World world, Material material, int logicalAmount) {
            this.world = world;
            stack = new ItemStack(material, Math.min(material.getMaxStackSize(), logicalAmount));
            proxy = (Item) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{Item.class}, this);
            if (logicalAmount > stack.getAmount()) {
                pdcValues.put(key("stack_amount"), Integer.valueOf(logicalAmount));
            }
        }

        /** 返回实体代理。 */
        private Item proxy() {
            return proxy;
        }

        /** 返回当前物理数量。 */
        private int stackAmount() {
            return stack.getAmount();
        }

        /** 模拟原版直接修改掉落物的物理堆数量。 */
        private void setPhysicalAmount(int amount) {
            stack.setAmount(amount);
        }

        /** 判断实体当前是否有效。 */
        private boolean isValid() {
            return valid;
        }

        /** 设置 remove 是否真正让实体失效。 */
        private void setRemovalSucceeds(boolean removalSucceeds) {
            this.removalSucceeds = removalSucceeds;
        }

        /** 让下一次指定逻辑数量写入落成错误值。 */
        private void failNextLogicalWrite(int expectedAmount, int storedAmount) {
            this.failExpectedLogicalAmount = expectedAmount;
            this.failStoredLogicalAmount = storedAmount;
        }

        /** 实现测试需要的掉落物方法。 */
        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("isValid".equals(name)) {
                return Boolean.valueOf(valid);
            }
            if ("isDead".equals(name)) {
                return Boolean.valueOf(dead);
            }
            if ("getItemStack".equals(name)) {
                return stack.clone();
            }
            if ("setItemStack".equals(name)) {
                stack = ((ItemStack) arguments[0]).clone();
                return null;
            }
            if ("getPersistentDataContainer".equals(name)) {
                return pdc;
            }
            if ("getUniqueId".equals(name)) {
                return uniqueId;
            }
            if ("getWorld".equals(name)) {
                return world;
            }
            if ("getLocation".equals(name)) {
                return new Location(world, 0D, 64D, 0D);
            }
            if ("getOwner".equals(name) || "getCustomName".equals(name)) {
                return null;
            }
            if ("isCustomNameVisible".equals(name) || "hasMetadata".equals(name)) {
                return Boolean.FALSE;
            }
            if ("remove".equals(name)) {
                if (removalSucceeds) {
                    valid = false;
                    dead = true;
                }
                return null;
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(ignoredProxy == arguments[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(ignoredProxy));
            }
            return defaultValue(method.getReturnType());
        }

        /** 在 PDC set 时注入一次错误逻辑数量。 */
        private Object filterPdcWrite(NamespacedKey namespacedKey, Object value) {
            if ("stack_amount".equals(namespacedKey.getKey())
                    && value instanceof Integer
                    && ((Integer) value).intValue() == failExpectedLogicalAmount) {
                int stored = failStoredLogicalAmount;
                failExpectedLogicalAmount = -1;
                failStoredLogicalAmount = -1;
                return Integer.valueOf(stored);
            }
            return value;
        }

        /** 创建固定世界代理。 */
        private static World worldProxy() {
            return (World) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, (proxy, method, arguments) -> {
                        if ("getUID".equals(method.getName())) {
                            return WORLD_ID;
                        }
                        if ("equals".equals(method.getName())) {
                            return Boolean.valueOf(proxy == arguments[0]);
                        }
                        if ("hashCode".equals(method.getName())) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        /** 创建与生产插件相同命名空间的键。 */
        private static NamespacedKey key(String value) {
            return new NamespacedKey("worldlisttrashcan", value);
        }
    }

    /** 可观察槽位变化的库存代理。 */
    private static final class FakeInventory implements InvocationHandler {
        private final ItemStack[] contents;
        private final Inventory proxy;

        /** 创建固定槽位数量的库存。 */
        private FakeInventory(int size) {
            contents = new ItemStack[size];
            proxy = (Inventory) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                    new Class<?>[]{Inventory.class}, this);
        }

        /** 返回库存代理。 */
        private Inventory proxy() {
            return proxy;
        }

        /** 使用物品副本填满全部槽位。 */
        private void fill(ItemStack sample) {
            for (int slot = 0; slot < contents.length; slot++) {
                set(slot, sample);
            }
        }

        /** 设置单个槽位。 */
        private void set(int slot, ItemStack itemStack) {
            contents[slot] = itemStack == null ? null : itemStack.clone();
        }

        /** 返回指定槽位物品。 */
        private ItemStack itemAt(int slot) {
            return contents[slot] == null ? null : contents[slot].clone();
        }

        /** 返回指定槽位数量。 */
        private int amountAt(int slot) {
            return contents[slot] == null ? 0 : contents[slot].getAmount();
        }

        /** 实现库存插入所需方法。 */
        @Override
        public Object invoke(Object ignoredProxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("getMaxStackSize".equals(name)) {
                return Integer.valueOf(64);
            }
            if ("getStorageContents".equals(name) || "getContents".equals(name)) {
                ItemStack[] copy = new ItemStack[contents.length];
                for (int slot = 0; slot < contents.length; slot++) {
                    copy[slot] = itemAt(slot);
                }
                return copy;
            }
            if ("getItem".equals(name)) {
                return itemAt(((Integer) arguments[0]).intValue());
            }
            if ("setItem".equals(name)) {
                set(((Integer) arguments[0]).intValue(), (ItemStack) arguments[1]);
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** 创建最小 PDC 代理并保留类型无关的测试值。 */
    private static PersistentDataContainer pdcProxy(final Map<NamespacedKey, Object> values,
                                                    final FakeItem owner) {
        return (PersistentDataContainer) Proxy.newProxyInstance(ItemStackingTransactionTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, new InvocationHandler() {
                    /** 实现数量事务需要的 PDC 读写方法。 */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        String name = method.getName();
                        if ("set".equals(name)) {
                            NamespacedKey key = (NamespacedKey) arguments[0];
                            values.put(key, owner.filterPdcWrite(key, arguments[2]));
                            return null;
                        }
                        if ("get".equals(name)) {
                            return values.get((NamespacedKey) arguments[0]);
                        }
                        if ("has".equals(name)) {
                            return Boolean.valueOf(values.containsKey((NamespacedKey) arguments[0]));
                        }
                        if ("remove".equals(name)) {
                            values.remove((NamespacedKey) arguments[0]);
                            return null;
                        }
                        if ("getKeys".equals(name)) {
                            return new HashSet<>(values.keySet());
                        }
                        if ("isEmpty".equals(name)) {
                            return Boolean.valueOf(values.isEmpty());
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }
}
