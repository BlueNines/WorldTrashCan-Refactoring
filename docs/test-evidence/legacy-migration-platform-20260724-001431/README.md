# WorldListTrashCan 旧配置跨平台迁移矩阵

- 被测产物: `dist/WorldListTrashCan-universal.jar`
- SHA256: `a42e1c4077f9f4250f89cb88ffbe22d2fe7be88e07efa31226e02786e1649a72`
- 输入: 旧版 6.9.8 原始 `config.yml`、`data/data.yml` 和 8 份语言文件
- 验收: 首次迁移、字节级备份、运行时读取、完成标记、第二次启动幂等
- 结论: PASS

## Spigot 26.1.2

- 平台分支: `paper-1.16-1.20 (universal)`
- 完成标记重启未改写: `true`
- 旧语言文件备份及报告: `8/8`
- 证据: `spigot2612/`

## Folia 1.21.8

- 平台分支: `folia-1.20 (universal)`
- 完成标记重启未改写: `true`
- 旧语言文件备份及报告: `8/8`
- 证据: `folia1218/`
