package pixeltech.bluenine.blworldtrashcan.core.trash;

/** 公共垃圾桶白名单拒绝扫地物品后的最终动作。 */
public enum RejectedCleanupAction {
    KEEP_GROUND,
    DIRECT_REMOVE;

    /** 从配置值解析拒绝动作。 */
    public static RejectedCleanupAction parse(String value) {
        return "direct-remove".equalsIgnoreCase(value == null ? "" : value.trim())
                ? DIRECT_REMOVE
                : KEEP_GROUND;
    }
}
