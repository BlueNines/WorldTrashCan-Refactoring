package pixeltech.bluenine.blworldtrashcan.core.trash;

/** 描述一次物品路由决策。 */
public final class TrashRoutingDecision {
    private final TrashRoute route;
    private final String reason;

    /** 创建路由决策。 */
    public TrashRoutingDecision(TrashRoute route, String reason) {
        this.route = route == null ? TrashRoute.SKIP : route;
        this.reason = reason == null ? "" : reason;
    }

    /** 返回路由目标。 */
    public TrashRoute getRoute() {
        return route;
    }

    /** 返回决策原因。 */
    public String getReason() {
        return reason;
    }
}
