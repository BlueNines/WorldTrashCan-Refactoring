# F-005 同名旧配置隔离与迁移专项验收

- 被测插件: `dist/WorldListTrashCan-universal.jar`
- SHA256: `a42e1c4077f9f4250f89cb88ffbe22d2fe7be88e07efa31226e02786e1649a72`
- 服务端: Paper 1.12.2 独立临时测试服，Java 8
- 验收方式: 真实服务端启动 + RCON + 文件级断言
- 覆盖: 首次迁移、完整目录隔离、日志保留、中断重入、无标记备份重试、标记跳过、旧结构回放拒绝
- 结论: PASS

## 证据

### exact-old-6.9.8-resources

- 来源类型: `current`
- 迁移后数据: `exact-old-6.9.8-resources/generated-plugin-data/`
- 首次启动: `exact-old-6.9.8-resources/logs/01-first-start/`
- 标记重启: `exact-old-6.9.8-resources/logs/02-marker-restart/`
- 旧结构回放拒绝: `exact-old-6.9.8-resources/logs/03-reintroduced-legacy-rejected/`（仅对应开启该断言的用例）

### current-root

- 来源类型: `current`
- 迁移后数据: `current-root/generated-plugin-data/`
- 首次启动: `current-root/logs/01-first-start/`
- 标记重启: `current-root/logs/02-marker-restart/`
- 旧结构回放拒绝: `current-root/logs/03-reintroduced-legacy-rejected/`（仅对应开启该断言的用例）

### partial-archive-resume

- 来源类型: `partial-archive`
- 迁移后数据: `partial-archive-resume/generated-plugin-data/`
- 首次启动: `partial-archive-resume/logs/01-first-start/`
- 标记重启: `partial-archive-resume/logs/02-marker-restart/`
- 旧结构回放拒绝: `partial-archive-resume/logs/03-reintroduced-legacy-rejected/`（仅对应开启该断言的用例）

### backup-only-retry

- 来源类型: `backup-only`
- 迁移后数据: `backup-only-retry/generated-plugin-data/`
- 首次启动: `backup-only-retry/logs/01-first-start/`
- 标记重启: `backup-only-retry/logs/02-marker-restart/`
- 旧结构回放拒绝: `backup-only-retry/logs/03-reintroduced-legacy-rejected/`（仅对应开启该断言的用例）

### invalid-config-yaml

- 类型: `negative-invalid-config`
- 结论: 已安全拒绝，插件未生成迁移完成标记且输入文件未被覆盖。
- 日志: `invalid-config-yaml/logs/01-safe-rejection/`

### invalid-world-data-yaml

- 类型: `negative-invalid-data`
- 结论: 已安全拒绝，插件未生成迁移完成标记且输入文件未被覆盖。
- 日志: `invalid-world-data-yaml/logs/01-safe-rejection/`

### corrupt-complete-marker

- 类型: `negative-corrupt-marker`
- 结论: 已安全拒绝，插件未生成迁移完成标记且输入文件未被覆盖。
- 日志: `corrupt-complete-marker/logs/01-safe-rejection/`

### mixed-new-and-old-structure

- 类型: `negative-mixed-new-old`
- 结论: 已安全拒绝，插件未生成迁移完成标记且输入文件未被覆盖。
- 日志: `mixed-new-and-old-structure/logs/01-safe-rejection/`

### backup-content-conflict

- 类型: `negative-backup-conflict`
- 结论: 已安全拒绝，插件未生成迁移完成标记且输入文件未被覆盖。
- 日志: `backup-content-conflict/logs/01-safe-rejection/`

