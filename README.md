# BLWorldTrashCan 重构实现

这是 `WorldListTrashCan` 的重构实现工作区。原项目只作为行为基准保留，新实现按“共同核心 + 多版本产物”拆分，避免把 Legacy、Paper、Folia 差异堆进同一个主类。

## 产物

- `dist/BLWorldTrashCan-legacy-1.12.jar`：Paper/Spigot 1.12.2 测试产物，已在 `paper-1.12.2-test-server` 启动验证。
- `dist/BLWorldTrashCan-bukkit-1.13-1.15.jar`：Bukkit/Spigot 1.13-1.15 产物，已在 `paper-1.13.2-test-server` 用 Java 8 完成启动 smoke 和 RCON 命令复测。
- `dist/BLWorldTrashCan-paper-1.16-1.20.jar`：现代 Paper 产物，已在 `paper-1.20.4-test-server` 完成启动 smoke 和 RCON 命令验证。
- `dist/BLWorldTrashCan-folia-1.20.jar`：Folia 1.20 产物，已在 `folia-1.20.1-test-server` 完成启动 smoke；当前世界实体扫描清理尚未实现全链路 region-safe，因此定时世界扫描会关闭，`/blwtc clear` 会拒绝执行世界实体扫描。

## 模块

- `bl-world-trashcan-core`：纯 Java 清理和路由决策，不依赖 Bukkit/Paper/Folia。
- `bl-world-trashcan-config`：拆分配置加载和类型化配置。
- `bl-world-trashcan-storage`：世界垃圾桶存储模型。
- `bl-world-trashcan-shared-bukkit`：版本中立的 Bukkit 功能层、GUI、调度适配、路由服务。
- `bl-world-trashcan-platform-legacy-1_12`：1.12 平台能力、旧告示牌、无 PDC 物品标记。
- `bl-world-trashcan-platform-bukkit-1_13_1_15`：1.13-1.15 平台能力、现代告示牌、无 PDC 物品标记，避免 1.13 缺 PDC API。
- `bl-world-trashcan-platform-paper-1_16_1_20`：现代 Paper 平台能力、PDC 玩家掉落标记。
- `bl-world-trashcan-platform-folia-1_20`：Folia 平台能力、PDC 玩家掉落标记、Folia 全局调度入口。
- `bl-world-trashcan-plugin-legacy-1_12`：1.12 插件入口和命令。
- `bl-world-trashcan-plugin-bukkit-1_13_1_15`：1.13-1.15 插件入口、Vault 和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-paper-1_16_1_20`：现代 Paper 插件入口、Vault 和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-folia-1_20`：Folia 插件入口、Vault 和 PlaceholderAPI 适配。

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

## 旧配置迁移

插件启动时默认会尝试执行一次旧 `WorldListTrashCan` 配置迁移。迁移器优先识别当前 `plugins/BLWorldTrashCan` 目录中的旧结构；如果当前目录不是旧结构，则读取相邻旧目录 `plugins/WorldListTrashCan`。

可在 `config.yml` 调整：

```yaml
migration-enabled: true
migration-legacy-folder: "WorldListTrashCan"
```

- `migration-enabled`：是否允许迁移，默认 `true`。
- `migration-legacy-folder`：旧插件数据目录名或绝对路径，默认 `WorldListTrashCan`。
- 迁移完成后会生成 `legacy-migration-report.md`，后续启动看到该报告就不会重复迁移。
- 如果旧配置在当前 `plugins/BLWorldTrashCan` 目录，迁移前会先备份到 `legacy-migration-backup/`。
- 当前会自动迁移主配置、清理配置、通知配置、保护配置、实体限制配置、公共/个人/世界垃圾桶配置，以及旧 `data/data.yml` 中的世界垃圾桶运行数据。
- 当前不能自动承接的旧字段会写入报告的“需要人工确认字段”，例如公共垃圾桶 GUI 的 `ModelId`、BossBar 倒计时具体文本等。

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
/blwtc debugdamage <玩家> <Material> <数量>
/blwtc debugstock
/blwtc debugsummary <玩家>
/blwtc debugplayer <玩家> <dropmode|look|ban|globalban>
```

`debugworldtrash` 会在玩家附近创建并登记一个测试箱子，`debugdrop` 会生成真实掉落物，`debugdamage` 会生成真实掉落物并通过正式事件总线模拟岩浆损坏回收，`debugroute` 会向指定垃圾桶写入测试物品，`debugstock` 会在不要求玩家在线的情况下输出当前公共垃圾桶库存，`debugplayer` 会用真实在线 `Player` 对象触发玩家入口和 GUI；除 `debugstock` 外它们都会改变测试服运行态，只用于验收，不是普通玩家功能。

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

- Bukkit 1.13-1.15 产物提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- Paper 1.16-1.20 产物提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- Folia 1.20 产物提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- Legacy 1.12 产物当前不注册 PAPI 变量。

发包变量：

- 当前不提供 CoreBridge / EasyCore / 龙核 / 萌芽发包变量。

## 验证记录

本机 `mvn` 不在 PATH，本轮使用 `javac` 手工编译并用 JDK 21 `jar.exe` 打包。跨版本构建必须按目标运行时指定 `--release`：1.12 Legacy 与 Bukkit 1.13-1.15 相关模块使用 `--release 8`，现代 Paper 和 Folia 产物使用 `--release 17`，否则旧 Java 8 测试服会出现 `UnsupportedClassVersionError`。

已通过：

- `core -> config -> storage -> shared-bukkit -> platform-legacy -> platform-bukkit -> platform-paper -> platform-folia -> plugin-legacy -> plugin-bukkit -> plugin-paper -> plugin-folia`
- `CorePolicySelfTest passed`
- 1.12.2 测试服加载 `BLWorldTrashCan v0.1.0-SNAPSHOT`
- Legacy 1.12 产物主类 class major version 为 52，确认面向 Java 8；jar 内 `platform.yml` 目标为 `legacy-1.12`。
- Bukkit 1.13-1.15 产物主类 class major version 为 52，确认面向 Java 8。
- Paper 1.16-1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Folia 1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Paper 1.20.4 测试服加载 `BLWorldTrashCan v0.1.0-SNAPSHOT`，`platform` 显示 `paper-1.16-1.20`，`stats` 和 `clear` 正常返回。
- `Material.STAINED_GLASS_PANE` 跨版本修复后，Legacy 1.12 测试服重新加载新产物，`platform` 显示 `legacy-1.12`，`stats` 和 `clear` 正常返回。
- 旧配置迁移器已在独立 `paper-1.12.2-migration-test-server` 验证相邻旧目录和当前插件目录旧结构两种入口；迁移后 `legacy-migration-report.md`、`legacy-migration-backup/`、`trash.yml`、`cleanup.yml`、`data/worlds.yml` 均符合预期。
- Bukkit 1.13.2 测试服加载当前 Folia 保护构建后的 `BLWorldTrashCan-bukkit-1.13-1.15.jar`，`platform` 显示 `bukkit-1.13-1.15`，`stats` 和 `clear` 正常返回，确认共享清理保护没有误伤普通 Bukkit 世界扫描。
- Folia 1.20.1 测试服首轮执行 `/blwtc clear` 暴露 global thread 扫描实体的 region 线程错误；当前版本已改为 Folia 未声明 `FOLIA_REGION_SAFE` 时关闭定时世界扫描，并让 `/blwtc clear` 明确拒绝执行，复测未再出现该异常。
- 世界垃圾桶默认不再写入未加载区块；`paper-1.13.2-test-server` 用远处未加载区块坐标验证，清理日志出现 `worldTrashSkippedUnloadedChunks=1`，掉落物降级进入公共垃圾桶，未强制访问远处箱子。
- RCON 验证 `platform`、`stats`、`debugstock`、`debugsummary`、`debugworldtrash`、`debugroute`、`debugdrop`、`clear`、`debugopen`、`debugplayer`
- `client-1.12.2` 真实玩家 `AIAutoTest` 进服后执行玩家入口和 GUI 打开测试
- 旧功能补齐验证覆盖：防丢弃模式、look 查询、单世界黑名单 GUI、公共黑名单 GUI、聊天/命令限频、不可拾取箭矢清理、防踩踏农田、经验球/实体清理、实体白名单/黑名单、世界实体数量限制、密集实体限制、公共垃圾桶日志、公共垃圾桶按清理次数刷新、定时清理倒计时通知
- 世界垃圾桶强制加载区块问题已按 `docs/世界垃圾桶区块加载性能方案.md` 落地默认保护；`world-trash.allow-load-unloaded-chunks` 默认 `false`，真实测试服已验证未加载区块会被跳过并降级路由。
- 旧插件仙人掌/岩浆损坏回收的 `UseModel/Delay` 已自动迁移为 `personal-trash.damage-recovery.mode/delay-seconds`，默认仍为关闭，开启后只在短时间内追踪玩家主动丢弃物，避免长期占用内存；后台测试入口为 `/blwtc debugdamage <玩家> <Material> <数量>`。
- 仙人掌/岩浆损坏回收已在 `paper-1.12.2-test-server` 使用真实 Forge 1.12.2 客户端验证：客户端发送 `/blwtc debugdamage AIClientAlpha STONE 2`，服务端日志出现 `debugDamageRecovery ... recovered=true`，`/blwtc debugstock` 显示公共垃圾桶物品 `2`、堆叠 `1`。

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
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-smoke-rcon-smoke.log`
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-smoke-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-smoke-stop-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-resmoke-rcon-smoke-2.log`
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-resmoke-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-platforms-20260601-legacy-resmoke-rcon-stop.log`
- `paper-1.20.4-test-server/ai-blwtc-paper1204-smoke-rcon-main.log`
- `paper-1.20.4-test-server/ai-blwtc-paper1204-smoke-latest.log`
- `paper-1.20.4-test-server/ai-blwtc-paper1204-smoke-rcon-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-material-legacy-resmoke-20260602-042235-rcon-main.log`
- `paper-1.12.2-test-server/ai-blwtc-material-legacy-resmoke-20260602-042235-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-material-legacy-resmoke-20260602-042235-rcon-stop.log`
- `paper-1.12.2-migration-test-server/ai-blwtc-migration-adjacent-20260602-1726-rcon-main.log`
- `paper-1.12.2-migration-test-server/ai-blwtc-migration-adjacent-20260602-1726-latest.log`
- `paper-1.12.2-migration-test-server/ai-blwtc-migration-current-20260602-1732-rcon-main.log`
- `paper-1.12.2-migration-test-server/ai-blwtc-migration-current-20260602-1732-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-bukkit113-guard-resmoke-20260602-1830-java8-console.log`
- `paper-1.13.2-test-server/ai-blwtc-bukkit113-guard-resmoke-20260602-1830-java8-rcon-main.log`
- `paper-1.13.2-test-server/ai-blwtc-bukkit113-guard-resmoke-20260602-1830-java8-rcon-stop.log`
- `paper-1.13.2-test-server/ai-blwtc-bukkit113-guard-resmoke-20260602-1830-java8-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-bukkit113-guard-resmoke-20260602-1830-error.log`
- `folia-1.20.1-test-server/ai-blwtc-folia1201-smoke-20260602-1853-latest.log`
- `folia-1.20.1-test-server/ai-blwtc-folia1201-guarded-console-smoke-20260602-1803-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-rcon-main-2.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-trash-test.yml`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-worlds-test.yml`

已知测试环境噪声：

- 打开 GUI 时 EasyCore 会因缺少 `top.wcpe.wcneteasemodrpc.item.texture.match.TextureMatchs` 报 `InventoryOpenEvent` 异常；RCON 返回和 BLWorldTrashCan debug 日志均显示本插件 GUI 打开调用已执行。
- 测试服上其他前置插件存在 MythicMobs 版本兼容警告和 Druid/MySQL 连接超时日志；本轮日志未发现 BLWorldTrashCan 自身的 `UnsupportedClassVersionError`、`NoSuchMethodError`、`NoSuchFieldError` 或插件启用失败。
- Paper 1.13.2 不能使用默认 Java 21 启动，本轮误用 Java 21 时服务端输出 `Unsupported Java detected (65.0). Only up to Java 12 is supported.`；有效复测使用 `C:\Program Files\Java\jdk-1.8\bin\java.exe` 启动。
- Folia 1.20 当前只是安全拒绝危险世界扫描入口，并不等于已经完成 region-safe 清理；后续若要恢复 Folia 清理功能，必须把实体扫描改为 region/entity scheduler 分段执行后再做实服回归。
- `world-trash.allow-load-unloaded-chunks` 默认 `false` 会改变旧插件“远处真实箱子也尽量写入”的行为；这是为了避免后台清理同步加载区块。确实需要旧行为时可以改为 `true`，但会在启动时输出性能风险警告。
