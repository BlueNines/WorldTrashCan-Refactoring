package pixeltech.bluenine.blworldtrashcan.core.cleanup;

/** 命中自定义物品规则后的扫地路由设置。 */
public final class CustomItemRoutingSettings {
    private final boolean enabled;
    private final ItemMatchRules rules;
    private final Mode mode;
    private final UnavailableAction personalUnavailable;

    /** 创建自定义物品路由设置。 */
    public CustomItemRoutingSettings(boolean enabled, ItemMatchRules rules, Mode mode,
                                     UnavailableAction personalUnavailable) {
        this.enabled = enabled;
        this.rules = rules == null ? ItemMatchRules.empty() : rules;
        this.mode = mode == null ? Mode.PERSONAL_ONLY : mode;
        this.personalUnavailable = personalUnavailable == null
                ? UnavailableAction.KEEP_GROUND
                : personalUnavailable;
    }

    /** 返回默认关闭的设置。 */
    public static CustomItemRoutingSettings defaults() {
        return new CustomItemRoutingSettings(false, ItemMatchRules.empty(),
                Mode.PERSONAL_ONLY, UnavailableAction.KEEP_GROUND);
    }

    /** 判断自定义路由是否启用。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 返回五类识别规则。 */
    public ItemMatchRules getRules() {
        return rules;
    }

    /** 返回命中后的目标模式。 */
    public Mode getMode() {
        return mode;
    }

    /** 返回个人路由不可用时的动作。 */
    public UnavailableAction getPersonalUnavailable() {
        return personalUnavailable;
    }

    /** 自定义物品路由模式。 */
    public enum Mode {
        PERSONAL_ONLY,
        KEEP_GROUND,
        DIRECT_REMOVE;

        /** 从配置值解析路由模式。 */
        public static Mode parse(String value) {
            String normalized = value == null ? "" : value.trim();
            if ("keep-ground".equalsIgnoreCase(normalized)) {
                return KEEP_GROUND;
            }
            if ("direct-remove".equalsIgnoreCase(normalized)) {
                return DIRECT_REMOVE;
            }
            return PERSONAL_ONLY;
        }
    }

    /** 个人垃圾桶不可用时允许的动作。 */
    public enum UnavailableAction {
        KEEP_GROUND,
        DIRECT_REMOVE;

        /** 从配置值解析不可用动作。 */
        public static UnavailableAction parse(String value) {
            return "direct-remove".equalsIgnoreCase(value == null ? "" : value.trim())
                    ? DIRECT_REMOVE
                    : KEEP_GROUND;
        }
    }
}
