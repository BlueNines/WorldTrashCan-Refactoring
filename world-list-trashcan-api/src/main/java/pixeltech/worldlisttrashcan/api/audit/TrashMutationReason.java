package pixeltech.worldlisttrashcan.api.audit;

/** 垃圾桶变更的稳定业务原因。 */
public enum TrashMutationReason {
    MANUAL_DEPOSIT,
    NON_CLEANUP_DEPOSIT,
    PLAYER_TAKE,
    GLOBAL_REFRESH,
    PERSONAL_AUTO_CLEAR,
    TRACKING_RESET
}
