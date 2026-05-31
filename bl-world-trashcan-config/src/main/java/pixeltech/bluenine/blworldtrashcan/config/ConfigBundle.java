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

    /** 创建配置集合。 */
    public ConfigBundle(CleanupConfig cleanupConfig, TrashConfig trashConfig,
                        ProtectionConfig protectionConfig, EntityLimitConfig entityLimitConfig,
                        NotifyConfig notifyConfig,
                        String languageFile, boolean debug) {
        this.cleanupConfig = cleanupConfig;
        this.trashConfig = trashConfig;
        this.protectionConfig = protectionConfig;
        this.entityLimitConfig = entityLimitConfig;
        this.notifyConfig = notifyConfig;
        this.languageFile = languageFile == null || languageFile.trim().isEmpty() ? "message_zh.yml" : languageFile;
        this.debug = debug;
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
}
