# BLWorldTrashCan 重构实现

这是 `WorldListTrashCan` 的重构实现工作区。原项目只作为行为基准保留，新实现按“共同核心 + 多版本产物”拆分，避免把 Legacy、Paper、Folia 差异堆进同一个主类。

## 产物

- `dist/BLWorldTrashCan-legacy-1.12.jar`：Paper/Spigot 1.12.2 测试产物，已在 `paper-1.12.2-test-server` 启动验证。
- `dist/BLWorldTrashCan-paper-1.16-1.20.jar`：现代 Paper 产物，已完成编译打包；当前本机没有 1.16+ 测试服，尚未做启动验证。
- `bukkit-1_13_1_15` 与 `folia-1_20` 当前保留模块边界，避免后续把版本实现塞回同一份 jar。

## 模块

- `bl-world-trashcan-core`：纯 Java 清理和路由决策，不依赖 Bukkit/Paper/Folia。
- `bl-world-trashcan-config`：拆分配置加载和类型化配置。
- `bl-world-trashcan-storage`：世界垃圾桶存储模型。
- `bl-world-trashcan-shared-bukkit`：版本中立的 Bukkit 功能层、GUI、调度适配、路由服务。
- `bl-world-trashcan-platform-legacy-1_12`：1.12 平台能力、旧告示牌、无 PDC 物品标记。
- `bl-world-trashcan-platform-paper-1_16_1_20`：现代 Paper 平台能力、PDC 玩家掉落标记。
- `bl-world-trashcan-plugin-legacy-1_12`：1.12 插件入口和命令。
- `bl-world-trashcan-plugin-paper-1_16_1_20`：现代 Paper 插件入口、Vault 和 PlaceholderAPI 适配。

## 配置文件

默认资源均带中文注释：

- `config.yml`：主配置占位和全局说明。
- `cleanup.yml`：后台清理周期、忽略世界、物品保护、实体清理规则。
- `trash.yml`：世界垃圾桶、公共垃圾桶、个人垃圾桶配置。
- `platform.yml`：版本能力说明。
- `notify.yml`：清理倒计时通知，支持 Chat、ActionBar、Title、Sound、Command。
- `entity-limits.yml`：世界实体数量限制和密集实体限制。
- `protections.yml`：聊天/命令限频、防丢弃模式、不可拾取箭矢清理、防踩踏农田。
- `messages/message_zh.yml`：中文消息预留。
- `data/worlds.yml`：世界垃圾桶运行数据。

## 命令

正式命令：

```text
/blwtc help
/blwtc platform
/blwtc clear
/blwtc stats
/blwtc global
/blwtc personal
/blwtc dropmode
/blwtc look
/blwtc ban
/blwtc globalban
/blwtc add <数量>
/blwtc reload
```

兼容旧命令入口：

```text
/WorldListTrashCan
/WTC
/wtc
```

后台测试命令，均需要 `blworldtrashcan.admin`：

```text
/blwtc debugopen <玩家> <global|personal>
/blwtc debugworldtrash <玩家>
/blwtc debugroute <玩家> <world|personal|global> <Material> <数量>
/blwtc debugdrop <玩家> <Material> <数量> [owner]
/blwtc debugsummary <玩家>
/blwtc debugplayer <玩家> <dropmode|look|ban|globalban>
```

`debugworldtrash` 会在玩家附近创建并登记一个测试箱子，`debugdrop` 会生成真实掉落物，`debugroute` 会向指定垃圾桶写入测试物品，`debugplayer` 会用真实在线 `Player` 对象触发玩家入口和 GUI；它们都会改变测试服运行态，只用于验收，不是普通玩家功能。

## 权限

- `blworldtrashcan.admin`：重载、清理、后台测试命令。
- `blworldtrashcan.use`：基础功能权限。
- `blworldtrashcan.world.create`：创建世界垃圾桶。
- `blworldtrashcan.global.open`：打开公共垃圾桶。
- `blworldtrashcan.global.take`：从公共垃圾桶取出物品。
- `blworldtrashcan.global.put`：向公共垃圾桶放入物品。
- `blworldtrashcan.personal.open`：打开个人垃圾桶。
- `blworldtrashcan.personal.take`：从个人垃圾桶取出物品。
- `blworldtrashcan.personal.put`：向个人垃圾桶放入物品。
- `blworldtrashcan.dropmode`：切换防丢弃模式。
- `blworldtrashcan.look`：查询手持物品和右键实体类型。

兼容旧权限：

- `WorldListTrashCan.Main`：旧创建世界垃圾桶权限。
- `WorldListTrashCan.BanGui`：旧世界垃圾桶黑名单 GUI 权限。
- `WorldListTrashCan.GlobalTrashOpen`：旧公共垃圾桶打开权限。
- `WorldListTrashCan.GlobalTrashTakeItem`：旧公共垃圾桶取出权限。
- `WorldListTrashCan.GlobalTrashPutItem`：旧公共垃圾桶放入权限。
- `WorldListTrashCan.PersonalTrashTakeItem`：旧个人垃圾桶取出权限。
- `WorldListTrashCan.PersonalTrashPutItem`：旧个人垃圾桶放入权限。
- `WorldListTrashCan.PlayerTrash`：旧个人垃圾桶打开权限。
- `WorldListTrashCan.GlobalBan`：旧公共垃圾桶黑名单 GUI 权限。
- `WorldListTrashCan.Look`：旧查询权限。
- `WorldListTrashCan.DropMode`：旧防丢弃模式权限。
- `WorldListTrashCan.help`：旧帮助权限。

## 变量

PAPI 变量：

- Paper 1.16-1.20 产物提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- Legacy 1.12 产物当前不注册 PAPI 变量。

发包变量：

- 当前不提供 CoreBridge / EasyCore / 龙核 / 萌芽发包变量。

## 验证记录

本机 `mvn` 不在 PATH，本轮使用 `javac` 手工编译并用 JDK 21 `jar.exe` 打包。跨版本构建必须按目标运行时指定 `--release`：1.12 Legacy 相关模块使用 `--release 8`，现代 Paper 产物使用 `--release 17`，否则 1.12.2 Java 8 测试服会出现 `UnsupportedClassVersionError`。

已通过：

- `core -> config -> storage -> shared-bukkit -> platform-legacy -> plugin-legacy -> platform-paper -> plugin-paper`
- `CorePolicySelfTest passed`
- 1.12.2 测试服加载 `BLWorldTrashCan v0.1.0-SNAPSHOT`
- RCON 验证 `platform`、`stats`、`debugsummary`、`debugworldtrash`、`debugroute`、`debugdrop`、`clear`、`debugopen`、`debugplayer`
- `client-1.12.2` 真实玩家 `AIAutoTest` 进服后执行玩家入口和 GUI 打开测试
- 旧功能补齐验证覆盖：防丢弃模式、look 查询、单世界黑名单 GUI、公共黑名单 GUI、聊天/命令限频、不可拾取箭矢清理、防踩踏农田、经验球/实体清理、实体白名单/黑名单、世界实体数量限制、密集实体限制、公共垃圾桶日志、公共垃圾桶按清理次数刷新、定时清理倒计时通知
- 世界垃圾桶强制加载区块问题已先记录方案，见 `docs/世界垃圾桶区块加载性能方案.md`；本轮不直接改实现，避免在旧功能未完全闭环前扩大行为变更。

本轮关键日志：

- `paper-1.12.2-test-server/ai-blwtc-fullregression-rcon-smoke-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-rcon-main-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-debugplayer-rcon-smoke-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-debugplayer-rcon-online-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-debugplayer-rcon-main-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-final-rcon-20260601.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-final-latest-20260601.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-final-stop-20260601.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-final-stop-latest-20260601.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-debugplayer-client-stdout-20260531.log`
- `paper-1.12.2-test-server/ai-blwtc-fullregression-debugplayer-client-stderr-20260531.log`

已知测试环境噪声：

- 打开 GUI 时 EasyCore 会因缺少 `top.wcpe.wcneteasemodrpc.item.texture.match.TextureMatchs` 报 `InventoryOpenEvent` 异常；RCON 返回和 BLWorldTrashCan debug 日志均显示本插件 GUI 打开调用已执行。
- 测试服上其他前置插件存在 MythicMobs 版本兼容警告和 Druid/MySQL 连接超时日志；本轮日志未发现 BLWorldTrashCan 自身的 `UnsupportedClassVersionError`、`NoSuchMethodError` 或插件启用失败。
