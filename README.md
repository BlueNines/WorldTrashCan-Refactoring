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
| 公共垃圾桶缩容 | 缩小容量可能造成旧物品无处显示 | 自动进入只读溢出页，可查看和取出，不静默丢失 |
| 公共垃圾桶动作 | 只能使用固定按钮 | 支持 `[console]`、`[command]`、`[message]` 和 PAPI 变量 |
| 个人垃圾桶提示 | 单个或批量提示不完整 | 单个物品单独提示，扫地批量汇总提示，默认显示前 3 类 |
| 扫地启动条件 | 到时间就执行 | 可按在线人数和实体数量跳过低压力清理 |
| 手动扫地 | 只有固定执行方式 | `/wtc clear true/false` 可选择是否忽略扫地门禁 |
| 清理状态 | 主要依赖倒计时变量 | `/wtc stats` 直接查看回收、删除、实体、库存和剩余时间 |
| 控制台明细 | 主要显示总数 | 显示前 10 类实体名称、类型、实际数量和其他数量 |
| 实体保护 | 保护项有限 | 支持鞍保护和 Bukkit `Tameable` 主人保护，并排除误判的 shooter/source/掉落者 |
| 按世界直接删除 | 没有独立配置 | 可指定世界，垃圾不进任何垃圾桶，直接删除 |
| 移动物品保护 | 没有 | 可选择扫地时跳过仍在移动的掉落物，默认关闭 |
| 潜影盒物品保护 | 没有 | 可选择跳过掉落物携带的装满潜影盒物品，默认关闭 |

上表只列服主能直接感知的业务变化，没有把内部实现细节重复算成新功能。

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

重构版的新增保护默认不会改变旧用户行为：

```yaml
# cleanup.yml
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

“潜影盒物品”指掉落物实体携带的 `ItemStack`，不是世界中已经放置的潜影盒方块。开启后，装有物品的潜影盒掉落物会保留在地面；空潜影盒仍会正常清理。

### 常用命令

```text
/wtc help                 查看帮助
/wtc reload               重载配置
/wtc stats                查看最近一次清理状态
/wtc clear true           手动清理并忽略扫地门禁
/wtc clear false          手动清理但遵守扫地门禁
```

正式长命令为 `/worldlisttrashcan`，简写为 `/wtc`。权限统一使用 `WorldListTrashCan.*`。

### 使用哪个 Jar

- 想在不同服务端之间无缝切换：使用 `WorldListTrashCan-universal.jar`。
- 想减少包体和运行时分支：按服务端版本使用对应的轻量分版本 Jar。
- 同一个服务端的 `plugins` 目录只放一个 WorldListTrashCan Jar，不能同时放 universal 和轻量版本。


## English

WorldListTrashCan is a cleanup and trash-can plugin for Bukkit, Spigot, Paper, and Folia/Luminol.

It keeps the legacy world trash can, public trash can, personal trash can, item blacklist, entity cleanup, dense entity cleanup, drop protection, and cleanup notifications. The refactored version also addresses the legacy configuration sprawl, Folia lag, forced chunk loading, accidental item loss, and inconsistent cross-version behavior.

### Changes administrators will notice

#### New user-facing features

| Feature | Legacy version | Refactored version |
| --- | --- | --- |
| Public trash-can layout | Fixed layout | Configurable 1-6 row content area, page buttons, background, and display items |
| Public trash-can buttons | Fixed material and position | Material fallbacks, CustomModelData, name, Lore, and replacement items |
| Smaller public trash-can capacity | Items could become inaccessible | A read-only overflow page keeps items visible and retrievable instead of silently losing them |
| Public trash-can actions | Only fixed button behavior | Supports `[console]`, `[command]`, `[message]`, and PlaceholderAPI variables |
| Personal trash-can notifications | Incomplete single-item and batch messages | Individual notifications for single items and grouped notifications for cleanup batches, showing up to 3 types by default |
| Cleanup guards | Cleanup ran when its timer elapsed | Automatic cleanup can be skipped when online-player or entity-count thresholds are not met |
| Manual cleanup | One fixed execution mode | `/wtc clear true/false` chooses whether cleanup guards are ignored |
| Cleanup status | Mainly exposed through countdown variables | `/wtc stats` shows the latest recovery, deletion, entity, inventory, and remaining-time status |
| Console details | Mostly showed totals | Shows the top 10 entity categories with display name, type, actual count, and remainder count |
| Entity protection | Limited protection rules | Protects saddled entities and Bukkit `Tameable` owners while avoiding shooter/source/drop-owner false positives |
| Direct deletion by world | No dedicated setting | Selected worlds can delete items directly without routing them to any trash can |
| Moving-item protection | Not available | Cleanup can skip moving dropped items; disabled by default |
| Filled shulker-box item protection | Not available | Cleanup can skip dropped item stacks containing filled shulker boxes; disabled by default |

This table lists changes directly visible to server administrators and does not count internal implementation details as separate features.

#### Compatibility and stability improvements

- `WorldListTrashCan-universal.jar` runs across supported server versions, including Folia/Luminol; lightweight version-specific JARs are also available.
- Compatible implementations are provided across the 1.12.2-1.21.x version line. RGB is supported on 1.16.5 and newer, with automatic downgrade on older versions. Legacy `&a` and `&c` color codes remain supported.
- Folia/Luminol uses region-safe, segmented cleanup tasks instead of running ordinary Bukkit repeating tasks in a Folia environment.
- Unloaded chunks are not force-loaded by default for world trash cans, preventing sudden cleanup lag spikes.
- Player-drop ownership is stored on the dropped entity rather than inside the item stack, so normal item stacking is not affected.
- Legacy configurations are detected and isolated in `old-version-config` instead of being used directly by the new implementation.
- Missing default configuration entries are restored, and default configuration files include Chinese comments.
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

The new protections are disabled by default and do not change legacy behavior until enabled:

```yaml
# cleanup.yml
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

“Shulker-box items” means an `ItemStack` carried by a dropped-item entity, not a shulker-box block placed in the world. When enabled, dropped filled shulker boxes remain on the ground; empty shulker boxes are still cleaned normally.

### Common commands

```text
/wtc help                 Show help
/wtc reload               Reload configuration
/wtc stats                Show the latest cleanup status
/wtc clear true           Run manual cleanup and ignore cleanup guards
/wtc clear false          Run manual cleanup while respecting cleanup guards
```

The formal long command is `/worldlisttrashcan`, with `/wtc` as its short alias. Permissions use the `WorldListTrashCan.*` namespace.

### Which JAR should I use?

- For seamless switching between supported server types and versions, use `WorldListTrashCan-universal.jar`.
- For a smaller package and fewer runtime branches, use the lightweight JAR matching the server version.
- Put only one WorldListTrashCan JAR in a server's `plugins` directory. Do not install both the universal and lightweight JARs together.

Final universal artifact information:

