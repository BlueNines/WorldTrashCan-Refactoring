# BLWorldTrashCan 重构实现

这是 `WorldListTrashCan` 的重构实现工作区。原项目只作为行为基准保留，新实现按“共同核心 + 多版本轻量产物 + 通用总包”拆分，避免把 Legacy、Paper、Folia 差异硬堆进同一个主类。

面向服主的新增功能说明见 [docs/重构版新增功能说明.md](docs/重构版新增功能说明.md)。

## 产物

- `dist/BLWorldTrashCan-legacy-1.12.jar`：Paper/Spigot 1.12.2 测试产物，已在 `paper-1.12.2-test-server` 启动验证。
- `dist/BLWorldTrashCan-bukkit-1.13-1.15.jar`：Bukkit/Spigot 1.13-1.15 产物，已在 `paper-1.13.2-test-server` 用 Java 8 完成启动 smoke 和 RCON 命令复测。
- `dist/BLWorldTrashCan-paper-1.16-1.20.jar`：现代 Paper 产物，已用真实原版客户端 F2 截图覆盖 1.16.5、1.17.1、1.18.2、1.19.4、1.20.4、1.21.4、外部 Paper 1.21.8 和外部 Paper 1.21.11 的 RGB 可见通道；文件名暂沿用重构阶段命名。
- `dist/BLWorldTrashCan-folia-1.20.jar`：Folia 1.20 产物，已在 `folia-1.20.1-test-server` 完成启动、region-safe 清理、Folia 专用实体限制和通知后台 smoke；世界实体扫描清理使用 Folia region/entity scheduler 分段执行，`/blwtc clear` 为异步启动语义。当前仍不声明整产物 `FOLIA_REGION_SAFE`，因为 BossBar/Title/Sound 等通知尚未做 Folia 客户端视觉验收，且命令通知允许服主配置任意控制台命令。
- `dist/BLWorldTrashCan-universal.jar`：通用总包，面向习惯“一个 jar 跨端切换”的服主；已完成四端 console smoke，并在 6 个外部服务端全部使用同一个 universal 整包完成真实客户端 RGB 三通道截图和基础功能回归，也已在 Paper 26.1.2 与 Spigot 26.1.2 使用同一个 universal 整包完成真实客户端 RGB 截图和 11 项基础功能复测。进阶用户仍可以继续使用上面四个轻量分版本 jar，减少包体和运行时选择逻辑。本轮 1.12.2-1.21.4 以及 26.1.2 RGB 截图矩阵以真实客户端 F2 截图为准，旧协议客户端证据不再作为玩家可见 RGB 的最终结论。

## 模块

- `bl-world-trashcan-core`：纯 Java 清理和路由决策，不依赖 Bukkit/Paper/Folia。
- `bl-world-trashcan-config`：拆分配置加载和类型化配置。
- `bl-world-trashcan-storage`：世界垃圾桶存储模型。
- `bl-world-trashcan-shared-bukkit`：版本中立的 Bukkit 功能层、GUI、调度适配、路由服务。
- `bl-world-trashcan-platform-legacy-1_12`：1.12 平台能力、旧告示牌、无 PDC 物品标记，玩家掉落 owner 由短期运行态追踪补齐。
- `bl-world-trashcan-platform-bukkit-1_13_1_15`：1.13-1.15 平台能力、现代告示牌、无 PDC 物品标记，避免 1.13 缺 PDC API，玩家掉落 owner 由短期运行态追踪补齐。
- `bl-world-trashcan-platform-paper-1_16_1_20`：现代 Paper 平台能力、PDC 玩家掉落标记。
- `bl-world-trashcan-platform-folia-1_20`：Folia 平台能力、PDC 玩家掉落标记、Folia 全局与 region 调度入口。
- `bl-world-trashcan-plugin-legacy-1_12`：1.12 插件入口、命令和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-bukkit-1_13_1_15`：1.13-1.15 插件入口、Vault 和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-paper-1_16_1_20`：现代 Paper 插件入口、Vault 和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-folia-1_20`：Folia 插件入口、Folia 专用清理、Vault 和 PlaceholderAPI 适配。
- `bl-world-trashcan-plugin-universal`：通用总包入口，主类按 Java 8 编译，只在运行时延迟加载对应 platform/plugin 功能。

## 通用总包运行策略

`BLWorldTrashCan-universal.jar` 不是把四个平台差异写进一个巨大 `if-else` 主类，而是保留四套平台实现并在启动时选择：

- Folia：当 `Bukkit.getName()` 或 `Bukkit.getVersion()` 明确包含 `folia` 时，加载 `folia-1.20` 分支。
- 1.12.x：加载 `legacy-1.12` 分支。
- 1.13-1.15：加载 `bukkit-1.13-1.15` 分支。
- 1.16+ 现代 Paper/Spigot：加载 `paper-1.16-1.20` 分支。

通用总包主类、命令适配层和 Paper 现代分支保持 Java 8 class major 52，避免 1.16.5 服主常见 Java 8/17 运行环境出现 class major 不兼容。Folia 分支保持 Java 17 class 并只在 Folia 运行时延迟加载，避免 1.12.2 Java 8 服务端在启用阶段提前解析 Java 17 class。Paper 1.20.4 不能只因为存在 `getGlobalRegionScheduler` 就判定为 Folia；当前 Folia 判定只看服务端名称和版本文本中的 `folia` 标记。

## 配置文件

默认资源均带中文注释：

- `config.yml`：主配置占位和全局说明。
- `cleanup.yml`：后台清理周期、忽略世界、物品保护、实体清理规则、清理倒计时通知。
- `trash.yml`：世界垃圾桶、公共垃圾桶、个人垃圾桶配置。
- `platform.yml`：版本能力说明。
- `entity-limits.yml`：世界实体数量限制和密集实体限制。
- `protections.yml`：聊天/命令限频、防丢弃模式、不可拾取箭矢清理、防踩踏农田。
- `messages/message_zh.yml`：简体中文消息。
- `messages/message_zh_TW.yml`：繁体中文消息。
- `messages/message_en.yml`：英文消息。
- `messages/message_es.yml`：西班牙语消息。
- `data/worlds.yml`：世界垃圾桶运行数据。

## 消息与语言

`config.yml` 的 `language` 指定 `plugins/BLWorldTrashCan/messages/` 下的语言文件名，默认 `message_zh.yml`。插件会在启动或重载时保存 jar 内自带语言文件；如果旧服已有外部语言文件且缺少新节点，正式玩家文案会继续回退到 jar 内默认节点，避免升级后命令、GUI 或提示变成空白。

当前已外置的正式玩家文案包括：主命令、帮助、平台能力、统计、add 命令、公共/个人垃圾桶、个人垃圾桶自动回收提示、世界垃圾桶创建/移除、黑名单 GUI、防丢弃模式、look 查询和手持物品/区块实体查询。后台 `debug*` 测试命令仍保留内部中文调试文案，用于验收夹具，不作为普通玩家语言包范围。

## RGB 与富文本消息

重构版使用 PrismaticAPI `1.5.2` 作为统一富文本渲染库，依赖从 `https://croabeast.github.io/repo/` 获取，并在四个平台产物中 shade 后 relocation 到 `pixeltech.bluenine.blworldtrashcan.libs.croabeast`，避免与服务器上其它插件的 PrismaticAPI 版本冲突。打包时会过滤 PrismaticAPI 自带 `plugin.yml`，最终插件名仍为 `BLWorldTrashCan`。

正式消息入口统一走 `RichTextRenderer`，包括普通 Chat、可点击 Chat、ActionBar、Title、BossBar 标题、GUI 标题和平台层 `sendMessage(UUID, message)`。1.16.5+ 服务端可以使用 `&#RRGGBB` 这类 RGB 写法；1.12.2 和 1.13-1.15 会自动降级为传统 `&` 颜色码，不要求真实 RGB。

本轮 RGB 视觉验收使用真实原版客户端生成的 F2 截图，不用服务端日志或协议抓包替代截图结论。`/blwtc debugrgb <玩家>` 会向在线玩家发送 Chat、ActionBar、Title、Subtitle、BossBar、GUI 标题、物品名和 Lore 八个可见通道；截图矩阵覆盖 Paper 1.12.2、1.13.2、1.14.4、1.15.2、1.16.5、1.17.1、1.18.2、1.19.4、1.20.4 和 1.21.4。其中 1.12.2-1.15.2 为传统颜色降级证据，1.16.5-1.21.4 为 RGB 视觉证据。可提交截图证明保留在 `docs/test-evidence/rgb-visual-proof-20260607-104606/`，本机原始运行缓存保留在 `build/rgb-visual-matrix/runs/rgb-visual-proof-20260607-104606/`，汇总文件为 `build/rgb-visual-matrix/latest-visual-proof.json`。

外部服务端补充矩阵同样使用真实客户端 F2 截图，并额外校验 `blwtc platform` 确实被插件接收，避免把插件未加载或命令未注册误判为通过。覆盖 `E:\server_work\server_1.21.8_0`、`E:\server_work\server_cat_1.12.2`、`E:\server_work\folia1.21.8`、`E:\server_work\1.21.11spigot`、`E:\server_work\1.21.11arclight-neoforge` 和 `E:\server_work\1.20.1fabric.banner`，6/6 PASS。可提交截图和日志证据保留在 `docs/test-evidence/rgb-external-server-proof-20260607-155341/`。

同一批外部服务端已补做 universal 整包复测，6 个端全部部署 `BLWorldTrashCan-universal.jar`。本轮 RGB 截图限定聊天框、ActionBar、Title/Subtitle，不再使用箱子 GUI、物品名或 Lore 作为颜色证据；每个端同时执行 `reload`、世界垃圾桶创建、公共/个人/世界路由、损坏回收、玩家掉落 owner、手动清理、摘要、公共/个人 GUI 打开共 11 项基础功能检查，全部 PASS。可提交证据目录：`docs/test-evidence/rgb-universal-channels-proof-20260607-175511/`。

针对上一轮 Title 颜色接近传统 `&a`、`&6` 的人工观感问题，已再次使用高辨识度 RGB 文案重测同一批外部服务端。Title 改为多段 `RGB TITLE FF1493`，分别使用 `#FF1493`、`#00E5FF`、`#BAFF00`，Subtitle 使用 `#7B2CFF` 与 `#FF4F00`；聊天框和 ActionBar 也显示 `RGB-FF1493`、`RGB-00E5FF`、`RGB-BAFF00`、`RGB-7B2CFF`、`RGB-FF4F00` 文本标记。6 个端仍全部使用同一个 `BLWorldTrashCan-universal.jar`，截图、日志和基础功能证据保留在 `docs/test-evidence/rgb-universal-highcontrast-channels-proof-20260607-202234/`。

26.1.2 兼容验收已补充 Paper 与 Spigot 两端：Paper 26.1.2 build 69 通过 Paper fill API 获取服务端 jar，Spigot 26.1.2 通过 BuildTools 使用 Java 25 构建。两个测试服都部署同一个 `BLWorldTrashCan-universal.jar`，使用真实原版 26.1.2 客户端 F2 截图验证 Chat、ActionBar、Title/Subtitle RGB，同时执行 `reload`、世界垃圾桶创建、公共/个人/世界路由、损坏回收、玩家掉落 owner、手动清理、摘要、公共/个人 GUI 打开共 11 项基础功能检查，全部 PASS。可提交证据目录：`docs/test-evidence/rgb-26-1-spigot-paper-proof-20260608-005225/`。

BossBar 需要区分两类颜色：BossBar 标题文本可以按上面的富文本规则渲染；BossBar 条本身的颜色仍受 Bukkit `BarColor` 枚举限制，不支持任意 `#RRGGBB`。

PrismaticAPI 当前在 Modrinth 标注为 `GPL-3.0-only`。本项目接入它的前提是基础版后续按开源要求发布；如果未来要制作不满足 GPL-3.0-only 要求的闭源发行版，需要重新评估为外置软依赖或更换许可证兼容的 RGB 库。

## 个人垃圾桶回收提示

`trash.yml` 可配置物品自动进入个人垃圾桶时是否提醒在线玩家，以及批量提示最多展示多少个完整物品条目：

```yaml
personal-trash:
  notify:
    enabled: true
    max-display-items: 3
```

`messages/message_*.yml` 的 `personal-trash.recycle` 控制提示格式。`single` 用于仙人掌、岩浆、虚空等单个掉落实体损坏回收；`batch` 用于 `/blwtc clear` 或后台扫地这种一次清理多个掉落物的批量提示。`{items}` 是完整物品列表占位符，由 `list/separator/item/item-single/ellipsis` 组合生成。

```yaml
personal-trash:
  recycle:
    single: "{prefix}&a已回收到个人垃圾桶: {items}"
    batch: "{prefix}&a本次清理已回收到个人垃圾桶: {items}"
    list: "&7[{items}&7]"
    separator: "&7, "
    item: "&f{name}&7*&f{amount}"
    item-single: "&f{name}"
    ellipsis: "&7..."
```

默认 `max-display-items: 3` 时，未超过 3 类物品会完整显示，例如 `[STONE*5, COBBLESTONE*30, DIRT]`；超过 3 类物品时会追加省略标记，例如 `[STONE*5, COBBLESTONE*30, DIRT, ...]`。

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
- 公共垃圾桶 GUI 的旧 `ModelId` 会迁移到 `global-trash.gui.*-model-id`；低版本没有 `CustomModelData` API 时会自动忽略外观字段，不影响 GUI 打开。
- 旧 `BossBarFlag` 和 `BossBarMessageForCount` 会迁移到 `bossbar.enabled` 与 `bossbar.messages`，格式仍为 `剩余秒数;内容;样式;颜色`。
- 当前不能自动承接的旧字段会写入报告的“需要人工确认字段”。

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
/blwtc add <世界名> <数量>
/blwtc reload
```

Folia 产物中 `/blwtc clear` 会启动异步 region-safe 清理；命令返回只表示清理任务已提交，最终统计以后台 `[FoliaCleanup]` 日志或后续 `/blwtc stats` 为准。

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

`debugworldtrash` 会在玩家附近创建并登记一个测试箱子，`debugdrop` 会生成带拾取延迟的真实掉落物，`debugdamage` 会生成真实掉落物并通过正式事件总线模拟岩浆损坏回收，`debugroute` 会向指定垃圾桶写入测试物品，`debugstock` 会在不要求玩家在线的情况下输出当前公共垃圾桶库存，`debugplayer` 会用真实在线 `Player` 对象触发玩家入口和 GUI；除 `debugstock` 外它们都会改变测试服运行态，只用于验收，不是普通玩家功能。

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

- Legacy 1.12、Bukkit 1.13-1.15、Paper 1.16-1.20、Folia 1.20 四个产物的代码都提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- Legacy 1.12、Bukkit 1.13.2、Paper 1.20.4 已使用 PlaceholderAPI 2.11.6 实服验证；Folia 需要服务器安装支持 Folia 的 PlaceholderAPI，本机 PlaceholderAPI 2.11.6 会被 Folia 拒绝加载，不能作为 Folia PAPI 验收前置。

发包变量：

- 当前不提供 CoreBridge / EasyCore / 龙核 / 萌芽发包变量。

## bStats

插件已接入 bStats，沿用旧插件 serviceId `24350`。

统计项：

- `players`：当前在线玩家数。
- `servers`：固定上报 `1`。
- `players_and_servers`：同时上报 `servers` 与 `players`。
- `platform`：当前重构产物标识，例如 `legacy-1.12`、`bukkit-1.13-1.15`、`paper-1.16-1.20`、`folia-1.20`。

bStats 使用官方全局配置 `plugins/bStats/config.yml`，本插件不提供单独统计开关，也不会创建第二套统计配置。bStats 官方模板保留全局 `enabled` 关闭项；不能通过修改 Metrics 类绕过或隐藏该 opt-out，否则不符合 bStats 使用规则。

2026-06-08 已用 `BLWorldTrashCan-universal.jar` 的 `7.0.0` 构建完成 bStats 端到端验证：服务端加载 `BLWorldTrashCan v7.0.0`，bStats 上报包包含 `"pluginVersion":"7.0.0"` 和 `"id":24350`，bStats 返回响应；等待 bStats 页面半点刷新后，[WorldTrashCan / 24350](https://bstats.org/plugin/bukkit/WorldTrashCan/24350) 的 `Plugin Version` 图表出现 `7.0.0`，数量 `1`。证据目录：`docs/test-evidence/bstats-7.0.0-proof-20260608-062316/`。

## 验证记录

本机 `mvn` 不在 PATH，本轮使用 `javac` 手工编译并用 JDK 21 `jar.exe` 打包。跨版本构建必须按目标运行时指定 `--release`：1.12 Legacy 与 Bukkit 1.13-1.15 相关模块使用 `--release 8`，现代 Paper 和 Folia 产物使用 `--release 17`，否则旧 Java 8 测试服会出现 `UnsupportedClassVersionError`。

已通过：

- `core -> config -> storage -> shared-bukkit -> platform-legacy -> platform-bukkit -> platform-paper -> platform-folia -> plugin-legacy -> plugin-bukkit -> plugin-paper -> plugin-folia`
- `CorePolicySelfTest passed`
- 最终产物大小：Legacy `197741` bytes，Bukkit `199286` bytes，Paper `199758` bytes，Folia `257818` bytes。
- 1.12.2 测试服加载 `BLWorldTrashCan v0.1.0-SNAPSHOT`
- Legacy 1.12 产物主类 class major version 为 52，确认面向 Java 8；jar 内 `platform.yml` 目标为 `legacy-1.12`。
- Bukkit 1.13-1.15 产物主类 class major version 为 52，确认面向 Java 8。
- Paper 1.16-1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Folia 1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Paper 1.20.4 测试服加载 `BLWorldTrashCan v0.1.0-SNAPSHOT`，`platform` 显示 `paper-1.16-1.20`，`stats` 和 `clear` 正常返回。
- `Material.STAINED_GLASS_PANE` 跨版本修复后，Legacy 1.12 测试服重新加载新产物，`platform` 显示 `legacy-1.12`，`stats` 和 `clear` 正常返回。
- 旧配置迁移器已在独立 `paper-1.12.2-migration-test-server` 验证相邻旧目录和当前插件目录旧结构两种入口；迁移后 `legacy-migration-report.md`、`legacy-migration-backup/`、`trash.yml`、`cleanup.yml`、`data/worlds.yml` 均符合预期。
- Bukkit 1.13.2 测试服加载当前 Folia 保护构建后的 `BLWorldTrashCan-bukkit-1.13-1.15.jar`，`platform` 显示 `bukkit-1.13-1.15`，`stats` 和 `clear` 正常返回，确认共享清理保护没有误伤普通 Bukkit 世界扫描。
- Folia 1.20.1 测试服首轮执行 `/blwtc clear` 暴露 global thread 扫描实体的 region 线程错误；当前版本已改为 Folia 专用清理 Feature，通过 `RegionScheduler` 扫描已加载 chunk，通过实体调度删除物品，控制台 `summon` 4 个圆石掉落物后执行 `/blwtc clear`，日志输出 `worlds=3, itemsRouted=4`，`/blwtc stats` 显示公共垃圾桶物品 `4`、堆叠 `1`。
- Folia 产物已接入专用 `FoliaEntityLimitFeature`：单世界实体上限用 `EntityAddToWorldEvent` / `EntityRemoveFromWorldEvent` 维护数量缓存并用 region-safe 复算兜底，密集实体限制只扫描当前 chunk；`folia-1.20.1-test-server` 验证 PIG 第二次生成被 `current=1, max=1` 拦截，COW 密集限制移除 `1` 个实体。
- Folia 专用清理已补齐通知触发：短间隔后台 smoke 验证 Chat 控制台日志、完成后 `-1/-2` 提示、Command 通知和 `[FoliaCleanup]` 汇总均会输出；玩家可见的 ActionBar、BossBar、Title、Sound 在代码中改为提交到玩家实体 scheduler，但本机没有 Folia 1.20 客户端验收资产，尚未做视觉验收。
- 四个平台默认 `cleanup.yml` 的 `notify.*` 已补回旧配置里的清理后 `-1/-2` 提醒：Chat、ActionBar、BossBar、Title 都默认包含“公共垃圾桶未刷新/已刷新”两类消息；包内检查确认四个 dist jar 的 `cleanup.yml` 均包含这些条目。
- 世界垃圾桶默认不再写入未加载区块；`paper-1.13.2-test-server` 用远处未加载区块坐标验证，清理日志出现 `worldTrashSkippedUnloadedChunks=1`，掉落物降级进入公共垃圾桶，未强制访问远处箱子。
- RCON 验证 `platform`、`stats`、`debugstock`、`debugsummary`、`debugworldtrash`、`debugroute`、`debugdrop`、`clear`、`debugopen`、`debugplayer`
- `client-1.12.2` 真实玩家 `AIAutoTest` 进服后执行玩家入口和 GUI 打开测试
- 旧功能补齐验证覆盖：防丢弃模式、look 查询、单世界黑名单 GUI、公共黑名单 GUI、聊天/命令限频、不可拾取箭矢清理、防踩踏农田、经验球/实体清理、实体白名单/黑名单、世界实体数量限制、密集实体限制、公共垃圾桶日志、公共垃圾桶按清理次数刷新、定时清理倒计时通知
- 世界垃圾桶强制加载区块问题已按 `docs/世界垃圾桶区块加载性能方案.md` 落地默认保护；`world-trash.allow-load-unloaded-chunks` 默认 `false`，真实测试服已验证未加载区块会被跳过并降级路由。
- 旧插件仙人掌/岩浆损坏回收的 `UseModel/Delay` 已自动迁移为 `personal-trash.damage-recovery.mode/delay-seconds`，默认仍为关闭，开启后只在短时间内追踪玩家主动丢弃物，避免长期占用内存；后台测试入口为 `/blwtc debugdamage <玩家> <Material> <数量>`。
- 仙人掌/岩浆损坏回收已在 `paper-1.12.2-test-server` 使用真实 Forge 1.12.2 客户端验证：客户端发送 `/blwtc debugdamage AIClientAlpha STONE 2`，服务端日志出现 `debugDamageRecovery ... recovered=true`，`/blwtc debugstock` 显示公共垃圾桶物品 `2`、堆叠 `1`。
- 公共垃圾桶 GUI `ModelId` 和 BossBar 旧配置已补齐：四个平台默认 `trash.yml` 增加 `global-trash.gui.back/next/background-model-id`，默认 `cleanup.yml` 的 `notify.bossbar.messages` 增加 BossBar 消息；迁移器不再把这些字段列为人工确认。
- BossBar 已在 `paper-1.12.2-test-server` 用真实 Forge 1.12.2 客户端在线验证：真实玩家 `babyZiXuan` 在线时，RCON 执行 `/blwtc clear` 成功，短间隔自动清理连续输出 `AI BossBar smoke 2/1/done`，日志未发现 BLWorldTrashCan 自身异常。测试后已恢复临时 `cleanup.yml`。
- Legacy 1.12 产物已补齐旧 `%Wtc_ClearTime%` PAPI 变量注册逻辑，已在 `paper-1.12.2-test-server` 安装 PlaceholderAPI 2.11.6 时验证：`papi parse --null %Wtc_ClearTime%` 返回 `296`，日志出现 `Successfully registered internal expansion: Wtc` 和 `[BLWorldTrashCan] [PlaceholderAPI] 已注册变量: %Wtc_ClearTime%`。
- Bukkit 1.13.2 和 Paper 1.20.4 已补做 `%Wtc_ClearTime%` PAPI 验证：`papi parse --null %Wtc_ClearTime%` 分别返回 `315`、`333`；`plugins` 均显示 `BLWorldTrashCan` 和 `PlaceholderAPI` 已启用。
- Folia 1.20.1 尝试安装本地 PlaceholderAPI 2.11.6 验证 PAPI 时，Folia 在加载阶段拒绝该前置，原因是 `PlaceholderAPI v2.11.6` 未声明支持 Folia；BLWorldTrashCan 因未检测到 PlaceholderAPI 正常跳过变量注册。本轮已将该临时 PAPI jar 改名为 disabled，避免污染后续 Folia 测试。
- 旧命令 `/WorldListTrashCan add [世界名] <数量>` 已在新命令 `/blwtc add <世界名> <数量>` 中恢复控制台指定世界路径；`paper-1.12.2-test-server` 通过 RCON 验证 `blwtc add world 1` 成功、`blwtc add missing_world 1` 提示世界不存在、控制台 `blwtc add 1` 提示必须指定世界名，并确认 `data/worlds.yml` 落盘为 `world.max-count: 4`。
- 多语言消息服务已接入四个平台产物并完成 Legacy 1.12 smoke：临时把测试服 `plugins/BLWorldTrashCan/config.yml` 的 `language` 改为 `message_en.yml`，启动后日志显示 `[Message] 已加载语言文件: messages/message_en.yml`，RCON 执行 `blwtc reload/help/platform/stats` 均返回英文文案；测试后 config 已恢复为 `message_zh.yml`，日志和生成的语言文件保留。
- Legacy 1.12 命令类已补齐公共/个人垃圾桶打开权限校验：`global/globaltrash/trash` 同时接受 `blworldtrashcan.global.open` 与旧权限 `WorldListTrashCan.GlobalTrashOpen`，`personal/playertrash` 同时接受 `blworldtrashcan.personal.open` 与旧权限 `WorldListTrashCan.PlayerTrash`。本轮 1.12.2 smoke 验证 `WorldListTrashCan`、`WTC`、`wtc` 兼容入口可用，控制台打开 GUI 分支仍返回“该命令只能由玩家执行”，且日志未发现 BLWorldTrashCan 自身异常。
- 公共黑名单 GUI 保存后已改为即时刷新运行期配置：关闭 `/blwtc globalban` GUI 保存 `trash.yml` 后会调用插件自身 reload 流程，立即刷新 `ConfigBundle`、公共垃圾桶黑名单和路由服务；四个平台语言文件的保存提示已从“需要 reload”改为“已立即生效”。本轮重新打包四个平台，并在 1.12.2 测试服验证新 Legacy jar 正常加载、`platform/stats/reload` 正常返回。
- 旧配置 `Set.ClearEntity.Flag` 已补齐迁移到 `cleanup.yml` 的 `entities.enabled`，默认值为 `true`。关闭该总开关时，经验球、怪物、动物、投射物和实体黑名单都会整体跳过；`CorePolicySelfTest` 已覆盖关闭语义，1.12.2 测试服验证新 Legacy jar 正常加载、`platform/stats/reload/clear` 与 `%Wtc_ClearTime%` 正常返回。
- 公共/个人垃圾桶 GUI 取出、放入物品的权限检查已恢复旧插件 OP 旁路：现在同时接受 OP、新权限节点和旧权限节点；Legacy jar 字节码已确认 `GlobalTrashService` 与 `PersonalTrashService` 均包含 `Player.isOp()` 分支，1.12.2 测试服 smoke 验证新 jar 正常加载、`platform/stats/reload/clear` 正常返回。
- 四个平台命令类已补齐旧插件 OP 旁路：`reload/clear/add/debug*` 走 OP 或 `blworldtrashcan.admin`，`global/personal/dropmode/look/ban/globalban` 走 OP 或对应新旧权限节点。四个 jar 的命令 class 字节码均确认包含 `CommandSender.isOp()` 分支；1.12.2 测试服 RCON smoke 验证 `blwtc platform`、旧入口 `WorldListTrashCan platform`、`stats/reload/clear/add`、`%Wtc_ClearTime%` 和 `debugstock` 正常返回。本轮未做真实玩家负向权限测试，玩家专属 OP 分支以源码和最终 jar 字节码为证据。
- 世界垃圾桶 `/blwtc add <世界名> <数量>` 写入的 `data/worlds.yml` 上限现在会参与正式创建限制：`WorldTrashRouter` 使用单世界运行数据计算有效上限，告示牌创建的 OP 路径会按旧插件行为绕过数量上限。1.12.2 测试服使用真实 Forge 客户端 `AIClientAlpha` 进服后，RCON 通过在线 `Player` 对象连续执行 `debugworldtrash`：上限 5 时新增到 5 成功、第 6 个失败；执行 `blwtc add world 1` 后上限变 6，再新增 1 个成功、第 7 个失败。最终 `data/worlds.yml` 落盘为 `world.max-count: 6` 且 6 个位置，窄匹配未发现 BLWorldTrashCan 自身异常。
- 旧配置 `Set.PersonalTrashCan.NoWorldTrashCanEnterPersonalTrashCan` 迁移到 `personal-trash.track-player-dropped-items` 后，Legacy/Bukkit 这类无 PDC 平台现在会用短期运行态 owner 追踪补齐普通清理路由；Paper/Folia 仍优先使用 PDC，并用同一追踪器兜底。1.12.2 测试服临时清空 `world` 的世界垃圾桶登记后，真实客户端 `AIClientAlpha` 在线执行：`debugdrop AIClientAlpha STONE 2 owner` 后 `/blwtc clear` 显示回收 2 个物品、个人路由 1 个堆叠，`debugsummary` 显示个人垃圾桶物品 `2`；未带 owner 的 `debugdrop AIClientAlpha COBBLESTONE 3` 对照用例进入公共垃圾桶，个人桶保持 `2`。测试后已恢复原 `data/worlds.yml`，窄匹配未发现 BLWorldTrashCan 自身异常。
- 个人垃圾桶自动回收提示已在 `paper-1.12.2-test-server` 用真实 `client-1.12.2` 客户端验证：`debugdamage babyZiXuan STONE 2` 后客户端收到 `已回收到个人垃圾桶: [STONE*2]`；三类 `debugdrop ... owner` 后 `/blwtc clear` 收到 `本次清理已回收到个人垃圾桶: [STONE*5, COBBLESTONE*30, DIRT]`；四类物品时按 `max-display-items: 3` 收到 `本次清理已回收到个人垃圾桶: [STONE*5, COBBLESTONE*30, DIRT, ...]`。RCON `debugsummary/stats` 同时确认世界/公共垃圾桶为 0，个人路由分别为 1、3、4 个堆叠；测试后已恢复临时 `config.yml`、`trash.yml`、`messages/message_zh.yml` 和 `data/worlds.yml`。
- Paper/Folia 的玩家掉落 owner 标记现在写在掉落实体 PDC 上，不再写入 `ItemStack` 的 `ItemMeta`，避免隐藏 PDC 破坏物品叠加；公共、个人、世界垃圾桶入库前会清理旧版本残留在 `ItemStack` 上的 `player_uuid` 标记。
- bStats 已合规接入四个平台产物：四个 jar 均包含 `Metrics.class` 和 `BStatsMetricsService.class`，四个平台入口均有 `BStatsMetricsService.start(...)` 与 `Metrics.shutdown()` 调用；Legacy/Bukkit/Paper 主类和 universal 内 Paper 分支均为 class major 52，Folia 主类为 class major 61。`paper-1.20.4-test-server` 验证新 Paper jar 正常加载，RCON `plugins` 显示 `BLWorldTrashCan` 和 `PlaceholderAPI`，`blwtc platform` 显示 `paper-1.16-1.20`，`blwtc stats` 正常返回；`plugins/bStats/config.yml` 保持官方全局配置且 `enabled: true`。窄匹配未发现 BLWorldTrashCan 或 bStats 异常。
- 7.0.0 版本构建已验证 5 个交付 jar 内 `plugin.yml` 均为 `version: 7.0.0`；`paper-1.20.4-test-server` 部署 universal 7.0.0 后，bStats serviceId `24350` 的服务端上报、返回响应和页面 `Plugin Version` 图表刷新均完成闭环。
- `/wtc reload` 已修复默认 yml 缺失时不会补回的问题：四个平台 `reloadPlugin()` 会先执行默认资源补齐，再读取配置和刷新功能模块。当前默认资源不再包含 `notify.yml`；清理通知已合并到 `cleanup.yml` 的 `notify.*` 区域。
- PrismaticAPI RGB 消息已完成构建和客户端侧视觉矩阵：四个平台 jar 均包含 relocation 后的 `pixeltech/bluenine/blworldtrashcan/libs/croabeast/prismatic/PrismaticAPI.class`，且不残留原始 `me/croabeast` 类。`/blwtc debugrgb <玩家>` 已用真实原版客户端 F2 截图验证 GUI 标题、聊天文本、物品名和 Lore 等可见内容。通过版本包括 Paper 1.12.2、1.13.2、1.14.4、1.15.2、1.16.5、1.17.1、1.18.2、1.19.4、1.20.4 和 1.21.4；低版本为降级色，1.16.5+ 为 RGB。截图证据目录：`docs/test-evidence/rgb-visual-proof-20260607-104606/`。
- 默认中文文案已改为蓝、黄、灰、黑的 RGB 色板：冷蓝 `#38BDF8/#2563EB`，鎏金 `#F5B82E/#E7C873`，雾灰 `#D5DEE9/#AAB6C5/#64748B`，深墨 `#111827/#0F172A`。`/blwtc debugrgbchannels <玩家>` 已补齐到四个轻量产物和 universal 产物，用于只展示聊天栏、ActionBar、Title 的 RGB 通道。Paper 1.20.4 + 原版 1.20.4 客户端验证通过，截图证据目录：`docs/test-evidence/rgb-blue-gold-palette-20260608-072705/`。
- 通用总包 `BLWorldTrashCan-universal.jar` 已完成构建和四端 console smoke：同一个 jar 在 `paper-1.12.2-test-server` 识别为 `legacy-1.12`，在 `paper-1.13.2-test-server` 识别为 `bukkit-1.13-1.15`，在 `paper-1.20.4-test-server` 识别为 `paper-1.16-1.20`，在 `folia-1.20.1-test-server` 识别为 `folia-1.20`。通用总包内 PrismaticAPI 已 relocation，原始 `me/croabeast` 类数量为 0；主类 Java 8 加载 smoke 输出 `loaded-universal-main`。
- 2026-06-07 已补做真实客户端工作流回归，服务端日志只作为辅助排障，不作为最终通过依据。真实 Forge 1.12.2 客户端 `AIClientAlpha` 执行 30 条玩家侧聊天命令并写回 `status=PASS`，客户端断言覆盖 `platform/stats/reload`、旧别名、公共/个人/世界/黑名单 GUI、防丢弃模式、look、个人垃圾桶批量与单条提示、世界垃圾桶、三类路由和 `debugsummary`；`client-screen.log` 记录多个 `GuiChest, slots=90`，截图目录保留 32 张 PNG。
- 2026-06-07 已补做 universal 整包外部端三通道 RGB 复测：`E:\server_work` 下 6 个外部服务端全部部署同一个 `BLWorldTrashCan-universal.jar`，RGB 证据限定聊天框、ActionBar、Title/Subtitle 的真实客户端 F2 截图，不使用箱子 GUI 或物品 Lore；每端 11 项基础功能检查全部 PASS。截图总览和日志证据目录：`docs/test-evidence/rgb-universal-channels-proof-20260607-175511/`。
- 2026-06-07 已补做高辨识度 RGB 二次复测：上一轮颜色被指出接近传统 `&a`、`&6` 后，调试 Title 改为多段 RGB 的 `RGB TITLE FF1493`，Subtitle 改为 `SUBTITLE FF4F00`。同一批 6 个外部端全部使用 `BLWorldTrashCan-universal.jar` 重跑，RGB 截图仍限定聊天框、ActionBar、Title/Subtitle，每端 11 项基础功能检查全部 PASS。截图总览和日志证据目录：`docs/test-evidence/rgb-universal-highcontrast-channels-proof-20260607-202234/`。

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
- `folia-1.20.1-test-server/ai-blwtc-folia-region-cleanup-20260602-latest.log`
- `folia-1.20.1-test-server/ai-blwtc-folia-region-cleanup-20260602-commands.log`
- `folia-1.20.1-test-server/ai-blwtc-folia-entitylimit-20260602-final-latest.log`
- `folia-1.20.1-test-server/ai-blwtc-folia-entitylimit-20260602-final-commands.log`
- `folia-1.20.1-test-server/ai-blwtc-folia-notify-20260602-latest.log`
- `folia-1.20.1-test-server/ai-blwtc-folia-notify-20260602-commands.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-rcon-main-2.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-trash-test.yml`
- `paper-1.13.2-test-server/ai-blwtc-worldtrash-chunkguard-20260602-1835-worlds-test.yml`
- `paper-1.12.2-test-server/ai-blwtc-bossbar-20260602-rcon-start.log`
- `paper-1.12.2-test-server/ai-blwtc-bossbar-20260602-rcon-wait-client.log`
- `paper-1.12.2-test-server/ai-blwtc-bossbar-20260602-rcon-clear-online.log`
- `paper-1.12.2-test-server/ai-blwtc-bossbar-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-20260602-bossbar-modelid-smoke-backup/`
- `paper-1.12.2-test-server/ai-blwtc-legacy-papi-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-legacy-papi-20260602-final-latest.log`
- `paper-1.13.2-test-server/ai-blwtc-papi-bukkit113-20260602-rcon.log`
- `paper-1.20.4-test-server/ai-blwtc-papi-paper1204-20260602-rcon.log`
- `paper-1.20.4-test-server/ai-blwtc-bstats-20260603-rcon-main-2.log`
- `paper-1.20.4-test-server/ai-blwtc-bstats-20260603-final-latest-2.log`
- `folia-1.20.1-test-server/ai-blwtc-papi-folia1201-20260602-console.log`
- `folia-1.20.1-test-server/ai-blwtc-papi-folia1201-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-add-world-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-add-world-20260602-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-add-world-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-message-service-20260602-console.log`
- `paper-1.12.2-test-server/ai-blwtc-message-service-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-message-service-20260602-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-legacy-permission-20260602-console.log`
- `paper-1.12.2-test-server/ai-blwtc-legacy-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-legacy-permission-20260602-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-legacy-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-globalban-instant-20260602-console.log`
- `paper-1.12.2-test-server/ai-blwtc-globalban-instant-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-globalban-instant-20260602-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-globalban-instant-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-entity-toggle-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-entity-toggle-20260602-disabled-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-entity-toggle-20260602-disabled-rcon-2.log`
- `paper-1.12.2-test-server/ai-blwtc-entity-toggle-20260602-restore-stop-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-entity-toggle-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-trash-op-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-trash-op-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-command-op-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-blwtc-command-op-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-blwtc-effective-max-20260602-rcon-main.log`
- `paper-1.12.2-test-server/ai-blwtc-effective-max-20260602-rcon-add-then-create.log`
- `paper-1.12.2-test-server/ai-blwtc-effective-max-20260602-final-latest.log`
- `客户端自动化测试工作区/runs/20260602-blwtc-effective-max-live-player/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260602-blwtc-effective-max-live-player/logs/forge-latest-before-stop.log`
- `paper-1.12.2-test-server/ai-blwtc-drop-owner-20260603-retry-rcon-main.log`
- `paper-1.12.2-test-server/ai-blwtc-drop-owner-20260603-retry-rcon-control.log`
- `paper-1.12.2-test-server/ai-blwtc-drop-owner-20260603-retry-final-latest.log`
- `客户端自动化测试工作区/runs/20260603-blwtc-drop-owner-live-player-retry/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260603-blwtc-drop-owner-live-player-retry/logs/forge-latest-before-stop.log`
- `客户端自动化测试工作区/runs/20260602-blwtc-bossbar-real-client/control/client-response.properties`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-prismatic-20260605-032221/test-summary.md`
- `paper-1.20.4-test-server/ai-blwtc-rgb-prismatic-20260605-032221-paper1204-rconhex-rcon-main.log`
- `paper-1.13.2-test-server/ai-blwtc-rgb-prismatic-20260605-032221-bukkit113-final-rcon-main.log`
- `paper-1.12.2-test-server/ai-blwtc-rgb-prismatic-20260605-032221-legacy112-rcon-main.log`
- `folia-1.20.1-test-server/ai-blwtc-rgb-prismatic-20260605-032221-folia1201-latest.log`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/rgb-visual-proof-contact-sheet.png`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/screenshots/`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/summary.json`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-protocol-runs/final-20260607-rgb-matrix/summary.json`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-protocol-runs/final-20260607-rgb-matrix/summary-table.txt`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/universal-console-smoke-summary-20260607-012407.txt`
- `paper-1.12.2-test-server/ai-blwtc-universal-console-20260607-012407-legacy112.log`
- `paper-1.13.2-test-server/ai-blwtc-universal-console-20260607-012407-bukkit113.log`
- `paper-1.20.4-test-server/ai-blwtc-universal-console-20260607-012407-paper1204.log`
- `folia-1.20.1-test-server/ai-blwtc-universal-console-20260607-012407-folia1201.log`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/client-workflow-assertions.txt`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/logs/client-chat.log`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/logs/client-screen.log`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/logs/forge-latest.log`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/screenshots/runner_sequence_9.png`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/screenshots/runner_sequence_10.png`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/screenshots/runner_sequence_11.png`
- `客户端自动化测试工作区/runs/20260607-031912-blwtc-client-workflow-regression/screenshots/runner_sequence_12.png`

已知测试环境噪声：

- 打开 GUI 时 EasyCore 会因缺少 `top.wcpe.wcneteasemodrpc.item.texture.match.TextureMatchs` 报 `InventoryOpenEvent` 异常；RCON 返回和 BLWorldTrashCan debug 日志均显示本插件 GUI 打开调用已执行。
- 测试服上其他前置插件存在 MythicMobs 版本兼容警告和 Druid/MySQL 连接超时日志；本轮日志未发现 BLWorldTrashCan 自身的 `UnsupportedClassVersionError`、`NoSuchMethodError`、`NoSuchFieldError` 或插件启用失败。
- 通用总包 1.12.2 smoke summary 里的 `ErrorPattern=true` 来自测试服其它前置插件噪声；同轮日志中 BLWorldTrashCan 已正常启用，平台识别、命令和停服流程均有证据。
- 2026-06-07 真实客户端工作流回归中，Paper/Forge 日志只用于辅助定位；玩家可见功能结论以 `client-response.properties`、`client-workflow-assertions.txt`、`client-chat.log`、`client-screen.log` 和真实 PNG 截图为准。
- Paper 1.13.2 不能使用默认 Java 21 启动，本轮误用 Java 21 时服务端输出 `Unsupported Java detected (65.0). Only up to Java 12 is supported.`；有效复测使用 `C:\Program Files\Java\jdk-1.8\bin\java.exe` 启动。
- Folia 1.20 当前已经完成世界清理、专用实体限制和通知后台 smoke，但仍不等于整产物 `FOLIA_REGION_SAFE`；BossBar、Title、Sound 等通知尚未做 Folia 客户端视觉验收，密集实体限制为了避免跨 region 查询，目前只覆盖当前 chunk 内实体。
- 本机 PlaceholderAPI 2.11.6 不支持 Folia，Folia PAPI 变量仍需换用支持 Folia 的 PlaceholderAPI 前置后再验收；不能用普通 Paper 的 PAPI 验证结果替代 Folia。
- `world-trash.allow-load-unloaded-chunks` 默认 `false` 会改变旧插件“远处真实箱子也尽量写入”的行为；这是为了避免后台清理同步加载区块。确实需要旧行为时可以改为 `true`，但会在启动时输出性能风险警告。
- 1.12.2 控制台直接执行 `summon Zombie 0 64 0` 会返回 `Cannot summon the object out of the world`，因此本轮未用原版 summon 完成“关闭 `entities.enabled` 后实体仍保留”的运行态子用例；该语义已由核心自测和字节码检查覆盖。
