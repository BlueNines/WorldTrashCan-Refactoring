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
| 个人垃圾桶界面 | 固定 54 格原版库存 | 与公共桶一样支持独立布局、紧凑/堆叠双模式、分页、玩家独立排序、actions、close、glow 和 PAPI |
| 个人垃圾桶容量 | 满时默认直接清空旧内容 | 默认拒绝新路由且保留旧内容；可显式开启“容量不足时清空并只重试一次” |
| 个人垃圾桶提示 | 单个或批量提示不完整 | 单个物品单独提示，扫地批量汇总提示，默认显示前 3 类；点击消息可直接打开个人垃圾桶 |
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
| 地面掉落物聚集 | 依赖原版最多 64 个 | 实验性逻辑堆叠默认让一个实体代表最多 `1024` 个物品，可按 Material 独立覆盖，并兼容拾取、漏斗、重启、扫地与损坏回收；默认完全关闭 |

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
    # 最多展开多少行原物品 Lore；-1 表示不截断。
    max-original-lore-lines: 5
    # 完整 Lore 模板；{content} 必须独占一行并在当前位置展开原物品 Lore。
    item-lore:
      - "&#38BDF8数量：&#F5B82E{amount}"
      - "{content}"
      - "&#38BDF8左键 &#D5DEE9取出 &#F5B82E{take-amount} &#D5DEE9个"
      - "&#FFD166Shift + 左键 &#D5DEE9取出 &#F5B82E{shift-take-amount} &#D5DEE9个"
    # 原 Lore 超过上限时显示；{count} 是省略行数。
    omitted-lore: "&#64748B...省略 &#AAB6C5{count} &#64748B行..."
    left-click-amount: 1
    shift-left-click-amount: 64
  stacked:
    # stacked 模式独立的默认排序。
    default-sort: "insertion"
    # 旧模式自己的页数，不复用 compact.max-pages。
    max-pages: 5
```

紧凑模式不会把每个数量拆成多个展示物。例如当前有 `9980` 个物品，再放入 `20` 个时会接收到剩余容量，显示为 `9999`，不会产生 20 个重复条目；超过容量的剩余部分按原有路由规则继续处理。公共垃圾桶存量是运行期数据，重启后按原版行为清空。

`compact.item-lore` 同时控制数量、原 Lore 和操作提示的完整顺序，公共桶与个人桶都可独立配置。`{content}` 展开经过 `max-original-lore-lines` 截断后的原 Lore；没有该行表示主动隐藏原 Lore，重复配置时只展开第一个并在重载时警告。固定空行可写 `""` 或 `"&7"`。普通模板行支持 RGB、传统颜色、页码/数量变量和 PlaceholderAPI。旧服已有的 `show-amount-lore`、`amount-lore`、`action-lore` 仍会按原顺序兼容，但新生成配置不再写出这些旧节点。

### 配置文件保守更新

当前重构版默认不会改写服主已有配置，旧配置节点会继续兼容读取。希望把受支持的旧写法整理为当前格式时，可以在 `config.yml` 主动开启：

```yaml
config-update:
  enabled: true
```

启动插件或执行 `/wtc reload` 后，更新器会先在原文件旁生成带时间戳且不会覆盖旧备份的 `.bak`，再原地注释弃用节点并添加新节点；不删除、不移动其他原始行。备份失败、YAML 无法解析、节点重复、配置类型错误或无法可靠确定节点范围时会取消写入并继续使用原配置。当前 `trash.yml` 结构版本为 `5`，`cleanup.yml` 为 `2`；同一轮同一文件只生成一份备份。`trash.yml` 的旧 Lore 节点会按原显示语义组成 `item-lore`，并补充公共桶白名单、个人桶按钮示例和个人桶通知双按钮命令；`cleanup.yml` 会补入缺失的命名实体名单、历史 `-5` 门禁通知、直删世界和五类匹配示例。自定义颜色、PAPI 文本、空列表和用户注释都会保留，已有新节点绝不覆盖。

### 按类型和自定义名称清理实体

`cleanup.yml` 的 `entities.named-whitelist` 和 `entities.named-blacklist` 用于区分同一 Bukkit 实体类型下的普通怪、MythicMobs 小怪和 Boss。每条规则要求 `type-patterns` 与 `name-patterns` 同时命中；两个列表内部均为 OR，且支持 `*` 通配。名称不含 `*` 时按包含匹配，因此等级、血量等前后缀不会影响固定名称正文。

```yaml
entities:
  named-whitelist:
    - type-patterns: ["ZOMBIE", "SKELETON"]
      name-patterns: ["&6世界 Boss", "&6副本 Boss"]
  named-blacklist:
    - type-patterns: ["ZOMBIE"]
      name-patterns: ["&c特殊的怪物"]
```

节点缺失、空列表或没有有效规则时直接跳过名称匹配。配置包含颜色时颜色参与匹配，`&c` 与 `§c` 等价；配置不含颜色时忽略实体名称中的颜色。白名单优先于所有黑名单，船内、带鞍和 Bukkit `Tameable` 主人保护继续保持更高优先级。`/wtc look` 右键实体后会把实体类型直接标记为 `type-patterns`，把配置颜色名称和去色名称标记为 `name-patterns`，结果可点击复制。若名称只由客户端发包、伪装或独立悬浮字显示而未写入 Bukkit `getCustomName()`，则不会命中本规则。

公共垃圾桶默认底栏提供玩家独立排序按钮，支持进入顺序、数量升降序、名称 A-Z 和材质 A-Z。排序只在打开菜单或玩家明确切换时执行；打开后的翻页和取物使用同一份轻量条目 ID 快照，不会因其他玩家操作或扫地入库突然重排。每名玩家的 `compact`、`stacked` 偏好分别保存在内存中，退出后释放，不写数据库，也不会改变公共存储的真实顺序。

玩家在已打开的公共桶或个人桶中手动放入物品后，菜单会立即更新，不需要关闭后重新打开。同类物品的数量和 Lore 会留在当前页原地刷新；如果新物品或新堆叠只能进入后续页，菜单会自动切到新增内容所在页。该增量更新不会重新排序；下一次打开菜单或主动切换排序时才重新按所选规则排列。

公共垃圾桶布局展示物支持 `glow: true` 附魔光效，适用于翻页、背景、排序、actions 和关闭按钮。Minecraft 1.20.5 及以上使用 Bukkit 原生纯光效，不写入真实附魔；旧版本自动降级为隐藏附魔，Tooltip 不显示附魔名称。`type: content` 始终忽略该字段，不会修改真实垃圾物品。

### 个人垃圾桶显示与容量

个人垃圾桶使用与公共垃圾桶相同的存储和菜单核心，但每个玩家按 UUID 隔离状态。`personal-trash.mode` 可选 `compact` 或 `stacked`，两种模式拥有独立的 `max-pages` 和默认排序；布局、翻页、排序、`actions`、`close`、`glow`、RGB、传统颜色和 PAPI 写法与公共桶一致。

个人桶默认使用紧凑模式、2 页、单种物品上限 `9999`，允许手动放入且没有拿取冷却。个人桶满时默认拒绝路由并保留旧物品。只有显式设置 `auto-clear-when-full: true` 后，自动路由遇到“整个容器容量不足”才会清空并只重试一次；玩家 GUI 手动放入、单种物品达到上限和已经部分接收的请求绝不会触发清空。个人专属物品也不会因为个人桶满而自动改投公共桶。

个人桶回收通知中的物品名与地面堆叠共用同一条解析链路：物品自身自定义名优先，其次读取 `item-stacking.yml -> items` 的逐物品 `display-name`，然后使用 `display.custom-name.locale` 指定的内置语言，最后才降级为可读英文材质名。关闭堆叠时仍可复用同一份小型配置快照显示名称，但不会创建任何堆叠监听、任务、队列或索引。通知默认在同一条消息中显示两个独立按钮：左侧 `[打开个人垃圾桶]` 执行 `/wtc personal`，右侧 `[打开公共垃圾桶]` 执行 `/wtc global`。按钮文本位于语言文件的 `personal-trash.recycle.personal-button`、`button-separator` 和 `global-button`，两侧命令位于 `trash.yml`：

```yaml
personal-trash:
  notify:
    enabled: true
    max-display-items: 3
    # 留空隐藏左侧按钮。
    personal-click-command: "/wtc personal"
    # 留空隐藏右侧按钮。
    global-click-command: "/wtc global"
```

两个命令分别绑定到自己的聊天组件，互不覆盖；只配置一侧时只显示一侧。旧配置中的 `personal-trash.notify.click-command` 仍作为个人按钮命令读取，且在没有新公共命令的旧配置中不会强行增加公共按钮。两个命令都为空时恢复普通文本消息。点击命令会在玩家所属合法线程执行，兼容普通 Bukkit/Paper 和 Folia/Luminol。

### 地面掉落物逻辑堆叠

这是默认关闭的实验性功能。唯一配置文件 `item-stacking.yml` 会随其它默认配置一起生成，包含总开关、全局上限、性能参数、显示设置和 `items` 单物品覆盖规则。关闭时不会创建堆叠功能对象、探测现代 API 或注册对应监听器、任务、队列和索引。开启方式：

```yaml
# item-stacking.yml
enabled: true
```

插件在启动时按 PDC、掉落物 owner、拾取、漏斗、合并、区块事件和调度 API 的实际能力决定是否启用，不读取服务端品牌名，也不硬编码 Minecraft 小版本。缺少实体 PDC 的运行时会明确拒绝；具备完整能力的运行时才会启用。1.12.2 属于前者，但插件不把这个结论写死成版本判断。检测到 RoseStacker、WildStacker、UltimateStacker 或 StackMob 时拒绝同时运行。

逻辑数量只写在地面掉落物实体的 PDC 中，不修改背包物品，因此不会破坏普通物品堆叠。相同 `ItemStack` 数据和相同 owner 的附近物品才会聚集，不同名称、Lore、附魔、PDC、Data Components 或 owner 不会混合。主动处理使用有容量、TTL、数量和微秒预算的 dirty-chunk 队列与空间网格，不周期遍历全部世界实体，也不强制加载区块。Folia 的世界垃圾桶转移在目标箱子所属 region 内完成写入和数量扣减。

内置名称支持 `zh_CN`、`en_US`、`ja_JP` 三种原版物品翻译，并同时用于掉落物头顶名称和个人垃圾桶回收通知，不改变插件消息语言文件。名称按“物品自身的自定义名 → `item-stacking.yml -> items` 的独立显示名 → 内置翻译 → 可读英文材质名”解析。翻译资源位于 JAR 内，运行时不联网；只保留当前语言的 `Material.ordinal()` 数组。堆叠和个人桶通知都关闭时不加载这份数组。

```yaml
# item-stacking.yml
stack:
  # 所有未在 items 中单独设置的物品最多堆叠 1024 个。
  max-logical-amount: 1024

items:
  DIAMOND_BLOCK:
    # false 表示本插件完全不接管钻石块，保留服务端原版掉落物合并。
    enabled: true
    # 本例让钻石块最多堆叠 200 个；填 -1 则继承上面的 1024。
    max-stack-size: 200
    # default 或空值使用内置语言，也可以直接填写自定义名称。
    display-name: "default"
```

`items` 是稀疏覆盖表，只需填写确实需要特殊规则的 Bukkit Material，不会生成上千个默认条目。未配置 Material 自动启用并继承 `stack.max-logical-amount` 和内置语言名称。`/wtc reload` 可直接应用单物品启用状态、上限和名称：改为禁用会按预算拆回原版实体，降低上限会无损拆成多个不超过新上限的逻辑实体，不强制加载未加载区块。开发阶段使用过的 `item-stacking-items.yml` 和旧 `display.custom-name.overrides` 均不读取、不迁移，可直接删除。

所有详细参数与总开关都在 `item-stacking.yml`。旧位置 `config.yml -> features.item-stacking.enabled` 不再读取。`/wtc stacking status` 查看运行、排空、队列和数量统计；把 `enabled` 改回 `false` 后，插件会把已加载区块中的逻辑数量逐批拆回原版堆叠，未加载区块只在自然加载后处理，也可以使用 `/wtc stacking drain` 主动请求排空。

漏斗吸取保留服务端原生流程，遵守核心配置的吸取冷却并正常更新比较器。超过原版一组的逻辑数量会暂存余量，吸取后按实际数量结算；漏斗满、仅能接收部分物品或其它插件取消事件时，地面余量仍会完整保留。

### 扫地统计变量

`cleanup.yml` 的聊天、控制台、ActionBar、BossBar、Title 和命令通知共用以下变量。旧变量保持旧版的掉落实体语义，新变量用于需要精确物品件数的场景：

| 变量 | 含义 |
| --- | --- |
| `%DealItemSum%` | 成功处理的来源掉落实体数；三个各含 64 件物品的实体计为 `3` |
| `%GlobalTrashAddSum%` | 至少有一件物品进入公共垃圾桶的来源掉落实体数；同一实体分批写入只计一次 |
| `%DealItemAmount%` | 成功处理的实际物品件数；三个各含 64 件物品的实体计为 `192` |
| `%GlobalTrashAddAmount%` | 实际进入公共垃圾桶的物品件数 |

关闭 `item-stacking` 时，`Amount` 变量仍按原版 `ItemStack` 数量统计；开启后会读取逻辑实际数量。垃圾桶写入、防复制事务、个人通知和 Audit 始终使用实际件数，不受旧消息变量语义恢复影响。

定时清理和新生成语言文件中的 `/wtc clear` 默认消息使用 `%DealItemSum%`，保持旧版掉落实体数语义。两个 `Amount` 变量只作为注释和变量文档中的可选项，不进入默认消息正文；已有外部语言文件的 `{routed}` 仍表示实际进入垃圾桶的物品件数。

#### 兼容性和稳定性增强

- 提供 `WorldListTrashCan-universal.jar`，高低版本和 Folia/Luminol 使用同一个整包；也保留轻量分版本 Jar。
- 1.12.2 到 1.21.x 版本线提供兼容实现；1.16.5+ 支持 RGB，低版本自动降级，也兼容 `&a`、`&c` 等传统颜色码。
- Folia/Luminol 使用区域安全的分段清理任务，不把普通 Bukkit 定时任务直接运行到 Folia 环境。
- 世界垃圾桶默认不强制加载未加载区块，避免清理时突然加载区块造成卡顿。
- 玩家掉落标记放在掉落实体上，不写入物品本身，避免影响物品正常堆叠。
- 扫地把物品写入任意垃圾桶前后都会轻量核对掉落实体、材质和数量；若玩家恰好完成拾取、实体删除失败或数量已变化，本次垃圾桶写入会按追踪键回滚，避免背包与垃圾桶同时获得同一份物品。
- 旧版配置会被识别并隔离到 `old-version-config`，不直接拿旧配置启动新版逻辑。
- 默认配置缺失项会补回，并且默认配置项带有中文注释。
- bStats 已内置，服主不需要额外开关；插件版本为 `7.5.5`。

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

世界实体上限仍需启用、但不希望每次拦截生成都刷控制台时，在 `entity-limits.yml` 使用：

```yaml
world-limits:
  enabled: true
  # false 只关闭逐条拦截日志，不会关闭实体数量限制。
  log-blocked-spawns: false
```

需要排障时可临时改为 `true`，然后执行 `/wtc reload`；默认值为 `false`。

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
      material-patterns: ["DIAMOND_SWORD", "*_SHULKER_BOX"]
      name-key-patterns: ["*史诗武器*", "&#FFD166限定道具"]
      lore-key-patterns: ["*不可交易*", "*灵魂绑定*"]
      pdc-key-patterns:
        - "myplugin:item_id"
        - "myplugin:*"
      nbt-key-patterns: ["tag.PublicBukkitValues.myplugin:item_id"]
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
    material-patterns: ["STONE", "*_INGOT"]
    name-key-patterns: ["*可回收*"]
    lore-key-patterns: ["*允许进入公共垃圾桶*"]
    pdc-key-patterns: ["myplugin:recyclable", "myplugin:*"]
    nbt-key-patterns: ["tag.PublicBukkitValues.myplugin:recyclable"]
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
/wtc look                 查看匹配值，并直接标明对应的 *-patterns 配置键
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

- 版本：`7.5.5`
- 文件：`WorldListTrashCan-universal.jar`
- 文件大小：`986957` 字节
- SHA-256：`9E14A708EE192E5AC1A4C1C8BB92E5DAF528FF125F12E3F75A5CFC36D42BE868`
- 当前 SHA 已在 Paper 1.21.8 使用真实 Fabric 1.21.8 客户端验证个人桶回收提示的共享名称链路：堆叠关闭且逐物品配置不存在时显示 `橡树树苗*8、小麦种子*14、泥土*7`；开启堆叠并配置独立名称后显示 `图鉴树苗*5、农作种子*30、建筑泥土*54`，两轮均未暴露原始 Material 枚举名。
- 本次最终整包已在 Paper 1.21.8 使用真实 Fabric 1.21.8 客户端验证完整逐 Material 配置：圆石独立上限 `30`、金锭独立上限 `50`、钻石禁用接管、独立显示名，以及物品自身名称 `Item_Custom_Name x 80` 的最高优先级。另一次连续验收确认 `/wtc reload` 把逻辑数量 `80` 无损拆为最大 `30`，再禁用后保持总量 `80` 且受管实体归零。
- 当前 SHA 还在 Paper 1.21.4 与 Folia 1.21.8 使用双真实客户端验证扫地和拾取竞争：堆叠开启、关闭及正常个人桶路由均保持 `地面 + 双方背包 + 个人桶 + 公共桶 + 世界桶 = 初始数量`，未出现背包与垃圾桶同时获得同一份物品。
- 地面掉落物逻辑堆叠的数量上限、完整物品身份隔离、满背包部分拾取、漏斗转移、重启恢复、关闭排空和扫地路由，已在翻译功能加入前使用同一套实现的历史 Universal 整包于 Paper 1.21.8 和 Folia 1.21.8 完成真实客户端全链路验收；这些历史业务证据不冒充当前 SHA 的重复全量验收。
- 个人垃圾桶通知双按钮已使用整包 JAR 在 Paper 1.21.4、Paper 1.12.2 和 Folia 1.21.8 的真实客户端中验证，覆盖正式扫地回收通知、左右按钮可见性，以及从客户端聊天输入两条命令后分别打开个人桶/公共桶 GUI；本轮未把鼠标实际点击计入通过项。
- 公共垃圾桶排序已在 Paper 1.12.2、Paper 1.21.4 和 Folia 1.21.4 使用真实客户端验证。
- 自定义数据路由已在 Paper 1.12.2 验证 Raw NBT，在 Folia 1.21.8 验证 PDC、个人桶路由、留地、直删和公共桶准入。
- 公共垃圾桶 `glow` 已使用同一整包在 Paper 1.12.2、Paper 1.20.4 和 Folia 1.21.8 完成真实客户端验证。
- 个人垃圾桶统一容器已使用同一整包在 Paper 1.12.2、Paper 1.21.4 和 Folia 1.21.8 完成匹配版本真实客户端验收，覆盖完整 `item-lore` 模板、空行、多行原 Lore 截断、隐藏原 Lore、重复 `{content}`、旧节点回退，以及紧凑/堆叠、翻页、玩家排序、actions、close、glow、PAPI、手动放入、取出、重载保留、容量策略和 UUID 隔离。
- 三端各完成 600 次菜单打开压力检查，观测 TPS 分别为 `19.981`、`19.996`、`19.998`，服务端日志未发现本专项禁止异常。
- 2026-08-28 修复现代逻辑堆叠物品被猪灵等非玩家实体拾取时的事务竞争：按事件后的实际物理余量对账逻辑数量，保护并发拾取实体，并在事件取消、调度失败、实体失效或插件关闭时回滚；当前最终 Universal 整包在 Paper 1.21.4 的 21 只猪灵压力验收达到 `128` 次有效拾取、`128` 次交易，初始 `128` 个逻辑物品最终全部守恒，另有 `14` 次竞争拾取被保护取消且无堆叠警告。四张原生客户端截图和完整服务端日志保存在本地 `wtc-piglin-paper-1214-20260828-fixed-r60` 证据目录。

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
| Personal trash-can UI | Fixed 54-slot inventory | Independent layout, compact/stacked modes, pagination, per-player sorting, actions, close, glow, and PAPI using the same container core |
| Personal trash capacity | Full containers clear old contents by default | New routes are rejected by default while old contents remain; optional clear-and-retry runs only once for container-capacity failures |
| Compact per-item capacity | No logical per-item cap | Each type has a configurable accumulated cap; `-1` means unlimited, and incoming batches use remaining capacity instead of creating duplicate entries |
| Smaller public trash-can capacity | Items could become inaccessible | A read-only overflow page keeps items visible and retrievable instead of silently losing them |
| Public trash-can actions | Only fixed button behavior | Supports `[console]`, `[command]`, `[message]`, `[close]`, `type: close`, and PlaceholderAPI variables |
| Personal trash-can notifications | Incomplete single-item and batch messages | Individual notifications and grouped cleanup summaries show up to 3 types by default; one message contains independent buttons for personal and global trash |
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
| Ground-item aggregation | Limited to vanilla stacks of 64 | Experimental logical stacking lets one entity represent up to `1024` items by default, supports per-Material overrides, pickup, hoppers, restarts, cleanup, and damage recovery; fully disabled by default |

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
    # Maximum original Lore lines to expand; -1 keeps all lines.
    max-original-lore-lines: 5
    # Complete Lore template. {content} must be on its own line and expands original Lore here.
    item-lore:
      - "&#38BDF8Amount: &#F5B82E{amount}"
      - "{content}"
      - "&#38BDF8Left click &#D5DEE9to take &#F5B82E{take-amount}"
      - "&#FFD166Shift + left click &#D5DEE9to take &#F5B82E{shift-take-amount}"
    # Shown after truncated original Lore; {count} is the number of omitted lines.
    omitted-lore: "&#64748B...omitted &#AAB6C5{count} &#64748Blines..."
    left-click-amount: 1
    shift-left-click-amount: 64
  stacked:
    # Independent default sort for stacked mode.
    default-sort: "insertion"
    # Independent page count for the legacy mode.
    max-pages: 5
```

Compact mode does not split a batch into many duplicate display entries. For example, when `9980` items are stored and another `20` arrive, the remaining capacity is accepted and the entry becomes `9999`; any remainder follows the existing routing rules. Public trash contents are runtime data and are cleared on restart, matching the legacy behavior.

`compact.item-lore` controls the complete order of the amount, original Lore, and operation hints. Global and personal trash can configure it independently. `{content}` expands original Lore after `max-original-lore-lines` truncation. Omitting it intentionally hides original Lore; duplicates expand only the first and produce one reload warning. Use `""` or `"&7"` for a fixed blank line. Normal template lines support RGB, legacy colors, page/amount variables, and PlaceholderAPI. Existing `show-amount-lore`, `amount-lore`, and `action-lore` settings remain compatible in their legacy order, but new defaults no longer emit them.

### Conservative configuration updates

The refactored plugin does not rewrite an administrator's existing configuration by default. Legacy nodes remain readable. To convert supported legacy syntax to the current format, explicitly enable this option in `config.yml`:

```yaml
config-update:
  enabled: true
```

On startup or `/wtc reload`, the updater first creates a unique timestamped `.bak` beside the original file. It then comments deprecated nodes in place and adds the replacement nodes without deleting or moving unrelated original lines. A failed backup, invalid YAML, duplicate path, invalid value type, or uncertain node boundary cancels the write and leaves the original configuration active. The current schema is `5` for `trash.yml` and `2` for `cleanup.yml`, and each changed file receives only one backup per update. Legacy Lore nodes in `trash.yml` are combined into `item-lore`, and usage examples are added for admission rules, personal buttons, and the two personal-notification button commands. `cleanup.yml` receives missing named-entity lists, legacy `-5` guard notifications, and examples for direct-remove worlds and all five item match sources. Custom colors, PAPI text, empty lists, and administrator comments are retained, and existing replacement nodes are never overwritten.

### Entity type and custom-name rules

`entities.named-whitelist` and `entities.named-blacklist` in `cleanup.yml` distinguish ordinary mobs, MythicMobs minions, and bosses that share one Bukkit entity type. A rule requires both `type-patterns` and `name-patterns` to match. Each list uses OR semantics and supports `*`; a name without `*` uses substring matching, so level and health prefixes or suffixes do not break a stable name fragment.

```yaml
entities:
  named-whitelist:
    - type-patterns: ["ZOMBIE", "SKELETON"]
      name-patterns: ["&6World Boss", "&6Dungeon Boss"]
  named-blacklist:
    - type-patterns: ["ZOMBIE"]
      name-patterns: ["&cSpecial Monster"]
```

Missing, empty, or entirely invalid lists bypass name matching. Rules containing colors are color-sensitive, while `&c` and `§c` are equivalent; rules without colors compare against the color-stripped custom name. The whitelist wins over all blacklists, and boat, saddle, and Bukkit `Tameable` owner protections keep their higher priority. `/wtc look` labels the entity type as `type-patterns` and both custom-name forms as `name-patterns`; each value remains clickable. Names rendered only through client packets, disguises, or separate holograms cannot match unless they are also stored in Bukkit `getCustomName()`.

The default footer includes per-player sorting for insertion order, amount ascending or descending, name A-Z, and material A-Z. Sorting runs only when the menu is opened or the player explicitly switches modes. Pagination and item taking keep the same lightweight entry-ID snapshot, so another player or cleanup deposit cannot unexpectedly reorder an open menu. Compact and stacked preferences are held separately in memory, released on quit, never written to a database, and never mutate the global storage order.

After a player manually deposits an item into an open global or personal trash menu, the menu updates immediately without requiring a close and reopen. An existing item stays on the current page while its amount and Lore refresh in place. If a new item or stack only fits on a later page, the menu automatically opens the page containing that new content. This incremental update does not resort the view; the selected sort is applied again only when the menu is reopened or the player explicitly changes it.

Layout display items support `glow: true` for page, background, sort, actions, and close buttons. Minecraft 1.20.5 and newer use Bukkit's native glint override without a real enchantment. Older versions automatically fall back to a hidden enchantment, so no enchantment name appears in the tooltip. `type: content` always ignores this option and never mutates stored trash items.

### Personal trash-can display and capacity

Personal trash uses the same storage and menu core as global trash, while state remains isolated by owner UUID. `personal-trash.mode` accepts `compact` or `stacked`; both modes have independent `max-pages` and default sort settings. Layout, pagination, sorting, `actions`, `close`, `glow`, RGB, legacy colors, and PAPI work the same way as global trash.

The personal default is compact mode, 2 pages, a per-item limit of `9999`, manual deposits enabled, and no take delay. When full, personal trash rejects new routes by default and keeps its existing contents. Only with `auto-clear-when-full: true` will an automatic route clear and retry once after a whole-container-capacity rejection. Manual GUI deposits, per-entry limits, and partially accepted requests never trigger a clear. Personal-only items are not redirected to global trash just because the personal container is full.

Personal-trash recovery notifications use the same item-name chain as ground-item stacking: the item's own custom name wins, followed by per-material `display-name` values under `item-stacking.yml -> items`, the bundled locale selected by `display.custom-name.locale`, and finally a readable English material-name fallback. While stacking is disabled, the same small configuration snapshot can still provide names, but no stacking listener, task, queue, or index is created. Notifications contain two independent buttons in one chat message by default: `[Open Personal Trash]` runs `/wtc personal` on the left, and `[Open Global Trash]` runs `/wtc global` on the right. Button labels are configured in `personal-trash.recycle.personal-button`, `button-separator`, and `global-button` in the language file. Commands are configured in `trash.yml`:

```yaml
personal-trash:
  notify:
    enabled: true
    max-display-items: 3
    personal-click-command: "/wtc personal"
    global-click-command: "/wtc global"
```

Each command is attached to its own chat component. Leaving one command empty hides only that button; leaving both empty restores a plain text notification. The legacy `personal-trash.notify.click-command` remains the personal-button command, and an old configuration without a new global command does not gain a global button unexpectedly. Commands execute on the player's legal scheduler context on both Bukkit/Paper and Folia/Luminol.

### Logical ground-item stacking

This is an experimental, opt-in feature. `item-stacking.yml` is the only configuration file and contains the switch, global limit, performance parameters, display settings, and per-item overrides under `items`. While disabled, no stacking object, API probe, listener, task, queue, or index is created. Enable it in this file:

```yaml
# item-stacking.yml
enabled: true
```

Startup checks the actual PDC, item-owner, pickup, hopper, merge, chunk-event, and scheduler APIs. It does not inspect server brand names or hard-code a Minecraft minor version. Runtimes without entity PDC are rejected; only runtimes that pass the complete capability probe are enabled. Minecraft 1.12.2 is one such rejected runtime, but this is not hard-coded as a version rule. RoseStacker, WildStacker, UltimateStacker, and StackMob are treated as conflicts.

The logical amount is stored only on the dropped entity, never on inventory item stacks. Nearby items merge only when their complete `ItemStack` data and owner match, so names, Lore, enchantments, PDC, Data Components, and different owners stay separate. Processing uses a bounded, deduplicated dirty-chunk queue with TTL, count, and microsecond budgets plus a spatial grid. It does not periodically scan every world entity or force-load chunks. On Folia, world-trash insertion and entity deduction are committed in the target chest's region.

Bundled vanilla names support `zh_CN`, `en_US`, and `ja_JP` for both ground-item labels and personal-trash recovery notifications without changing the plugin message locale. Names resolve in this order: the item's own custom name, the independent name under `item-stacking.yml -> items`, the bundled translation, then a readable English material name. Resources stay inside the JAR and are never downloaded; only the selected locale is retained as a `Material.ordinal()` array. The array is not loaded when both stacking and personal-trash notifications are disabled.

```yaml
# item-stacking.yml
stack:
  # All items without an override under items stack up to 1024.
  max-logical-amount: 1024

items:
  DIAMOND_BLOCK:
    # false leaves diamond blocks entirely to vanilla item merging.
    enabled: true
    # This example limits diamond blocks to 200; -1 would inherit 1024 above.
    max-stack-size: 200
    # default or an empty value uses the bundled locale; a custom name can be entered directly.
    display-name: "default"
```

`items` is a sparse override map: list only Bukkit Materials that need special behavior. Unlisted materials are enabled automatically and inherit `stack.max-logical-amount` plus the bundled locale name. `/wtc reload` applies per-item switches, limits, and names immediately: disabling a material drains its managed stacks back to vanilla entities under the normal processing budget, while lowering a limit losslessly splits oversized logical stacks without force-loading unloaded chunks. The development-only `item-stacking-items.yml` and the former `display.custom-name.overrides` node are not read or migrated and can be deleted.

The switch and all detailed settings live in `item-stacking.yml`. The former `config.yml -> features.item-stacking.enabled` path is no longer read. `/wtc stacking status` shows runtime, draining, queue, and amount counters. Setting `enabled` back to `false` drains logical amounts in loaded chunks into vanilla stacks; unloaded chunks wait for natural loading. `/wtc stacking drain` can also request this explicitly.

Hopper pickup uses the server's native transfer flow, preserving configured cooldowns and comparator updates. Logical quantities beyond a vanilla stack are retained and reconciled against the amount actually collected. Full inventories, partial transfers, and pickup events cancelled by other plugins preserve the remaining ground items.

### Cleanup statistic variables

Chat, console, ActionBar, BossBar, Title, and command notifications in `cleanup.yml` share these variables. Legacy variables retain their dropped-entity semantics, while the new variables expose precise item amounts:

| Variable | Meaning |
| --- | --- |
| `%DealItemSum%` | Source dropped-item entities successfully handled; three entities containing 64 items each count as `3` |
| `%GlobalTrashAddSum%` | Source dropped-item entities with at least one item accepted by global trash; partial writes count once |
| `%DealItemAmount%` | Actual items successfully handled; three entities containing 64 items each count as `192` |
| `%GlobalTrashAddAmount%` | Actual items accepted by global trash |

With `item-stacking` disabled, the `Amount` variables still use the vanilla `ItemStack` amount. When enabled, they use the logical amount. Trash routing, duplication guards, personal notifications, and Audit always retain precise item amounts.

Scheduled cleanup and `/wtc clear` messages in newly generated language files use `%DealItemSum%` by default, preserving the legacy dropped-entity count. The two `Amount` variables remain optional entries documented in comments and this table rather than appearing in default message bodies. Existing external language files using `{routed}` continue to receive the exact number of items routed into trash storage.

#### Compatibility and stability improvements

- `WorldListTrashCan-universal.jar` runs across supported server versions, including Folia/Luminol; lightweight version-specific JARs are also available.
- Compatible implementations are provided across the 1.12.2-1.21.x version line. RGB is supported on 1.16.5 and newer, with automatic downgrade on older versions. Legacy `&a` and `&c` color codes remain supported.
- Folia/Luminol uses region-safe, segmented cleanup tasks instead of running ordinary Bukkit repeating tasks in a Folia environment.
- Unloaded chunks are not force-loaded by default for world trash cans, preventing sudden cleanup lag spikes.
- Player-drop ownership is stored on the dropped entity rather than inside the item stack, so normal item stacking is not affected.
- Cleanup lightly rechecks the dropped entity, material, and amount around every trash destination write. If a player wins the pickup, entity removal fails, or the amount changes, the destination write is rolled back by its tracking key so the inventory and trash cannot both receive the same item.
- Legacy configurations are detected and isolated in `old-version-config` instead of being used directly by the new implementation.
- bStats is built in and has no plugin-level enable/disable switch; the plugin version is `7.5.5`.

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

To keep per-world entity limits enabled without logging every blocked spawn, use this in `entity-limits.yml`:

```yaml
world-limits:
  enabled: true
  # false suppresses per-spawn logs without disabling the entity limit.
  log-blocked-spawns: false
```

Temporarily set it to `true` and run `/wtc reload` when troubleshooting. The default is `false`.

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
      material-patterns: ["DIAMOND_SWORD", "*_SHULKER_BOX"]
      name-key-patterns: ["*Epic Weapon*", "&6Limited Item"]
      lore-key-patterns: ["*Untradeable*", "*Soulbound*"]
      pdc-key-patterns:
        - "myplugin:item_id"
        - "myplugin:*"
      nbt-key-patterns: ["tag.PublicBukkitValues.myplugin:item_id"]
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
    material-patterns: ["STONE", "*_INGOT"]
    name-key-patterns: ["*Recyclable*"]
    lore-key-patterns: ["*Allowed in public trash*"]
    pdc-key-patterns: ["myplugin:recyclable", "myplugin:*"]
    nbt-key-patterns: ["tag.PublicBukkitValues.myplugin:recyclable"]
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
/wtc look                 Inspect match values with their target *-patterns keys
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

- Version: `7.5.5`
- File: `WorldListTrashCan-universal.jar`
- File size: `986957` bytes
- SHA-256: `9E14A708EE192E5AC1A4C1C8BB92E5DAF528FF125F12E3F75A5CFC36D42BE868`
- This SHA was verified on Paper 1.21.8 with a real Fabric 1.21.8 client for the shared personal-trash item-name chain. With stacking disabled and no per-material file, the notification showed `橡树树苗*8, 小麦种子*14, 泥土*7`; after enabling stacking and configuring independent names, it showed `图鉴树苗*5, 农作种子*30, 建筑泥土*54`. Neither run exposed raw Material enum names.
- This final universal JAR was verified on Paper 1.21.8 with a real Fabric 1.21.8 client for the complete per-Material configuration: independent limits of `30` for cobblestone and `50` for gold ingots, disabled management for diamonds, independent display names, and highest precedence for the item's own `Item_Custom_Name x 80`. A second continuous run verified that `/wtc reload` losslessly split a logical amount of `80` to a maximum of `30`, then disabled management while preserving the total amount of `80` and reducing managed entities to zero.
- This SHA was also verified on Paper 1.21.4 and Folia 1.21.8 with two real clients racing cleanup against pickup. Stacking enabled, stacking disabled, and normal personal-trash routing all preserved `ground + both inventories + personal + global + world = initial amount`, with no item appearing in both an inventory and trash.
- Before the translation resources were added, the same stacking implementation completed real-client end-to-end verification on Paper 1.21.8 and Folia 1.21.8 for logical caps, full item-identity isolation, partial pickup with a full inventory, hopper transfer, restart recovery, drain-on-disable, and cleanup routing. Those historical business checks are not presented as a full rerun of the current SHA.
- Personal-trash dual notification buttons were verified with the universal JAR on real clients running Paper 1.21.4, Paper 1.12.2, and Folia 1.21.8. The evidence covers the cleanup notification, separate left/right button visibility, and opening the personal/global GUIs from commands entered in the client chat; physical mouse clicks are not claimed as passed.
- Public trash-can sorting was verified with real clients on Paper 1.12.2, Paper 1.21.4, and Folia 1.21.4.
- Custom-data routing was verified with Raw NBT on Paper 1.12.2 and with PDC, personal-only routing, keep-ground, direct removal, and public admission rules on Folia 1.21.8.
- Public trash-can `glow` was verified with the same universal JAR on Paper 1.12.2, Paper 1.20.4, and Folia 1.21.8 using real clients.
- The unified personal container was verified with the same universal JAR and matching real clients on Paper 1.12.2, Paper 1.21.4, and Folia 1.21.8. Coverage includes the complete `item-lore` template, blank lines, multi-line original Lore truncation, hidden original Lore, duplicate `{content}`, legacy-node fallback, compact/stacked modes, pagination, per-player sorting, actions, close, glow, PAPI, manual deposits, withdrawals, reload retention, capacity policies, and UUID isolation.
- Each platform completed a 600-open menu stress check with observed TPS of `19.981`, `19.996`, and `19.998`; no forbidden runtime exceptions were found for this matrix.
- On 2026-08-28, modern logical stacks picked up by piglins and other non-player entities were fixed to reconcile logical amounts with the post-event physical remainder, protect competing pickup transactions, and roll back on cancellation, scheduling failure, entity invalidation, or plugin shutdown. The current final universal JAR completed a Paper 1.21.4 pressure run with 21 piglins: `128` valid pickups and `128` trades from an initial logical amount of `128`; `14` competing pickups were protected and cancelled, with conservation preserved and no stacking warnings. Four native client screenshots and the complete server log are retained locally under the `wtc-piglin-paper-1214-20260828-fixed-r60` evidence directory.
