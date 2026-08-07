package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.plugin.Plugin;

/** 在插件启动阶段选择并固定公共垃圾桶的物品身份实现。 */
public final class ItemIdentityProviderSelector {
    /** 选择 Raw NBT，失败时一次性降级为 Bukkit Similar。 */
    public ItemIdentityProvider select(Plugin plugin) {
        ReflectiveNbtIdentityProvider rawNbt = new ReflectiveNbtIdentityProvider();
        if (rawNbt.isReady()) {
            log(plugin, "[GlobalTrash] item-identity=raw-nbt");
            return rawNbt;
        }
        BukkitSimilarIdentityProvider fallback = new BukkitSimilarIdentityProvider();
        String reason = rawNbt.getFailureReason();
        log(plugin, "[GlobalTrash] item-identity=bukkit-similar (fallback)"
                + (reason.isEmpty() ? "" : "; raw-nbt=" + reason));
        return fallback;
    }

    /** 输出一次身份选择结果。 */
    private void log(Plugin plugin, String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
        }
    }
}
