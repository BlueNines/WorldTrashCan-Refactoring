package pixeltech.bluenine.blworldtrashcan.bukkit.platform;

/** 可取消任务句柄，屏蔽 Bukkit 与 Folia 的任务类型差异。 */
public interface TaskHandle {
    /** 取消任务。 */
    void cancel();
}
