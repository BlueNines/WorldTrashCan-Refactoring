# F-005 旧配置迁移专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `18b2f29229dba529098a94748db6abf8b729c81a0c3ab749a461d28d8d14f55b`
- 服务端: Paper 1.12.2 独立临时测试服，Java 8
- 验收方式: 真实服务端启动 + RCON 命令 smoke + 迁移后文件断言
- 覆盖来源: 相邻旧目录 `plugins/WorldListTrashCan`、当前目录旧结构 `plugins/BLWorldTrashCan`
- 结论: PASS

## 证据

### adjacent-legacy-folder

- 来源类型: `adjacent`
- 生成配置: `adjacent-legacy-folder/generated-plugin-data/`
- 旧源备份: `adjacent-legacy-folder/legacy-source/`
- 服务端日志: `adjacent-legacy-folder/logs/latest.log`、`adjacent-legacy-folder/logs/server-stdout.log`
- RCON 记录: `adjacent-legacy-folder/logs/rcon-commands.log`

### current-plugin-folder

- 来源类型: `current`
- 生成配置: `current-plugin-folder/generated-plugin-data/`
- 旧源备份: `current-plugin-folder/legacy-source/`
- 服务端日志: `current-plugin-folder/logs/latest.log`、`current-plugin-folder/logs/server-stdout.log`
- RCON 记录: `current-plugin-folder/logs/rcon-commands.log`
