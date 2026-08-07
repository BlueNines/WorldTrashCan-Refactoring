package pixeltech.bluenine.blworldtrashcan.platform.folia;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Folia 1.20 平台实现。 */
public final class FoliaPlatform implements ServerPlatform {
    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final EntitySnapshotMapper entitySnapshotMapper;
    private final CapabilityReport capabilityReport;

    /** 创建 Folia 平台实现。 */
    public FoliaPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = new FoliaSchedulerAdapter(plugin);
        this.itemSnapshotMapper = new FoliaItemSnapshotMapper(plugin);
        this.entitySnapshotMapper = new FoliaEntitySnapshotMapper();
        this.capabilityReport = new CapabilityReport("regionized-api-1.20+", EnumSet.of(
                Capability.SCHEDULER_GLOBAL,
                Capability.SCHEDULER_REGION,
                Capability.INVENTORY_BASIC,
                Capability.ITEM_PDC_TAG,
                Capability.RGB_MESSAGE,
                Capability.CLICKABLE_MESSAGE,
                Capability.BOSS_BAR,
                Capability.SIGN_MODERN,
                Capability.ENTITY_MOVE_EVENT
        ));
    }

    /** 返回平台 ID。 */
    @Override
    public String id() {
        return capabilityReport.getPlatformId();
    }

    /** 返回能力报告。 */
    @Override
    public CapabilityReport capabilities() {
        return capabilityReport;
    }

    /** 返回 Folia 调度器。 */
    @Override
    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** 返回 Folia 物品映射器。 */
    @Override
    public ItemSnapshotMapper itemSnapshotMapper() {
        return itemSnapshotMapper;
    }

    /** 返回 Folia 实体映射器。 */
    @Override
    public EntitySnapshotMapper entitySnapshotMapper() {
        return entitySnapshotMapper;
    }

    /** 返回现代告示牌依附的容器方块。 */
    @Override
    public Block getAttachedContainerBlock(Block signBlock) {
        if (signBlock == null) {
            return null;
        }
        if (signBlock.getState() instanceof Sign && signBlock.getType().name().contains("WALL_SIGN")) {
            BlockData blockData = signBlock.getBlockData();
            if (blockData instanceof Directional) {
                BlockFace face = ((Directional) blockData).getFacing().getOppositeFace();
                return signBlock.getRelative(face);
            }
        }
        return signBlock.getRelative(BlockFace.DOWN);
    }

    /** 通过玩家实体调度器向在线玩家发送消息。 */
    @Override
    public void sendMessage(UUID playerUuid, final String message) {
        if (playerUuid == null || message == null || message.isEmpty()) {
            return;
        }
        final Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        player.getScheduler().execute(plugin, new Runnable() {
            /** 在玩家实体上下文发送消息。 */
            @Override
            public void run() {
                player.sendMessage(RichTextRenderer.color(player, message));
            }
        }, new Runnable() {
            /** 玩家实体不可用时跳过消息。 */
            @Override
            public void run() {
            }
        }, 1L);
    }

    /** 通过玩家实体调度器执行附属插件回调。 */
    @Override
    public boolean executeForPlayer(UUID playerUuid, final Consumer<Player> action) {
        if (playerUuid == null || action == null) {
            return false;
        }
        final Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return false;
        }
        player.getScheduler().execute(plugin, new Runnable() {
            /** 在玩家实体上下文执行回调。 */
            @Override
            public void run() {
                action.accept(player);
            }
        }, new Runnable() {
            /** 玩家实体不可用时静默跳过。 */
            @Override
            public void run() {
            }
        }, 1L);
        return true;
    }
}
