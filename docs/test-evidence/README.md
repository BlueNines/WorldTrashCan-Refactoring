# BLWorldTrashCan 测试证据索引

本目录只保存重构版测试证据。证据目录很多，不能只因为某个目录存在就把它写成通过结论；必须同时查看对应 `README.md`、`summary.json`、截图、日志和上层文档引用。

## 证据分级

- 最终 PASS 证据：已被 `README.md`、`docs/重构版完整功能与测试矩阵.md`、`docs/长期硬化缺口清单.md` 或 `docs/重构执行记录.md` 明确引用，并且目录内保留机器可读 summary、服务端日志、客户端侧证据或截图。
- 失败对照证据：用于说明根因、脚本修正或环境问题，不能单独写成 PASS。只有上层文档明确标注为失败对照时才能引用。
- 本地缓存证据：`git status --short docs/test-evidence` 中仍未跟踪、且没有被上层文档明确引用的目录。默认只作为排障缓存，不删除、不移动，也不能当成最终验收。

## 当前最终证据

| 功能范围 | 最终证据目录 | 说明 |
| --- | --- | --- |
| bStats 7.0.0 | `bstats-7.0.0-proof-20260608-062316/` | bStats 页面和插件版本证明 |
| universal 整包完整回归 | `universal-full-regression-1122-2612-20260608-192725/` | 1.12.2、Paper 26.1.2、Spigot 26.1.2 基础功能回归 |
| 80 项完整矩阵基线 | `universal-function-matrix-1122-2612-20260608-213500/` | 2026-06-08 完整功能矩阵基线 |
| Folia 清理通知视觉 | `cleanup-notify-visual-20260630-092840/` | Chat、ActionBar、BossBar、Title、Sound、Command 通知专项 |
| GUI 真实点击 | `trash-gui-click-visual-20260701-155408/` | 公共/个人垃圾桶取放、分页、冷却、黑名单 GUI、个人满桶自动清空 |
| 旧配置迁移 | `legacy-migration-universal-20260701-165606/` | 旧目录和当前目录旧结构迁移 |
| 船内实体保护 | `boat-entity-protection-20260701-171046/` | 船内实体保留、普通实体清理 |
| 世界垃圾桶边界 | `world-trash-boundary-20260701-172919/` | 创建上限、破坏移除、禁用世界、黑名单降级、未加载区块降级 |
| 保护边界 | `protection-boundary-20260701-174709/` | 不可拾取箭矢、防踩踏农田 |
| 世界实体上限 | `world-entity-limit-20260701-180527/` | 世界实体上限和忽略世界 |
| 清理通知点击 | `cleanup-notify-click-20260701-190056/` | Chat 可点击命令真实客户端点击 |
| 多语言切换与缺节点回退 | `language-visual-20260701-163536/` | 非默认语言切换和 jar 内默认节点回退 |
| Vault 扣费 | `vault-payment-visual-20260702-200346/` | 余额充足、余额不足、背包满三分支 |
| Folia PAPI | `folia-papi-20260702-202801/` | Folia 1.21.8 + PlaceholderAPI 2.11.7 DEV 解析 `%Wtc_ClearTime%` |

## 当前失败对照

| 功能范围 | 对照目录 | 用途 |
| --- | --- | --- |
| Vault 扣费 | `vault-payment-visual-20260702-195606/` | 暴露 `withdrawPlayer(Player,double)` 反射签名错误 |
| Vault 扣费 | `vault-payment-visual-20260702-195757/` | 暴露背包满场景脚本断言错误 |
| Vault 扣费 | `vault-payment-visual-20260702-200044/` | 业务 PASS 但截图被暂停菜单遮挡，不作为最终视觉证据 |
| Folia PAPI | `folia-papi-20260702-202526/` | PlaceholderAPI 和 Wtc 注册成功，但脚本用结束 marker 过早截断 PAPI 输出 |
| Folia PAPI | `folia-papi-20260702-202706/` | PAPI 已返回 `358/350`，但脚本解析函数未识别 Folia console 的 `[time INFO]: 数字` 格式 |
| 清理通知点击 | `cleanup-notify-click-20260701-181352/` | 通知可见但点击未触发 `/blwtc stats` |
| 清理通知点击 | `cleanup-notify-click-20260701-181801/` | 只给顶层 Bungee 组件补点击事件后仍失败 |
| 清理通知点击 | `cleanup-notify-click-20260701-182556/` | 硬编码点击点位偏离实际文本 |
| 清理通知点击 | `cleanup-notify-click-20260701-183142/` | 像素定位误把 PlaceholderAPI 更新提示和天空识别成目标 |
| 清理通知点击 | `cleanup-notify-click-20260701-184245/` | Folia 1.21.8 未稳定打开聊天 |
| 清理通知点击 | `cleanup-notify-click-20260701-185234/` | Folia 1.21.8 未稳定打开聊天 |

## 新增证据要求

1. 每个最终证据目录至少包含 `README.md`、`summary.json` 或等价机器可读摘要、服务端日志、命令日志、配置备份和被测 jar 的 SHA256。
2. 玩家可见功能必须包含真实客户端截图、客户端日志、客户端响应文件或同等级客户端侧证据；服务端日志只能辅助说明。
3. 失败对照必须在目录 README 或上层文档中写清失败原因，不能让后续维护者误判为 PASS。
4. 不删除测试服 `logs`、`world*`、`cache`、`assets`，也不为了让目录变干净而丢弃失败日志。
5. 新增最终证据后，同步更新插件根 `README.md`、完整功能矩阵、长期硬化缺口清单或重构执行记录，并提交推送到当前插件仓库。
