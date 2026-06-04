package pixeltech.bluenine.blworldtrashcan.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

import java.util.EnumSet;
import java.util.UUID;

/** Paper 1.16-1.20 平台实现。 */
public final class PaperPlatform implements ServerPlatform {
    private final SchedulerAdapter scheduler;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final EntitySnapshotMapper entitySnapshotMapper;
    private final CapabilityReport capabilityReport;

    /** 创建 Paper 平台实现。 */
    public PaperPlatform(Plugin plugin) {
        this.scheduler = new BukkitSchedulerAdapter(plugin);
        this.itemSnapshotMapper = new PaperItemSnapshotMapper(plugin);
        this.entitySnapshotMapper = new PaperEntitySnapshotMapper();
        this.capabilityReport = new CapabilityReport("paper-1.16-1.20", EnumSet.of(
                Capability.SCHEDULER_GLOBAL,
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

    /** 返回平台调度器。 */
    @Override
    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** 返回 Paper 物品快照映射器。 */
    @Override
    public ItemSnapshotMapper itemSnapshotMapper() {
        return itemSnapshotMapper;
    }

    /** 返回 Paper 实体快照映射器。 */
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

    /** 向在线玩家发送消息。 */
    @Override
    public void sendMessage(UUID playerUuid, String message) {
        if (playerUuid == null || message == null || message.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            player.sendMessage(RichTextRenderer.color(player, message));
        }
    }
}
