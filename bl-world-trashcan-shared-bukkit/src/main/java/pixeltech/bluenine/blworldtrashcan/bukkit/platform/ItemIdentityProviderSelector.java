package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.logging.DebugOutput;

/** 在插件启动阶段选择并固定公共垃圾桶的物品身份实现。 */
public final class ItemIdentityProviderSelector {
    /** 选择 Raw NBT，失败时一次性降级为 Bukkit Similar。 */
    public ItemIdentityProvider select(Plugin plugin) {
        return select(plugin, DebugOutput.disabled());
    }

    /** 选择物品身份实现，并把选择细节交给统一调试输出。 */
    public ItemIdentityProvider select(Plugin plugin, DebugOutput debugOutput) {
        final DebugOutput output = debugOutput == null ? DebugOutput.disabled() : debugOutput;
        ReflectiveNbtIdentityProvider rawNbt = new ReflectiveNbtIdentityProvider();
        if (rawNbt.isReady()) {
            output.debug(() -> "[GlobalTrash] item-identity=raw-nbt");
            return rawNbt;
        }
        BukkitSimilarIdentityProvider fallback = new BukkitSimilarIdentityProvider();
        String reason = rawNbt.getFailureReason();
        output.debug(() -> "[GlobalTrash] item-identity=bukkit-similar (fallback)"
                + (reason.isEmpty() ? "" : "; raw-nbt=" + reason));
        return fallback;
    }
}
