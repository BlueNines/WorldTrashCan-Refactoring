package pixeltech.worldlisttrashcan.api.audit;

/** 虚拟垃圾桶中会影响历史数量核算的变更类型。 */
public enum TrashMutationType {
    UNTRACKED_DEPOSIT,
    TAKE,
    CLEAR
}
