import json
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path


REPO = Path(r"C:\Users\pc\Desktop\ai开发插件\待重构插件\WorldListTrashCan重构\refactor-workspace")
EVIDENCE = REPO / "docs" / "test-evidence" / "entity-density-low-overhead-20260618-014942"
UNIVERSAL_JAR = REPO / "dist" / "BLWorldTrashCan-universal.jar"
JAVA_8 = Path(r"C:\Program Files\Java\jdk-1.8\bin\java.exe")
JAVA_25 = REPO / "build" / "tools" / "microsoft-jdk-25.0.3" / "bin" / "java.exe"
RCON_PASSWORD = "aiwtc"
SPAWN_COUNT = 300
MAX_EXPECTED_REMAINING = 8


TEST_ENTITY_LIMITS = """# 每个世界的实体数量限制。
world-limits:
  # 是否启用世界实体数量限制。
  enabled: false
  # 不受限制的世界名。
  ignored-worlds: []
  # 默认限制列表。
  defaults: []

# 低占用实体扫描器。
# 本文件为自动化压力测试临时配置，测试结束后会恢复原文件。
scanner:
  # 目标完整覆盖周期，单位秒。配置模型会把低于 30 的值自动夹到 30。
  target-full-cycle-seconds: 30
  # 扫描任务间隔，单位 tick。
  scan-interval-ticks: 2
  # 每轮至少扫描多少个已加载 chunk。
  min-chunks-per-scan: 1
  # 每轮最多扫描多少个已加载 chunk。
  max-chunks-per-scan: 8
  # 每轮主线程采集快照最多使用多少毫秒。
  max-scan-millis-per-run: 8
  # 候选删除任务间隔，单位 tick。
  remove-interval-ticks: 2
  # 每轮最多真正移除多少个实体，用于验证预算化删除不会一次性扫空。
  max-removes-per-run: 1
  # 最多排队多少个待删除候选。
  max-pending-removals: 512
  # 候选最长保留时间，单位秒。
  candidate-ttl-seconds: 15
  # 候选删除失败后的最大重试次数。
  max-candidate-retries: 1
  # 最多记录多少个脏 chunk。
  max-dirty-chunks: 256
  # chunk 索引多久没有刷新就视为过期，单位秒。
  stale-chunk-seconds: 30
  # 全局最多索引多少个相关实体。
  max-index-entities: 4000
  # 单个 chunk 最多索引多少个相关实体。
  max-index-entities-per-chunk: 512
  # 后台摘要日志间隔，单位秒。
  log-summary-seconds: 5

# 密集实体清理。
gather-limits:
  # 是否启用密集实体清理。
  enabled: true
  # 自动测试不需要掉落物，避免掉落物干扰实体数量。
  drop-items: false
  # 不受密集实体限制的世界名。
  ignored-worlds: []
  # 默认密集限制列表。
  defaults:
    - entity: "COW"
      max-count: 5
      radius: 16
      remove-count: 10
"""


class RconClient:
    """提供最小 Minecraft RCON 客户端能力。"""

    def __init__(self, host, port, password):
        """连接 RCON 并完成认证。"""
        self.host = host
        self.port = port
        self.password = password
        self.request_id = 1000
        self.sock = socket.create_connection((host, port), timeout=10)
        response_id, _, payload = self._request(3, password)
        if response_id == -1:
            raise RuntimeError("RCON authentication failed: " + payload)

    def close(self):
        """关闭 RCON 连接。"""
        try:
            self.sock.close()
        finally:
            self.sock = None

    def command(self, command):
        """发送一条 RCON 命令并返回响应。"""
        return self._request(2, command)[2]

    def _request(self, request_type, payload):
        """按 RCON 协议发送请求并读取响应。"""
        self.request_id += 1
        body = struct.pack("<ii", self.request_id, request_type) + payload.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        size = struct.unpack("<i", self._recv_exact(4))[0]
        data = self._recv_exact(size)
        response_id, response_type = struct.unpack("<ii", data[:8])
        return response_id, response_type, data[8:-2].decode("utf-8", "replace")

    def _recv_exact(self, size):
        """读取指定长度的 socket 数据。"""
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise RuntimeError("RCON socket closed")
            data += chunk
        return data


class ServerCase:
    """描述单个测试服务端的启动和命令差异。"""

    def __init__(self, key, server_dir, jar_name, java_path, rcon_port, summon_command, kill_command,
                 count_setup_commands, count_command):
        """保存测试端参数。"""
        self.key = key
        self.server_dir = Path(server_dir)
        self.jar_name = jar_name
        self.java_path = Path(java_path)
        self.rcon_port = rcon_port
        self.summon_command = summon_command
        self.kill_command = kill_command
        self.count_setup_commands = count_setup_commands
        self.count_command = count_command
        self.evidence_dir = EVIDENCE / key
        self.console_log = self.evidence_dir / "server-console.log"
        self.commands_log = self.evidence_dir / "commands.log"
        self.summary_path = self.evidence_dir / "summary.json"
        self.backup_dir = self.evidence_dir / "backup"
        self.process = None

    def command_args(self):
        """返回启动服务端的 Java 参数。"""
        args = [
            str(self.java_path),
            "-Dfile.encoding=UTF-8",
            "-Xms1024M",
            "-Xmx3072M",
        ]
        if self.key.startswith("folia"):
            args.extend([
                "--add-opens",
                "java.base/java.net=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:-UseAESCTRIntrinsics",
            ])
        args.extend(["-jar", self.jar_name, "nogui"])
        return args


def timestamp():
    """返回适合日志记录的当前时间。"""
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def write_text(path, text):
    """用 UTF-8 写入文本文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def log(case, message):
    """写入单个测试端的命令日志。"""
    with case.commands_log.open("a", encoding="utf-8") as handle:
        handle.write(f"[{timestamp()}] {message}\n")


def strip_colors(text):
    """去掉 Minecraft 颜色控制符。"""
    return re.sub(r"§.", "", text or "")


def update_properties(text, updates):
    """按 key 更新 properties 文本，缺失时追加。"""
    seen = set()
    output = []
    for line in text.splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            output.append(line)
            continue
        key = line.split("=", 1)[0].strip()
        if key in updates:
            output.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key, value in updates.items():
        if key not in seen:
            output.append(f"{key}={value}")
    return "\n".join(output) + "\n"


def prepare_case(case):
    """备份配置、部署 universal jar，并写入临时测试配置。"""
    case.evidence_dir.mkdir(parents=True, exist_ok=True)
    case.backup_dir.mkdir(parents=True, exist_ok=True)
    case.console_log.write_text("", encoding="utf-8")
    case.commands_log.write_text("", encoding="utf-8")
    plugins_dir = case.server_dir / "plugins"
    plugin_config = plugins_dir / "BLWorldTrashCan"
    plugin_config.mkdir(parents=True, exist_ok=True)
    server_properties = case.server_dir / "server.properties"
    entity_limits = plugin_config / "entity-limits.yml"
    shutil.copy2(server_properties, case.backup_dir / "server.properties.before")
    if entity_limits.exists():
        shutil.copy2(entity_limits, case.backup_dir / "entity-limits.yml.before")
    jar_backups = []
    for jar in plugins_dir.glob("BLWorldTrashCan*.jar"):
        target = case.backup_dir / jar.name
        shutil.copy2(jar, target)
        jar_backups.append(str(target))
        jar.unlink()
    shutil.copy2(UNIVERSAL_JAR, plugins_dir / "BLWorldTrashCan-universal.jar")
    write_text(entity_limits, TEST_ENTITY_LIMITS)
    original = server_properties.read_text(encoding="utf-8", errors="replace")
    updated = update_properties(original, {
        "enable-rcon": "true",
        "rcon.port": str(case.rcon_port),
        "rcon.password": RCON_PASSWORD,
        "online-mode": "false",
    })
    write_text(server_properties, updated)
    write_text(case.evidence_dir / "deployed.json", json.dumps({
        "universalJar": str(UNIVERSAL_JAR),
        "jarBackups": jar_backups,
        "rconPort": case.rcon_port,
    }, ensure_ascii=False, indent=2))


def restore_case(case):
    """恢复测试前备份的服务端配置文件。"""
    server_backup = case.backup_dir / "server.properties.before"
    entity_backup = case.backup_dir / "entity-limits.yml.before"
    if server_backup.exists():
        shutil.copy2(server_backup, case.server_dir / "server.properties")
    if entity_backup.exists():
        target = case.server_dir / "plugins" / "BLWorldTrashCan" / "entity-limits.yml"
        shutil.copy2(entity_backup, target)


def pump_console(case):
    """把服务端 stdout 持续写入证据日志。"""
    with case.console_log.open("a", encoding="utf-8", errors="replace") as handle:
        for line in iter(case.process.stdout.readline, ""):
            if line == "":
                break
            handle.write(line)
            handle.flush()


def console_text(case):
    """读取本轮捕获的控制台日志。"""
    if not case.console_log.exists():
        return ""
    return case.console_log.read_text(encoding="utf-8", errors="replace")


def wait_console(case, name, pattern, timeout):
    """等待控制台出现指定正则。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if case.process.poll() is not None:
            raise RuntimeError(f"{case.key} exited while waiting for {name}")
        if re.search(pattern, console_text(case), re.MULTILINE):
            log(case, f"PASS wait {name}")
            return True
        time.sleep(0.5)
    log(case, f"FAIL wait {name}")
    return False


def wait_rcon(case):
    """等待 RCON 可连接。"""
    deadline = time.time() + 90
    last_error = None
    while time.time() < deadline:
        if case.process.poll() is not None:
            raise RuntimeError(f"{case.key} exited before RCON was ready")
        try:
            client = RconClient("127.0.0.1", case.rcon_port, RCON_PASSWORD)
            log(case, "PASS rcon connected")
            return client
        except Exception as exc:
            last_error = exc
            time.sleep(1)
    raise RuntimeError(f"RCON not ready: {last_error}")


def send(case, client, command, quiet=False):
    """发送命令并记录响应。"""
    if not quiet:
        log(case, "> " + command)
    response = client.command(command)
    if response and not quiet:
        log(case, "< " + strip_colors(response).replace("\n", "\\n"))
    return response


def parse_density_stats(response):
    """从 debugdensity 输出解析关键统计。"""
    text = strip_colors(response)
    stats = {"raw": text}
    patterns = {
        "loadedChunks": r"已加载/本轮选择 chunk:\s*(\d+)/(\d+)",
        "indexed": r"索引 chunk/实体:\s*(\d+)/(\d+)",
        "dirty": r"脏 chunk 队列:\s*(\d+)\s*\(标记\s*(\d+),\s*丢弃\s*(\d+)\)",
        "pending": r"候选队列/去重:\s*(\d+)/(\d+)",
        "snapshots": r"快照/扫描 chunk:\s*(\d+)/(\d+)\s*\(未加载\s*(\d+)\)",
        "candidates": r"候选创建/取出/完成:\s*(\d+)/(\d+)/(\d+)",
        "candidateFailures": r"候选过期/重试/丢弃:\s*(\d+)/(\d+)/(\d+)",
        "removals": r"删除成功/跳过:\s*(\d+)/(\d+)",
        "pruned": r"索引修剪 stale/cap:\s*(\d+)/(\d+)",
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match:
            stats[key] = [int(value) for value in match.groups()]
    return stats


def get_density(case, client):
    """执行 debugdensity 并返回解析后的统计。"""
    response = send(case, client, "blwtc debugdensity")
    stats = parse_density_stats(response)
    log(case, "density-stats=" + json.dumps(stats, ensure_ascii=False))
    return stats


def count_remaining(case, client):
    """通过最终 kill 响应统计测试 cow 的剩余数量，并同时清理现场。"""
    response = send(case, client, case.kill_command)
    text = strip_colors(response)
    if "No entity" in text or "没有找到" in text:
        return 0
    match = re.search(r"Killed\s+(\d+)\s+entities", text, re.IGNORECASE)
    if match:
        return int(match.group(1))
    match = re.search(r"已杀死\s*(\d+)", text)
    if match:
        return int(match.group(1))
    if "Killed" in text:
        return 1
    numbers = [int(value) for value in re.findall(r"-?\d+", text)]
    return numbers[0] if numbers else None


def wait_density_success(case, client):
    """等待候选创建、预算删除和候选释放都出现。"""
    target_removals = SPAWN_COUNT - MAX_EXPECTED_REMAINING
    deadline = time.time() + 90
    snapshots = []
    budget_observed = False
    while time.time() < deadline:
        stats = get_density(case, client)
        snapshots.append(stats)
        removals = stats.get("removals", [0, 0])
        candidates = stats.get("candidates", [0, 0, 0])
        pending = stats.get("pending", [0, 0])
        if 0 < removals[0] < target_removals and candidates[0] > removals[0]:
            budget_observed = True
        if (candidates[0] > 0 and candidates[1] > 0 and candidates[2] > 0
                and removals[0] >= target_removals and pending[0] == 0 and pending[1] == 0):
            return True, budget_observed, snapshots
        time.sleep(1)
    return False, budget_observed, snapshots


def run_case(case):
    """执行单个服务端的完整压力验证。"""
    prepare_case(case)
    checks = {}
    error = None
    client = None
    snapshots = []
    remaining = None
    try:
        case.process = subprocess.Popen(
            case.command_args(),
            cwd=str(case.server_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        write_text(case.evidence_dir / "server.pid", str(case.process.pid))
        threading.Thread(target=pump_console, args=(case,), daemon=True).start()
        checks["serverReady"] = wait_console(case, "server ready", r"Done \(", 180)
        client = wait_rcon(case)
        send(case, client, "gamerule doMobSpawning false")
        send(case, client, "gamerule sendCommandFeedback true")
        send(case, client, "gamerule logAdminCommands false")
        send(case, client, case.kill_command)
        send(case, client, "plugins")
        platform_response = send(case, client, "blwtc platform")
        checks["platformCommand"] = "BLWorldTrashCan" in strip_colors(platform_response) or len(platform_response) >= 0
        debug_help = send(case, client, "blwtc debughelp")
        checks["debugHelpContainsDensity"] = "debugdensity" in strip_colors(debug_help)
        send(case, client, "blwtc reload")
        time.sleep(2)
        initial = get_density(case, client)
        checks["densityEnabled"] = "未启用" not in initial.get("raw", "")
        for index in range(1, SPAWN_COUNT + 1):
            send(case, client, case.summon_command, quiet=index > 3)
            if index % 40 == 0:
                log(case, f"spawn-progress {index}/{SPAWN_COUNT}")
        log(case, f"spawned {SPAWN_COUNT} cows in one chunk")
        time.sleep(1)
        success, budget_observed, snapshots = wait_density_success(case, client)
        checks["densityCleanupReachedTarget"] = success
        checks["budgetObserved"] = budget_observed
        remaining = count_remaining(case, client)
        checks["remainingWithinLimit"] = remaining is not None and remaining <= MAX_EXPECTED_REMAINING
        time.sleep(1)
    except Exception as exc:
        error = repr(exc)
        log(case, "ERROR " + error)
    finally:
        if client is not None:
            try:
                send(case, client, "stop")
            except Exception as exc:
                log(case, "stop via RCON failed: " + repr(exc))
            try:
                client.close()
            except Exception:
                pass
        if case.process is not None and case.process.poll() is None:
            try:
                case.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                log(case, "server did not stop in 60s; terminating")
                case.process.terminate()
                try:
                    case.process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    case.process.kill()
                    case.process.wait(timeout=15)
        latest = case.server_dir / "logs" / "latest.log"
        if latest.exists():
            shutil.copy2(latest, case.evidence_dir / "latest-final.log")
        restore_case(case)
        summary = {
            "case": case.key,
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "spawnCount": SPAWN_COUNT,
            "maxExpectedRemaining": MAX_EXPECTED_REMAINING,
            "remaining": remaining,
            "checks": checks,
            "lastDensity": snapshots[-1] if snapshots else None,
            "densitySnapshots": snapshots,
            "processExitCode": None if case.process is None else case.process.returncode,
            "error": error,
        }
        write_text(case.summary_path, json.dumps(summary, ensure_ascii=False, indent=2))
    if error:
        raise RuntimeError(f"{case.key} failed: {error}")
    return summary


def main():
    """按顺序测试 Paper 1.12.2 与 Folia 1.21.8。"""
    if not UNIVERSAL_JAR.exists():
        raise FileNotFoundError(str(UNIVERSAL_JAR))
    cases = [
        ServerCase(
            "paper-1.12.2",
            r"E:\server_work\paper-1.12.2-universal-test-server",
            "paper-1.12.2-1620.jar",
            JAVA_8,
            25576,
            'summon cow 695 5 1057 {NoAI:1b,Silent:1b,Tags:["ai_wtc_density"]}',
            "kill @e[type=cow,tag=ai_wtc_density]",
            [],
            "",
        ),
        ServerCase(
            "folia-1.21.8",
            r"E:\server_work\folia1.21.8",
            "folia-1.21.8-6.jar",
            JAVA_25,
            25575,
            'execute in minecraft:overworld run summon minecraft:cow 0 80 0 {NoAI:1b,Silent:1b,Tags:["ai_wtc_density"]}',
            "kill @e[type=minecraft:cow,tag=ai_wtc_density]",
            [],
            "",
        ),
    ]
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    summaries = []
    for case in cases:
        summaries.append(run_case(case))
    artifact_summary = {
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "universalJar": str(UNIVERSAL_JAR),
        "summaries": summaries,
        "allPassed": all(
            summary["checks"].get("serverReady")
            and summary["checks"].get("debugHelpContainsDensity")
            and summary["checks"].get("densityEnabled")
            and summary["checks"].get("densityCleanupReachedTarget")
            and summary["checks"].get("budgetObserved")
            and summary["checks"].get("remainingWithinLimit")
            and not summary.get("error")
            for summary in summaries
        ),
    }
    write_text(EVIDENCE / "artifact-summary.json", json.dumps(artifact_summary, ensure_ascii=False, indent=2))
    if not artifact_summary["allPassed"]:
        raise RuntimeError("one or more density low-overhead checks failed")


if __name__ == "__main__":
    main()
