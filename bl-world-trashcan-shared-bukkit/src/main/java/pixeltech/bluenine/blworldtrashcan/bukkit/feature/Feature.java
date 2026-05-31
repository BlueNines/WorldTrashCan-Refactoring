package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

/** 可启停功能模块。 */
public interface Feature {
    /** 返回功能 ID。 */
    String id();

    /** 启用功能。 */
    void enable();

    /** 重载功能。 */
    void reload();

    /** 禁用功能并释放资源。 */
    void disable();
}
