import hashlib
import json
import re
import shutil
import socket
import struct
import subprocess
import time
from pathlib import Path


RCON_PASSWORD = "wtc"
RCON_TIMEOUT_SECONDS = 180
SERVER_TIMEOUT_SECONDS = 240


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def repo_root() -> Path:
    """返回 WorldListTrashCan 重构仓库根目录。"""
    path = Path(__file__).resolve()
    for parent in path.parents:
        if (parent / "pom.xml").is_file() and (parent / "bl-world-trashcan-core").is_dir():
            return parent
    raise RuntimeError("无法定位 refactor-workspace 仓库根目录")


def workspace_root() -> Path:
    """返回 ai 开发插件工作区根目录。"""
    return repo_root().parents[2]


REPO = repo_root()
WORKSPACE = workspace_root()
EVIDENCE_ROOT = REPO / "docs" / "test-evidence"
BUILD_ROOT = REPO / "build" / "legacy-migration-matrix"
JAVA8 = Path(r"C:\Program Files\Java\jre1.8.0_451\bin\java.exe")
PAPER1122_JAR = WORKSPACE / "paper-1.12.2-test-server" / "paper-1.12.2-1620.jar"
UNIVERSAL_JAR = REPO / "dist" / "WorldListTrashCan-universal.jar"


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def to_json_value(value):
    """把 Path 等对象转换为 JSON 可写值。"""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, list):
        return [to_json_value(item) for item in value]
    if isinstance(value, dict):
        return {key: to_json_value(item) for key, item in value.items()}
    return value


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_free_port() -> int:
    """从系统申请一个临时可用端口。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def ensure_inputs() -> None:
    """确认迁移矩阵需要的 Jar 和 Java 可用。"""
    missing = [str(path) for path in (JAVA8, PAPER1122_JAR, UNIVERSAL_JAR) if not path.is_file()]
    if missing:
        raise RuntimeError("缺少 F-005 迁移验证输入: " + "; ".join(missing))


def pack_rcon(packet_id: int, packet_type: int, payload: str) -> bytes:
    """构造 RCON 数据包。"""
    body = struct.pack("<ii", packet_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def recv_exact(sock: socket.socket, size: int) -> bytes:
    """读取指定长度的 socket 数据。"""
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("RCON 连接提前关闭")
        data += chunk
    return data


def recv_rcon(sock: socket.socket) -> tuple[int, int, str]:
    """读取一个 RCON 响应包。"""
    length = struct.unpack("<i", recv_exact(sock, 4))[0]
    packet = recv_exact(sock, length)
    packet_id, packet_type = struct.unpack("<ii", packet[:8])
    return packet_id, packet_type, packet[8:-2].decode("utf-8", errors="replace")


def rcon_command(port: int, command: str) -> str:
    """通过 RCON 执行单条命令。"""
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        sock.settimeout(10)
        sock.sendall(pack_rcon(1, 3, RCON_PASSWORD))
        auth_id, _, auth_body = recv_rcon(sock)
        if auth_id == -1:
            raise RuntimeError("RCON 认证失败: " + auth_body)
        sock.sendall(pack_rcon(2, 2, command))
        _, _, body = recv_rcon(sock)
        return body


def wait_for_rcon(port: int) -> None:
    """等待 RCON 可以连接并执行命令。"""
    deadline = time.time() + RCON_TIMEOUT_SECONDS
    last_error = None
    while time.time() < deadline:
        try:
            rcon_command(port, "list")
            return
        except Exception as error:
            last_error = error
            time.sleep(1.0)
    raise RuntimeError("等待 RCON 超时: " + repr(last_error))


def make_server_properties(port: int, rcon_port: int) -> str:
    """生成隔离测试服 server.properties。"""
    return "\n".join([
        "server-port=" + str(port),
        "enable-rcon=true",
        "rcon.port=" + str(rcon_port),
        "rcon.password=" + RCON_PASSWORD,
        "online-mode=false",
        "motd=WorldListTrashCan legacy migration matrix",
        "level-name=world",
        "spawn-protection=0",
        "view-distance=4",
        "max-tick-time=60000",
        "enable-command-block=false",
        "allow-flight=true",
        "",
    ])


def legacy_config(case: dict) -> str:
    """生成旧版单文件 config.yml 测试夹具。"""
    use_model = 1 if case["recoveryMode"] == "global-trash" else 2
    return f"""Set:
  Lang: {case['language']}
  Debug: true
  SecondCount: {case['interval']}
  WorldClearWhiteList:
  - legacy_ignore
  ClearEntity:
    Flag: false
    ClearExpBottle: true
    ClearMonster: true
    ClearAnimals: false
    ClearProjectile: true
    ClearReNameEntity: false
    IgnoreEntitiesInBoat: true
  GlobalTrash:
    Flag: true
    MaxPage: {case['maxPages']}
    Delay: {case['takeDelay']}
    EveryClearGlobalTrash: 3
    Log:
      Enable: true
    GlobalItems:
      BackItem:
        ModelId: 101
      NextItem:
        ModelId: 102
      BackgroundItem:
        ModelId: 103
  SighCheckName: '[trash]'
  SighCheckedName: '[created]'
  DefaultRashCanMax: {case['worldMax']}
  BanWorldNameList:
  - banned_world
  PersonalTrashCan:
    Flag: true
    NoWorldTrashCanEnterPersonalTrashCan: true
    OriginalFeatureClearItemAddGlobalTrash:
      Delay: {case['recoveryDelay']}
      UseModel: {use_model}
      Model2:
        AutoClear: true
        Coins: {case['takeCost']}
  ChatFlag: true
  ChatConsoleLogFlag: true
  ChatClickCommand: '/wtc stats'
  ChatMessageForCount:
  - '0;&a旧清理完成'
  ActionBarFlag: true
  ActionBarMessageForCount:
  - '0;&b旧ActionBar'
  CommandFlag: true
  CommandForCount:
  - '0;say legacy-command'
  TitleFlag: true
  TitleMessageForCount:
  - '0;&e旧标题;&7旧副标题'
  SoundFlag: true
  SoundForCount:
  - '0;ENTITY_EXPERIENCE_ORB_PICKUP;1;1'
  BossBarFlag: true
  BossBarMessageForCount:
  - '0;&6旧BossBar;SOLID;YELLOW'
GlobalBanItem:
- DIRT
- COBBLESTONE
ChatSet:
  QuickSendMessage:
    Flag: true
    Time: 1.5
    Message: '&c慢一点'
    Command: 'say chat-rate'
  QuickUseCommand:
    Flag: true
    Time: 2.5
    Message: '&c命令慢一点'
    Command: 'say command-rate'
    WhiteList:
    - /login
DropItemCheck:
  Flag: true
SimpleOptimize:
  NotPickArrow: true
  NotTreadingFarmLand: true
WorldEntityLimitCount:
  Flag: true
  BanWorldNameList:
  - ignored_world_limit
  DefaultCount:
  - 'ZOMBIE;50'
GatherEntityLimitCount:
  Flag: true
  ItemDropFlag: true
  BanWorldNameList:
  - ignored_gather_limit
  DefaultCount:
  - 'DROPPED_ITEM;20;8;4'
"""


def legacy_data(case: dict) -> str:
    """生成旧版 data/data.yml 测试夹具。"""
    return f"""WorldData:
  world:
    SignLocation:
    - '1,64,1'
    - '2.8,65.2,3.9'
    RashMaxCount: {case['worldMax']}
    BanItem:
    - {case['firstMaterial']}
    - {case['secondMaterial']}
"""


def prepare_server(case: dict, run_root: Path) -> Path:
    """准备一个独立 Paper 1.12.2 迁移测试服。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "plugins").mkdir(parents=True)
    shutil.copy2(PAPER1122_JAR, server_dir / PAPER1122_JAR.name)
    copy_paper_runtime_cache(server_dir)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "WorldListTrashCan-universal.jar")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        make_server_properties(case["port"], case["rcon"]), encoding="utf-8")
    write_legacy_source(server_dir, case)
    return server_dir


def copy_paper_runtime_cache(server_dir: Path) -> None:
    """复制 Paper 1.12.2 运行缓存，避免重复下载。"""
    source_server = PAPER1122_JAR.parent
    for name in ("cache", "libraries"):
        source = source_server / name
        target = server_dir / name
        if source.exists() and not target.exists():
            shutil.copytree(source, target)


def write_legacy_source(server_dir: Path, case: dict) -> None:
    """按用例写入根目录旧结构或未完成备份结构。"""
    data_dir = server_dir / "plugins" / "WorldListTrashCan"
    backup_dir = data_dir / "old-version-config"
    source_dir = backup_dir if case["source"] == "backup-only" else data_dir
    (source_dir / "data").mkdir(parents=True, exist_ok=True)
    (source_dir / "config.yml").write_text(legacy_config(case), encoding="utf-8")
    (source_dir / "data" / "data.yml").write_text(legacy_data(case), encoding="utf-8")
    (source_dir / "logs").mkdir(parents=True, exist_ok=True)
    (source_dir / "logs" / "legacy.log").write_text("legacy-log-must-be-kept\n", encoding="utf-8")
    (source_dir / "custom.yml").write_text("custom: keep\n", encoding="utf-8")
    if case["source"] == "partial-archive":
        backup_dir.mkdir(parents=True, exist_ok=True)
        (backup_dir / "config.yml").write_text(legacy_config(case), encoding="utf-8")
    if case["source"] == "backup-only":
        staging = data_dir / ".migration-staging"
        staging.mkdir(parents=True, exist_ok=True)
        (staging / "stale.txt").write_text("interrupted\n", encoding="utf-8")


def run_server_phase(case: dict, server_dir: Path, evidence_dir: Path, phase: str,
                     commands: list[str]) -> dict[str, str]:
    """启动一次服务端、执行命令并保留该阶段完整日志。"""
    phase_dir = evidence_dir / case["id"] / "logs" / phase
    phase_dir.mkdir(parents=True, exist_ok=True)
    stdout_log = phase_dir / "server-stdout.log"
    stderr_log = phase_dir / "server-stderr.log"
    with stdout_log.open("w", encoding="utf-8", errors="replace") as stdout, \
            stderr_log.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(
            [str(JAVA8), "-Xms512M", "-Xmx1024M", "-jar", PAPER1122_JAR.name, "nogui"],
            cwd=server_dir,
            stdin=subprocess.PIPE,
            stdout=stdout,
            stderr=stderr,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        try:
            wait_for_rcon(case["rcon"])
            responses = {}
            entries = []
            for command in commands:
                body = rcon_command(case["rcon"], command)
                responses[command] = body
                entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(0.25)
            (phase_dir / "rcon-commands.log").write_text(
                "\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(0.75)
            stop_server(process, case["rcon"])
        finally:
            terminate_server(process)
    copy_if_exists(server_dir / "logs" / "latest.log", phase_dir / "latest.log")
    return responses


def stop_server(process: subprocess.Popen, rcon_port: int) -> None:
    """优先通过 RCON 停止服务端。"""
    if process.poll() is not None:
        return
    try:
        rcon_command(rcon_port, "stop")
    except Exception:
        if process.stdin:
            process.stdin.write("stop\n")
            process.stdin.flush()
    try:
        process.wait(timeout=SERVER_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=30)


def terminate_server(process: subprocess.Popen) -> None:
    """确保异常路径下服务端不会残留。"""
    if process.poll() is None:
        process.kill()
        process.wait(timeout=30)


def run_case(case: dict, run_root: Path, evidence_dir: Path) -> dict:
    """执行首迁、二次启动和可选旧配置回放拒绝验证。"""
    log("准备迁移用例 " + case["id"])
    server_dir = prepare_server(case, run_root)
    commands = ["plugins", "wtc platform", "wtc stats", "wtc clear true"]
    first_responses = run_server_phase(case, server_dir, evidence_dir, "01-first-start", commands)
    first_checks = assert_migrated(case, server_dir, first_responses)
    marker = server_dir / "plugins" / "WorldListTrashCan" / "old-version-config" / "migration-complete.yml"
    marker_before = marker.read_bytes()
    second_responses = run_server_phase(case, server_dir, evidence_dir, "02-marker-restart", commands)
    second_checks = assert_marker_restart(case, server_dir, second_responses, marker_before, evidence_dir)
    snapshot_case_data(case, server_dir, evidence_dir)
    reject_checks = []
    if case.get("checkReject"):
        reject_checks = run_reintroduced_legacy_rejection(case, server_dir, evidence_dir)
    return {
        "id": case["id"],
        "source": case["source"],
        "passed": True,
        "checks": first_checks + second_checks + reject_checks,
        "commands": {"first": first_responses, "restart": second_responses},
    }


def assert_migrated(case: dict, server_dir: Path, responses: dict[str, str]) -> list[dict]:
    """断言旧结构已隔离、配置已迁移并且插件可正常运行。"""
    data_dir = server_dir / "plugins" / "WorldListTrashCan"
    backup = data_dir / "old-version-config"
    generated = {
        "config": data_dir / "config.yml",
        "cleanup": data_dir / "cleanup.yml",
        "trash": data_dir / "trash.yml",
        "protections": data_dir / "protections.yml",
        "entityLimits": data_dir / "entity-limits.yml",
        "worlds": data_dir / "data" / "worlds.yml",
        "report": backup / "migration-report.md",
        "marker": backup / "migration-complete.yml",
        "oldConfig": backup / "config.yml",
        "oldData": backup / "data" / "data.yml",
        "oldLog": backup / "logs" / "legacy.log",
        "oldCustom": backup / "custom.yml",
    }
    for name, path in generated.items():
        if not path.is_file():
            raise AssertionError(case["id"] + " 缺少文件 " + name + ": " + str(path))
    checks = []
    checks.extend(assert_text(generated["config"], [
        "config-schema-version: 2", "language: " + case["language"], "debug: true"]))
    checks.extend(assert_text(generated["cleanup"], [
        "interval-seconds: " + str(case["interval"]), "- legacy_ignore", "旧ActionBar", "旧BossBar"]))
    checks.extend(assert_text(generated["trash"], [
        "max-pages: " + str(case["maxPages"]),
        "take-delay-millis: " + str(case["takeDelay"]),
        "default-max-count: " + str(case["worldMax"]),
        "mode: " + case["recoveryMode"],
        "model-id: 103",
    ]))
    checks.extend(assert_text(generated["protections"], [
        "interval-seconds: 1.5", "interval-seconds: 2.5", "remove-unpickable-arrow: true"]))
    checks.extend(assert_text(generated["entityLimits"], [
        "entity: ZOMBIE", "entity: DROPPED_ITEM", "remove-count: 4"]))
    checks.extend(assert_text(generated["worlds"], [
        "1,64,1", "2,65,3", "max-count: " + str(case["worldMax"]), case["firstMaterial"]]))
    checks.extend(assert_text(generated["report"], [
        "old-version-config 隔离备份", "Set.SecondCount -> interval-seconds"]))
    checks.extend(assert_text(generated["marker"], [
        "status: complete", "target-config-schema-version: 2", "target-plugin-version: \"7.0.0\""]))
    checks.extend(assert_text(generated["oldLog"], ["legacy-log-must-be-kept"]))
    checks.extend(assert_text(generated["oldCustom"], ["custom: keep"]))
    marker_text = generated["marker"].read_text(encoding="utf-8")
    if not re.search(r'source-sha256: "[0-9a-f]{64}"', marker_text):
        raise AssertionError(case["id"] + " 完成标记缺少 64 位 source-sha256")
    root_config = generated["config"].read_text(encoding="utf-8", errors="replace")
    if "Set:" in root_config or "GlobalBanItem:" in root_config:
        raise AssertionError(case["id"] + " 根目录仍在直接使用旧配置结构")
    assert_runtime_commands(case, responses)
    return checks


def assert_runtime_commands(case: dict, responses: dict[str, str]) -> None:
    """断言规范命令入口和迁移后的运行数据可用。"""
    if "WorldListTrashCan" not in responses.get("plugins", ""):
        raise AssertionError(case["id"] + " plugins 未显示 WorldListTrashCan")
    platform = responses.get("wtc platform", "")
    if "legacy-1.12" not in platform or "universal" not in platform:
        raise AssertionError(case["id"] + " platform 未显示 legacy-1.12 universal: " + platform)
    if str(case["maxPages"]) not in responses.get("wtc stats", ""):
        raise AssertionError(case["id"] + " stats 未包含迁移后的公共页数")


def assert_marker_restart(case: dict, server_dir: Path, responses: dict[str, str],
                          marker_before: bytes, evidence_dir: Path) -> list[dict]:
    """断言完成标记使第二次启动跳过迁移且业务仍可用。"""
    marker = server_dir / "plugins" / "WorldListTrashCan" / "old-version-config" / "migration-complete.yml"
    if marker.read_bytes() != marker_before:
        raise AssertionError(case["id"] + " 第二次启动改写了迁移完成标记")
    stdout = evidence_dir / case["id"] / "logs" / "02-marker-restart" / "server-stdout.log"
    stdout_text = stdout.read_text(encoding="utf-8", errors="replace")
    if "[Migration] 已完成旧 WorldListTrashCan 配置迁移" in stdout_text:
        raise AssertionError(case["id"] + " 第二次启动重复执行了迁移")
    assert_runtime_commands(case, responses)
    return [{"file": str(marker), "needle": "restart-marker-unchanged", "ok": True}]


def run_reintroduced_legacy_rejection(case: dict, server_dir: Path, evidence_dir: Path) -> list[dict]:
    """把旧结构重新放回根目录并断言插件拒绝加载。"""
    data_dir = server_dir / "plugins" / "WorldListTrashCan"
    (data_dir / "config.yml").write_text(legacy_config(case), encoding="utf-8")
    responses = run_server_phase(case, server_dir, evidence_dir, "03-reintroduced-legacy-rejected", [
        "plugins", "wtc platform"])
    stdout = evidence_dir / case["id"] / "logs" / "03-reintroduced-legacy-rejected" / "server-stdout.log"
    text = stdout.read_text(encoding="utf-8", errors="replace")
    if "[MigrationGuard] legacy-root-after-complete" not in text:
        raise AssertionError(case["id"] + " 重新放回旧配置后没有明确拒绝原因")
    platform = responses.get("wtc platform", "")
    if "legacy-1.12" in platform or "universal" in platform:
        raise AssertionError(case["id"] + " 重新放回旧配置后插件仍在处理命令")
    return [{"file": str(stdout), "needle": "[MigrationGuard] legacy-root-after-complete", "ok": True}]


def assert_text(path: Path, needles: list[str]) -> list[dict]:
    """断言 UTF-8 文本中包含全部关键内容。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    checks = []
    for needle in needles:
        if needle not in text:
            raise AssertionError(str(path) + " 缺少关键文本: " + needle)
        checks.append({"file": str(path), "needle": needle, "ok": True})
    return checks


def snapshot_case_data(case: dict, server_dir: Path, evidence_dir: Path) -> None:
    """在破坏性拒绝用例前复制迁移后的完整插件数据。"""
    source = server_dir / "plugins" / "WorldListTrashCan"
    target = evidence_dir / case["id"] / "generated-plugin-data"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(source, target, ignore=shutil.ignore_patterns("*.jar"))


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的单个文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入迁移专项证据说明。"""
    lines = [
        "# F-005 同名旧配置隔离与迁移专项验收",
        "",
        "- 被测插件: `dist/WorldListTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 服务端: Paper 1.12.2 独立临时测试服，Java 8",
        "- 验收方式: 真实服务端启动 + RCON + 文件级断言",
        "- 覆盖: 首次迁移、完整目录隔离、日志保留、中断重入、无标记备份重试、标记跳过、旧结构回放拒绝",
        "- 结论: " + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
        "## 证据",
        "",
    ]
    for result in summary["results"]:
        lines.extend([
            "### " + result["id"],
            "",
            "- 来源类型: `" + result["source"] + "`",
            "- 迁移后数据: `" + result["id"] + "/generated-plugin-data/`",
            "- 首次启动: `" + result["id"] + "/logs/01-first-start/`",
            "- 标记重启: `" + result["id"] + "/logs/02-marker-restart/`",
            "- 旧结构回放拒绝: `" + result["id"] + "/logs/03-reintroduced-legacy-rejected/`（仅对应开启该断言的用例）",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def case_data(identifier: str, source: str, index: int, check_reject: bool = False) -> dict:
    """创建一组具有独立迁移值的测试用例。"""
    return {
        "id": identifier,
        "source": source,
        "port": find_free_port(),
        "rcon": find_free_port(),
        "language": "legacy_case_" + str(index) + ".yml",
        "interval": 70 + index,
        "maxPages": 6 + index,
        "takeDelay": 200 + index,
        "worldMax": 8 + index,
        "takeCost": 3.5 + index,
        "recoveryDelay": 4 + index,
        "recoveryMode": "personal-trash" if index % 2 else "global-trash",
        "firstMaterial": "COBBLESTONE" if index % 2 else "STONE",
        "secondMaterial": "STONE" if index % 2 else "DIRT",
        "checkReject": check_reject,
    }


def main() -> int:
    """执行 F-005 同名旧配置隔离与迁移矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("legacy-migration-universal-" + timestamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    cases = [
        case_data("current-root", "current", 1, True),
        case_data("partial-archive-resume", "partial-archive", 2),
        case_data("backup-only-retry", "backup-only", 3),
    ]
    results = [run_case(case, run_root, evidence_dir) for case in cases]
    summary = {
        "timestamp": timestamp,
        "allPassed": all(item["passed"] for item in results),
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "paperJar": str(PAPER1122_JAR),
        "java": str(JAVA8),
        "evidenceDir": str(evidence_dir),
        "results": results,
    }
    write_json(evidence_dir / "summary.json", summary)
    write_readme(evidence_dir, summary)
    log("F-005 迁移专项完成: " + str(evidence_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
