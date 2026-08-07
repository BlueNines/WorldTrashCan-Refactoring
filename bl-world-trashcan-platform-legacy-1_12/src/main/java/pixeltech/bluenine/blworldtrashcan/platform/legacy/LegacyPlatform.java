package pixeltech.bluenine.blworldtrashcan.platform.legacy;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.BukkitSchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.EntitySnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemSnapshotMapper;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.SchedulerAdapter;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.capability.CapabilityReport;

import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Paper/Spigot 1.12 平台实现。 */
public final class LegacyPlatform implements ServerPlatform {
    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final ItemSnapshotMapper itemSnapshotMapper;
    private final EntitySnapshotMapper entitySnapshotMapper;
    private final CapabilityReport capabilityReport;

    /** 创建 Legacy 平台实现。 */
    public LegacyPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = new BukkitSchedulerAdapter(plugin);
        this.itemSnapshotMapper = new LegacyItemSnapshotMapper();
        this.entitySnapshotMapper = new LegacyEntitySnapshotMapper();
        this.capabilityReport = new CapabilityReport("bukkit-api-1.12", EnumSet.of(
                Capability.SCHEDULER_GLOBAL,
                Capability.INVENTORY_BASIC,
                Capability.CLICKABLE_MESSAGE,
                Capability.BOSS_BAR,
                Capability.SIGN_LEGACY
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

    /** 返回全局调度器。 */
    @Override
    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** 返回物品映射器。 */
    @Override
    public ItemSnapshotMapper itemSnapshotMapper() {
        return itemSnapshotMapper;
    }

    /** 返回实体映射器。 */
    @Override
    public EntitySnapshotMapper entitySnapshotMapper() {
        return entitySnapshotMapper;
    }

    /** 返回 1.12 告示牌所依附的容器方块。 */
    @Override
    public Block getAttachedContainerBlock(Block signBlock) {
        if (signBlock == null) {
            return null;
        }
        if (signBlock.getType().name().contains("WALL_SIGN")) {
            byte data = signBlock.getData();
            if (data == 2) {
                return signBlock.getRelative(BlockFace.SOUTH);
            }
            if (data == 3) {
                return signBlock.getRelative(BlockFace.NORTH);
            }
            if (data == 4) {
                return signBlock.getRelative(BlockFace.EAST);
            }
            if (data == 5) {
                return signBlock.getRelative(BlockFace.WEST);
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

    /** 在 Bukkit 主线程执行玩家回调。 */
    @Override
    public boolean executeForPlayer(final UUID playerUuid, final Consumer<Player> action) {
        if (playerUuid == null || action == null || Bukkit.getPlayer(playerUuid) == null) {
            return false;
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            /** 重新确认玩家在线后执行回调。 */
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    action.accept(player);
                }
            }
        });
        return true;
    }
}
