# WorldListTrashCan 重构实现

这是 `WorldListTrashCan` 的重构实现工作区。原项目只作为行为基准保留，新实现按“共同核心 + 多版本轻量产物 + 通用总包”拆分，避免把 Legacy、Paper、Folia 差异硬堆进同一个主类。

面向服主的简短数据对比见 [docs/新旧版本功能与性能数据对比.md](docs/新旧版本功能与性能数据对比.md)，完整新增功能说明见 [docs/重构版新增功能说明.md](docs/重构版新增功能说明.md)。`WorldListTrashCanAudit` 附属插件的设计评估见 [docs/实验性清理审计功能评估.md](docs/实验性清理审计功能评估.md)，主插件与附属插件的 API v2 稳定边界见 [docs/WorldListTrashCanAudit附属插件API契约.md](docs/WorldListTrashCanAudit附属插件API契约.md)；附属插件源码和最终客户端证据位于独立仓库 `待开发插件/WorldListTrashCanAudit`。

## 产物

- `dist/WorldListTrashCan-legacy-1.12.jar`：Paper/Spigot 1.12.2 测试产物，已在 `paper-1.12.2-test-server` 启动验证。
- `dist/WorldListTrashCan-bukkit-1.13-1.15.jar`：Bukkit/Spigot 1.13-1.15 产物，已在 `paper-1.13.2-test-server` 用 Java 8 完成启动 smoke 和 RCON 命令复测。
- `dist/WorldListTrashCan-paper-1.16-1.20.jar`：现代 Paper 产物，已用真实原版客户端 F2 截图覆盖 1.16.5、1.17.1、1.18.2、1.19.4、1.20.4、1.21.4、外部 Paper 1.21.8 和外部 Paper 1.21.11 的 RGB 可见通道；文件名暂沿用重构阶段命名。
- `dist/WorldListTrashCan-folia-1.20.jar`：Folia 1.20 产物，已在 Folia 测试服完成启动、region-safe 清理、Folia 专用实体限制和通知后台 smoke；世界实体扫描清理使用 Folia region/entity scheduler 分段执行，`/wtc clear` 为异步启动语义。清理通知的 Chat、ActionBar、BossBar、Title、Sound 和 Command 已补真实客户端专项截图与日志证据；当前仍不声明整产物 `FOLIA_REGION_SAFE`，因为 Command 通知允许服主配置任意控制台命令。
- `dist/WorldListTrashCan-universal.jar`：通用总包，面向习惯“一个 jar 跨端切换”的服主；已完成四端 console smoke，并在 6 个外部服务端全部使用同一个 universal 整包完成真实客户端 RGB 三通道截图和基础功能回归，也已在 Paper 1.12.2、Paper 26.1.2 与 Spigot 26.1.2 使用同一个 universal 整包完成真实客户端 RGB 截图、基础功能和完整功能矩阵复测。进阶用户仍可以继续使用上面四个轻量分版本 jar，减少包体和运行时选择逻辑。本轮 1.12.2-1.21.4 以及 26.1.2 RGB 截图矩阵以真实客户端 F2 截图为准，旧协议客户端证据不再作为玩家可见 RGB 的最终结论。

从当前版本起，对外名称统一为 `WorldListTrashCan`。正式长命令为 `/worldlisttrashcan`，简写为 `/wtc`；权限统一使用 `WorldListTrashCan.*`。不再声明 Bl 品牌命令、权限、数据目录迁移或前缀兼容。内部 Java 包名仍保留 `pixeltech.bluenine.blworldtrashcan`，它不属于服主可见接口。

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
- `world-list-trashcan-api`：Java 8 公开 API，仅包含清理审计、玩家线程调度和一级 `/wtc` 副指令契约，不是可放入 `plugins` 的 Bukkit 插件。

## 附属插件 API v2

五个主插件交付 Jar 都提供同一套 `pixeltech.worldlisttrashcan.api` API。附属插件通过 Bukkit `ServicesManager` 获取：

- `WorldListTrashCanAuditBridge`：注册唯一清理审计消费者，并把 GUI 回调切回普通 Bukkit 主线程或 Folia 玩家 EntityScheduler。API v2 额外传递不可变的精确去向和垃圾桶变更 DTO。
- `WorldListTrashCanCommandRegistry`：注册一级 `/wtc <副指令>`，自动接入权限过滤、常规帮助面板和 Tab 补全。

主插件内置命令和别名不可覆盖；附属插件禁用时，主插件会移除审计会话、命令、帮助、补全和处理器引用。未安装附属插件时使用空审计会话，不复制或序列化物品，也不会创建数据库线程。API v2 为旧 `recordItem(ItemStack)` 保留 Java 8 `default` 退化实现，避免旧附属出现 `AbstractMethodError`。完整线程、生命周期和 ClassLoader 契约见 [docs/WorldListTrashCanAudit附属插件API契约.md](docs/WorldListTrashCanAudit附属插件API契约.md)。

2026-07-22 使用同一个 `dist/WorldListTrashCan-universal.jar` 在 Paper 1.12.2、Spigot 26.1.2 和 Folia 1.21.8 隔离真实服务端完成“未安装附属插件”退化回归：三端均正常启动，`/wtc help` 不显示 `audit`，`/wtc clear true` 可执行，不生成 `WorldListTrashCanAudit` 目录，主插件 Jar 不含 SQLite/MySQL/MariaDB/Hikari 驱动。脚本为 `tools/rgb-visual-matrix/run_addon_api_no_addon_smoke.py`，通过证据为 `docs/test-evidence/addon-api-no-addon-smoke-20260722-183820/`。

2026-07-23 最终 API v2 与附属插件在 Folia 1.21.8 + 真实 1.21.8 客户端和 Paper 1.12.2 + 真实 Forge 1.12.2 客户端完成去向与旧库迁移验收。四种去向、世界桶创建者/坐标、个人/公共 FIFO 账本、系统清空、富元数据 Lore、复制和禁止交互均由客户端原图、SQLite、服务端日志及实际区块 NBT 交叉验证；最终 universal Jar SHA-256 为 `78f1139a72e04e65b8a9290251e6c0dd2cbda5f3b06876f0f1e74fa7e9ce17c8`。

## 通用总包运行策略

`WorldListTrashCan-universal.jar` 不是把四个平台差异写进一个巨大 `if-else` 主类，而是保留四套平台实现并在启动时选择：

- Folia/Luminol：优先探测运行时是否存在 `io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler` 或 `io.papermc.paper.threadedregions.RegionizedServer`；若类探测不可用，再按 `Bukkit.getName()` 或 `Bukkit.getVersion()` 中明确包含的 `folia`、`luminol` 文本兜底，加载 `folia-1.20` 分支并使用 region-threaded 安全调度。
- 1.12.x：加载 `legacy-1.12` 分支。
- 1.13-1.15：加载 `bukkit-1.13-1.15` 分支。
- 1.16+ 现代 Paper/Spigot：加载 `paper-1.16-1.20` 分支。

通用总包主类、命令适配层和 Paper 现代分支保持 Java 8 class major 52，避免 1.16.5 服主常见 Java 8/17 运行环境出现 class major 不兼容。Folia 分支保持 Java 17 class 并只在 Folia/Luminol 运行时延迟加载，避免 1.12.2 Java 8 服务端在启用阶段提前解析 Java 17 class。Paper 1.20.4 不能只因为存在 `getGlobalRegionScheduler` 就判定为 Folia；当前 region-threaded 判定优先使用 Folia/Luminol 实际运行时实现类，再用服务端名称和版本文本中的 `folia`、`luminol` 明确标记兜底。

启动日志里的 `Capability folia-region-safe: disabled` 是整产物能力声明，不代表 universal 没有选择 Folia/Luminol 分支。当前 Folia/Luminol 分支会使用 region-threaded 安全调度启动清理任务，但因为通知系统允许服主配置任意控制台 Command，不能把整个插件包承诺为完全 `FOLIA_REGION_SAFE`。2026-07-07 已在 `C:\Users\pc\Desktop\ai开发插件\luminol-26.1.2-test-server` 使用真实客户端验证通用总包会识别为 `folia-1.20 (universal)`，并完成 RGB、清理、路由和公共/个人 GUI 基础功能验收。

构建后需要把 Maven 最新 target 产物同步到 `dist`，因为真实测试脚本默认部署 `dist/WorldListTrashCan-universal.jar`：

```powershell
build\tools\apache-maven-3.9.9\bin\mvn.cmd -q clean package
py -3 tools\rgb-visual-matrix\sync_dist_jars.py
```

当前同步脚本会从根 `pom.xml` 读取版本号，并输出五个交付 jar 的 SHA256；`--dry-run` 可只比较 target 和 dist 是否一致。

同步后可运行以下脚本检查五个交付 jar 的包完整性，范围包含 `plugin.yml` 版本和主类、Java class major、默认资源、bStats 类与入口启动、插件配置无 bStats 关闭项、PrismaticAPI 重定位、正式权限声明，以及通用总包是否包含四个平台实现：

```powershell
py -3 tools\rgb-visual-matrix\check_dist_package_integrity.py
```

当前审计结果为 `version: 7.0.0`、`artifacts: 5`、`errors: 0`，当前 `dist/WorldListTrashCan-universal.jar` SHA256 为 `ee58dd0e1d4834087916b0920bf23e237a9bc5cfad6a74129ad8b1e658481635`。

公开品牌有独立审计脚本，检查源码文件名和内容、默认资源、文档、dist 文件名以及 jar 内 `plugin.yml`，防止重新出现旧品牌：

```powershell
py -3 tools\rgb-visual-matrix\check_brand_case.py
```

当前结果为 5 个 dist 产物全部通过，`errors: 0`；源码文件数量由脚本按当前 Git 跟踪内容动态统计。

`plugin.yml` 的源码和 dist 交付接口还有独立审计脚本，检查 5 个源码 `plugin.yml` 和 5 个 dist jar 内 `plugin.yml` 的稳定字段、softdepend、命令别名、13 个权限节点和默认值：

```powershell
py -3 tools\rgb-visual-matrix\check_plugin_yml_parity.py
```

当前审计覆盖 5 个源码 `plugin.yml`、5 个 dist `plugin.yml`、1 个主命令入口和 13 个权限节点，结果为 `errors: 0`。

更新当前 dist 后还需要运行以下脚本，确认 README、长期硬化清单和执行记录没有继续写着旧交付包 SHA：

```powershell
py -3 tools\rgb-visual-matrix\check_current_dist_hash_docs.py
```

当前审计覆盖 5 个 dist jar，结果为 `errors: 0`。

完整功能矩阵文档也有独立审计脚本，检查 `docs/重构版完整功能与测试矩阵.md` 中的功能 ID 是否从 F-001 起连续、当前是否至少覆盖到 F-092、历史 `SKIP` 项是否全部写明后续收敛，以及“当前仍未收敛的通用专项项”是否保持为“无”：

```powershell
py -3 tools\rgb-visual-matrix\check_function_matrix_doc.py
```

当前矩阵为 F-001 到 F-092 共 92 个功能项，2026-06-08 历史 25 个 `SKIP` 通用专项项均已在后续专项中写明收敛，结果为 `errors: 0`。

常规帮助、普通补全和 debug 帮助分离也有独立审计脚本，检查 Java fallback、一参 tab 补全、源码语言文件和 dist jar 内语言文件：`/wtc help` 只允许保留 `/wtc debughelp` 入口，空前缀 tab 补全只显示正式命令与 `debughelp`，具体 `debug*` 命令必须只出现在 `/wtc debughelp` 面板或输入 `debug` 前缀后的补全中：

```powershell
py -3 tools\rgb-visual-matrix\check_command_help_separation.py
```

当前审计覆盖 5 个命令类、16 个源码语言文件、5 个 dist jar 内 20 个语言文件，结果为 `errors: 0`。

正式命令入口有独立审计脚本，检查源码 `plugin.yml`、当前 dist jar 内 `plugin.yml` 和五个平台入口源码，确保只保留规范长命令 `worldlisttrashcan` 与简写别名 `wtc`：

```powershell
py -3 tools\rgb-visual-matrix\check_command_entrypoints.py
```

当前审计覆盖 5 个源码 `plugin.yml`、5 个 dist jar 和 5 个入口源码，结果为 `errors: 0`。

五个平台命令类还有一致性审计脚本，检查 legacy、bukkit、paper、folia、universal 的普通子命令列表、完整子命令列表、处理分支和关键补全值是否一致：

```powershell
py -3 tools\rgb-visual-matrix\check_command_parity.py
```

当前审计覆盖 5 个命令类，普通子命令 13 个、总子命令 25 个，结果为 `errors: 0`。

多语言消息文件还有键结构一致性审计脚本，检查四个平台源码语言文件和当前 `dist` jar 内语言资源，防止某个语言漏键、节点类型从列表变成文本，或通用总包语言资源与当前源码基准不同步：

```powershell
py -3 tools\rgb-visual-matrix\check_message_key_parity.py
```

当前审计覆盖 4 个源码平台、4 种语言、5 个 dist jar 内 20 个语言资源，基准消息键 91 个，结果为 `errors: 0`。

涉及世界垃圾桶或实体限制扫描时，还需要运行区块强加载防护审计，确认正式源码没有新增 `loadChunk`、实体限制 `getChunkAt` 前仍有 `isChunkLoaded` 保护、世界垃圾桶默认仍不会访问未加载区块：

```powershell
py -3 tools\rgb-visual-matrix\check_chunk_load_guards.py
```

当前审计覆盖 96 个正式 Java 源码文件，结果为 `errors: 0`。

涉及真实客户端、外部测试服或证据目录的测试脚本时，还需要运行破坏性操作防护审计，确认脚本不会删除测试服 `logs`、`world*`、`cache`、`assets`，也不会对 `E:\server_work` 等真实服务端目录执行递归删除：

```powershell
py -3 tools\rgb-visual-matrix\check_test_script_destructive_guards.py
```

当前审计覆盖 `tools/rgb-visual-matrix` 下 42 个 Python 脚本、21 个 `shutil.rmtree` 调用和 23 个 `unlink` 调用，结果为 `errors: 0`。

统一预检本身的数量和文档口径也有独立审计，防止新增审计后 README、长期清单或执行记录仍保留旧数字：

```powershell
py -3 tools\rgb-visual-matrix\check_delivery_audit_docs.py
```

当前审计读取 `run_delivery_audits.py` 的实际命令列表，确认 README、长期硬化缺口清单和重构执行记录均同步当前默认/完整预检数量，结果为 `errors: 0`。

交付前可用统一预检入口一次性执行当前全部后台审计；默认不跑 Maven，完整模式会额外执行 `mvn -q test`：

```powershell
py -3 tools\rgb-visual-matrix\run_delivery_audits.py
py -3 tools\rgb-visual-matrix\run_delivery_audits.py --with-maven-test
```

当前默认 21 项审计和完整 22 项审计均为 `failed: 0`。

## 配置文件

默认资源均带中文注释：

- `config.yml`：主配置占位和全局说明。
- `cleanup.yml`：后台清理周期、忽略世界、物品保护、实体清理规则、清理通知和控制台详细统计。
- `trash.yml`：世界垃圾桶、公共垃圾桶、个人垃圾桶配置。
- `platform.yml`：版本能力说明。
- `entity-limits.yml`：世界实体数量限制、低占用实体扫描器和密集实体限制。
- `protections.yml`：聊天/命令限频、防丢弃模式、不可拾取箭矢清理、防踩踏农田。
- `messages/message_zh.yml`：简体中文消息。
- `messages/message_zh_TW.yml`：繁体中文消息。
- `messages/message_en.yml`：英文消息。
- `messages/message_es.yml`：西班牙语消息。
- `data/worlds.yml`：世界垃圾桶运行数据。

提交前可运行以下脚本检查四个平台默认配置资源和现有 `dist` 交付 jar 内资源是否保留中文注释：

```powershell
py -3 tools\rgb-visual-matrix\check_resource_yaml_comments.py
```

当前审计覆盖 28 个源码默认配置资源、5 个 dist jar、35 个包内配置资源和 1197 个 YAML 键，结果为 `errors: 0`。

默认配置资源还需要保持四个平台与 `dist` 交付包的键结构一致，避免某个平台漏配置项但中文注释审计仍然通过：

```powershell
py -3 tools\rgb-visual-matrix\check_default_resource_key_parity.py
```

当前审计覆盖 28 个源码默认配置资源、35 个 dist 包内默认配置资源和 134 个基准键，结果为 `errors: 0`。

多语言消息文件不要求逐项中文注释，但要求键路径和节点类型一致；新增或调整任何 `messages/message_*.yml` 后必须运行：

```powershell
py -3 tools\rgb-visual-matrix\check_message_key_parity.py
```

当前审计覆盖 16 个源码语言文件和 20 个 dist 包内语言资源，结果为 `errors: 0`。

默认多语言消息需要保持当前蓝黄灰黑 RGB 色板，同时仍允许服主在外部语言文件里继续使用 `&a` 这类传统颜色。新增或调整 `messages/message_*.yml`、RGB 渲染器或低版本降级逻辑后必须运行：

```powershell
py -3 tools\rgb-visual-matrix\check_default_language_rgb_messages.py
```

当前审计覆盖 16 个源码默认语言文件、20 个 dist 包内默认语言文件和 `RichTextRenderer` 的 RGB 降级兼容逻辑，结果为 `errors: 0`。

## 公共垃圾桶 GUI 布局

`trash.yml` 的 `global-trash.gui.layout` 可以用单字符定义公共垃圾桶每页的行数、内容槽、翻页按钮和自定义动作按钮。布局支持 1-6 行，每行必须正好包含 9 个英文字母、数字或下划线；单页不会超过原版箱子 GUI 的 54 格限制，总容量仍由每页 `content` 槽数量和 `global-trash.max-pages` 共同决定。

```yaml
global-trash:
  gui:
    layout:
      position:
        - "xxxxxxxxx"
        - "xxxxxxxxx"
        - "xxxxxxxxx"
        - "xxxxxxxxx"
        - "xxxxxxxxx"
        - "abbbdbbbc"
      items:
        x:
          type: "content"
        a:
          type: "previous-page"
          model-id: -1
          material:
            - "ARROW"
          unavailable-item: "b"
          name: "&#5AC8FA上一页"
          lore:
            - "&#C9D4E2当前第 &#FFD166{page} &#C9D4E2页"
        b:
          type: "background"
          model-id: -1
          material:
            - "BLACK_STAINED_GLASS_PANE"
            - "STAINED_GLASS_PANE"
            - "GLASS_PANE"
          name: " "
          lore: []
        c:
          type: "next-page"
          model-id: -1
          material:
            - "ARROW"
          unavailable-item: "b"
        d:
          type: "actions"
          model-id: -1
          material:
            - "BOOK"
          name: "&#FFD166垃圾桶统计"
          lore:
            - "&#C9D4E2玩家: &f{player}"
            - "&#C9D4E2等级: &f%player_level%"
            - "&#79879C当前第 {page}/{max-page} 页"
          actions:
            - "[message] &#5AC8FA正在查看公共垃圾桶第 &#FFD166{page}/{max-page} &#5AC8FA页"
            - "[command] wtc stats"
```

- `content` 是真正存放公共垃圾桶物品的槽位，不生成展示物。
- `previous-page`、`next-page`、`background` 和 `actions` 支持材质候选、`model-id`、`name` 和 `lore`。
- `actions` 按配置顺序分派三种动作：`[console]` 以控制台执行命令、`[command]` 以点击玩家执行命令、`[message]` 向点击玩家发送消息；命令开头的 `/` 可以省略。
- `name`、`lore` 和 `actions` 支持 `{player}`、`{uuid}`、`{world}`、`{page}`、`{max-page}`、`{previous-page}`、`{next-page}`。
- 安装 PlaceholderAPI 后，`name`、`lore` 和 `actions` 会按点击玩家实时解析 `%...%`；未安装时保留 PAPI 原文，内置变量和按钮动作继续可用。
- 玩家展示页与唯一公共库存分离，玩家专属 PAPI 文本不会串给其他玩家；取放始终回写唯一公共库存，不会因视图复制物品。
- 只有普通左键或右键会触发 `actions`；Shift、数字键、双击和拖拽不会触发，按钮展示物也不能拿取。
- `actions` 可以被玩家重复点击。`[console]` 拥有完整控制台权限，不要直接配置没有权限、次数或冷却限制的奖励命令。
- 未知动作前缀会被跳过，并在 reload 或首次触发时限频输出警告，不会默认升级为控制台命令。
- 查水表附属插件继续使用自己的固定菜单；主插件不会把审计布局配置化，也不会在默认公共垃圾桶中预置或引导 `/wtc audit` 跳转。
- 翻页按钮没有目标页时使用 `unavailable-item` 指向的展示物；留空则显示为空槽。
- 布局错误时会输出具体中文原因并回退六行默认布局，不会创建超过 54 格的无效 GUI。
- reload 后布局容量允许缩小；旧存量放不下时会追加可访问的临时溢出页。溢出页只允许取出，不接收新物品，避免缩容时静默删除垃圾桶内容。
- 旧版 `global-trash.gui.back-model-id`、`next-model-id`、`background-model-id` 仍可在缺少新布局时生成兼容默认布局。

2026-07-21 已使用同一个 `dist/WorldListTrashCan-universal.jar` 在 Paper 1.12.2 与 Folia 1.21.8 上完成真实客户端专项。两端均先写入 12 个满堆叠，再 reload 缩成 2 行、9 个内容槽、1 个正常页；日志和库存证明 12 堆全部保留为 1 个正常页加 1 个临时溢出页，新路由在正常页已满时返回 `routed=false`，玩家真实点击取出一堆后新路由恢复为 `routed=true`。真实 F2 截图同时证明翻页、材质候选降级、RGB/传统色名称与 Lore、页码占位符和 7 行非法布局回退 6 行。最终双端统一证据：`docs/test-evidence/global-trash-layout-visual-20260721-024653/`。

2026-07-24 使用当前 universal 整包和附属 Jar 完成 F-092 真实客户端专项。Paper 1.12.2 临时移除 PAPI 后，按钮名称、Lore、message 与 console 动作及独立 `/wtc audit` 消息均保留 `%Wtc_ClearTime%` 原文，其它内置变量和三种动作仍正常；Folia 1.21.8 使用真实 PAPI 后，同一变量在 Tooltip、message、console 和附属消息中均解析为数字。普通左键、右键、Shift、数字键和物理双击事件语义均有计数证据，真正的 Bukkit `DOUBLE_CLICK` 由单元测试覆盖。查水表保持固定布局，公共垃圾桶没有审计跳转。最终统一证据：`docs/test-evidence/global-trash-actions-visual-20260724-163808/`。

## 清理控制台明细

`cleanup.yml` 的 `notify.console` 是独立控制台通道，不依赖 `notify.chat.enabled`。旧配置没有 `notify.console.enabled` 时，仍会兼容读取旧键 `notify.chat.console-log`。

```yaml
notify:
  console:
    enabled: true
    details-enabled: true
    max-entries: 10
    entity-format: "{name}_{type}: {count}"
    items-format: "items: {count}"
    others-format: "others: {count}"
```

- 实体按成功清理数量降序，默认显示前 10 组，运行时限制为 1-100 组。
- 名称优先读取非空自定义名，再降级到 `getName()`，最后降级到小写 `getType()`；聚合前会移除颜色、控制字符和多余空白。
- 同名但颜色不同的实体会合并；类型始终使用小写，例如 `神话最强怪_armor_stand: 300`。
- `items` 是本轮成功进入世界、个人、公共垃圾桶或直接删除的物品实际个数，一组 64 个物品计为 64。
- 超出 `max-entries` 的实体数量合并到 `others`；内部最多跟踪 4096 个实体名称与类型组合，避免异常自定义名撑大内存。
- Folia 只在实体所属 region 读取名称并合并字符串计数，不会把 Bukkit 实体对象保存到统计结果中；超时时明细标记为 `partial=true`。

2026-07-14 已使用同一个 `dist/WorldListTrashCan-universal.jar` 在隔离 Paper 1.12.2 和 Luminol 26.1.2 上完成真实后台矩阵。两端都生成 5 个颜色不同但同名的盔甲架、4 只普通羊、1 只猪和 97 个实际物品；最终日志均为 `神话最强怪_armor_stand: 5`、`Sheep_sheep: 4`、`others: 1`、`items: 97`，且夹具确认清理后残留为 0。机器摘要：`build/cleanup-console-detail-matrix/20260714-024646/summary.json`。

## 扫地启动门禁

`cleanup.yml` 新增 `guards` 配置，用来决定本轮扫地是否启动：

```yaml
guards:
  min-online-players: 1
  min-total-entities: 150
```

- `min-online-players`：在线玩家低于该值时跳过扫地，默认 `1`，即无人在线不扫地。
- `min-total-entities`：会被本轮扫地处理的目标实体数量低于该值时跳过扫地，默认 `150`。
- 任意一项设为 `0` 可关闭对应门禁。
- `/wtc clear` 和 `/wtc stats` 会显示最近一轮门禁状态；自动扫地被跳过时使用 `notify.*.messages` 的 `-5` 文案。
- 已存在的旧 `cleanup.yml` 如果缺少 `-5` 文案，插件启动时只追加缺失项，不整体重写服主已有配置。
- Folia 分支会先按 RegionScheduler 安全计数，达到阈值后再进入 region-safe 清理阶段。

## Folia 清理保护

Folia 分支的世界清理不再在 global thread 上执行普通 Bukkit 的全世界实体扫描。当前实现会先收集未忽略世界的已加载 chunk，再按 `cleanup.yml` 的 `folia.*` 配置分批提交到 RegionScheduler；掉落物删除、实体删除、通知和写入收尾都有异常兜底，避免单个 region 或实体任务异常卡住整轮清理。

默认配置：

```yaml
folia:
  timeout-seconds: 30
  max-chunks-per-cleanup: 4096
  chunk-batch-size: 64
  chunk-batch-delay-ticks: 1
```

- `timeout-seconds`：单轮 Folia 清理最长等待秒数；超时后释放运行中状态，迟到 region 任务不再计入本轮统计。
- `max-chunks-per-cleanup`：单轮最多扫描多少个已加载 chunk；`0` 表示不限制。
- `chunk-batch-size`：每批派发多少个 chunk 扫描任务。
- `chunk-batch-delay-ticks`：每批之间间隔多少 tick，用于削峰。

`/wtc clear` 在 Folia 下仍是异步启动语义：命令返回“已启动”只表示任务成功提交。若上一轮仍在运行，会返回运行中保护并跳过本次请求；若本轮超时，会发送 `-4` 通知文案“Folia 清理超时”，并允许下一次 `/wtc clear` 再启动。公共垃圾桶刷新次数配置为负数时会发送 `-3`，明确提示“公共垃圾桶不会自动刷新”，不再显示“还有 0 次”。

## 实体限制低占用扫描

`entity-limits.yml` 的世界实体上限和密集实体清理不再在生成事件里同步扫描 `world.getEntities()` 或大范围附近实体。新实现使用低占用分片体系：

- 实体生成、进入世界或离开世界只标记所在 chunk 为 dirty，不维护永久全实体事件表。
- Bukkit/Paper/Spigot 端在主线程按预算采集少量已加载 chunk 的不可变快照。
- Folia 端在 global region 只挑选 chunk，实际快照采集和删除都派发到 chunk 所在 region。
- 异步 worker 只处理 UUID、世界、类型、chunk、坐标等轻量快照，计算待删除候选。
- 删除候选回到主线程或 region 线程按 `max-removes-per-run` 预算执行。
- 候选带 TTL、去重、最大队列和重试上限；实体查不到、失效、类型/世界/chunk 不匹配或规则关闭时会直接消费候选并释放去重标记。

关键配置：

```yaml
scanner:
  target-full-cycle-seconds: 300
  scan-interval-ticks: 20
  min-chunks-per-scan: 4
  max-chunks-per-scan: 64
  max-scan-millis-per-run: 4
  remove-interval-ticks: 2
  max-removes-per-run: 20
  max-pending-removals: 2000
  candidate-ttl-seconds: 120
  max-candidate-retries: 3
  max-dirty-chunks: 4096
  stale-chunk-seconds: 600
  max-index-entities: 50000
  max-index-entities-per-chunk: 512
  log-summary-seconds: 60
```

这套设计接受一定延迟，优先保证清理插件自身占用低且不会因为密集实体瞬间生成而卡住主线程。`/wtc debugdensity` 可查看 loaded/selected chunk、索引实体数、候选队列、删除成功/跳过、TTL/重试/丢弃等统计，用于压测和排障。

2026-06-18 已用 `dist/WorldListTrashCan-universal.jar` 完成低占用实体密度压测，SHA256 为 `CB78511DBD9645F7127CC4D02C06BF37E89920378BBC2CCC0FED6EE2E933403B`。测试端覆盖 Paper 1.12.2 与 Folia 1.21.8，均临时启用密集 cow 限制、生成 300 只 cow，并把 `scanner.max-removes-per-run` 压到 `1` 验证预算化删除。Paper 1.12.2 最终剩余 1 只，候选队列/去重 `0/0`；Folia 1.21.8 最终剩余 6 只，候选队列/去重 `0/0`。两端均通过 `debugdensity` 证明候选创建、取出、完成和删除/跳过生命周期闭合，证据目录为 `docs/test-evidence/entity-density-low-overhead-20260618-015437/`。

2026-06-18 另用同一个 `dist/WorldListTrashCan-universal.jar` 完成真实客户端游戏内截图矩阵，SHA256 为 `73d1069403d50ebad4e37720fd801f0109f186e91b6f2f428239282f2699bd56`，`plugin.yml` 版本 `7.0.0`。测试覆盖 `E:\server_work\1.21.11spigot`、`E:\server_work\folia1.21.8`、`E:\server_work\server_cat_1.12.2`、`E:\server_work\spigot-26.1.2-test-server`、`E:\server_work\1.21.11arclight-neoforge`、`E:\server_work\1.20.1fabric.banner` 六个服务端；每端均由真实客户端进服、生成 80 只 cow，分别截取 before、正式清理提示 notify、玩家 `/wtc debugdensity` 输出三列 F2 游戏内 PNG。`summary.json` 同时断言客户端日志出现正式“密集实体清理”提示和玩家命令输出“实体密度扫描统计”，避免只凭服务端日志或截图误判。证据目录为 `docs/test-evidence/entity-density-visual-20260618-101314/`，总览图为 `entity-density-visual-contact-sheet.png`。

2026-06-28 对照旧版 `GatherEntityLimitCount: 实体类型;数量;范围;清理数量` 语义修复 `remove-count`：它表示密集条件触发后本轮最多清理多少只，不是只清理“当前数量 - max-count”的差值。重构版现在按 `min(remove-count, 当前密集数量)` 选择候选，删除阶段不再因为前面候选已经把数量降到上限附近而跳过同轮剩余候选；同一半径内已经有待删除候选时，后续跨 chunk 快照不会再重复创建第二组候选。关闭密集实体限制后，已排队候选也会直接消费而不继续删除。最终整包 SHA256 为 `38c33ad0a32e782c0878b858e9f8cd6cb871d858afd97ae6e216b504720958a7`，在 `E:\server_work\folia1.21.8` 与 `E:\server_work\1.21.11spigot` 用真实客户端验证 `spawn-count: 20`、`max-count: 8`、`remove-count: 5`，正式聊天提示每条均为“本次已清理 5 只”，并保存 before/notify/debugdensity F2 截图。证据目录为 `docs/test-evidence/entity-density-visual-20260628-032747/` 和 `docs/test-evidence/entity-density-visual-20260628-032445/`。

## 消息与语言

`config.yml` 的 `language` 指定 `plugins/WorldListTrashCan/messages/` 下的语言文件名，默认 `message_zh.yml`。插件会在启动或重载时保存 jar 内自带语言文件；如果旧服已有外部语言文件且缺少新节点，正式玩家文案会优先回退到当前语言对应的 jar 内置节点，再回退到默认中文节点，避免升级后命令、GUI 或提示变成空白。旧外部语言文件如果仍把调试命令堆在 `command.help` 主帮助里，运行时会改用当前语言的 jar 内置主帮助，避免英文语言切换时被强制退回中文主帮助。

2026-07-21 已用当前 `dist/WorldListTrashCan-universal.jar` 完成多语言与公开品牌真实客户端专项，SHA256 为 `d821feef1a5e9158f027c530c048527195183d4954e41af13701ec8373b9ea24`。测试端覆盖 Paper 1.12.2 managed、Spigot 26.1.2 managed 和 Folia 1.21.8：每端先切换到 `message_en.yml` 并由真实客户端执行 `/wtc help`，确认英文主帮助；再删除外部 `message_zh.yml` 的 `command.help` 节点并重载，确认中文帮助从 jar 内默认节点回退出来；客户端与服务端同时确认公开前缀为 `WorldListTrashCan`。证据目录为 `docs/test-evidence/language-visual-20260721-022637/`。

当前已外置的正式玩家文案包括：主命令、帮助、平台能力、统计、add 命令、公共/个人垃圾桶、个人垃圾桶自动回收提示、世界垃圾桶创建/移除、黑名单 GUI、防丢弃模式、look 查询、手持物品/区块实体查询和密集实体清理提示。密集实体清理对应消息节点为 `entity-limit.gather-cleared`，可使用 `{range}`、`{entity}`、`{size}`、`{max}`、`{removed}` 占位符。后台 `debug*` 测试命令仍保留内部中文调试文案，用于验收夹具，不作为普通玩家语言包范围。

当前四种默认语言 `message_zh.yml`、`message_zh_TW.yml`、`message_en.yml`、`message_es.yml` 均使用 RGB 默认色板；旧服已有外部语言文件仍可继续使用 `&a`、`&c` 这类传统颜色码，运行时会继续兼容。

## RGB 与富文本消息

重构版使用 PrismaticAPI `1.5.2` 作为统一富文本渲染库，依赖从 `https://croabeast.github.io/repo/` 获取，并在四个平台产物中 shade 后 relocation 到 `pixeltech.bluenine.blworldtrashcan.libs.croabeast`，避免与服务器上其它插件的 PrismaticAPI 版本冲突。打包时会过滤 PrismaticAPI 自带 `plugin.yml`，最终插件名仍为 `WorldListTrashCan`。

正式消息入口统一走 `RichTextRenderer`，包括普通 Chat、可点击 Chat、ActionBar、Title、BossBar 标题、GUI 标题和平台层 `sendMessage(UUID, message)`。1.16.5+ 服务端可以使用 `&#RRGGBB` 这类 RGB 写法；1.12.2 和 1.13-1.15 会自动降级为传统 `&` 颜色码，不要求真实 RGB。当前默认多语言 `messages/message_*.yml` 全部使用 RGB 写法；服主已有外部语言文件仍可继续写 `&a`、`&c` 等老式颜色。若 PrismaticAPI 在运行时不可用或抛出兼容异常，`RichTextRenderer` 会先把 `&#RRGGBB` 近似降级为 16 色传统颜色，再处理 `&` 颜色码，避免把原始 RGB 标记发给低版本玩家。

本轮 RGB 视觉验收使用真实原版客户端生成的 F2 截图，不用服务端日志或协议抓包替代截图结论。`/wtc debugrgb <玩家>` 会向在线玩家发送 Chat、ActionBar、Title、Subtitle、BossBar、GUI 标题、物品名和 Lore 八个可见通道；截图矩阵覆盖 Paper 1.12.2、1.13.2、1.14.4、1.15.2、1.16.5、1.17.1、1.18.2、1.19.4、1.20.4 和 1.21.4。其中 1.12.2-1.15.2 为传统颜色降级证据，1.16.5-1.21.4 为 RGB 视觉证据。可提交截图证明保留在 `docs/test-evidence/rgb-visual-proof-20260607-104606/`，本机原始运行缓存保留在 `build/rgb-visual-matrix/runs/rgb-visual-proof-20260607-104606/`，汇总文件为 `build/rgb-visual-matrix/latest-visual-proof.json`。

外部服务端补充矩阵同样使用真实客户端 F2 截图，并额外校验 `wtc platform` 确实被插件接收，避免把插件未加载或命令未注册误判为通过。覆盖 `E:\server_work\server_1.21.8_0`、`E:\server_work\server_cat_1.12.2`、`E:\server_work\folia1.21.8`、`E:\server_work\1.21.11spigot`、`E:\server_work\1.21.11arclight-neoforge` 和 `E:\server_work\1.20.1fabric.banner`，6/6 PASS。可提交截图和日志证据保留在 `docs/test-evidence/rgb-external-server-proof-20260607-155341/`。

同一批外部服务端已补做 universal 整包复测，6 个端全部部署 `WorldListTrashCan-universal.jar`。本轮 RGB 截图限定聊天框、ActionBar、Title/Subtitle，不再使用箱子 GUI、物品名或 Lore 作为颜色证据；每个端同时执行 `reload`、世界垃圾桶创建、公共/个人/世界路由、损坏回收、玩家掉落 owner、手动清理、摘要、公共/个人 GUI 打开共 11 项基础功能检查，全部 PASS。可提交证据目录：`docs/test-evidence/rgb-universal-channels-proof-20260607-175511/`。

针对上一轮 Title 颜色接近传统 `&a`、`&6` 的人工观感问题，已再次使用高辨识度 RGB 文案重测同一批外部服务端。Title 改为多段 `RGB TITLE FF1493`，分别使用 `#FF1493`、`#00E5FF`、`#BAFF00`，Subtitle 使用 `#7B2CFF` 与 `#FF4F00`；聊天框和 ActionBar 也显示 `RGB-FF1493`、`RGB-00E5FF`、`RGB-BAFF00`、`RGB-7B2CFF`、`RGB-FF4F00` 文本标记。6 个端仍全部使用同一个 `WorldListTrashCan-universal.jar`，截图、日志和基础功能证据保留在 `docs/test-evidence/rgb-universal-highcontrast-channels-proof-20260607-202234/`。

26.1.2 兼容验收已补充 Paper 与 Spigot 两端：Paper 26.1.2 build 69 通过 Paper fill API 获取服务端 jar，Spigot 26.1.2 通过 BuildTools 使用 Java 25 构建。两个测试服都部署同一个 `WorldListTrashCan-universal.jar`，使用真实原版 26.1.2 客户端 F2 截图验证 Chat、ActionBar、Title/Subtitle RGB，同时执行 `reload`、世界垃圾桶创建、公共/个人/世界路由、损坏回收、玩家掉落 owner、手动清理、摘要、公共/个人 GUI 打开共 11 项基础功能检查，全部 PASS。可提交证据目录：`docs/test-evidence/rgb-26-1-spigot-paper-proof-20260608-005225/`。

2026-06-08 完整功能复测只使用 `dist/WorldListTrashCan-universal.jar`，测试服 `plugins` 目录中的文件名也保持为 `WorldListTrashCan-universal.jar`，没有混入任何轻量特供 jar。覆盖 Paper 1.12.2、Paper 26.1.2 和 Spigot 26.1.2，三端 artifact 的 SHA256 都是 `da30aecf3b4b5976ac95778bdb4301f08e72fd33f1edda16e3617e5045e85117`；真实客户端截图、GUI 打开、路由、清理、PAPI、bStats 配置、reload 自愈、旧短命令 `wtc` 和旧长命令 `WorldListTrashCan` 均 PASS。`add <world> <amount>` 额外读取 `data/worlds.yml` 验证 `max-count` 从 `3` 增加到 `5`。可提交证据目录：`docs/test-evidence/universal-full-regression-1122-2612-20260608-192725/`。

2026-07-21 已用当前 universal 整包重跑 GUI 正向点击三端真实客户端矩阵。被测 SHA256 为 `d821feef1a5e9158f027c530c048527195183d4954e41af13701ec8373b9ea24`，覆盖 Spigot 26.1.2、Paper 1.12.2、Folia 1.21.8。已通过 F-024 公共分页、F-026 公共取出、F-027 公共放入、F-028 公共取出冷却、F-029 公共操作日志、F-030 公共黑名单 GUI 保存并立即生效、F-034 个人取出、F-035 个人放入、F-036 个人满桶自动清空。最终证据目录：`docs/test-evidence/trash-gui-click-visual-20260721-023817/`，测试脚本：`tools/rgb-visual-matrix/run_trash_gui_click_visual_matrix.py`。

2026-07-02 已补做 Vault 扣费 F-037 专项验收。本轮修复 universal 总包反射 VaultAPI 时查找 `withdrawPlayer(Player,double)` 的签名错误，改为 VaultAPI 1.7 实际存在的 `withdrawPlayer(OfflinePlayer,double)`；同时个人垃圾桶取出改为先确认玩家主背包存储槽能完整接收物品，再扣费。测试脚本 `tools/rgb-visual-matrix/run_vault_payment_matrix.py` 会临时编译插件名为 `Vault` 的 fake Economy 夹具，启动 Spigot 26.1.2 managed 测试服和真实 26.1.2 客户端，点击个人垃圾桶 GUI 验证余额充足扣费成功、余额不足不取出不扣费、背包满不取出不扣费。最终整包 SHA256 为 `18d92a709d48fc291dd77c74a3be7d543f5bb20fa0fc01c4c70ab00eb33773d4`，证据目录：`docs/test-evidence/vault-payment-visual-20260702-200346/`。

2026-07-01 已补做世界实体上限专项验收。本轮只部署 `dist/WorldListTrashCan-universal.jar`，SHA256 为 `0b8fe41981a5933058983d644c14fb80de11f5825e9ce02d2ce12faacf19df84`，用 Paper 1.12.2 与 Spigot 26.1.2 隔离真实服务端验证 F-070/F-072。测试先关闭实体限制铺底 2 只 COW，再开启 `world-limits` 等待低占用索引建立；达到缓存上限后第 3 只 COW 被正式生成路径拦截，随后把 `world`、`world_nether`、`world_the_end` 写入 `ignored-worlds` 后同样生成被放行，且 `debugdensity` 显示 ignored 状态下本轮扫描选择为 `0/0`。证据目录：`docs/test-evidence/world-entity-limit-20260701-180527/`，测试脚本：`tools/rgb-visual-matrix/run_world_entity_limit_matrix.py`。

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

`messages/message_*.yml` 的 `personal-trash.recycle` 控制提示格式。`single` 用于仙人掌、岩浆、虚空等单个掉落实体损坏回收；`batch` 用于 `/wtc clear` 或后台扫地这种一次清理多个掉落物的批量提示。`{items}` 是完整物品列表占位符，由 `list/separator/item/item-single/ellipsis` 组合生成。

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

旧版和重构版使用同一个 `plugins/WorldListTrashCan` 数据目录。插件会在保存新版默认配置前检查以下旧结构特征：`config.yml` 顶层 `Set`、`GlobalBanItem`，或 `data/data.yml` 顶层 `WorldData`。

- 检测到旧结构后，当前数据目录中的全部内容会先移动到 `old-version-config/`，包括旧配置、自定义文件和日志；旧文件不会再从根目录直接生效。
- 新版默认配置先生成到 `.migration-staging/`，迁移并通过全部 YAML 与 `config-schema-version: 2` 校验后才发布到根目录。
- 迁移报告写入 `old-version-config/migration-report.md`。
- 只有迁移、发布和最终解析校验全部成功，才会生成 `old-version-config/migration-complete.yml`。标记包含插件版本、迁移时间和旧配置 SHA-256。
- 未生成完成标记时，下一次启动会从根目录剩余旧结构或 `old-version-config/` 继续重试；备份移动过程支持中断后重入，相同文件不会被重复覆盖。
- 完成标记存在后不再重复对照。如果根目录再次出现旧结构，插件会明确拒绝启动，避免旧配置覆盖新版配置。
- 该迁移没有关闭开关，也不读取相邻插件目录或绝对路径。
- 当前会自动迁移主配置、清理配置、通知配置、保护配置、实体限制配置、公共/个人/世界垃圾桶配置，以及旧 `data/data.yml` 中的世界垃圾桶运行数据。
- 公共垃圾桶 GUI 的旧 `ModelId` 和 `Material` 会迁移到 `global-trash.gui.layout.items.a/c/b`；材质保存为有序候选列表，低版本没有 `CustomModelData` API 时只忽略 model-id，不影响 GUI 打开。
- 旧 `BossBarFlag` 和 `BossBarMessageForCount` 会迁移到 `bossbar.enabled` 与 `bossbar.messages`，格式仍为 `剩余秒数;内容;样式;颜色`。
- 当前不能自动承接的旧字段会自动写入报告的“需要人工确认字段”，不再依赖开发者手工维护字段清单。
- 旧 `message/` 下 8 份语言文件会逐文件原样备份并列入人工合并清单；新版不会把不同键结构的旧语言文件静默当成当前语言文件加载。
- 旧 `config.yml`、`data/data.yml`、完成标记或备份内容损坏时会以稳定错误码拒绝迁移；新版与旧版结构混放、备份同名文件内容冲突时也会拒绝启动，不生成完成标记且不覆盖输入。

2026-07-24 使用 SHA-256 为 `a42e1c4077f9f4250f89cb88ffbe22d2fe7be88e07efa31226e02786e1649a72` 的同一个 universal 整包完成三层验收：

- Paper 1.12.2 完整矩阵覆盖旧版 6.9.8 原始资源、当前根目录、中断归档恢复、仅备份目录重试，以及损坏主配置、损坏世界数据、损坏完成标记、新旧混放、备份冲突 5 个安全拒绝场景；证据为 `docs/test-evidence/legacy-migration-universal-20260724-000752/`。
- Spigot 26.1.2 与 Folia 1.21.8 使用同一整包和同一旧版原始配置验证首次迁移、运行时读取、8/8 语言文件备份及二次启动幂等；证据为 `docs/test-evidence/legacy-migration-platform-20260724-001431/`。
- 真实 Forge 1.12.2 客户端执行 `/wtc stats`、`/wtc debugnotify 0`、`/wtc globaltrash`，截图明确显示迁移后的页数 7、旧聊天文案、`1/7` GUI 标题、玻璃背景和 `STICK` 下一页按钮；15 张原始 PNG 中 10 个非重复帧已逐张复核。证据为 `docs/test-evidence/legacy-migration-client-20260724-003203/`。

## 世界垃圾桶边界

2026-07-01 已使用 `dist/WorldListTrashCan-universal.jar` 完成 F-019 至 F-022 世界垃圾桶边界专项验收。测试脚本 `tools/rgb-visual-matrix/run_world_trash_boundary_matrix.py` 在 Paper 1.12.2 与 Spigot 26.1.2 隔离服务端中加载临时 Bukkit 夹具，触发正式 `SignChangeEvent`、`BlockBreakEvent` 和 `/wtc clear true` 路由流程。已验证禁止世界普通玩家创建被拒绝、破坏容器移除 `data/worlds.yml` 登记、世界物品黑名单会降级到公共垃圾桶、未加载区块不会被同步加载且会降级到公共垃圾桶；两端日志均出现 `worldTrashSkippedUnloadedChunks=1`。通过证据：`docs/test-evidence/world-trash-boundary-20260701-172919/`，失败对照：`docs/test-evidence/world-trash-boundary-20260701-172343/`、`docs/test-evidence/world-trash-boundary-20260701-172602/`、`docs/test-evidence/world-trash-boundary-20260701-172748/`、`docs/test-evidence/world-trash-boundary-20260701-172836/`，被测整包 SHA256 为 `18b2f29229dba529098a94748db6abf8b729c81a0c3ab749a461d28d8d14f55b`。

## 船内实体保护

`cleanup.yml` 的 `entities.ignore-entities-in-boat` 用于保护正在船内的实体。启用实体清理时，如果动物、怪物或其它实体位于船内，本轮清理会跳过该实体，避免扫地功能误删玩家正在运输或展示的生物。

2026-07-01 已使用 `dist/WorldListTrashCan-universal.jar` 完成 F-054 船内实体保护专项验收。测试脚本 `tools/rgb-visual-matrix/run_boat_entity_protection_matrix.py` 会在 Paper 1.12.2 与 Spigot 26.1.2 隔离服务端中加载临时 Bukkit 夹具，生成一只船内牛和一只普通牛，并执行正式 `/wtc clear true`。最终断言船内牛仍存在且 `protectedInsideBoat=true`，普通牛 `normalExists=false`，证明 legacy 与现代 universal 分支均遵守 `ignore-entities-in-boat`。通过证据：`docs/test-evidence/boat-entity-protection-20260701-171046/`，失败对照：`docs/test-evidence/boat-entity-protection-20260701-170854/`，被测整包 SHA256 为 `18b2f29229dba529098a94748db6abf8b729c81a0c3ab749a461d28d8d14f55b`。

## 保护功能专项

`protections.yml` 的 `simple-optimize.remove-unpickable-arrow` 会清理不可拾取箭矢，`simple-optimize.prevent-farmland-trampling` 会阻止玩家和实体踩踏农田。现代 Spigot/Paper 中箭矢拾取状态从旧 `Arrow.PickupStatus` 迁移到 `AbstractArrow.PickupStatus`，正式插件已改为反射读取 `getPickupStatus`，避免高版本 API 变动导致不可拾取箭矢不被清理。

2026-07-01 已使用 `dist/WorldListTrashCan-universal.jar` 完成 F-068/F-069 保护边界专项验收。测试脚本 `tools/rgb-visual-matrix/run_protection_boundary_matrix.py` 会先同步 Maven 最新 universal 产物到 `dist`，再在 Paper 1.12.2 与 Spigot 26.1.2 隔离服务端中加载临时 Bukkit 夹具，触发正式 `ProjectileHitEvent`、`EntityShootBowEvent`、`EntityInteractEvent` 和 `PlayerInteractEvent(Action.PHYSICAL)`。最终断言不可拾取箭矢与骷髅/无限弓追踪箭矢均被移除，实体和玩家踩踏农田事件均被取消。通过证据：`docs/test-evidence/protection-boundary-20260701-174709/`，失败对照：`docs/test-evidence/protection-boundary-20260701-173858/`、`docs/test-evidence/protection-boundary-20260701-174200/`、`docs/test-evidence/protection-boundary-20260701-174348/`，被测整包 SHA256 为 `0b8fe41981a5933058983d644c14fb80de11f5825e9ce02d2ce12faacf19df84`。

## 命令

正式命令：

```text
/wtc help
/wtc platform
/wtc clear [true/false]
/wtc stats
/wtc global
/wtc personal
/wtc dropmode
/wtc look
/wtc ban
/wtc globalban
/wtc add <数量>
/wtc add <世界名> <数量>
/wtc debughelp
/wtc reload
```

`/wtc clear [true/false]` 与 `/wtc clear [true/false]` 会立即执行一次手动扫地。第二参数默认 `true`，表示本次忽略 `guards` 门禁；传入 `false` 时才会遵守 `guards.min-online-players` 和 `guards.min-total-entities`。Folia 产物中该命令会启动异步 region-safe 清理；命令返回只表示清理任务已提交，最终统计以后台 `[FoliaCleanup]` 日志或后续 `/wtc stats` 为准。

正式命令入口：

```text
/worldlisttrashcan
/wtc
```

后台测试命令已从 `/wtc help` 主面板移出，统一通过 `/wtc debughelp` 查看。旧版本已生成的默认语言文件如果仍把调试命令写在主帮助里，运行时会自动使用新内置主帮助列表，避免调试入口继续显要展示。除 `debughelp` 只展示说明外，以下命令均需要 `WorldListTrashCan.Admin`：

```text
/wtc debugopen <玩家> <global|personal>
/wtc debugworldtrash <玩家>
/wtc debugroute <玩家> <world|personal|global> <Material> <数量>
/wtc debugdrop <玩家> <Material> <数量> [owner]
/wtc debugdamage <玩家> <Material> <数量>
/wtc debugstock
/wtc debugsummary <玩家>
/wtc debugdensity
/wtc debugnotify <count>
/wtc debugplayer <玩家> <dropmode|look|ban|globalban>
/wtc debugrgb <玩家>
/wtc debugrgbchannels <玩家>
```

`debugworldtrash` 会在玩家附近创建并登记一个测试箱子，`debugdrop` 会生成带拾取延迟的真实掉落物，`debugdamage` 会生成真实掉落物并通过正式事件总线模拟岩浆损坏回收，`debugroute` 会向指定垃圾桶写入测试物品，`debugstock` 和 `debugdensity` 不要求玩家在线，分别输出垃圾桶库存与实体密度扫描摘要，`debugnotify <count>` 会按 `cleanup.yml` 的正式 `notify.*` 配置直接触发对应编号的清理通知，适合补 Chat、ActionBar、BossBar、Title、Sound 和 Command 通知截图；`debugplayer` 会用真实在线 `Player` 对象触发玩家入口和 GUI。除 `debugstock`、`debugdensity` 外它们都会改变测试服运行态或玩家可见状态，只用于验收，不是普通玩家功能。

## 权限

- `WorldListTrashCan.Admin`：重载、清理、增加世界容量和后台测试命令。
- `WorldListTrashCan.Main`：创建世界垃圾桶。
- `WorldListTrashCan.BanGui`：打开世界垃圾桶黑名单 GUI。
- `WorldListTrashCan.GlobalTrashOpen`：打开公共垃圾桶。
- `WorldListTrashCan.GlobalTrashTakeItem`：从公共垃圾桶取出物品。
- `WorldListTrashCan.GlobalTrashPutItem`：向公共垃圾桶放入物品。
- `WorldListTrashCan.PersonalTrashTakeItem`：从个人垃圾桶取出物品。
- `WorldListTrashCan.PersonalTrashPutItem`：向个人垃圾桶放入物品。
- `WorldListTrashCan.help`：查看帮助。
- `WorldListTrashCan.GlobalBan`：打开公共垃圾桶黑名单 GUI。
- `WorldListTrashCan.Look`：查询手持物品和实体类型。
- `WorldListTrashCan.DropMode`：切换防丢弃模式。
- `WorldListTrashCan.PlayerTrash`：打开个人垃圾桶。
- `WorldListTrashCan.help`：旧帮助权限。

## 变量

PAPI 变量：

- Legacy 1.12、Bukkit 1.13-1.15、Paper 1.16-1.20、Folia 1.20 四个产物的代码都提供 `%Wtc_ClearTime%`，返回下次自动清理剩余秒数。
- 公共垃圾桶 `actions` 按钮的 `name`、`lore`、`actions` 可以消费任意已安装扩展提供的 PAPI 变量；解析发生在玩家打开或点击按钮时，不会把某个玩家的结果写入共享库存。
- Legacy 1.12、Bukkit 1.13.2、Paper 1.20.4 已使用 PlaceholderAPI 2.11.6 实服验证；Folia 1.21.8 已使用支持 Folia 的 `[PAPI]PlaceholderAPI-2.11.7-DEV-null (1).jar` 实服验证。旧 PlaceholderAPI 2.11.6 仍会被 Folia 拒绝加载，不能作为 Folia PAPI 验收前置。

提交前可运行以下脚本检查五个平台 PAPI expansion、注册入口和当前 `dist` jar 内 class 常量是否保持 `%Wtc_ClearTime%` 一致：

```powershell
py -3 tools\rgb-visual-matrix\check_papi_placeholder_parity.py
```

当前审计覆盖 5 个 expansion 源码、5 个注册入口和 5 个 dist jar，结果为 `errors: 0`。

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

2026-06-08 已用 `WorldListTrashCan-universal.jar` 的 `7.0.0` 构建完成 bStats 端到端验证：服务端加载 `WorldListTrashCan v7.0.0`，bStats 上报包包含 `"pluginVersion":"7.0.0"` 和 `"id":24350`，bStats 返回响应；等待 bStats 页面半点刷新后，[WorldTrashCan / 24350](https://bstats.org/plugin/bukkit/WorldTrashCan/24350) 的 `Plugin Version` 图表出现 `7.0.0`，数量 `1`。证据目录：`docs/test-evidence/bstats-7.0.0-proof-20260608-062316/`。

## 验证记录

本机 `mvn` 不在 PATH，本轮使用 `javac` 手工编译并用 JDK 21 `jar.exe` 打包。跨版本构建必须按目标运行时指定 `--release`：1.12 Legacy 与 Bukkit 1.13-1.15 相关模块使用 `--release 8`，现代 Paper 和 Folia 产物使用 `--release 17`，否则旧 Java 8 测试服会出现 `UnsupportedClassVersionError`。

已通过：

- `core -> config -> storage -> shared-bukkit -> platform-legacy -> platform-bukkit -> platform-paper -> platform-folia -> plugin-legacy -> plugin-bukkit -> plugin-paper -> plugin-folia`
- `CorePolicySelfTest passed`
- 最终产物大小：Legacy `197741` bytes，Bukkit `199286` bytes，Paper `199758` bytes，Folia `257818` bytes。
- 1.12.2 测试服加载 `WorldListTrashCan v0.1.0-SNAPSHOT`
- Legacy 1.12 产物主类 class major version 为 52，确认面向 Java 8；jar 内 `platform.yml` 目标为 `legacy-1.12`。
- Bukkit 1.13-1.15 产物主类 class major version 为 52，确认面向 Java 8。
- Paper 1.16-1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Folia 1.20 产物主类 class major version 为 61，确认面向 Java 17。
- Paper 1.20.4 测试服加载 `WorldListTrashCan v0.1.0-SNAPSHOT`，`platform` 显示 `paper-1.16-1.20`，`stats` 和 `clear` 正常返回。
- `Material.STAINED_GLASS_PANE` 跨版本修复后，Legacy 1.12 测试服重新加载新产物，`platform` 显示 `legacy-1.12`，`stats` 和 `clear` 正常返回。
- 旧配置迁移器使用独立 Paper 1.12.2 服务端验证同名目录旧结构隔离、`old-version-config/` 完整备份、无标记重试、完成标记跳过和旧结构回放拒绝；迁移后 `trash.yml`、`cleanup.yml`、`data/worlds.yml` 均符合预期。最终证据：`docs/test-evidence/legacy-migration-universal-20260721-021850/`。
- Bukkit 1.13.2 测试服加载当前 Folia 保护构建后的 `WorldListTrashCan-bukkit-1.13-1.15.jar`，`platform` 显示 `bukkit-1.13-1.15`，`stats` 和 `clear` 正常返回，确认共享清理保护没有误伤普通 Bukkit 世界扫描。
- Folia 1.20.1 测试服首轮执行 `/wtc clear` 暴露 global thread 扫描实体的 region 线程错误；当前版本已改为 Folia 专用清理 Feature，通过 `RegionScheduler` 扫描已加载 chunk，通过实体调度删除物品，控制台 `summon` 4 个圆石掉落物后执行 `/wtc clear`，日志输出 `worlds=3, itemsRouted=4`，`/wtc stats` 显示公共垃圾桶物品 `4`、堆叠 `1`。
- Folia 产物已接入专用 `FoliaEntityLimitFeature`：单世界实体上限用 `EntityAddToWorldEvent` / `EntityRemoveFromWorldEvent` 维护数量缓存并用 region-safe 复算兜底；后续低占用密集实体扫描改为分片 chunk 快照和异步候选队列，不再按旧实现只看当前 chunk。
- Folia 专用清理已补齐通知触发：短间隔后台 smoke 验证 Chat 控制台日志、完成后 `-1/-2` 提示、Command 通知和 `[FoliaCleanup]` 汇总均会输出；玩家可见 RGB Chat、ActionBar、Title 已在 Folia 1.21.8 的通道截图矩阵中覆盖。2026-06-30 又用真实客户端专项矩阵补齐清理通知 Chat、ActionBar、BossBar、Title、Sound 和 Command，证据目录：`docs/test-evidence/cleanup-notify-visual-20260630-092840/`。
- 2026-06-15 Folia 1.21.8 压力回归只使用 `dist/WorldListTrashCan-universal.jar`，SHA256 `A84D2EC08402500505D9BE4F0EDFD404AF86A861AC0DC98F8C86FC53832E7CCC`。147 个已加载 chunk 的基线清理 `timedOut=false`；约 5946 个 `ai_wtc_pressure` armor_stand canary 被清理，立即二次 `/wtc clear` 命中运行中保护；`chunk-batch-size: 1`、`chunk-batch-delay-ticks: 2`、`timeout-seconds: 1` 稳定触发两次 `timedOut=true`，第二次能重新启动证明 `cleanupRunning` 已释放；超时通知改为 `-4`“Folia 清理超时”，不再误报“清理成功”。证据目录：`docs/test-evidence/folia-cleanup-pressure-20260615-030918/`。
- 四个平台默认 `cleanup.yml` 的 `notify.*` 已补回旧配置里的清理后 `-1/-2` 提醒：Chat、ActionBar、BossBar、Title 都默认包含“公共垃圾桶未刷新/已刷新”两类消息；包内检查确认四个 dist jar 的 `cleanup.yml` 均包含这些条目。
- 世界垃圾桶默认不再写入未加载区块；`paper-1.13.2-test-server` 用远处未加载区块坐标验证，清理日志出现 `worldTrashSkippedUnloadedChunks=1`，掉落物降级进入公共垃圾桶，未强制访问远处箱子。
- RCON 验证 `platform`、`stats`、`debugstock`、`debugsummary`、`debugworldtrash`、`debugroute`、`debugdrop`、`clear`、`debugopen`、`debugplayer`
- `client-1.12.2` 真实玩家 `AIAutoTest` 进服后执行玩家入口和 GUI 打开测试
- 旧功能补齐验证覆盖：防丢弃模式、look 查询、单世界黑名单 GUI、公共黑名单 GUI、聊天/命令限频、不可拾取箭矢清理、防踩踏农田、经验球/实体清理、实体白名单/黑名单、世界实体数量限制、密集实体限制、公共垃圾桶日志、公共垃圾桶按清理次数刷新、定时清理倒计时通知
- 世界垃圾桶强制加载区块问题已按 `docs/世界垃圾桶区块加载性能方案.md` 落地默认保护；`world-trash.allow-load-unloaded-chunks` 默认 `false`，真实测试服已验证未加载区块会被跳过并降级路由。
- 旧插件仙人掌/岩浆损坏回收的 `UseModel/Delay` 已自动迁移为 `personal-trash.damage-recovery.mode/delay-seconds`，默认仍为关闭，开启后只在短时间内追踪玩家主动丢弃物，避免长期占用内存；后台测试入口为 `/wtc debugdamage <玩家> <Material> <数量>`。
- 仙人掌/岩浆损坏回收已在 `paper-1.12.2-test-server` 使用真实 Forge 1.12.2 客户端验证：客户端发送 `/wtc debugdamage AIClientAlpha STONE 2`，服务端日志出现 `debugDamageRecovery ... recovered=true`，`/wtc debugstock` 显示公共垃圾桶物品 `2`、堆叠 `1`。
- 公共垃圾桶 GUI `ModelId` 和 BossBar 旧配置已补齐：四个平台默认 `trash.yml` 增加 `global-trash.gui.back/next/background-model-id`，默认 `cleanup.yml` 的 `notify.bossbar.messages` 增加 BossBar 消息；迁移器不再把这些字段列为人工确认。
- BossBar 已在 `paper-1.12.2-test-server` 用真实 Forge 1.12.2 客户端在线验证：真实玩家 `babyZiXuan` 在线时，RCON 执行 `/wtc clear` 成功，短间隔自动清理连续输出 `AI BossBar smoke 2/1/done`，日志未发现 WorldListTrashCan 自身异常。测试后已恢复临时 `cleanup.yml`。
- Legacy 1.12 产物已补齐旧 `%Wtc_ClearTime%` PAPI 变量注册逻辑，已在 `paper-1.12.2-test-server` 安装 PlaceholderAPI 2.11.6 时验证：`papi parse --null %Wtc_ClearTime%` 返回 `296`，日志出现 `Successfully registered internal expansion: Wtc` 和 `[WorldListTrashCan] [PlaceholderAPI] 已注册变量: %Wtc_ClearTime%`。
- Bukkit 1.13.2 和 Paper 1.20.4 已补做 `%Wtc_ClearTime%` PAPI 验证：`papi parse --null %Wtc_ClearTime%` 分别返回 `315`、`333`；`plugins` 均显示 `WorldListTrashCan` 和 `PlaceholderAPI` 已启用。
- Folia 1.20.1 尝试安装本地 PlaceholderAPI 2.11.6 验证 PAPI 时，Folia 在加载阶段拒绝该前置，原因是 `PlaceholderAPI v2.11.6` 未声明支持 Folia；WorldListTrashCan 因未检测到 PlaceholderAPI 正常跳过变量注册。本轮已将该临时 PAPI jar 改名为 disabled，避免污染后续 Folia 测试。
- Folia PAPI 已在 2026-07-02 使用 `E:\server_work\folia1.21.8`、`[PAPI]PlaceholderAPI-2.11.7-DEV-null (1).jar` 和最终 `dist/WorldListTrashCan-universal.jar` 验证通过：`papi parse --null %Wtc_ClearTime%` 返回 `358`，日志出现 `Successfully registered internal expansion: Wtc [7.0.0]`，证据目录 `docs/test-evidence/folia-papi-20260702-202801/`。
- 旧命令 `/WorldListTrashCan add [世界名] <数量>` 已在新命令 `/wtc add <世界名> <数量>` 中恢复控制台指定世界路径；`paper-1.12.2-test-server` 通过 RCON 验证 `wtc add world 1` 成功、`wtc add missing_world 1` 提示世界不存在、控制台 `wtc add 1` 提示必须指定世界名，并确认 `data/worlds.yml` 落盘为 `world.max-count: 4`。
- 多语言消息服务已接入四个平台产物并完成 Legacy 1.12 smoke：临时把测试服 `plugins/WorldListTrashCan/config.yml` 的 `language` 改为 `message_en.yml`，启动后日志显示 `[Message] 已加载语言文件: messages/message_en.yml`，RCON 执行 `wtc reload/help/platform/stats` 均返回英文文案；测试后 config 已恢复为 `message_zh.yml`，日志和生成的语言文件保留。
- Legacy 1.12 命令类使用正式权限：`global/globaltrash/trash` 检查 `WorldListTrashCan.GlobalTrashOpen`，`personal/playertrash` 检查 `WorldListTrashCan.PlayerTrash`。规范长命令 `/worldlisttrashcan` 和简写 `/wtc` 共用同一执行器。
- 公共黑名单 GUI 保存后已改为即时刷新运行期配置：关闭 `/wtc globalban` GUI 保存 `trash.yml` 后会调用插件自身 reload 流程，立即刷新 `ConfigBundle`、公共垃圾桶黑名单和路由服务；四个平台语言文件的保存提示已从“需要 reload”改为“已立即生效”。本轮重新打包四个平台，并在 1.12.2 测试服验证新 Legacy jar 正常加载、`platform/stats/reload` 正常返回。
- 旧配置 `Set.ClearEntity.Flag` 已补齐迁移到 `cleanup.yml` 的 `entities.enabled`，默认值为 `true`。关闭该总开关时，经验球、怪物、动物、投射物和实体黑名单都会整体跳过；2026-07-02 已补做实体清理总开关运行态专项，使用 `dist/WorldListTrashCan-universal.jar` 在 Paper 1.12.2 与 Spigot 26.1.2 隔离真实服务端中临时生成牛、僵尸、箭、经验球和黑名单命名实体。`entities.enabled=false` 后正式 `/wtc clear true` 移除实体为 0 且 5 个目标全部保留；切回 `entities.enabled=true` 后同一批目标全部被清理。证据目录：`docs/test-evidence/entity-cleanup-toggle-20260702-212255/`，被测整包 SHA256 为 `18d92a709d48fc291dd77c74a3be7d543f5bb20fa0fc01c4c70ab00eb33773d4`。
- 公共/个人垃圾桶 GUI 取出、放入物品的权限检查接受 OP 或对应 `WorldListTrashCan.*` 正式权限。真实客户端权限专项覆盖公共取出、公共放入、个人取出、个人放入四个 deny 分支；取出会出现客户端无权限提示，放入会被拒绝且垃圾桶库存与公共操作日志不变化。证据目录：`docs/test-evidence/gui-operation-permission-visual-20260702-231000/`。
- 五个平台命令类统一保留 OP 旁路：`reload/clear/add/debug*` 走 OP 或 `WorldListTrashCan.Admin`，其余玩家功能走对应 `WorldListTrashCan.*` 权限。权限负向真实客户端脚本已同步使用正式权限节点。
- 世界垃圾桶 `/wtc add <世界名> <数量>` 写入的 `data/worlds.yml` 上限现在会参与正式创建限制：`WorldTrashRouter` 使用单世界运行数据计算有效上限，告示牌创建的 OP 路径会按旧插件行为绕过数量上限。1.12.2 测试服使用真实 Forge 客户端 `AIClientAlpha` 进服后，RCON 通过在线 `Player` 对象连续执行 `debugworldtrash`：上限 5 时新增到 5 成功、第 6 个失败；执行 `wtc add world 1` 后上限变 6，再新增 1 个成功、第 7 个失败。最终 `data/worlds.yml` 落盘为 `world.max-count: 6` 且 6 个位置，窄匹配未发现 WorldListTrashCan 自身异常。
- 旧配置 `Set.PersonalTrashCan.NoWorldTrashCanEnterPersonalTrashCan` 迁移到 `personal-trash.track-player-dropped-items` 后，Legacy/Bukkit 这类无 PDC 平台现在会用短期运行态 owner 追踪补齐普通清理路由；Paper/Folia 仍优先使用 PDC，并用同一追踪器兜底。1.12.2 测试服临时清空 `world` 的世界垃圾桶登记后，真实客户端 `AIClientAlpha` 在线执行：`debugdrop AIClientAlpha STONE 2 owner` 后 `/wtc clear` 显示回收 2 个物品、个人路由 1 个堆叠，`debugsummary` 显示个人垃圾桶物品 `2`；未带 owner 的 `debugdrop AIClientAlpha COBBLESTONE 3` 对照用例进入公共垃圾桶，个人桶保持 `2`。测试后已恢复原 `data/worlds.yml`，窄匹配未发现 WorldListTrashCan 自身异常。
- 个人垃圾桶自动回收提示已在 `paper-1.12.2-test-server` 用真实 `client-1.12.2` 客户端验证：`debugdamage babyZiXuan STONE 2` 后客户端收到 `已回收到个人垃圾桶: [STONE*2]`；三类 `debugdrop ... owner` 后 `/wtc clear` 收到 `本次清理已回收到个人垃圾桶: [STONE*5, COBBLESTONE*30, DIRT]`；四类物品时按 `max-display-items: 3` 收到 `本次清理已回收到个人垃圾桶: [STONE*5, COBBLESTONE*30, DIRT, ...]`。RCON `debugsummary/stats` 同时确认世界/公共垃圾桶为 0，个人路由分别为 1、3、4 个堆叠；测试后已恢复临时 `config.yml`、`trash.yml`、`messages/message_zh.yml` 和 `data/worlds.yml`。
- Paper/Folia 的玩家掉落 owner 标记现在写在掉落实体 PDC 上，不再写入 `ItemStack` 的 `ItemMeta`，避免隐藏 PDC 破坏物品叠加；公共、个人、世界垃圾桶入库前会清理旧版本残留在 `ItemStack` 上的 `player_uuid` 标记。
- bStats 已合规接入四个平台产物：四个 jar 均包含 `Metrics.class` 和 `BStatsMetricsService.class`，四个平台入口均有 `BStatsMetricsService.start(...)` 与 `Metrics.shutdown()` 调用；Legacy/Bukkit/Paper 主类和 universal 内 Paper 分支均为 class major 52，Folia 主类为 class major 61。`paper-1.20.4-test-server` 验证新 Paper jar 正常加载，RCON `plugins` 显示 `WorldListTrashCan` 和 `PlaceholderAPI`，`wtc platform` 显示 `paper-1.16-1.20`，`wtc stats` 正常返回；`plugins/bStats/config.yml` 保持官方全局配置且 `enabled: true`。窄匹配未发现 WorldListTrashCan 或 bStats 异常。
- 7.0.0 版本构建已验证 5 个交付 jar 内 `plugin.yml` 均为 `version: 7.0.0`；`paper-1.20.4-test-server` 部署 universal 7.0.0 后，bStats serviceId `24350` 的服务端上报、返回响应和页面 `Plugin Version` 图表刷新均完成闭环。
- `/wtc reload` 已修复默认 yml 缺失时不会补回的问题：四个平台 `reloadPlugin()` 会先执行默认资源补齐，再读取配置和刷新功能模块。当前默认资源不再包含 `notify.yml`；清理通知已合并到 `cleanup.yml` 的 `notify.*` 区域。
- PrismaticAPI RGB 消息已完成构建和客户端侧视觉矩阵：四个平台 jar 均包含 relocation 后的 `pixeltech/bluenine/blworldtrashcan/libs/croabeast/prismatic/PrismaticAPI.class`，且不残留原始 `me/croabeast` 类。`/wtc debugrgb <玩家>` 已用真实原版客户端 F2 截图验证 GUI 标题、聊天文本、物品名和 Lore 等可见内容。通过版本包括 Paper 1.12.2、1.13.2、1.14.4、1.15.2、1.16.5、1.17.1、1.18.2、1.19.4、1.20.4 和 1.21.4；低版本为降级色，1.16.5+ 为 RGB。截图证据目录：`docs/test-evidence/rgb-visual-proof-20260607-104606/`。
- 默认中文文案已改为蓝、黄、灰、黑的 RGB 色板：冷蓝 `#38BDF8/#2563EB`，鎏金 `#F5B82E/#E7C873`，雾灰 `#D5DEE9/#AAB6C5/#64748B`，深墨 `#111827/#0F172A`。`/wtc debugrgbchannels <玩家>` 已补齐到四个轻量产物和 universal 产物，用于只展示聊天栏、ActionBar、Title 的 RGB 通道。Paper 1.20.4 + 原版 1.20.4 客户端验证通过，截图证据目录：`docs/test-evidence/rgb-blue-gold-palette-20260608-072705/`。
- 通用总包 `WorldListTrashCan-universal.jar` 已完成构建和四端 console smoke：同一个 jar 在 `paper-1.12.2-test-server` 识别为 `legacy-1.12`，在 `paper-1.13.2-test-server` 识别为 `bukkit-1.13-1.15`，在 `paper-1.20.4-test-server` 识别为 `paper-1.16-1.20`，在 `folia-1.20.1-test-server` 识别为 `folia-1.20`。通用总包内 PrismaticAPI 已 relocation，原始 `me/croabeast` 类数量为 0；主类 Java 8 加载 smoke 输出 `loaded-universal-main`。
- 2026-06-07 已补做真实客户端工作流回归，服务端日志只作为辅助排障，不作为最终通过依据。真实 Forge 1.12.2 客户端 `AIClientAlpha` 执行 30 条玩家侧聊天命令并写回 `status=PASS`，客户端断言覆盖 `platform/stats/reload`、旧别名、公共/个人/世界/黑名单 GUI、防丢弃模式、look、个人垃圾桶批量与单条提示、世界垃圾桶、三类路由和 `debugsummary`；`client-screen.log` 记录多个 `GuiChest, slots=90`，截图目录保留 32 张 PNG。
- 2026-06-07 已补做 universal 整包外部端三通道 RGB 复测：`E:\server_work` 下 6 个外部服务端全部部署同一个 `WorldListTrashCan-universal.jar`，RGB 证据限定聊天框、ActionBar、Title/Subtitle 的真实客户端 F2 截图，不使用箱子 GUI 或物品 Lore；每端 11 项基础功能检查全部 PASS。截图总览和日志证据目录：`docs/test-evidence/rgb-universal-channels-proof-20260607-175511/`。
- 2026-06-07 已补做高辨识度 RGB 二次复测：上一轮颜色被指出接近传统 `&a`、`&6` 后，调试 Title 改为多段 RGB 的 `RGB TITLE FF1493`，Subtitle 改为 `SUBTITLE FF4F00`。同一批 6 个外部端全部使用 `WorldListTrashCan-universal.jar` 重跑，RGB 截图仍限定聊天框、ActionBar、Title/Subtitle，每端 11 项基础功能检查全部 PASS。截图总览和日志证据目录：`docs/test-evidence/rgb-universal-highcontrast-channels-proof-20260607-202234/`。
- `docs/重构版完整功能与测试矩阵.md` 当前覆盖 F-001 至 F-092；正式命令入口为 `worldlisttrashcan/wtc`，公开权限为 `WorldListTrashCan.*`。历史 universal 整包矩阵及后续 GUI、世界垃圾桶、保护、实体限制、通知、Vault 和公共动作按钮专项共同构成当前回归基线。
- 2026-06-26 已补验扫地门禁 `-5` 正式通知：修复旧 `cleanup.yml` 不自动补齐新增 `-5` 文案、普通 Bukkit/Paper/Legacy 跳过路径不发送正式通知的问题。`dist/WorldListTrashCan-universal.jar` SHA256 `5D0BB85487F632DD3BC221D5BC749C5DEB3AF2FF92748E14A0C71A00CB134A0D`，Spigot 26.1.2、Folia 1.21.8、Paper 1.12.2 均用真实客户端和服务端截图复测 PASS；1.12.2 控制台中文乱码，因此以服务端截图中的 `cleanup.yml` `-5` 配置行、`skippedByGuard=true` 汇总和客户端可见中文截图共同验收。证据目录：`docs/test-evidence/cleanup-guard-visual-20260626-214947/`、`docs/test-evidence/cleanup-guard-visual-20260626-215753/`。
- 2026-06-27 已修复公共垃圾桶按次数刷新时序：`global-trash.clear-every-cleanups: 3` 触发时先清空旧公共垃圾桶，再把本轮清理物品写入公共垃圾桶，避免第 3 次清理把本轮新物品一起清空。`dist/WorldListTrashCan-universal.jar` SHA256 `52da08cc546e767d9a9a6b0bc983b8847c39e7ee787ec930b2c5a8aa3b756466`，Paper 1.12.2、Spigot 26.1.2、Folia 1.21.8 均用真实客户端连续三轮 `/wtc clear` + `/wtc debugstock` 截图复测 PASS；第 3 轮服务端日志为 `globalTrashRefreshed=true`，客户端库存仍为公共垃圾桶物品 `1`。证据目录：`docs/test-evidence/global-refresh-visual-20260627-013926/`、`docs/test-evidence/global-refresh-visual-20260627-014123/`、`docs/test-evidence/global-refresh-visual-20260627-014340/`。
- 2026-06-30 已补做清理通知专项真实客户端矩阵：同一个 `dist/WorldListTrashCan-universal.jar`，SHA256 `9691aff181a60413cdb2ebfdcade97e68fbb74b3a862757de5f7c5d46aabd5fd`，覆盖 Paper 1.12.2、Spigot 26.1.2、Folia 1.21.8。每端临时开启 `notify.chat/actionbar/bossbar/title/sound/command`，分别触发 `/wtc debugnotify 0` 和 `/wtc debugnotify -5`；真实客户端截图可见 Chat、ActionBar、BossBar、Title，客户端字幕 `Experience gained` 作为 Sound 辅助证据，服务端日志 `AI_WTC_NOTIFY_COMMAND_*` 作为 Command 执行证据。证据目录：`docs/test-evidence/cleanup-notify-visual-20260630-092840/`。
- 2026-07-01 已补做清理通知 Chat 可点击命令专项：同一个 `dist/WorldListTrashCan-universal.jar`，SHA256 `8e664bac3b5091c3db35e0e3db74d6c51680ccba854f7733923f9c86c384b2e9`，覆盖 Paper 1.12.2、Spigot 26.1.2、Folia 1.21.8。每端临时把 `notify.chat.click-command` 设置为 `/wtc stats`，触发 `/wtc debugnotify 0` 后用真实客户端点击 `AI_CLICK_NOTIFY_0` 文本，客户端日志均出现“清理统计”和“公共垃圾桶”，证明 F-058 完成通知点击命令生效。证据目录：`docs/test-evidence/cleanup-notify-click-20260701-190056/`。
- 2026-07-21 已用当前 universal jar `d821feef1a5e9158f027c530c048527195183d4954e41af13701ec8373b9ea24` 重跑 Spigot 26.1.2、Paper 1.12.2、Folia 1.21.8 三端 GUI 正向点击矩阵，公共/个人垃圾桶取放、公共取出冷却、公共分页、公共操作日志、公共黑名单保存和路由负向、个人满桶自动清空全部 PASS。证据目录：`docs/test-evidence/trash-gui-click-visual-20260721-023817/`。

本轮关键日志：

- `paper-1.12.2-test-server/ai-wtc-fullregression-rcon-smoke-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-rcon-main-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-debugplayer-rcon-smoke-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-debugplayer-rcon-online-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-debugplayer-rcon-main-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-final-rcon-20260601.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-final-latest-20260601.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-final-stop-20260601.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-final-stop-latest-20260601.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-debugplayer-client-stdout-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-fullregression-debugplayer-client-stderr-20260531.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-smoke-rcon-smoke.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-smoke-latest.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-smoke-stop-latest.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-resmoke-rcon-smoke-2.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-resmoke-latest.log`
- `paper-1.12.2-test-server/ai-wtc-platforms-20260601-legacy-resmoke-rcon-stop.log`
- `paper-1.20.4-test-server/ai-wtc-paper1204-smoke-rcon-main.log`
- `paper-1.20.4-test-server/ai-wtc-paper1204-smoke-latest.log`
- `paper-1.20.4-test-server/ai-wtc-paper1204-smoke-rcon-stop.log`
- `paper-1.12.2-test-server/ai-wtc-material-legacy-resmoke-20260602-042235-rcon-main.log`
- `paper-1.12.2-test-server/ai-wtc-material-legacy-resmoke-20260602-042235-latest.log`
- `paper-1.12.2-test-server/ai-wtc-material-legacy-resmoke-20260602-042235-rcon-stop.log`
- `paper-1.12.2-migration-test-server/ai-wtc-migration-adjacent-20260602-1726-rcon-main.log`
- `paper-1.12.2-migration-test-server/ai-wtc-migration-adjacent-20260602-1726-latest.log`
- `paper-1.12.2-migration-test-server/ai-wtc-migration-current-20260602-1732-rcon-main.log`
- `paper-1.12.2-migration-test-server/ai-wtc-migration-current-20260602-1732-latest.log`
- `paper-1.13.2-test-server/ai-wtc-bukkit113-guard-resmoke-20260602-1830-java8-console.log`
- `paper-1.13.2-test-server/ai-wtc-bukkit113-guard-resmoke-20260602-1830-java8-rcon-main.log`
- `paper-1.13.2-test-server/ai-wtc-bukkit113-guard-resmoke-20260602-1830-java8-rcon-stop.log`
- `paper-1.13.2-test-server/ai-wtc-bukkit113-guard-resmoke-20260602-1830-java8-latest.log`
- `paper-1.13.2-test-server/ai-wtc-bukkit113-guard-resmoke-20260602-1830-error.log`
- `folia-1.20.1-test-server/ai-wtc-folia1201-smoke-20260602-1853-latest.log`
- `folia-1.20.1-test-server/ai-wtc-folia1201-guarded-console-smoke-20260602-1803-latest.log`
- `folia-1.20.1-test-server/ai-wtc-folia-region-cleanup-20260602-latest.log`
- `folia-1.20.1-test-server/ai-wtc-folia-region-cleanup-20260602-commands.log`
- `folia-1.20.1-test-server/ai-wtc-folia-entitylimit-20260602-final-latest.log`
- `folia-1.20.1-test-server/ai-wtc-folia-entitylimit-20260602-final-commands.log`
- `folia-1.20.1-test-server/ai-wtc-folia-notify-20260602-latest.log`
- `folia-1.20.1-test-server/ai-wtc-folia-notify-20260602-commands.log`
- `paper-1.13.2-test-server/ai-wtc-worldtrash-chunkguard-20260602-1835-rcon-main-2.log`
- `paper-1.13.2-test-server/ai-wtc-worldtrash-chunkguard-20260602-1835-latest.log`
- `paper-1.13.2-test-server/ai-wtc-worldtrash-chunkguard-20260602-1835-trash-test.yml`
- `paper-1.13.2-test-server/ai-wtc-worldtrash-chunkguard-20260602-1835-worlds-test.yml`
- `paper-1.12.2-test-server/ai-wtc-bossbar-20260602-rcon-start.log`
- `paper-1.12.2-test-server/ai-wtc-bossbar-20260602-rcon-wait-client.log`
- `paper-1.12.2-test-server/ai-wtc-bossbar-20260602-rcon-clear-online.log`
- `paper-1.12.2-test-server/ai-wtc-bossbar-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-20260602-bossbar-modelid-smoke-backup/`
- `paper-1.12.2-test-server/ai-wtc-legacy-papi-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-legacy-papi-20260602-final-latest.log`
- `paper-1.13.2-test-server/ai-wtc-papi-bukkit113-20260602-rcon.log`
- `paper-1.20.4-test-server/ai-wtc-papi-paper1204-20260602-rcon.log`
- `paper-1.20.4-test-server/ai-wtc-bstats-20260603-rcon-main-2.log`
- `paper-1.20.4-test-server/ai-wtc-bstats-20260603-final-latest-2.log`
- `folia-1.20.1-test-server/ai-wtc-papi-folia1201-20260602-console.log`
- `folia-1.20.1-test-server/ai-wtc-papi-folia1201-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-add-world-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-add-world-20260602-stop.log`
- `paper-1.12.2-test-server/ai-wtc-add-world-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-message-service-20260602-console.log`
- `paper-1.12.2-test-server/ai-wtc-message-service-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-message-service-20260602-stop.log`
- `paper-1.12.2-test-server/ai-wtc-legacy-permission-20260602-console.log`
- `paper-1.12.2-test-server/ai-wtc-legacy-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-legacy-permission-20260602-stop.log`
- `paper-1.12.2-test-server/ai-wtc-legacy-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-globalban-instant-20260602-console.log`
- `paper-1.12.2-test-server/ai-wtc-globalban-instant-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-globalban-instant-20260602-stop.log`
- `paper-1.12.2-test-server/ai-wtc-globalban-instant-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-entity-toggle-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-entity-toggle-20260602-disabled-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-entity-toggle-20260602-disabled-rcon-2.log`
- `paper-1.12.2-test-server/ai-wtc-entity-toggle-20260602-restore-stop-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-entity-toggle-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-trash-op-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-trash-op-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-command-op-permission-20260602-rcon.log`
- `paper-1.12.2-test-server/ai-wtc-command-op-permission-20260602-final-latest.log`
- `paper-1.12.2-test-server/ai-wtc-effective-max-20260602-rcon-main.log`
- `paper-1.12.2-test-server/ai-wtc-effective-max-20260602-rcon-add-then-create.log`
- `paper-1.12.2-test-server/ai-wtc-effective-max-20260602-final-latest.log`
- `客户端自动化测试工作区/runs/20260602-wtc-effective-max-live-player/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260602-wtc-effective-max-live-player/logs/forge-latest-before-stop.log`
- `paper-1.12.2-test-server/ai-wtc-drop-owner-20260603-retry-rcon-main.log`
- `paper-1.12.2-test-server/ai-wtc-drop-owner-20260603-retry-rcon-control.log`
- `paper-1.12.2-test-server/ai-wtc-drop-owner-20260603-retry-final-latest.log`
- `客户端自动化测试工作区/runs/20260603-wtc-drop-owner-live-player-retry/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260603-wtc-drop-owner-live-player-retry/logs/forge-latest-before-stop.log`
- `客户端自动化测试工作区/runs/20260602-wtc-bossbar-real-client/control/client-response.properties`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-prismatic-20260605-032221/test-summary.md`
- `paper-1.20.4-test-server/ai-wtc-rgb-prismatic-20260605-032221-paper1204-rconhex-rcon-main.log`
- `paper-1.13.2-test-server/ai-wtc-rgb-prismatic-20260605-032221-bukkit113-final-rcon-main.log`
- `paper-1.12.2-test-server/ai-wtc-rgb-prismatic-20260605-032221-legacy112-rcon-main.log`
- `folia-1.20.1-test-server/ai-wtc-rgb-prismatic-20260605-032221-folia1201-latest.log`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/rgb-visual-proof-contact-sheet.png`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/screenshots/`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/docs/test-evidence/rgb-visual-proof-20260607-104606/summary.json`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-protocol-runs/final-20260607-rgb-matrix/summary.json`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/rgb-protocol-runs/final-20260607-rgb-matrix/summary-table.txt`
- `待重构插件/WorldListTrashCan重构/refactor-workspace/build/universal-console-smoke-summary-20260607-012407.txt`
- `paper-1.12.2-test-server/ai-wtc-universal-console-20260607-012407-legacy112.log`
- `paper-1.13.2-test-server/ai-wtc-universal-console-20260607-012407-bukkit113.log`
- `paper-1.20.4-test-server/ai-wtc-universal-console-20260607-012407-paper1204.log`
- `folia-1.20.1-test-server/ai-wtc-universal-console-20260607-012407-folia1201.log`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/control/client-response.properties`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/client-workflow-assertions.txt`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/logs/client-chat.log`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/logs/client-screen.log`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/logs/forge-latest.log`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/screenshots/runner_sequence_9.png`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/screenshots/runner_sequence_10.png`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/screenshots/runner_sequence_11.png`
- `客户端自动化测试工作区/runs/20260607-031912-wtc-client-workflow-regression/screenshots/runner_sequence_12.png`

已知测试环境噪声：

- 打开 GUI 时 EasyCore 会因缺少 `top.wcpe.wcneteasemodrpc.item.texture.match.TextureMatchs` 报 `InventoryOpenEvent` 异常；RCON 返回和 WorldListTrashCan debug 日志均显示本插件 GUI 打开调用已执行。
- 测试服上其他前置插件存在 MythicMobs 版本兼容警告和 Druid/MySQL 连接超时日志；本轮日志未发现 WorldListTrashCan 自身的 `UnsupportedClassVersionError`、`NoSuchMethodError`、`NoSuchFieldError` 或插件启用失败。
- 通用总包 1.12.2 smoke summary 里的 `ErrorPattern=true` 来自测试服其它前置插件噪声；同轮日志中 WorldListTrashCan 已正常启用，平台识别、命令和停服流程均有证据。
- 2026-06-07 真实客户端工作流回归中，Paper/Forge 日志只用于辅助定位；玩家可见功能结论以 `client-response.properties`、`client-workflow-assertions.txt`、`client-chat.log`、`client-screen.log` 和真实 PNG 截图为准。
- Paper 1.13.2 不能使用默认 Java 21 启动，本轮误用 Java 21 时服务端输出 `Unsupported Java detected (65.0). Only up to Java 12 is supported.`；有效复测使用 `C:\Program Files\Java\jdk-1.8\bin\java.exe` 启动。
- Folia 当前已经完成世界清理、专用实体限制、低占用密集实体扫描和多轮真实客户端回归；清理通知 Chat、ActionBar、BossBar、Title、Sound 和 Command 也已补专项真实客户端截图与日志证据。但这仍不等于整产物 `FOLIA_REGION_SAFE`，因为 Command 通知可能被服主配置成触发其它插件的非 region-safe 行为。
- 本机旧 PlaceholderAPI 2.11.6 不支持 Folia；Folia PAPI 变量已换用支持 Folia 的 `[PAPI]PlaceholderAPI-2.11.7-DEV-null (1).jar` 完成验证。后续服主环境仍必须安装支持 Folia 的 PlaceholderAPI，不能用普通 Paper 的 PAPI 验证结果替代 Folia。
- `world-trash.allow-load-unloaded-chunks` 默认 `false` 会改变旧插件“远处真实箱子也尽量写入”的行为；这是为了避免后台清理同步加载区块。确实需要旧行为时可以改为 `true`，但会在启动时输出性能风险警告。
