# F-037 Vault 扣费专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- 夹具插件: 临时 `Vault-FakeEconomy.jar`，插件名为 `Vault`，注册 VaultAPI `Economy` 服务
- 验收方式: 真实服务端 + 真实客户端打开个人垃圾桶 GUI 并点击取出槽位
- 覆盖场景: 余额充足扣费成功；余额不足不取出不扣费；背包满不取出不扣费
- 结论: FAIL
- Contact sheet: `vault-payment-contact-sheet.png`

## universal_spigot2612

- 服务端: `spigot-26.1.2-managed`
- 客户端版本: `26.1.2`
- 插件 SHA256: `f058350aa410514bd4cbea843a316b25b3f21c04fd781aea0c058696128fe848`
- 夹具 SHA256: `e46a6f2bed80ab2fc765dc30427e181282add54e6ee84695c241c28f9de9a2e5`
- 结果: `FAIL`
- 关键日志: `universal_spigot2612/logs/universal_spigot2612-server-console.log`、`universal_spigot2612/logs/universal_spigot2612-console-commands.log`
