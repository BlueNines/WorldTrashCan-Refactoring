import hashlib
import json
import shutil
import socket
import struct
import subprocess
import time
from pathlib import Path


RCON_PASSWORD = "blwtc"
RCON_TIMEOUT_SECONDS = 180
SERVER_TIMEOUT_SECONDS = 240


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def repo_root() -> Path:
    """返回 BlWorldTrashCan 重构仓库根目录。"""
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
UNIVERSAL_JAR = REPO / "dist" / "BlWorldTrashCan-universal.jar"


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
    """确认本轮验证需要的 jar 和 Java 可用。"""
    missing = []
    for path in (JAVA8, PAPER1122_JAR, UNIVERSAL_JAR):
        if not path.is_file():
            missing.append(str(path))
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
    payload = packet[8:-2].decode("utf-8", errors="replace")
    return packet_id, packet_type, payload


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
        "motd=BlWorldTrashCan legacy migration matrix",
        "level-name=world",
        "spawn-protection=0",
        "view-distance=4",
        "max-tick-time=60000",
        "enable-command-block=false",
        "allow-flight=true",
        "",
    ])


def adjacent_old_config() -> str:
    """返回相邻旧目录迁移用的旧 config.yml。"""
    return legacy_config("legacy_adjacent.yml", 123, 7, 250, 9, 4.5, 5, "global-trash")


def current_old_config() -> str:
    """返回当前目录旧结构迁移用的旧 config.yml。"""
    return legacy_config("legacy_current.yml", 77, 8, 300, 11, 9.5, 6, "personal-trash")


def legacy_config(language: str, interval: int, max_pages: int, delay: int,
                  world_max: int, take_cost: float, recovery_delay: int, recovery_mode: str) -> str:
    """生成旧 WorldListTrashCan config.yml 测试夹具。"""
    use_model = 1 if recovery_mode == "global-trash" else 2
    return f"""Set:
  Lang: {language}
  Debug: true
  SecondCount: {interval}
  WorldClearWhiteList:
  - legacy_ignore
  NoClearContainerType:
  - DIAMOND
  NoClearContainerName:
  - 不清理名称
  NoClearContainerLore:
  - 不清理Lore
  ClearEntity:
    Flag: false
    ClearExpBottle: true
    ClearMonster: true
    ClearAnimals: false
    ClearProjectile: true
    ClearReNameEntity: false
    IgnoreEntitiesInBoat: true
    WhiteNameList:
    - 保护实体
    BlackNameList:
    - 强制实体
  GlobalTrash:
    Flag: true
    MaxPage: {max_pages}
    Delay: {delay}
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
  DefaultRashCanMax: {world_max}
  BanWorldNameList:
  - banned_world
  PersonalTrashCan:
    Flag: true
    NoWorldTrashCanEnterPersonalTrashCan: true
    OriginalFeatureClearItemAddGlobalTrash:
      Delay: {recovery_delay}
      UseModel: {use_model}
      Model2:
        AutoClear: true
        Coins: {take_cost}
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


def legacy_data(max_count: int, material: str, second_material: str) -> str:
    """生成旧 WorldListTrashCan data/data.yml 测试夹具。"""
    return f"""WorldData:
  world:
    SignLocation:
    - '1,64,1'
    - '2.8,65.2,3.9'
    RashMaxCount: {max_count}
    BanItem:
    - {material}
    - {second_material}
"""


def prepare_server(case: dict, run_root: Path) -> Path:
    """准备独立 Paper 1.12.2 迁移测试服目录。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "plugins").mkdir(parents=True)
    shutil.copy2(PAPER1122_JAR, server_dir / PAPER1122_JAR.name)
    copy_paper_runtime_cache(server_dir)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "BlWorldTrashCan-universal.jar")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(make_server_properties(case["port"], case["rcon"]), encoding="utf-8")
    write_legacy_source(server_dir, case)
    return server_dir


def copy_paper_runtime_cache(server_dir: Path) -> None:
    """复制 Paper 1.12.2 已有运行缓存，避免临时服重复下载 vanilla jar。"""
    source_server = PAPER1122_JAR.parent
    for name in ("cache", "libraries"):
        source = source_server / name
        target = server_dir / name
        if source.exists() and not target.exists():
            shutil.copytree(source, target)


def write_legacy_source(server_dir: Path, case: dict) -> None:
    """写入指定迁移来源的旧配置和旧数据。"""
    if case["source"] == "adjacent":
        target = server_dir / "plugins" / "WorldListTrashCan"
        config_text = adjacent_old_config()
        data_text = legacy_data(9, "STONE", "DIRT")
    else:
        target = server_dir / "plugins" / "BlWorldTrashCan"
        config_text = current_old_config()
        data_text = legacy_data(11, "COBBLESTONE", "STONE")
    (target / "data").mkdir(parents=True, exist_ok=True)
    (target / "config.yml").write_text(config_text, encoding="utf-8")
    (target / "data" / "data.yml").write_text(data_text, encoding="utf-8")


def run_case(case: dict, run_root: Path, evidence_dir: Path) -> dict:
    """运行单个迁移用例并返回断言结果。"""
    log("准备迁移用例 " + case["id"])
    server_dir = prepare_server(case, run_root)
    stdout_log = evidence_dir / case["id"] / "logs" / "server-stdout.log"
    stderr_log = evidence_dir / case["id"] / "logs" / "server-stderr.log"
    command_log = evidence_dir / case["id"] / "logs" / "rcon-commands.log"
    stdout_log.parent.mkdir(parents=True, exist_ok=True)
    with stdout_log.open("w", encoding="utf-8", errors="replace") as stdout, stderr_log.open("w", encoding="utf-8", errors="replace") as stderr:
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
            commands = ["plugins", "blwtc platform", "blwtc stats", "blwtc clear true"]
            responses = {}
            command_entries = []
            for command in commands:
                body = rcon_command(case["rcon"], command)
                responses[command] = body
                command_entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(0.3)
            command_log.parent.mkdir(parents=True, exist_ok=True)
            command_log.write_text("\n\n".join(command_entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, server_dir, responses)
            stop_server(process, case["rcon"])
        finally:
            terminate_server(process)
    copy_case_evidence(case, server_dir, evidence_dir)
    return result

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


def assert_case(case: dict, server_dir: Path, responses: dict) -> dict:
    """断言单个迁移用例的生成配置、报告和命令 smoke。"""
    data_dir = server_dir / "plugins" / "BlWorldTrashCan"
    generated = {
        "config": data_dir / "config.yml",
        "cleanup": data_dir / "cleanup.yml",
        "trash": data_dir / "trash.yml",
        "protections": data_dir / "protections.yml",
        "entityLimits": data_dir / "entity-limits.yml",
        "worlds": data_dir / "data" / "worlds.yml",
        "report": data_dir / "legacy-migration-report.md",
    }
    for name, path in generated.items():
        if not path.is_file():
            raise AssertionError(case["id"] + " 缺少生成文件 " + name + ": " + str(path))
    assertions = expected_assertions(case)
    checks = []
    checks.extend(assert_text(generated["config"], assertions["config"]))
    checks.extend(assert_text(generated["cleanup"], assertions["cleanup"]))
    checks.extend(assert_text(generated["trash"], assertions["trash"]))
    checks.extend(assert_text(generated["protections"], assertions["protections"]))
    checks.extend(assert_text(generated["entityLimits"], assertions["entityLimits"]))
    checks.extend(assert_text(generated["worlds"], assertions["worlds"]))
    checks.extend(assert_text(generated["report"], assertions["report"]))
    if case["source"] == "current":
        backup = data_dir / "legacy-migration-backup" / "config.yml"
        if not backup.is_file():
            raise AssertionError(case["id"] + " 当前目录旧结构未生成备份: " + str(backup))
        checks.append({"file": str(backup), "needle": "Set:", "ok": True})
    plugins_output = responses.get("plugins", "")
    platform_output = responses.get("blwtc platform", "")
    stats_output = responses.get("blwtc stats", "")
    if "BlWorldTrashCan" not in plugins_output:
        raise AssertionError(case["id"] + " plugins 未显示 BlWorldTrashCan: " + plugins_output)
    if "legacy-1.12" not in platform_output or "universal" not in platform_output:
        raise AssertionError(case["id"] + " platform 未显示 legacy-1.12 universal: " + platform_output)
    if str(case["maxPages"]) not in stats_output:
        raise AssertionError(case["id"] + " stats 未包含迁移后的公共页数: " + stats_output)
    return {
        "id": case["id"],
        "source": case["source"],
        "passed": True,
        "checks": checks,
        "commands": responses,
    }


def expected_assertions(case: dict) -> dict:
    """返回当前用例应当在生成文件中出现的关键文本。"""
    if case["source"] == "adjacent":
        return {
            "config": ["language: legacy_adjacent.yml", "debug: true"],
            "cleanup": ["interval-seconds: 123", "ignored-worlds:", "- legacy_ignore", "enabled: false", "ignore-entities-in-boat: true", "click-command: /wtc stats", "旧ActionBar", "旧BossBar"],
            "trash": ["max-pages: 7", "take-delay-millis: 250", "default-max-count: 9", "take-cost: 4.5", "mode: global-trash", "delay-seconds: 5", "- DIRT", "- COBBLESTONE", "background-model-id: 103"],
            "protections": ["interval-seconds: 1.5", "interval-seconds: 2.5", "drop-protection:", "enabled: true", "remove-unpickable-arrow: true", "prevent-farmland-trampling: true"],
            "entityLimits": ["world-limits:", "gather-limits:", "entity: ZOMBIE", "entity: DROPPED_ITEM", "remove-count: 4"],
            "worlds": ["1,64,1", "2,65,3", "max-count: 9", "- STONE", "- DIRT"],
            "report": ["相邻旧插件数据目录", "Set.SecondCount -> interval-seconds", "GatherEntityLimitCount.DefaultCount -> gather-limits.defaults"],
        }
    return {
        "config": ["language: legacy_current.yml", "debug: true", "migration-enabled: true"],
        "cleanup": ["interval-seconds: 77", "ignored-worlds:", "- legacy_ignore", "enabled: false", "ignore-entities-in-boat: true", "旧ActionBar", "旧BossBar"],
        "trash": ["max-pages: 8", "take-delay-millis: 300", "default-max-count: 11", "take-cost: 9.5", "mode: personal-trash", "delay-seconds: 6", "- DIRT", "- COBBLESTONE", "background-model-id: 103"],
        "protections": ["interval-seconds: 1.5", "interval-seconds: 2.5", "drop-protection:", "enabled: true", "remove-unpickable-arrow: true", "prevent-farmland-trampling: true"],
        "entityLimits": ["world-limits:", "gather-limits:", "entity: ZOMBIE", "entity: DROPPED_ITEM", "remove-count: 4"],
        "worlds": ["1,64,1", "2,65,3", "max-count: 11", "- COBBLESTONE", "- STONE"],
        "report": ["当前插件数据目录旧结构", "Set.SecondCount -> interval-seconds", "GatherEntityLimitCount.DefaultCount -> gather-limits.defaults"],
    }


def assert_text(path: Path, needles: list[str]) -> list[dict]:
    """断言文件中包含所有关键文本。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    checks = []
    for needle in needles:
        if needle not in text:
            raise AssertionError(str(path) + " 缺少关键文本: " + needle)
        checks.append({"file": str(path), "needle": needle, "ok": True})
    return checks


def copy_case_evidence(case: dict, server_dir: Path, evidence_dir: Path) -> None:
    """复制单个用例的旧源、生成配置、迁移报告和服务端日志。"""
    case_dir = evidence_dir / case["id"]
    copy_dir(server_dir / "plugins" / "BlWorldTrashCan", case_dir / "generated-plugin-data")
    if case["source"] == "adjacent":
        copy_dir(server_dir / "plugins" / "WorldListTrashCan", case_dir / "legacy-source")
    else:
        source_dir = server_dir / "plugins" / "BlWorldTrashCan" / "legacy-migration-backup"
        copy_dir(source_dir, case_dir / "legacy-source")
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")


def copy_dir(source: Path, target: Path) -> None:
    """复制目录到证据目录。"""
    if not source.exists():
        return
    if target.exists():
        shutil.rmtree(target)
    ignore = shutil.ignore_patterns("*.jar", "world", "world_*", "cache", "assets")
    shutil.copytree(source, target, ignore=ignore)


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的单个文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入本轮 F-005 迁移证据说明。"""
    lines = [
        "# F-005 旧配置迁移专项验收",
        "",
        "- 被测插件: `dist/BlWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 服务端: Paper 1.12.2 独立临时测试服，Java 8",
        "- 验收方式: 真实服务端启动 + RCON 命令 smoke + 迁移后文件断言",
        "- 覆盖来源: 相邻旧目录 `plugins/WorldListTrashCan`、当前目录旧结构 `plugins/BlWorldTrashCan`",
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
            "- 生成配置: `" + result["id"] + "/generated-plugin-data/`",
            "- 旧源备份: `" + result["id"] + "/legacy-source/`",
            "- 服务端日志: `" + result["id"] + "/logs/latest.log`、`" + result["id"] + "/logs/server-stdout.log`",
            "- RCON 记录: `" + result["id"] + "/logs/rcon-commands.log`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    """执行 F-005 旧配置迁移专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("legacy-migration-universal-" + timestamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    cases = [
        {"id": "adjacent-legacy-folder", "source": "adjacent", "port": find_free_port(), "rcon": find_free_port(), "maxPages": 7},
        {"id": "current-plugin-folder", "source": "current", "port": find_free_port(), "rcon": find_free_port(), "maxPages": 8},
    ]
    results = []
    for case in cases:
        results.append(run_case(case, run_root, evidence_dir))
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
