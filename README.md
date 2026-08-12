# WorldListTrashCan

清理与垃圾桶插件，面向 Bukkit、Spigot、Paper 和 Folia/Luminol。

[中文](#中文) | [English](#english)

## 中文

WorldListTrashCan 保留旧版的世界垃圾桶、公共垃圾桶、个人垃圾桶、物品黑名单、实体清理、密集实体清理、防丢弃和清理提示，同时重点解决旧版配置混乱、Folia 卡顿、区块强加载、物品误删和跨版本使用不一致的问题。

### 服主最关心的变化

#### 真正新增的功能

| 功能 | 旧版本 | 重构版 |
| --- | --- | --- |
| 公共垃圾桶布局 | 固定布局 | 可配置 1-6 行内容区、翻页按钮、背景和展示物品 |
| 公共垃圾桶按钮 | 材质和位置固定 | 支持材质候选、CustomModelData、名称、Lore 和替代物品 |
| 公共垃圾桶显示模式 | 只有原版堆叠显示 | `compact` 紧凑模式默认每种物品只显示一个，数量写入 Lore；`stacked` 保留 64/16/1 的旧显示方式 |
| 公共垃圾桶排序 | 所有玩家共用固定进入顺序 | 每名玩家可独立选择进入顺序、数量升降序、名称或材质排序；`compact` 与 `stacked` 偏好互不污染 |
| 紧凑模式容量 | 无单物品逻辑上限 | 每种物品可配置累计上限，`-1` 为无限；达到上限时按剩余容量接收，不会把一批物品拆成大量条目 |
| 公共垃圾桶缩容 | 缩小容量可能造成旧物品无处显示 | 自动进入只读溢出页，可查看和取出，不静默丢失 |
| 公共垃圾桶动作 | 只能使用固定按钮 | 支持 `[console]`、`[command]`、`[message]`、`[close]`、`type: close` 和 PAPI 变量 |
| 个人垃圾桶提示 | 单个或批量提示不完整 | 单个物品单独提示，扫地批量汇总提示，默认显示前 3 类 |
| 扫地启动条件 | 到时间就执行 | 可按在线人数和实体数量跳过低压力清理 |
| 手动扫地 | 只有固定执行方式 | `/wtc clear true/false` 可选择是否忽略扫地门禁 |
| 扫地世界过滤 | 只能逐个填写不清理的世界 | `include/exclude` 支持 `*` 通配；默认允许全部世界并保护名称包含 dungeon 的世界 |
| 清理状态 | 主要依赖倒计时变量 | `/wtc stats` 直接查看回收、删除、实体、库存和剩余时间 |
| 控制台明细 | 主要显示总数 | 显示前 10 类实体名称、类型、实际数量和其他数量 |
| 实体保护 | 保护项有限 | 支持鞍保护和 Bukkit `Tameable` 主人保护，并排除误判的 shooter/source/掉落者 |
| 按世界直接删除 | 没有独立配置 | 可指定世界，垃圾不进任何垃圾桶，直接删除 |
| 移动物品保护 | 没有 | 可选择扫地时跳过仍在移动的掉落物，默认关闭 |
| 潜影盒物品保护 | 没有 | 可选择跳过掉落物携带的装满潜影盒物品，默认关闭 |
| 自定义数据物品路由 | 只能依赖材质、名称和 Lore 排除 | 可按 Material、名称、Lore、PDC key、Raw NBT/Data Components key 识别，并选择只进个人桶、留地或直接删除 |
| 公共垃圾桶准入 | 只有材质黑名单 | 可选五类规则白名单；未命中物品不会进入公共桶，扫地拒绝动作可选留地或直接删除 |

上表只列服主能直接感知的业务变化，没有把内部实现细节重复算成新功能。

### 公共垃圾桶显示模式

两种模式使用独立配置节点，服主只需要修改 `global-trash.mode`，不需要手动切换服务端类型：

```yaml
global-trash:
  # compact 为紧凑模式；stacked 为旧版 64/16/1 堆叠显示。
  mode: "compact"
  compact:
    # 玩家首次打开时使用的排序；GUI 中的选择只在本次在线期间保留。
    default-sort: "insertion"
    # 紧凑模式最多显示多少页；每个内容槽代表一种物品。
    max-pages: 5
    # 单种物品最多累计数量；-1 表示无限。
    max-amount-per-entry: 9999
    # 数量、原始 Lore 截断和操作提示都写在展示物 Lore 中。
    max-original-lore-lines: 5
    left-click-amount: 1
    shift-left-click-amount: 64
  stacked:
    # stacked 模式独立的默认排序。
    default-sort: "insertion"
    # 旧模式自己的页数，不复用 compact.max-pages。
    max-pages: 5
```

紧凑模式不会把每个数量拆成多个展示物。例如当前有 `9980` 个物品，再放入 `20` 个时会接收到剩余容量，显示为 `9999`，不会产生 20 个重复条目；超过容量的剩余部分按原有路由规则继续处理。公共垃圾桶存量是运行期数据，重启后按原版行为清空。

公共垃圾桶默认底栏提供玩家独立排序按钮，支持进入顺序、数量升降序、名称 A-Z 和材质 A-Z。排序只在打开菜单或玩家明确切换时执行；打开后的翻页和取物使用同一份轻量条目 ID 快照，不会因其他玩家操作或扫地入库突然重排。每名玩家的 `compact`、`stacked` 偏好分别保存在内存中，退出后释放，不写数据库，也不会改变公共存储的真实顺序。

公共垃圾桶布局展示物支持 `glow: true` 附魔光效，适用于翻页、背景、排序、actions 和关闭按钮。Minecraft 1.20.5 及以上使用 Bukkit 原生纯光效，不写入真实附魔；旧版本自动降级为隐藏附魔，Tooltip 不显示附魔名称。`type: content` 始终忽略该字段，不会修改真实垃圾物品。

#### 兼容性和稳定性增强

- 提供 `WorldListTrashCan-universal.jar`，高低版本和 Folia/Luminol 使用同一个整包；也保留轻量分版本 Jar。
- 1.12.2 到 1.21.x 版本线提供兼容实现；1.16.5+ 支持 RGB，低版本自动降级，也兼容 `&a`、`&c` 等传统颜色码。
- Folia/Luminol 使用区域安全的分段清理任务，不把普通 Bukkit 定时任务直接运行到 Folia 环境。
- 世界垃圾桶默认不强制加载未加载区块，避免清理时突然加载区块造成卡顿。
- 玩家掉落标记放在掉落实体上，不写入物品本身，避免影响物品正常堆叠。
- 旧版配置会被识别并隔离到 `old-version-config`，不直接拿旧配置启动新版逻辑。
- 默认配置缺失项会补回，并且默认配置项带有中文注释。
- bStats 已内置，服主不需要额外开关；插件版本为 `7.0.0`。

### 性能优化估算

以下是根据新旧代码执行路径、默认批量配置和实体数量模型得到的估算，不是 Spark 实测承诺。实际结果会受到加载区块、实体数量、玩家活动和服务器核心影响。

| 场景 | 预计优化 |
| --- | ---: |
| 1000 个加载区块的密集实体周期检查 | 约减少 `96%` 的检查压力 |
| 4096 个加载区块的密集实体周期检查 | 约减少 `96.6%` 的检查压力 |
| 5000 个实体、每秒生成 20 只受限实体的生成路径 | 约减少 `99.98%` 的重复遍历压力 |
| 无玩家在线的自动扫地 | 直接跳过，相关本轮压力接近减少 `100%` |
| 世界垃圾桶位于未加载区块 | 插件主动强加载减少 `100%`，默认改为跳过并降级路由 |
| Folia 4096 区块单轮派发峰值 | 理论瞬时派发峰值约减少 `98.4%` |

#### 综合估算

- 只使用普通定时扫地的服务器：相关路径通常优化约 `0%-30%`。
- 开启密集实体限制的大型服务器：相关主线程压力预计优化约 `80%-99%`。
- 旧版主要卡在世界垃圾桶同步加载区块时：单次清理卡顿峰值预计优化 `90%+`。
- 重构版会增加有限的实体索引和候选队列，预计额外内存约 `5-10MB`，并设置数量上限控制内存增长。

这里的百分比是“对应场景的预计减少”，不是插件整体固定减少百分比。要得到某个服务器的真实数字，应使用 Spark 或服务器自身监控，在相同地图、玩家数和实体数量下分别测试旧版与重构版。

### 推荐配置

`cleanup.yml` 的普通扫地世界过滤与 `entity-limits.yml` 的实体限制过滤互相独立：

```yaml
# cleanup.yml
world-filter:
  # 默认允许全部 Bukkit 世界名；支持 * 通配且不区分大小写。
  include:
    - "*"
  # exclude 优先于 include；默认保护名称中包含 dungeon 的世界。
  exclude:
    - "*dungeon*"

guards:
  # 在线玩家少于该值时跳过自动扫地。
  min-online-players: 1
  # 目标实体少于该值时跳过自动扫地。
  min-total-entities: 150

# 默认关闭，开启后扫地跳过仍在移动的掉落物。
moving-items:
  enabled: false
  minimum-speed: 0.01

# 默认关闭，开启后扫地跳过掉落物携带的装满潜影盒物品。
filled-shulker-boxes:
  enabled: false
```

`world-filter` 同时作用于定时扫地和 `/wtc clear true/false`；`clear true` 只忽略 guards，不会绕过世界过滤。它不影响仙人掌、岩浆、虚空等独立回收，也不读取 `entity-limits.yml` 的 `world-limits.ignored-worlds` 或 `gather-limits.ignored-worlds`。

“潜影盒物品”指掉落物实体携带的 `ItemStack`，不是世界中已经放置的潜影盒方块。开启后，装有物品的潜影盒掉落物会保留在地面；空潜影盒仍会正常清理。

### 自定义数据物品路由

PDC 只是 Bukkit 1.14+ 的一种自定义数据来源。插件还可以在支持的运行时读取 Raw NBT/Data Components 的 key 路径，因此第三方插件或混合端写入、但没有固定名称和 Lore 的物品也可以被识别。插件只读取 key 和路径，不读取或输出对应值；`/wtc look` 会显示手持物品的 Material、名称、Lore、PDC key 和 Raw NBT 路径，便于填写配置。

```yaml
# cleanup.yml
custom-data-items:
  # 绝对保护，优先于直删世界和下面的 routing；旧顶层写法仍会合并读取。
  ignored-materials: []
  ignored-name-fragments: []
  ignored-lore-fragments: []
  routing:
    # 默认关闭；关闭时扫地不会读取 PDC 或 Raw NBT。
    enabled: false
    detection:
      # 五类规则是 OR，任意一类命中即可。
      material-patterns: []
      name-key-patterns: []
      lore-key-patterns: []
      pdc-key-patterns:
        - "*"
      nbt-key-patterns: []
    # personal-only、keep-ground、direct-remove。
    mode: "personal-only"
    # personal-only 无法确认物主或个人桶不可用时的动作。
    personal-unavailable: "keep-ground"
```

名称和 Lore 未写 `*` 时继续按“包含”匹配；Material、PDC key 和 NBT key 未写 `*` 时按完整值匹配。所有规则不区分大小写，`*` 是简单通配而不是正则表达式。`direct-remove-worlds` 高于 routing，但 `custom-data-items.ignored-*` 仍是最高优先级保护。

公共桶可以独立开启准入白名单，且会同时限制扫地、其它插件调用和玩家从 GUI 手动放入：

```yaml
# trash.yml
global-trash:
  admission-whitelist:
    enabled: false
    material-patterns: []
    name-key-patterns: []
    lore-key-patterns: []
    pdc-key-patterns: []
    nbt-key-patterns: []
    rejected-cleanup-action: "keep-ground"
```

白名单开启但五类规则全部为空时会拒绝全部物品。`global-trash.banned-materials` 的拒绝优先级仍高于白名单。Raw NBT 规则属于可选的较重检查，建议先用 `/wtc look` 找到尽可能具体的 key 路径，不要无目的填写 `*`。

### 常用命令

```text
/wtc help                 查看帮助
/wtc reload               重载配置
/wtc stats                查看最近一次清理状态
/wtc clear true           手动清理并忽略扫地门禁
/wtc clear false          手动清理但遵守扫地门禁
/wtc look                 查看手持物品的匹配信息，或等待右键查询实体
```

正式长命令为 `/worldlisttrashcan`，简写为 `/wtc`。权限统一使用 `WorldListTrashCan.*`。

### 可选清理审计附属

`WorldListTrashCanAudit` 通过公开 API v3 接收扫地记录。主插件会为公共垃圾桶的每个运行期条目和个人垃圾桶的同源物品生成不透明追踪键，因此玩家各自的排序、翻页和展示模式不会改变实际领取追踪。世界垃圾桶与直接删除没有后续领取，只记录最终世界/坐标或删除去向。

API v3 是破坏式更新，不兼容尚未发布的旧 Audit API/Jar。安装 Audit 时必须使用与当前主插件 API v3 对应的版本；未安装附属插件时不会计算个人垃圾桶物品身份哈希，也不会创建数据库线程。

### 使用哪个 Jar

- 想在不同服务端之间无缝切换：使用 `WorldListTrashCan-universal.jar`。
- 想减少包体和运行时分支：按服务端版本使用对应的轻量分版本 Jar。
- 同一个服务端的 `plugins` 目录只放一个 WorldListTrashCan Jar，不能同时放 universal 和轻量版本。

### 当前通用整包

- 版本：`7.0.0`
- 文件：`WorldListTrashCan-universal.jar`
- SHA-256：`52842291f67c3741195577529d320ad893ba23305b342fe7cff1759c8896965b`
- 公共垃圾桶排序已在 Paper 1.12.2、Paper 1.21.4 和 Folia 1.21.4 使用真实客户端验证。
- 自定义数据路由已在 Paper 1.12.2 验证 Raw NBT，在 Folia 1.21.8 验证 PDC、个人桶路由、留地、直删和公共桶准入。
- 公共垃圾桶 `glow` 已使用同一整包在 Paper 1.12.2、Paper 1.20.4 和 Folia 1.21.8 完成真实客户端验证。

## English

WorldListTrashCan is a cleanup and trash-can plugin for Bukkit, Spigot, Paper, and Folia/Luminol.

It keeps the legacy world trash can, public trash can, personal trash can, item blacklist, entity cleanup, dense entity cleanup, drop protection, and cleanup notifications. The refactored version also addresses the legacy configuration sprawl, Folia lag, forced chunk loading, accidental item loss, and inconsistent cross-version behavior.

### Changes administrators will notice

#### New user-facing features

| Feature | Legacy version | Refactored version |
| --- | --- | --- |
| Public trash-can layout | Fixed layout | Configurable 1-6 row content area, page buttons, background, and display items |
| Public trash-can buttons | Fixed material and position | Material fallbacks, CustomModelData, name, Lore, and replacement items |
| Public trash-can display mode | Only vanilla stack display | `compact` shows one display item per type and puts the amount in Lore; `stacked` preserves the legacy 64/16/1 display |
| Public trash-can sorting | One fixed insertion order shared by everyone | Each player can independently select insertion, amount, name, or material sorting; `compact` and `stacked` preferences remain separate |
| Compact per-item capacity | No logical per-item cap | Each type has a configurable accumulated cap; `-1` means unlimited, and incoming batches use remaining capacity instead of creating duplicate entries |
| Smaller public trash-can capacity | Items could become inaccessible | A read-only overflow page keeps items visible and retrievable instead of silently losing them |
| Public trash-can actions | Only fixed button behavior | Supports `[console]`, `[command]`, `[message]`, `[close]`, `type: close`, and PlaceholderAPI variables |
| Personal trash-can notifications | Incomplete single-item and batch messages | Individual notifications for single items and grouped notifications for cleanup batches, showing up to 3 types by default |
| Cleanup guards | Cleanup ran when its timer elapsed | Automatic cleanup can be skipped when online-player or entity-count thresholds are not met |
| Manual cleanup | One fixed execution mode | `/wtc clear true/false` chooses whether cleanup guards are ignored |
| Cleanup world filter | Only per-world exclusions | `include/exclude` supports `*` wildcards; all worlds are included by default while names containing dungeon are protected |
| Cleanup status | Mainly exposed through countdown variables | `/wtc stats` shows the latest recovery, deletion, entity, inventory, and remaining-time status |
| Console details | Mostly showed totals | Shows the top 10 entity categories with display name, type, actual count, and remainder count |
| Entity protection | Limited protection rules | Protects saddled entities and Bukkit `Tameable` owners while avoiding shooter/source/drop-owner false positives |
| Direct deletion by world | No dedicated setting | Selected worlds can delete items directly without routing them to any trash can |
| Moving-item protection | Not available | Cleanup can skip moving dropped items; disabled by default |
| Filled shulker-box item protection | Not available | Cleanup can skip dropped item stacks containing filled shulker boxes; disabled by default |
| Custom-data item routing | Exclusions relied on material, name, and Lore | Material, name, Lore, PDC keys, and Raw NBT/Data Components keys can route items to personal trash only, keep them on the ground, or remove them directly |
| Public trash admission | Material blacklist only | An optional five-source allowlist controls every public-trash entry; rejected cleanup items can remain on the ground or be removed |

This table lists changes directly visible to server administrators and does not count internal implementation details as separate features.

### Public trash-can display modes

The two modes have independent configuration nodes. Administrators only change `global-trash.mode`; no server-type switch is required:

```yaml
global-trash:
  # compact is the compact mode; stacked keeps the legacy 64/16/1 display.
  mode: "compact"
  compact:
    # Default sort for the first open; GUI choices last only for the online session.
    default-sort: "insertion"
    # Maximum compact pages; each content slot represents one item type.
    max-pages: 5
    # Maximum accumulated amount for one type; -1 means unlimited.
    max-amount-per-entry: 9999
    # Amount, original Lore truncation, and operation hints are placed in Lore.
    max-original-lore-lines: 5
    left-click-amount: 1
    shift-left-click-amount: 64
  stacked:
    # Independent default sort for stacked mode.
    default-sort: "insertion"
    # Independent page count for the legacy mode.
    max-pages: 5
```

Compact mode does not split a batch into many duplicate display entries. For example, when `9980` items are stored and another `20` arrive, the remaining capacity is accepted and the entry becomes `9999`; any remainder follows the existing routing rules. Public trash contents are runtime data and are cleared on restart, matching the legacy behavior.

The default footer includes per-player sorting for insertion order, amount ascending or descending, name A-Z, and material A-Z. Sorting runs only when the menu is opened or the player explicitly switches modes. Pagination and item taking keep the same lightweight entry-ID snapshot, so another player or cleanup deposit cannot unexpectedly reorder an open menu. Compact and stacked preferences are held separately in memory, released on quit, never written to a database, and never mutate the global storage order.

Layout display items support `glow: true` for page, background, sort, actions, and close buttons. Minecraft 1.20.5 and newer use Bukkit's native glint override without a real enchantment. Older versions automatically fall back to a hidden enchantment, so no enchantment name appears in the tooltip. `type: content` always ignores this option and never mutates stored trash items.

#### Compatibility and stability improvements

- `WorldListTrashCan-universal.jar` runs across supported server versions, including Folia/Luminol; lightweight version-specific JARs are also available.
- Compatible implementations are provided across the 1.12.2-1.21.x version line. RGB is supported on 1.16.5 and newer, with automatic downgrade on older versions. Legacy `&a` and `&c` color codes remain supported.
- Folia/Luminol uses region-safe, segmented cleanup tasks instead of running ordinary Bukkit repeating tasks in a Folia environment.
- Unloaded chunks are not force-loaded by default for world trash cans, preventing sudden cleanup lag spikes.
- Player-drop ownership is stored on the dropped entity rather than inside the item stack, so normal item stacking is not affected.
- Legacy configurations are detected and isolated in `old-version-config` instead of being used directly by the new implementation.
- bStats is built in and has no plugin-level enable/disable switch; the plugin version is `7.0.0`.

### Estimated performance improvements

The figures below are estimates based on old and new execution paths, default batch settings, and entity-count models. They are not a Spark benchmark guarantee. Actual results depend on loaded chunks, entity counts, player activity, and the server core.

| Scenario | Estimated improvement |
| --- | ---: |
| Periodic dense-entity checks across 1,000 loaded chunks | About `96%` less checking pressure |
| Periodic dense-entity checks across 4,096 loaded chunks | About `96.6%` less checking pressure |
| 5,000 entities with 20 restricted entities generated per second | About `99.98%` less repeated traversal pressure |
| Automatic cleanup with no players online | Skipped directly; near `100%` reduction for that cycle |
| World trash can in an unloaded chunk | Plugin-initiated forced loading reduced by `100%`; defaults to skip and fallback routing |
| Folia peak dispatch for a 4,096-chunk cycle | Theoretical instantaneous dispatch peak reduced by about `98.4%` |

#### Overall estimate

- Servers using only ordinary scheduled cleanup: typically about `0%-30%` improvement in the related path.
- Large servers with dense-entity limits enabled: estimated related main-thread pressure reduction of `80%-99%`.
- Servers previously affected by synchronous world-trash-can chunk loading: estimated single-cleanup lag-spike reduction of `90%+`.
- The refactored version adds a bounded entity index and candidate queue; estimated additional memory usage is about `5-10MB`, with limits to prevent unbounded growth.

These percentages describe estimated reduction for the specified scenario, not a fixed reduction for the entire plugin. For server-specific numbers, compare the legacy and refactored versions with Spark or server monitoring under the same map, player count, and entity count.

### Recommended configuration

The ordinary cleanup world filter in `cleanup.yml` remains independent from entity-limit filters in `entity-limits.yml`:

```yaml
# cleanup.yml
world-filter:
  # Allow every Bukkit world name by default. Matching is case-insensitive and supports *.
  include:
    - "*"
  # exclude wins over include; worlds whose names contain dungeon are protected by default.
  exclude:
    - "*dungeon*"

guards:
  # Skip automatic cleanup when online players are below this value.
  min-online-players: 1
  # Skip automatic cleanup when the target entity count is below this value.
  min-total-entities: 150

# Disabled by default. When enabled, moving dropped items are skipped.
moving-items:
  enabled: false
  minimum-speed: 0.01

# Disabled by default. When enabled, dropped item stacks containing filled
# shulker boxes are skipped.
filled-shulker-boxes:
  enabled: false
```

`world-filter` applies to scheduled cleanup and `/wtc clear true/false`; `clear true` only bypasses guards and never bypasses the world filter. It does not affect cactus, lava, void, or other independent recovery listeners, and it does not read `world-limits.ignored-worlds` or `gather-limits.ignored-worlds` from `entity-limits.yml`.

“Shulker-box items” means an `ItemStack` carried by a dropped-item entity, not a shulker-box block placed in the world. When enabled, dropped filled shulker boxes remain on the ground; empty shulker boxes are still cleaned normally.

### Custom-data item routing

PDC is only one custom-data source available on Bukkit 1.14 and newer. When the runtime supports it, WorldListTrashCan can also inspect Raw NBT/Data Components key paths, allowing it to identify third-party or hybrid-server items that have no stable display name or Lore. Only keys and paths are read and shown; values are never exposed. `/wtc look` reports the held item's Material, name, Lore, PDC keys, and Raw NBT paths for configuration.

```yaml
# cleanup.yml
custom-data-items:
  # Absolute protection; legacy top-level lists are still merged.
  ignored-materials: []
  ignored-name-fragments: []
  ignored-lore-fragments: []
  routing:
    # Disabled by default; PDC and Raw NBT are not read in cleanup while disabled.
    enabled: false
    detection:
      # The five sources use OR semantics.
      material-patterns: []
      name-key-patterns: []
      lore-key-patterns: []
      pdc-key-patterns:
        - "*"
      nbt-key-patterns: []
    # personal-only, keep-ground, or direct-remove.
    mode: "personal-only"
    personal-unavailable: "keep-ground"
```

Plain name and Lore rules keep substring semantics. Plain Material, PDC-key, and NBT-key rules require an exact match. Matching is case-insensitive, and `*` is a lightweight wildcard rather than a regular expression. `direct-remove-worlds` takes priority over routing, while `custom-data-items.ignored-*` remains the highest-priority item protection.

The public trash can has a separate admission allowlist that applies to cleanup, API/service deposits, and manual GUI deposits:

```yaml
# trash.yml
global-trash:
  admission-whitelist:
    enabled: false
    material-patterns: []
    name-key-patterns: []
    lore-key-patterns: []
    pdc-key-patterns: []
    nbt-key-patterns: []
    rejected-cleanup-action: "keep-ground"
```

Enabling the allowlist with all five rule lists empty rejects every item. `global-trash.banned-materials` still has higher rejection priority. Raw NBT matching is an optional, heavier path; use `/wtc look` and configure specific key paths instead of an unrestricted `*` whenever possible.

### Common commands

```text
/wtc help                 Show help
/wtc reload               Reload configuration
/wtc stats                Show the latest cleanup status
/wtc clear true           Run manual cleanup and ignore cleanup guards
/wtc clear false          Run manual cleanup while respecting cleanup guards
/wtc look                 Inspect held-item match data or wait to inspect an entity
```

The formal long command is `/worldlisttrashcan`, with `/wtc` as its short alias. Permissions use the `WorldListTrashCan.*` namespace.

### Optional cleanup audit add-on

`WorldListTrashCanAudit` consumes cleanup records through public API v3. The main plugin assigns opaque tracking keys to runtime public-trash entries and same-source personal-trash items, so per-player sorting, pagination, and display modes do not change withdrawal tracking. World trash and direct removal have no later withdrawal event and therefore record only their final location or removal destination.

API v3 is a breaking update and does not retain compatibility with the unpublished legacy Audit API/JAR. Install an Audit build that targets the current API v3. When the add-on is absent, the main plugin does not calculate personal-trash identity hashes or create database threads.

### Which JAR should I use?

- For seamless switching between supported server types and versions, use `WorldListTrashCan-universal.jar`.
- For a smaller package and fewer runtime branches, use the lightweight JAR matching the server version.
- Put only one WorldListTrashCan JAR in a server's `plugins` directory. Do not install both the universal and lightweight JARs together.

Final universal artifact information:

- Version: `7.0.0`
- File: `WorldListTrashCan-universal.jar`
- SHA-256: `52842291f67c3741195577529d320ad893ba23305b342fe7cff1759c8896965b`
- Public trash-can sorting was verified with real clients on Paper 1.12.2, Paper 1.21.4, and Folia 1.21.4.
- Custom-data routing was verified with Raw NBT on Paper 1.12.2 and with PDC, personal-only routing, keep-ground, direct removal, and public admission rules on Folia 1.21.8.
- Public trash-can `glow` was verified with the same universal JAR on Paper 1.12.2, Paper 1.20.4, and Folia 1.21.8 using real clients.
