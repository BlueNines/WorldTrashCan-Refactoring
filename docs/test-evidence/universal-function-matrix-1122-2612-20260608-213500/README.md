# BLWorldTrashCan universal 功能矩阵证据

本目录归档 2026-06-08 针对重构版完整功能文档执行的三端真实客户端矩阵测试。完整功能清单见 `docs/重构版完整功能与测试矩阵.md`。

## 被测产物

本轮只部署同一个整包：

```text
dist/BLWorldTrashCan-universal.jar
size: 432254
sha256: 9396d8c524ff44b03d3f2a1b4d7e7848e64c93043cc9ea4635ca5967bb7e0399
plugin.yml version: 7.0.0
main: pixeltech.bluenine.blworldtrashcan.plugin.universal.BLWorldTrashCanUniversalPlugin
```

部署前脚本会移除目标测试服 `plugins` 目录中的其它 `BLWorldTrashCan*.jar`，避免轻量特供 jar 干扰。测试过程中没有删除测试服 `logs`、`world*`、`cache`、`assets`。

## 测试结果

`docs/重构版完整功能与测试矩阵.md` 定义 80 个功能项。矩阵 JSON 记录的是更细的测试行，同一功能 ID 可能有多个子用例，例如旧命令别名和真实玩家命令会拆成多行；因此每端本轮共有 99 条矩阵行。

| 服务端 | 客户端 | run | 结果 | PASS | STATIC-PASS | SKIP | FAIL | 截图 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Paper 1.12.2 | Forge 1.12.2 | `20260608-211313` | PASS | 56 | 12 | 31 | 0 | 21 |
| Paper 26.1.2 | 原版 26.1.2 | `20260608-211637` | PASS | 55 | 12 | 32 | 0 | 21 |
| Spigot 26.1.2 | 原版 26.1.2 | `20260608-212037` | PASS | 55 | 12 | 32 | 0 | 21 |

`PASS` 只表示本轮真实执行过的运行态测试通过；`STATIC-PASS` 表示纯 Java 策略、源码结构或 jar 内容已经能证明；`SKIP` 表示本轮没有运行专项夹具或该端不适用，不能算通过。

## 核心证据文件

| 服务端 | summary | 矩阵 | 截图目录 | 日志目录 |
| --- | --- | --- | --- | --- |
| Paper 1.12.2 | `runs/20260608-211313/summary.json` | `runs/20260608-211313/universal_managed_paper1122/logs/universal_managed_paper1122-function-matrix.json` | `runs/20260608-211313/universal_managed_paper1122/screenshots/` | `runs/20260608-211313/universal_managed_paper1122/logs/` |
| Paper 26.1.2 | `runs/20260608-211637/summary.json` | `runs/20260608-211637/universal_paper2612/logs/universal_paper2612-function-matrix.json` | `runs/20260608-211637/universal_paper2612/screenshots/` | `runs/20260608-211637/universal_paper2612/logs/` |
| Spigot 26.1.2 | `runs/20260608-212037/summary.json` | `runs/20260608-212037/universal_spigot2612/logs/universal_spigot2612-function-matrix.json` | `runs/20260608-212037/universal_spigot2612/screenshots/` | `runs/20260608-212037/universal_spigot2612/logs/` |

玩家可见链路保留了 F2 截图，包括聊天栏、ActionBar、Title、公共/个人 GUI、个人垃圾桶单条提示、批量完整提示、批量省略提示、无权限提示、聊天/命令限频、玩家命令和 RGB GUI 样张。

## 未动态覆盖项

本轮仍有一批功能只记录为 `SKIP`，不是通过。后续要继续压缩这些项，需要补独立夹具或真实点击/声音验收：

```text
F-005 旧配置自动迁移
F-019 禁止世界普通玩家创建
F-020 破坏告示牌或容器移除登记
F-021 世界物品黑名单路由降级
F-022 未加载区块跳过
F-024 公共垃圾桶分页翻页
F-026 公共垃圾桶 GUI 取出
F-027 公共垃圾桶 GUI 放入
F-028 公共垃圾桶取出冷却
F-029 公共垃圾桶操作日志
F-030 公共黑名单 GUI 保存即时生效
F-031 公共垃圾桶按清理次数刷新
F-034 个人垃圾桶 GUI 取出
F-035 个人垃圾桶 GUI 放入
F-036 个人垃圾桶满时自动清空
F-037 Vault 扣费取出
F-054 船内实体保护
F-058 Chat 完成通知点击命令
F-060 BossBar 清理通知
F-061 Title 清理通知
F-062 Sound 清理通知
F-063 Command 清理通知
F-068 不可拾取箭矢清理
F-069 防踩踏农田
F-070 单世界实体数量限制
F-071 密集实体限制
F-072 实体限制 ignored-worlds
F-073 多语言切换
F-074 语言缺节点回退默认
```

此外，`F-003` 只适用于 1.12.2 Java 8 bootstrap；`F-041/F-075` 只适用于 legacy 端；`F-042/F-076` 只适用于现代端。这些跨端不适用项在对应服务端会显示为 `SKIP`。
