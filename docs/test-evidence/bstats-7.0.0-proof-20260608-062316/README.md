# BLWorldTrashCan bStats 7.0.0 验证证据

本目录归档 `BLWorldTrashCan-universal-7.0.0.jar` 的 bStats 端到端验证结果。

## 结论

```text
插件加载版本: BLWorldTrashCan v7.0.0
bStats serviceId: 24350
bStats 上报 pluginVersion: 7.0.0
bStats 上报响应: 已收到响应
bStats 页面插件版本图表: 已出现 7.0.0，数量 1
```

验证页面：[WorldTrashCan / 24350](https://bstats.org/plugin/bukkit/WorldTrashCan/24350)

## 关键证据

- `artifact-summary.json`：机器可读断言摘要，`serverLoadedVersion`、`sentPayloadHasVersion`、`sentPayloadHasServiceId`、`receivedBstatsResponse`、`sitePluginVersionChartHas700` 均为 `true`。
- `logs/ai-blwtc-bstats-7.0.0-20260608-061900-proxy-console.log`：带 JVM 代理参数后的成功验证日志。
- `logs/ai-blwtc-bstats-7.0.0-20260608-060715-console.log`：不带 JVM 代理时的失败对照，证明 Java `HttpsURLConnection` 直连 bStats 会超时。
- `page/ai-blwtc-bstats-7.0.0-20260608-plugin-version-chart-after-refresh.txt`：bStats 页面 06:30 刷新后插件版本图表片段。

成功日志中的关键行：

```text
[BLWorldTrashCan] Loading server plugin BLWorldTrashCan v7.0.0
[BLWorldTrashCan] Sent bStats metrics data: ... "service":{"pluginVersion":"7.0.0","id":24350 ...
[BLWorldTrashCan] Sent data to bStats and received response:
```

页面刷新后的插件版本图表片段：

```text
resolve(6, () => [[{name:"6.5.3",y:1},{name:"6.5.4",y:1},{name:"6.6.0",y:1},{name:"7.0.0",y:1}, ...]])
```

## 测试说明

本轮使用 `paper-1.20.4-test-server`，部署 `dist/BLWorldTrashCan-universal.jar` 复制出的 `BLWorldTrashCan-universal-7.0.0.jar`。测试期间临时打开 `plugins/bStats/config.yml` 的 `logSentData`、`logResponseStatusText` 和 `logFailedRequests`，测试后已恢复原 bStats 配置。

bStats 页面写明图表数据每 30 分钟在整点和半点刷新，因此本轮先在 06:23:51 观察到服务端上报和响应，再在 06:32:16 抓取页面，确认插件版本图表出现 `7.0.0`。
