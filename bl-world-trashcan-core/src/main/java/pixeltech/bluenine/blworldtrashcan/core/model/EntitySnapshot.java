package pixeltech.bluenine.blworldtrashcan.core.model;

/** 平台层从真实实体提取出的轻量快照，避免核心层依赖 Bukkit 实体类。 */
public final class EntitySnapshot {
    private final String typeKey;
    private final String name;
    private final String customName;
    private final boolean living;
    private final boolean monsterLike;
    private final boolean projectile;
    private final boolean insideBoat;

    /** 创建实体快照。 */
    public EntitySnapshot(String typeKey, String name, String customName, boolean living,
                          boolean monsterLike, boolean projectile, boolean insideBoat) {
        this.typeKey = typeKey == null ? "" : typeKey;
        this.name = name == null ? "" : name;
        this.customName = customName == null ? "" : customName;
        this.living = living;
        this.monsterLike = monsterLike;
        this.projectile = projectile;
        this.insideBoat = insideBoat;
    }

    /** 返回平台标准化后的实体类型。 */
    public String getTypeKey() {
        return typeKey;
    }

    /** 返回实体名称。 */
    public String getName() {
        return name;
    }

    /** 返回实体自定义名，未设置时为空字符串。 */
    public String getCustomName() {
        return customName;
    }

    /** 判断实体是否属于生物类。 */
    public boolean isLiving() {
        return living;
    }

    /** 判断实体是否按怪物处理。 */
    public boolean isMonsterLike() {
        return monsterLike;
    }

    /** 判断实体是否属于投射物。 */
    public boolean isProjectile() {
        return projectile;
    }

    /** 判断实体是否位于船内。 */
    public boolean isInsideBoat() {
        return insideBoat;
    }
}
