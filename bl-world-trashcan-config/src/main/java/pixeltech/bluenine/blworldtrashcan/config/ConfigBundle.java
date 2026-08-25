package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

/** 运行期使用的类型化配置集合，业务代码只读这个对象。 */
public final class ConfigBundle {
    private final CleanupConfig cleanupConfig;
    private final TrashConfig trashConfig;
    private final ProtectionConfig protectionConfig;
    private final EntityLimitConfig entityLimitConfig;
    private final NotifyConfig notifyConfig;
    private final String languageFile;
    private final boolean debug;
    private final ItemStackingConfig itemStackingConfig;

    /** 创建配置集合。 */
    public ConfigBundle(CleanupConfig cleanupConfig, TrashConfig trashConfig,
                        ProtectionConfig protectionConfig, EntityLimitConfig entityLimitConfig,
                         NotifyConfig notifyConfig,
                         String languageFile, boolean debug, ItemStackingConfig itemStackingConfig) {
        this.cleanupConfig = cleanupConfig;
        this.trashConfig = trashConfig;
        this.protectionConfig = protectionConfig;
        this.entityLimitConfig = entityLimitConfig;
        this.notifyConfig = notifyConfig;
        this.languageFile = languageFile == null || languageFile.trim().isEmpty() ? "message_zh.yml" : languageFile;
        this.debug = debug;
        this.itemStackingConfig = itemStackingConfig == null
                ? ItemStackingConfig.defaults() : itemStackingConfig;
    }

    /** 返回清理配置。 */
    public CleanupConfig getCleanupConfig() {
        return cleanupConfig;
    }

    /** 返回清理配置。 */
    public CleanupSettings getCleanupSettings() {
        return cleanupConfig.getSettings();
    }

    /** 返回垃圾桶配置。 */
    public TrashConfig getTrashConfig() {
        return trashConfig;
    }

    /** 返回防护配置。 */
    public ProtectionConfig getProtectionConfig() {
        return protectionConfig;
    }

    /** 返回实体限制配置。 */
    public EntityLimitConfig getEntityLimitConfig() {
        return entityLimitConfig;
    }

    /** 返回通知配置。 */
    public NotifyConfig getNotifyConfig() {
        return notifyConfig;
    }

    /** 返回语言文件名。 */
    public String getLanguageFile() {
        return languageFile;
    }

    /** 判断是否开启调试。 */
    public boolean isDebug() {
        return debug;
    }

    /** 判断服主是否请求启用地面掉落物逻辑堆叠。 */
    public boolean isItemStackingEnabled() {
        return itemStackingConfig.isFeatureEnabled();
    }

    /** 返回地面堆叠和垃圾桶提示共用的内置物品名称语言。 */
    public String getItemDisplayNameLocale() {
        return itemStackingConfig.getDisplayNameLocale();
    }

    /** 返回统一文件解析出的完整地面堆叠配置快照。 */
    public ItemStackingConfig getItemStackingConfig() {
        return itemStackingConfig;
    }
}
