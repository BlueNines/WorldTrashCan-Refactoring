# BLWorldTrashCan 重构实现

这是 `WorldListTrashCan` 的重构实现工作区，目标架构是“共同核心 + 多版本插件产物”。

## 当前模块

- `bl-world-trashcan-core`：纯 Java 业务决策，不允许依赖 Bukkit、Paper 或 Folia API。
- `bl-world-trashcan-config`：类型化配置边界和旧配置迁移计划。
- `bl-world-trashcan-storage`：运行数据存储模型，不依赖 Bukkit `Location`。
- `bl-world-trashcan-shared-bukkit`：所有版本都能稳定使用的 Bukkit 生命周期和调度接口。
- `bl-world-trashcan-platform-paper-1_16_1_20`：第一条现代 Paper 平台实现。
- `bl-world-trashcan-plugin-paper-1_16_1_20`：第一条可加载插件产物入口。
- 其他 `platform-*` 和 `plugin-*` 模块先占位，避免后续把多版本实现塞回同一个 jar。

## 已完成

- 多模块 Maven 骨架。
- 核心层 `ItemSnapshot`、`EntitySnapshot`、`CleanupPolicy`、`DefaultCleanupPolicy`。
- 能力矩阵模型 `CapabilityReport`。
- Paper 平台能力报告和 Bukkit 主线程调度适配。
- Paper 插件入口、命令和多配置文件默认资源。

## 手动验证

当前环境 `mvn` 不在 PATH，本轮先用 `javac` 按模块顺序验证：

```text
core -> config -> storage -> shared-bukkit -> platform-paper -> plugin-paper
```

验证结果：通过。仅有 Java 8/17 目标版本的常规 `javac` 警告。

## 下一步

1. 给 core 增加单元测试或纯 Java 测试入口，覆盖物品路由和实体清理策略。
2. 完成 Paper 产物的启动 smoke 测试。
3. 实现 `ConfigService`，把默认配置读成 `ConfigBundle`。
4. 开始迁移后台清理闭环，不碰 GUI。
