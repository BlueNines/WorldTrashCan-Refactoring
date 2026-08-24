package pixeltech.bluenine.blworldtrashcan.core.cleanup;

/** 描述平台层生成实体快照时真正需要读取的动态字段。 */
public final class EntitySnapshotRequirements {
    private static final EntitySnapshotRequirements ALL =
            new EntitySnapshotRequirements(true, true, true, true, true);
    private final boolean readEntityName;
    private final boolean readCustomName;
    private final boolean readInsideBoat;
    private final boolean readSaddle;
    private final boolean readOwner;

    /** 创建实体快照读取计划。 */
    public EntitySnapshotRequirements(boolean readEntityName, boolean readCustomName,
                                      boolean readInsideBoat, boolean readSaddle,
                                      boolean readOwner) {
        this.readEntityName = readEntityName;
        this.readCustomName = readCustomName;
        this.readInsideBoat = readInsideBoat;
        this.readSaddle = readSaddle;
        this.readOwner = readOwner;
    }

    /** 返回兼容旧调用方的完整读取计划。 */
    public static EntitySnapshotRequirements all() {
        return ALL;
    }

    /** 返回按清理配置裁剪后的读取计划。 */
    public static EntitySnapshotRequirements from(CleanupSettings settings) {
        if (settings == null) {
            return all();
        }
        return new EntitySnapshotRequirements(
                settings.hasEntityNameRules(),
                !settings.isClearNamedEntity() || settings.hasNamedEntityRules(),
                settings.isIgnoreEntitiesInBoat(),
                settings.isIgnoreEntitiesWithSaddle(),
                settings.isIgnoreEntitiesWithOwner()
        );
    }

    /** 判断是否需要读取 Bukkit 实体名称。 */
    public boolean readEntityName() {
        return readEntityName;
    }

    /** 判断是否需要读取实体自定义名称。 */
    public boolean readCustomName() {
        return readCustomName;
    }

    /** 判断是否需要读取实体载具关系。 */
    public boolean readInsideBoat() {
        return readInsideBoat;
    }

    /** 判断是否需要读取实体鞍状态。 */
    public boolean readSaddle() {
        return readSaddle;
    }

    /** 判断是否需要读取 Bukkit Tameable 主人。 */
    public boolean readOwner() {
        return readOwner;
    }
}
