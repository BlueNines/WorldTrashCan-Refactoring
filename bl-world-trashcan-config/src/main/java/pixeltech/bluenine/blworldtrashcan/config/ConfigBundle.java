package pixeltech.bluenine.blworldtrashcan.config;

import pixeltech.bluenine.blworldtrashcan.core.cleanup.CleanupSettings;

/** 运行期使用的类型化配置集合，业务代码只读这个对象。 */
public final class ConfigBundle {
    private final CleanupSettings cleanupSettings;
    private final String languageFile;
    private final boolean debug;

    /** 创建配置集合。 */
    public ConfigBundle(CleanupSettings cleanupSettings, String languageFile, boolean debug) {
        this.cleanupSettings = cleanupSettings;
        this.languageFile = languageFile == null || languageFile.trim().isEmpty() ? "message_zh.yml" : languageFile;
        this.debug = debug;
    }

    /** 返回清理配置。 */
    public CleanupSettings getCleanupSettings() {
        return cleanupSettings;
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
