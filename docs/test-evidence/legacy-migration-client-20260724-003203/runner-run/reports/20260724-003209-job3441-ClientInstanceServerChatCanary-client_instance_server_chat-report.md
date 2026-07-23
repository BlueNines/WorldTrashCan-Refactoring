# 客户端测试运行报告 20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat

## 基本信息

- jobId: `3441`
- plugin: `ClientInstanceServerChatCanary`
- scenario: `client_instance_server_chat`
- status: `PASS`
- worker: `client-instance-server-chat-canary`

## 结论

实例客户端 server chat canary 通过：实例客户端连接真实 Paper，发送中文命令，服务端日志 UTF-8 断言、截图、响应、停服和资源释放均闭合。

## 证据路径

- runRoot: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat`
- events: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\events.jsonl`
- manifest: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\manifest.json`
- artifactSummary: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\artifact-summary.json`
- control: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\control`
- screenshots: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\screenshots`
- obsHumanVideos: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\videos`
- logs: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\logs`

## 关键事件

- `2026-07-24T00:32:09+0800` type=`lease` message=任务已领取 data={"worker": "client-instance-server-chat-canary"}
- `2026-07-24T00:32:09+0800` type=`client-instance-server-chat-canary` message=实例客户端连接测试服 canary 已创建 data={"jobId": 3441, "serverResource": "server:paper-1.12.2-test-server", "clientResource": "client:legacy-migration-visual-a", "clientRoot": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a"}
- `2026-07-24T00:32:12+0800` type=`environment-snapshot` message=canary before-client-instance-server-chat 环境快照 data=stage=before-client-instance-server-chat port25565Listening=False paperProcessCount=0 clientProcessCount=0 testProcessCount=0 protectedPaths=[{"path": "paper-1.12.2-test-server/logs", "exists": true, "isDir": true, "childCount": 76, "mtimeNs": 1784795614729...
- `2026-07-24T00:32:12+0800` type=`client-instance-prepare` message=客户端轻量实例目录已准备 data={"manifestFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-prepare-manifest.json", "clientCount": 1}
- `2026-07-24T00:32:58+0800` type=`server-script` message=paper-start-client-instance-server-chat: exitCode=0 data=name=paper-start-client-instance-server-chat exitCode=0 logFile=C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\logs\paper-start-client-instance-server-chat-process.txt
- `2026-07-24T00:32:58+0800` type=`server-log-copy` message=paper-latest-after-client-instance-server-start.log: 已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\paper-1.12.2-test-server\\logs\\latest.log", "target": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\paper-latest-aft...
- `2026-07-24T00:32:59+0800` type=`environment-snapshot` message=canary after-client-instance-server-start 环境快照 data=stage=after-client-instance-server-start port25565Listening=True paperProcessCount=1 clientProcessCount=0 testProcessCount=1 protectedPaths=[{"path": "paper-1.12.2-test-server/logs", "exists": true, "isDir": true, "childCount": 77, "mtimeNs": 17848243364643...
- `2026-07-24T00:32:59+0800` type=`server-runtime-assertion` message=server-running-for-client-instance-chat: PASS data={"status": "PASS", "expectedRunning": true, "port25565Listening": true, "paperProcessCount": 1, "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\se...
- `2026-07-24T00:32:59+0800` type=`server-log-assertion` message=paper-log-ready-for-client-instance-chat: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\paper-log-ready-for-client-instance-chat-assertion.txt", "source": "C:\\Users\\...
- `2026-07-24T00:32:59+0800` type=`runner-client-start` message=启动实例客户端连接真实测试服 data={"command": ["powershell", "-ExecutionPolicy", "Bypass", "-File", "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\scripts\\run_runner_file_task.ps1", "-TaskFile", "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServer...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=instance-server-chat-01-00_position_recovery_1_stage_0.png: 截图已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a\\.minecraft\\versions\\1.12.2-Forge_14.23.5.2864\\ai-client-automation\\runner-file-default-20260724-003314\\raw\\screenshots\\00_position_recovery_1_stage_...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=instance-server-chat-02-00_expected_screen_GuiChest_step_3_tick_20.png: 截图已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a\\.minecraft\\versions\\1.12.2-Forge_14.23.5.2864\\ai-client-automation\\runner-file-default-20260724-003314\\raw\\screenshots\\00_expected_screen_GuiChest_s...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=instance-server-chat-03-00_expected_screen_GuiChest_step_3_tick_60.png: 截图已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a\\.minecraft\\versions\\1.12.2-Forge_14.23.5.2864\\ai-client-automation\\runner-file-default-20260724-003314\\raw\\screenshots\\00_expected_screen_GuiChest_s...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=instance-server-chat-04-00_expected_screen_GuiChest_step_3_tick_120.png: 截图已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a\\.minecraft\\versions\\1.12.2-Forge_14.23.5.2864\\ai-client-automation\\runner-file-default-20260724-003314\\raw\\screenshots\\00_expected_screen_GuiChest_s...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=instance-server-chat-05-00_expected_screen_GuiChest_step_3_tick_160.png: 截图已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a\\.minecraft\\versions\\1.12.2-Forge_14.23.5.2864\\ai-client-automation\\runner-file-default-20260724-003314\\raw\\screenshots\\00_expected_screen_GuiChest_s...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-copy` message=Forge 日志截图路径归档完成 data={"sourceLog": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\forge-latest.log", "copiedCount": 5, "copied": {"screenshot1": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动...
- `2026-07-24T00:33:58+0800` type=`runner-client-end` message=实例客户端真实测试服进程结束 data={"exitCode": 0, "clientRoot": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a"}
- `2026-07-24T00:33:58+0800` type=`client-response` message=chat sequence sent: 3 commands, chatLines=17 data={"status": "PASS", "path": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\control\\client-response.properties"}
- `2026-07-24T00:33:58+0800` type=`server-log-copy` message=paper-latest-before-client-instance-server-stop.log: 已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\paper-1.12.2-test-server\\logs\\latest.log", "target": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\paper-latest-bef...
- `2026-07-24T00:33:58+0800` type=`client-response-assertion` message=client-instance-server-chat-response: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-server-chat-response-assertion.txt", "responseFile": "C:\\Users...
- `2026-07-24T00:33:58+0800` type=`client-screenshot-assertion` message=client-instance-server-chat-screenshots: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-server-chat-screenshots-assertion.txt", "screenshotCount": 5, "...
- `2026-07-24T00:33:59+0800` type=`server-log-assertion` message=client-instance-paper-log-chat-command: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-paper-log-chat-command-assertion.txt", "source": "C:\\Users\\pc...
- `2026-07-24T00:33:59+0800` type=`server-log-assertion` message=client-instance-client-chat: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-client-chat-assertion.txt", "source": "C:\\Users\\pc\\Desktop\\...
- `2026-07-24T00:34:26+0800` type=`server-script` message=paper-stop-live-canary: exitCode=0 data=name=paper-stop-live-canary exitCode=0 logFile=C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\logs\paper-stop-live-canary-process.txt
- `2026-07-24T00:34:26+0800` type=`server-log-copy` message=paper-latest-after-client-instance-server-stop.log: 已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\paper-1.12.2-test-server\\logs\\latest.log", "target": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\paper-latest-aft...
- `2026-07-24T00:34:26+0800` type=`server-log-copy` message=rcon-stop-live-canary.log: 已归档 data={"source": "C:\\Users\\pc\\Desktop\\ai开发插件\\paper-1.12.2-test-server\\ai-client-framework-rcon-stop.log", "target": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\...
- `2026-07-24T00:34:30+0800` type=`environment-snapshot` message=canary after-client-instance-server-stop 环境快照 data=stage=after-client-instance-server-stop port25565Listening=False paperProcessCount=0 clientProcessCount=0 testProcessCount=0 protectedPaths=[{"path": "paper-1.12.2-test-server/logs", "exists": true, "isDir": true, "childCount": 77, "mtimeNs": 17848243364643...
- `2026-07-24T00:34:30+0800` type=`server-runtime-assertion` message=server-stopped-after-client-instance-chat: PASS data={"status": "PASS", "expectedRunning": false, "port25565Listening": false, "paperProcessCount": 0, "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\...
- `2026-07-24T00:34:30+0800` type=`server-log-assertion` message=paper-log-stop-after-client-instance-chat: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\paper-log-stop-after-client-instance-chat-assertion.txt", "source": "C:\\Users\...
- `2026-07-24T00:34:30+0800` type=`client-instance-server-chat-assertion` message=client-instance-server-chat: PASS data={"status": "PASS", "assertionFile": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\logs\\client-instance-server-chat-assertion.txt"}
- `2026-07-24T00:34:30+0800` type=`resource-lock-release` message=任务完成后资源锁已释放 data=releasedResources=["client:legacy-migration-visual-a", "machine:live-server-processes", "plugin:ClientInstanceServerChatCanary", "server:paper-1.12.2-test-server"] remainingLockCount=0 remainingLocks=[]
- `2026-07-24T00:34:33+0800` type=`artifact-summary` message=证据文件摘要已生成 data={"summary": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\runs\\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\\artifact-summary.json", "fileCount": 53}

## 客户端运行诊断

- clientAction: `chat-sequence`
- serverAddress: `127.0.0.1:25565`
- playerName: `AIClientAlpha`
- responseFileExists: `true`
- timeoutFileExists: `false`
- forgeLogSignals: aiAuto=`26` disconnect=`0` waitingJoin=`0`
- forgeLogTail:
  - [00:33:52] [Client thread/INFO] [aiclientautomation]: [AI-AUTO] runner screenshot C:\Users\pc\Desktop\ai�������\�ͻ����Զ������Թ�����\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_ch...
  - [00:33:55] [Client thread/INFO] [aiclientautomation]: [AI-AUTO] runner screenshot C:\Users\pc\Desktop\ai�������\�ͻ����Զ������Թ�����\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_ch...
  - [00:33:58] [Client thread/INFO] [aiclientautomation]: [AI-AUTO] runner screenshot C:\Users\pc\Desktop\ai�������\�ͻ����Զ������Թ�����\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_ch...
  - [00:33:58] [Client thread/INFO] [aiclientautomation]: [AI-AUTO] runner-file finished status=PASS, message=chat sequence sent: 3 commands, chatLines=17

## 客户端恢复事件

- eventCount: `2`
- recoveryLog: `C:\Users\pc\Desktop\ai开发插件\客户端自动化测试工作区\runs\20260724-003209-job3441-ClientInstanceServerChatCanary-client_instance_server_chat\logs\client-recovery-events.log` exists=`true`
- incident `teleported`: detected=1 recovered=1
- recentRecoveryEvents:
  - `2026-07-24T00:33:31+0800` type=`client-recovery-teleported-detected` message=incident=teleported, phase=detected, position jump detected, count=1, stage=0, movementSq=64161.45187445518, dy=-1.0
  - `2026-07-24T00:33:31+0800` type=`client-recovery-teleported-recovered` message=incident=teleported, phase=recovered, position watcher reset and scenario stage prepared, stage=0
- recoveryLogTail:
  - 2026-07-24T00:33:31+0800 incident=teleported, phase=detected, position jump detected, count=1, stage=0, movementSq=64161.45187445518, dy=-1.0
  - 2026-07-24T00:33:31+0800 incident=teleported, phase=recovered, position watcher reset and scenario stage prepared, stage=0

## 已归档证据

- artifactSummary: `artifact-summary.json` fileCount=`53` generatedAt=`2026-07-24T00:34:33+0800`
- `control/client-response.properties` bytes=`356` mtime=`2026-07-24T00:33:58+0800` sha256=`339647d4bea9d7d840438cf59f9709ae294efe9850cc8e9324b085d567fdf5aa`
- `control/client-task.properties` bytes=`2371` mtime=`2026-07-24T00:32:59+0800` sha256=`bcf2bcaa227034636c109c35492699291cd92ad36960abe8375a9eb092701ff9`
- `control/payload.json` bytes=`1303` mtime=`2026-07-24T00:32:59+0800` sha256=`8fa918a62541d6284d20bf9face97c729edc88d2ff09ad7a9a16bffbbb49d87e`
- `events.jsonl` bytes=`39505` mtime=`2026-07-24T00:34:30+0800` sha256=`e54a402c2d3378e17b1afccd7adfa5d75becabf563ce33271001906dfb72242d`
- `logs/client-chat.log` bytes=`2650` mtime=`2026-07-24T00:33:37+0800` sha256=`94d42bfa6c9d37a17954afad65be3306bf82d358ff8ac71129387e421622968b`
- `logs/client-game-dir-plan.json` bytes=`6580` mtime=`2026-07-24T00:32:12+0800` sha256=`b99454224044bae9d704495dfbcd17af4df5c3cb1d6e91fe4a0a90157a394375`
- `logs/client-instance-client-chat-assertion.txt` bytes=`301` mtime=`2026-07-24T00:33:59+0800` sha256=`d342df655c9f5c9419bdaf9ff9d33386b8bbb318338446246ed9607cebddfaea`
- `logs/client-instance-paper-log-chat-command-assertion.txt` bytes=`375` mtime=`2026-07-24T00:33:59+0800` sha256=`66c0d7bc97a36febdde77777ab18ac18e7fc065f11d95fa6e6068555127f3efc`
- `logs/client-instance-player-chat-command-assertion.txt` bytes=`825` mtime=`2026-07-24T00:33:58+0800` sha256=`07d685b7c0db873e24035aedeb27931efb180b3ad8c6abced94d8c3692612086`
- `logs/client-instance-prepare-manifest.json` bytes=`8296` mtime=`2026-07-24T00:32:12+0800` sha256=`7a84985c81f48919072e306cb3ba1863071ea127b6306b8b7115d78366b2e5aa`
- `logs/client-instance-server-chat-assertion.txt` bytes=`10746` mtime=`2026-07-24T00:34:30+0800` sha256=`4f560e8526763c7c0f4e4dcca1b1f46cc95f0fb5d23b61099d5833dd130b5012`
- `logs/client-instance-server-chat-response-assertion.txt` bytes=`420` mtime=`2026-07-24T00:33:58+0800` sha256=`1bcb70ffd7c10955326f81ef8a3cd52dfca0a10ca28a9f8b9bf302d1c4d4426d`
- `logs/client-instance-server-chat-screenshot-screen-assertion.txt` bytes=`1100` mtime=`2026-07-24T00:33:58+0800` sha256=`bd2e41df90186374bb58c843a94c5c8bc49aaf7d778f39dec7de54e39ea60db2`
- `logs/client-instance-server-chat-screenshots-assertion.txt` bytes=`1621` mtime=`2026-07-24T00:33:58+0800` sha256=`d415b35c4744b7ce9831a46ffb8cf0edb4c4d6b37a699c9038dd80d34eb46001`
- `logs/client-launch-context.json` bytes=`890` mtime=`2026-07-24T00:33:02+0800` sha256=`ef812bedc0e0c51ad95db6de2525ab6a5a739177104acdf4700b98c51ed13e5e`
- `logs/client-process-after-start.json` bytes=`9698` mtime=`2026-07-24T00:33:01+0800` sha256=`60a8597386a18f93906b44b36c60984fde23fda87af45db31b413057c78d73cd`
- `logs/client-process-before-stop.json` bytes=`9698` mtime=`2026-07-24T00:33:58+0800` sha256=`838c916b19ecbd738eecb4f6af62893403b1d4adf3f0946995fbbdfbd7330cde`
- `logs/client-recovery-events.log` bytes=`265` mtime=`2026-07-24T00:33:31+0800` sha256=`a87bd1c3d0f2f64c53975d192bf11f2b9e762740cb32ff9241039f9cea9e4114`
- `logs/client-screen.log` bytes=`460` mtime=`2026-07-24T00:33:58+0800` sha256=`f2f33258ec0f2db4320e16be07b08a74c893908c137f9588d16919c1dd9ecf70`
- `logs/client-screenshot-screen.log` bytes=`337` mtime=`2026-07-24T00:33:58+0800` sha256=`550073ce91fc3b4425830061bb67907d6f32b05e6258e70cecf727c4223b3da1`
- `logs/environment-after-client-instance-server-start.json` bytes=`2263` mtime=`2026-07-24T00:32:59+0800` sha256=`5a939da09aedb27d4b2b21284cc8480737c58b5396cbdf08eee0b315a2ba733e`
- `logs/environment-after-client-instance-server-stop.json` bytes=`1981` mtime=`2026-07-24T00:34:30+0800` sha256=`e6c4d1b92e1b0d2f7214bf975a2ec916353d904db13749f0d4d5d45f0620f846`
- `logs/environment-before-client-instance-server-chat.json` bytes=`1983` mtime=`2026-07-24T00:32:12+0800` sha256=`f7994dd7088f7bd17f3335832dc62bec09933eb2d0e43f2b1d0dd441365a34e8`
- `logs/forge-latest.log` bytes=`72896` mtime=`2026-07-24T00:33:58+0800` sha256=`4d12b3b9fab216869412371cf9242ed67f0799242ae0468268ae8671137dd492`
- `logs/paper-latest-after-client-instance-server-start.log` bytes=`63953` mtime=`2026-07-24T00:32:58+0800` sha256=`14484b4155e1d9429a929f96a3bd88b525d2275950c295b74dead77e5392e10d`
- `logs/paper-latest-after-client-instance-server-stop.log` bytes=`85046` mtime=`2026-07-24T00:34:06+0800` sha256=`01288d1f6bba734b7d47a4f019d853fb30183d9cbaaa398e18d0832b63fea13a`
- `logs/paper-latest-before-client-instance-server-stop.log` bytes=`80197` mtime=`2026-07-24T00:33:58+0800` sha256=`1d119fc7d917514efb251a8bfdeb1ab14233395b67a104da162b1344813ce6e2`
- `logs/paper-latest-before-complete.log` bytes=`79977` mtime=`2026-07-24T00:33:54+0800` sha256=`3d33e3b87f56d12db5543aaceadf0e1ef56423c0fd4ef3e419f48d19f1dba2b1`
- `logs/paper-log-fresh-for-client-instance-chat-assertion.txt` bytes=`370` mtime=`2026-07-24T00:32:59+0800` sha256=`8faa9d34b13d16eea9e9f98103b92a3d53fb993ca4a8776ebca569d4f0834f53`
- `logs/paper-log-ready-for-client-instance-chat-assertion.txt` bytes=`268` mtime=`2026-07-24T00:32:59+0800` sha256=`b3d6bd42196cb4c52e1501a070cc879c31585f3a569397f5e201df3aa384c74c`
- `logs/paper-log-stop-after-client-instance-chat-assertion.txt` bytes=`276` mtime=`2026-07-24T00:34:30+0800` sha256=`e3136a68ea0c8905667822f34a615a0a93561a8e0770c0dbe65b60985c10bfca`
- `logs/paper-start-client-instance-server-chat-process.txt` bytes=`256` mtime=`2026-07-24T00:32:58+0800` sha256=`d0e1deda6848b219a52bff5bda1c1662f9ef43b9f8283eb1af35c2a502f0bbca`
- `logs/paper-stop-live-canary-process.txt` bytes=`288` mtime=`2026-07-24T00:34:26+0800` sha256=`c49ad9c18350377efe3da5036967d8425457936435c7ed79bc93686e37c592ed`
- `logs/rcon-stop-live-canary.log` bytes=`1956` mtime=`2026-07-24T00:34:06+0800` sha256=`ebea19545299697ac0d4ca1b84bc19c7e7ad902c5afec32dbca44e7fbe824424`
- `logs/run-runner-file-with-task-env.bat` bytes=`418` mtime=`2026-07-24T00:32:59+0800` sha256=`3b9f4c9d151660515e7aaac47ae3fe53961f71a3ff87f243887a6ac947e99d3d`
- `logs/server-running-for-client-instance-chat-assertion.txt` bytes=`417` mtime=`2026-07-24T00:32:59+0800` sha256=`fd5d5581ff93819cdba42f83377457096453d65d1751707e335d481d683301e8`
- `logs/server-stopped-after-client-instance-chat-assertion.txt` bytes=`417` mtime=`2026-07-24T00:34:30+0800` sha256=`0029c2be515df9e58bd96ee9fdc1422adfb01ae520cdd60e3727afca7f8f73e4`
- `manifest.json` bytes=`1629` mtime=`2026-07-24T00:32:09+0800` sha256=`ec1f0edd91b679783d90f32d19a1ffcb35538ab927f5bfa7c97360a2713c5ea6`
- `screenshots/instance-server-chat-01-00_position_recovery_1_stage_0.png` bytes=`7980` mtime=`2026-07-24T00:33:31+0800` sha256=`c1e51825d5a69b32a8271449a7180d61ec333b486ce2cf5f3488ea4128d29d29` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/instance-server-chat-02-00_expected_screen_GuiChest_step_3_tick_20.png` bytes=`89892` mtime=`2026-07-24T00:33:43+0800` sha256=`e9d2cb15918b8749ea8124cd72a8f0aa9299fe8b0a8720270d97edad43ca7ba7` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/instance-server-chat-03-00_expected_screen_GuiChest_step_3_tick_60.png` bytes=`87223` mtime=`2026-07-24T00:33:45+0800` sha256=`a947a668f8d0df2882b6c56d79f7c0d86ea219fec439c12d11a761223662f38e` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/instance-server-chat-04-00_expected_screen_GuiChest_step_3_tick_120.png` bytes=`88417` mtime=`2026-07-24T00:33:49+0800` sha256=`3b0492f6b2ed34904992a7ef2c97251f28a91b7a2ea489535b20bf214a3603c1` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/instance-server-chat-05-00_expected_screen_GuiChest_step_3_tick_160.png` bytes=`84539` mtime=`2026-07-24T00:33:52+0800` sha256=`58fb67580bec7edf5051f8d07e133fc18c80240cc1a2e8602656d905ec6b79a0` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_expected_screen_GuiChest_step_3_tick_120.png` bytes=`88417` mtime=`2026-07-24T00:33:49+0800` sha256=`3b0492f6b2ed34904992a7ef2c97251f28a91b7a2ea489535b20bf214a3603c1` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_expected_screen_GuiChest_step_3_tick_160.png` bytes=`84539` mtime=`2026-07-24T00:33:52+0800` sha256=`58fb67580bec7edf5051f8d07e133fc18c80240cc1a2e8602656d905ec6b79a0` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_expected_screen_GuiChest_step_3_tick_20.png` bytes=`89892` mtime=`2026-07-24T00:33:43+0800` sha256=`e9d2cb15918b8749ea8124cd72a8f0aa9299fe8b0a8720270d97edad43ca7ba7` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_expected_screen_GuiChest_step_3_tick_60.png` bytes=`87223` mtime=`2026-07-24T00:33:46+0800` sha256=`a947a668f8d0df2882b6c56d79f7c0d86ea219fec439c12d11a761223662f38e` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_position_recovery_1_stage_0.png` bytes=`7980` mtime=`2026-07-24T00:33:31+0800` sha256=`c1e51825d5a69b32a8271449a7180d61ec333b486ce2cf5f3488ea4128d29d29` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_sequence_1.png` bytes=`229640` mtime=`2026-07-24T00:33:36+0800` sha256=`14cfdb8707d52526e4118d56d052608c197ba9d284f5615b26b7b09076ef1404` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_sequence_2.png` bytes=`209288` mtime=`2026-07-24T00:33:40+0800` sha256=`3e1470a0b88985e5cbc0faae216fe59802cd5ae0604793334cf805f522f03163` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_sequence_3.png` bytes=`209294` mtime=`2026-07-24T00:33:55+0800` sha256=`0d49cbaaa220dd992b1013f41320abd455ab96d8e75577ea6fd730582feb08c7` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_sequence_connected.png` bytes=`270361` mtime=`2026-07-24T00:33:34+0800` sha256=`5ad8682282f32fd08d11f204c83894b0356cb4e4dba87a538de657f5abbcb51f` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`
- `screenshots/runner_sequence_final.png` bytes=`210128` mtime=`2026-07-24T00:33:58+0800` sha256=`6fe63b4a162261f07711d5a6581b172633c58a17845f388d1fdf869c5cae5833` image=`validPng=true width=854 height=480 bitDepth=8 colorType=2 interlace=0`

## OBS 人工视频复核

- 本轮未录制人工复核视频。

## 子 Agent 评审

- 暂无子 agent 评审结果。

## 任务载荷

```json
{
  "resources": [
    "server:paper-1.12.2-test-server",
    "client:legacy-migration-visual-a"
  ],
  "clientAction": "chat-sequence",
  "serverAddress": "127.0.0.1:25565",
  "chatCommand": "/wtc stats||/wtc debugnotify 0||/wtc globaltrash",
  "expectedScreenSimpleName": "GuiChest",
  "clientId": "legacy-migration-visual-a",
  "clientRoot": "C:\\Users\\pc\\Desktop\\ai开发插件\\客户端自动化测试工作区\\client-instances\\legacy-migration-visual-a",
  "clientVersionDir": "1.12.2-Forge_14.23.5.2864",
  "playerName": "AIClientAlpha",
  "playerUuid": "11111111-1111-4111-8111-111111111111",
  "expected": [
    "真实 Paper 测试服启动并被当前任务持有",
    "实例 Forge 客户端从 client-instances 下的 gameDir 启动",
    "客户端连接测试服并通过玩家聊天路径发送中文命令",
    "服务端日志出现 issued server command 和 UTF-8 hex 断言",
    "客户端截图、响应、Forge 日志、Paper 日志和停服证据都归档到同一个 run"
  ],
  "forbidden": [
    "不能用 RCON 或服务端命令代替客户端聊天命令",
    "不能使用原始 forge-1.12.2-test-client 作为可变 gameDir",
    "服务端日志不能出现 raw=???",
    "结束后不能留下 Paper、Forge 或启动器进程"
  ]
}
```
