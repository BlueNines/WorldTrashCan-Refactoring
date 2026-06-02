package pixeltech.bluenine.blworldtrashcan.bukkit.bstats;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/** bStats 启动工具，集中保留旧插件 serviceId 和图表。 */
public final class BStatsMetricsService {
    private static final int SERVICE_ID = 24350;

    /** 启动 bStats 并注册旧插件已有的基础图表。 */
    public static Metrics start(Plugin plugin, String platformId) {
        Metrics metrics = new Metrics(plugin, SERVICE_ID);
        metrics.addCustomChart(new Metrics.SingleLineChart("players", new java.util.concurrent.Callable<Integer>() {
            /** 返回当前在线玩家数。 */
            @Override
            public Integer call() {
                return Integer.valueOf(Bukkit.getOnlinePlayers().size());
            }
        }));
        metrics.addCustomChart(new Metrics.SingleLineChart("servers", new java.util.concurrent.Callable<Integer>() {
            /** 返回当前服务端数量。 */
            @Override
            public Integer call() {
                return Integer.valueOf(1);
            }
        }));
        metrics.addCustomChart(new Metrics.MultiLineChart("players_and_servers", new java.util.concurrent.Callable<Map<String, Integer>>() {
            /** 返回玩家数和服务端数量。 */
            @Override
            public Map<String, Integer> call() {
                Map<String, Integer> valueMap = new HashMap<>();
                valueMap.put("servers", Integer.valueOf(1));
                valueMap.put("players", Integer.valueOf(Bukkit.getOnlinePlayers().size()));
                return valueMap;
            }
        }));
        metrics.addCustomChart(new Metrics.SimplePie("platform", new java.util.concurrent.Callable<String>() {
            /** 返回当前重构产物平台标识。 */
            @Override
            public String call() {
                return platformId;
            }
        }));
        return metrics;
    }

    /** 阻止实例化工具类。 */
    private BStatsMetricsService() {
    }
}
