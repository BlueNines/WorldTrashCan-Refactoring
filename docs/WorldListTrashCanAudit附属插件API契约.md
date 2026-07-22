# WorldListTrashCanAudit 附属插件 API 契约

## 文档状态

- 状态：API v1 实现前契约。
- 主插件：`WorldListTrashCan`。
- 附属插件：`WorldListTrashCanAudit`。
- 当前阶段：只冻结边界，不代表代码已经实现。
- 目标：让附属插件观察主插件成功清理的物品，同时保证未安装、关闭或故障时不影响主插件。

## 一、总体边界

```text
WorldListTrashCan
  清理开始
    -> 创建 API v1 审计会话
    -> 成功处理物品后调用 recordItem
    -> 清理完成、超时或取消后关闭会话

WorldListTrashCanAudit
  同步复制并序列化当前物品
    -> 投递有界队列
    -> 异步写入 SQLite 或 MySQL
    -> /wtcaudit 异步查询并打开只读 GUI
```

主插件负责：

- 判断什么属于一次有效扫地。
- 保证 `ItemStack` 回调发生在普通 Bukkit 主线程或 Folia 实体所属合法线程。
- 提供统一的玩家线程调度入口。
- 在附属插件不存在时提供空会话。
- 捕获附属插件非致命异常，保护清理主流程。

附属插件负责：

- 判断自身是否启用和是否还有队列容量。
- 在回调返回前复制并序列化 `ItemStack`。
- Folia 多区域片段的线程安全聚合。
- 数据库驱动、连接、建表、事务、查询和过期删除。
- `/wtcaudit` 命令、权限、多语言和只读 GUI。
- 禁用时先注销 API，再关闭队列、线程和数据库连接。

## 二、仓库和交付物

### 2.1 主插件仓库

现有仓库继续交付：

- `WorldListTrashCan-universal.jar`。
- 四个轻量分版本 Jar。
- 一个只用于编译附属插件的 API Jar。

API Jar 不是 Bukkit 插件，不能放入服务端 `plugins` 目录。API 类型会由每个 WorldListTrashCan 运行 Jar 提供。

### 2.2 附属插件仓库

附属插件建立独立项目、Git 仓库和 GitHub 仓库，建议目录：

```text
待开发插件/WorldListTrashCanAudit/
```

交付物：

```text
WorldListTrashCanAudit.jar
```

运行目录：

```text
plugins/WorldListTrashCanAudit/
├─ config.yml
└─ data/
   └─ cleanup-audit.db
```

## 三、插件身份和依赖

附属插件 `plugin.yml` 建议：

```yaml
name: WorldListTrashCanAudit
version: 1.0.0-experimental
main: pixeltech.worldlisttrashcan.audit.WorldListTrashCanAuditPlugin
depend:
  - WorldListTrashCan
commands:
  wtcaudit:
    aliases:
      - wtca
permissions:
  WorldListTrashCanAudit.open:
    default: op
```

硬依赖的作用：

- Bukkit 保证主插件先加载。
- 附属插件可以从主插件 ClassLoader 解析 API 类型。
- 主插件被禁用时，附属插件不能继续工作。

首版不修改 `/wtc` 命令。这样不需要在主插件五套命令实现中加入可选分支，也不需要建立通用命令扩展系统。

## 四、API 模块

主插件增加一个很小的 API 模块，建议 Maven artifact：

```text
world-list-trashcan-api
```

建议公开包名：

```text
pixeltech.worldlisttrashcan.api.audit
```

API 模块只允许包含：

- 契约接口。
- 不可变上下文对象。
- 稳定枚举。
- 必要的 Bukkit `Player`、`Plugin` 和 `ItemStack` 类型引用。

API 模块禁止包含：

- 数据库驱动。
- 配置解析。
- GUI 实现。
- 主插件清理实现。
- Folia 具体 API。
- 静态全局插件实例。

附属插件使用 `provided` 范围编译 API，**禁止把 API class 打入附属插件 Jar**。如果主插件和附属插件各自打包一份同名 API 类型，Java 会把它们视为不同 ClassLoader 的不同类型，最终产生注册或类型转换错误。

## 五、服务发现

主插件使用 Bukkit `ServicesManager` 注册 API v1 服务。附属插件不得通过反射读取主插件字段，也不得强制转换五种不同的主插件入口类。

附属插件启动流程：

1. 从 `ServicesManager` 获取 `WorldListTrashCanAuditBridge`。
2. 检查 `getApiVersion()` 是否等于 `1`。
3. 构建尚未连接数据库的审计收口。
4. 顶层 `enabled: true` 且配置校验通过后初始化数据库资源。
5. 调用 `register`，取得唯一的 `AuditRegistration`。
6. 注册成功后才允许记录清理数据。

如果 API 缺失或版本不兼容，只禁用附属插件并输出明确错误，不能禁用主插件。

## 六、API v1 草案

以下代码只定义形状，正式实现时所有公开方法都必须保留基本中文或英文 Javadoc。

### 6.1 WorldListTrashCanAuditBridge

```java
public interface WorldListTrashCanAuditBridge {

    int API_VERSION = 1;

    /** 返回当前清理审计 API 版本。 */
    int getApiVersion();

    /** 注册唯一的清理审计消费者，并返回可重复关闭的注册句柄。 */
    AuditRegistration register(Plugin owner, CleanupAuditSink sink);

    /** 在玩家所属合法线程执行回调；玩家离线或插件已禁用时返回 false。 */
    boolean executeForPlayer(Plugin owner, UUID playerId, Consumer<Player> action);
}
```

### 6.2 CleanupAuditSink

```java
public interface CleanupAuditSink {

    /** 为一次清理创建审计会话；容量不足时返回空会话。 */
    CleanupAuditSession beginRun(CleanupRunContext context);
}
```

### 6.3 CleanupAuditSession

```java
public interface CleanupAuditSession {

    /** 记录一个已经被主插件成功处理的物品。 */
    void recordItem(ItemStack itemStack);

    /** 正常或部分完成本轮审计；重复调用必须无副作用。 */
    void complete(CleanupRunCompletion completion);

    /** 放弃本轮审计并释放运行期缓存；重复调用必须无副作用。 */
    void discard();
}
```

### 6.4 AuditRegistration

```java
public interface AuditRegistration extends AutoCloseable {

    /** 注销消费者；重复调用必须无副作用。 */
    @Override
    void close();
}
```

### 6.5 CleanupRunContext

不可变字段建议：

```text
UUID runId
long startedAtMillis
CleanupTrigger trigger
boolean guardsIgnored
```

`runId` 只用于主插件与附属插件在一次运行期间关联 Folia 区域片段，不直接代替数据库自增 ID。

### 6.6 CleanupRunCompletion

不可变字段建议：

```text
long finishedAtMillis
boolean partial
```

`partial: true` 表示 Folia 区域超时或只有部分片段完成。插件停用并明确放弃的数据使用 `discard()`，不写成部分记录。

### 6.7 CleanupTrigger

API v1 只定义：

```text
SCHEDULED
MANUAL
```

密集实体限制、玩家手动放入垃圾桶、仙人掌、岩浆、虚空和其它插件删除不属于 API v1 的扫地批次。

## 七、注册规则

- 同一时间只允许一个审计消费者。
- 第二次注册必须明确失败，不能偷偷替换现有消费者。
- `owner`、`sink` 不能为空。
- 注册句柄 `close()` 必须使用原子方式移除对应消费者。
- 附属插件 `onDisable` 的第一步必须调用 `close()`。
- 主插件额外监听 `PluginDisableEvent`，发现注册者被禁用时自动解除引用。
- 主插件关闭时必须先把活动消费者替换为空实现，再继续关闭清理功能。

这些规则用于避免主插件长期持有已经卸载的附属插件对象和 ClassLoader。

## 八、线程契约

### 8.1 beginRun

- 普通 Spigot/Paper 通常由主线程调用。
- Folia 可以由主插件控制线程调用，但此方法不能访问世界、实体或玩家状态。
- 附属插件只允许检查开关、容量并创建运行期会话。
- 不允许在这里连接数据库或等待数据库响应。

### 8.2 recordItem

- 主插件保证调用发生在物品实体所属合法线程。
- Folia 同一清理会话的 `recordItem` 可能由多个区域线程并发调用。
- 附属插件会话必须线程安全。
- 传入的 `ItemStack` 是只读借用对象，只在当前方法调用期间有效。
- 附属插件必须在返回前完成必要的 clone 和 Bukkit/YAML 序列化。
- 附属插件不能保存传入的原始 `ItemStack` 引用。
- 此方法禁止数据库 I/O、文件 I/O、网络 I/O 和阻塞等待。
- 达到种类数或字节数上限后，后续调用必须快速返回。

### 8.3 complete 和 discard

- 普通服务端在本轮扫描结束时调用。
- Folia 在所有区域片段完成或总超时后调用。
- 调用后会话进入终态，后续迟到的 `recordItem` 必须直接忽略。
- `complete` 只允许投递不可变 DTO 到有界异步队列。
- `discard` 必须立即释放聚合缓存，不进入数据库队列。

### 8.4 executeForPlayer

- 普通服务端使用 Bukkit 玩家线程语义。
- Folia/Luminol 使用玩家 EntityScheduler。
- 主插件在执行前再次检查 `owner.isEnabled()` 和玩家在线状态。
- 附属插件只在此回调中反序列化当前 GUI 页最多 45 个物品并操作 Inventory。
- 数据库查询必须在调用该方法之前由附属插件异步完成。

## 九、异常隔离

主插件调用附属插件 API 时遵守：

```text
附属插件普通异常
  -> 当前审计会话失效
  -> 限频写入主插件日志
  -> 扫地继续

VirtualMachineError
  -> 不吞掉
```

主插件不能：

- 等待附属插件数据库成功。
- 因附属插件异常取消物品清理。
- 无限重试附属插件回调。
- 在每个物品异常时刷一条完整堆栈。
- 自动重新启用已经失败的附属插件。

附属插件数据库失败时遵守：

- 当前记录可以丢弃。
- 队列不能无限增长。
- 重连次数和日志必须限频。
- GUI 查询失败只提示管理员，不影响主插件。
- 严重初始化失败只禁用附属插件自身。

## 十、数据语义

`recordItem` 只表示：

> WorldListTrashCan 已经确认该物品属于本次扫地，并已经成功完成其正式清理或垃圾桶路由逻辑。

因此：

- 审计结果不能反向决定正式清理结果。
- 主插件不能在路由失败时发送 `recordItem`。
- 附属插件不能修改传入物品。
- API v1 不暴露世界垃圾桶 Inventory、个人垃圾桶 Inventory 或公共垃圾桶 Inventory。
- API v1 不允许附属插件拦截、取消或替换清理策略。

如果未来需要保存路由目标、世界名或玩家来源，应通过新增不可变字段并升级 API 版本处理，不能让附属插件读取主插件私有对象。

## 十一、命令与 GUI 边界

- `/wtcaudit` 和 `/wtca` 由附属插件注册。
- 权限 `WorldListTrashCanAudit.open` 默认 `op`。
- 清理记录和详情 GUI 默认只读。
- 数据库异步查询完成后，通过 `executeForPlayer` 回到合法玩家线程。
- 内容槽、按钮槽、拖拽、双击、数字键和 Shift 点击都必须防止复制或放入。
- 首版不支持从历史记录取回物品。
- 首版不在主插件 `/wtc help` 中显示附属插件命令。

## 十二、版本兼容

- API v1 的整数版本固定为 `1`。
- 增加新的必要方法或改变线程语义必须升级 API 大版本。
- 新增不影响旧实现的上下文字段也必须提供兼容读取方式，不能让旧附属插件出现链接错误。
- 附属插件启动时必须明确记录主插件版本、API 版本和当前平台。
- API 不兼容时直接禁用附属插件，并告知需要的主插件版本范围。
- 首版附属插件主类按 Java 8 编译，不直接引用 Folia 类；Folia 调度通过主插件 API 完成。

## 十三、构建契约

- 主插件构建先生成 API Jar 和五个运行 Jar。
- 五个运行 Jar 都必须包含同一份 API v1 class。
- 附属插件以 `provided` 依赖 API Jar。
- 附属插件成品不得包含 `pixeltech/worldlisttrashcan/api/audit/` 下的 class。
- 附属插件构建不得依赖开发机器上的绝对路径。
- 本地开发可以先把 API artifact 安装到本机 Maven 仓库；GitHub 构建需要使用公开、可复现的 artifact 来源。
- 发布时分别记录主插件 Jar、API Jar 和附属插件 Jar 的版本及 SHA-256。

## 十四、最低验收标准

### 14.1 未安装附属插件

- 主插件五个交付 Jar 均能正常启动。
- 定时扫地、`/wtc clear`、垃圾桶路由和 Folia 清理结果不变。
- 审计序列化次数、注册消费者数、数据库线程数均为 0。
- 主插件 universal 包体不能混入 SQLite/MySQL 驱动。

### 14.2 安装但关闭附属插件

- `enabled: false` 时不注册消费者、不加载驱动、不创建连接和线程。
- `/wtcaudit` 明确提示功能关闭。
- 主插件所有现有功能保持正常。

### 14.3 正常启用

- Paper 1.12.2、现代 Spigot 和 Folia 1.21.8 使用真实服务端验证。
- SQLite 和 MySQL 分别完成写入、查询、分页和过期级联删除。
- 真实客户端截图覆盖记录菜单、加载状态、详情菜单、翻页、返回和无权限分支。
- 截图生成后必须逐张重新读取，确认确实能证明验收项。

### 14.4 生命周期和故障

- 附属插件禁用后主插件消费者数立即回到 0。
- 重复启用、禁用不能累计消费者、线程、连接或任务。
- 数据库断线、队列满、超大物品和序列化失败时主插件扫地仍成功。
- Folia 多区域并发、超时和迟到回调不能产生内存泄漏或重复写入。
- 主插件关闭后不能继续调用附属插件对象。

## 十五、禁止事项

- 禁止附属插件监听全服实体移除事件代替主插件 API。
- 禁止反射主插件私有字段、清理类或五个平台入口。
- 禁止附属插件打包主插件 API class。
- 禁止主插件依赖附属插件才能启动或清理。
- 禁止附属插件数据库成功成为正式清理成功的前提。
- 禁止跨 Folia 区域线程读取实体或物品。
- 禁止无界队列、无界重试和无界批次缓存。
- 禁止开放历史物品拿取造成复制。
- 禁止为了首版增加通用命令扩展、公开插件市场或第三方扩展生态。

## 十六、长期流程约束

之后用户提出任何能够优化插件开发流程、测试流程或交付流程的内容，都应该尝试更新到工作流中。
