import hashlib
import json
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
EVIDENCE_ROOT = REPO / "docs" / "test-evidence"
SERVER_DIR = Path(r"E:\server_work\folia1.21.8")
PLUGIN_JAR = REPO / "dist" / "WorldListTrashCan-universal.jar"
SERVER_JAR = SERVER_DIR / "folia-1.21.8-6.jar"
PLUGIN_TARGET = SERVER_DIR / "plugins" / "WorldListTrashCan-universal.jar"
JAVA25 = REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
SERVER_PORT = 30004
READY_TIMEOUT_SECONDS = 120
COMMAND_TIMEOUT_SECONDS = 45
STOP_TIMEOUT_SECONDS = 60


def sha256(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def port_open(host: str, port: int) -> bool:
    """判断目标端口是否已有服务监听。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex((host, port)) == 0


def copy_artifact(run_dir: Path) -> dict:
    """部署当前 universal jar，并备份测试服旧 jar。"""
    if not PLUGIN_JAR.exists():
        raise FileNotFoundError(f"missing artifact: {PLUGIN_JAR}")
    if not SERVER_JAR.exists():
        raise FileNotFoundError(f"missing server jar: {SERVER_JAR}")
    backup_dir = run_dir / "backup"
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup_path = None
    if PLUGIN_TARGET.exists():
        backup_path = backup_dir / "WorldListTrashCan-universal.before.jar"
        shutil.copy2(PLUGIN_TARGET, backup_path)
    shutil.copy2(PLUGIN_JAR, PLUGIN_TARGET)
    return {
        "source": str(PLUGIN_JAR),
        "target": str(PLUGIN_TARGET),
        "size": PLUGIN_JAR.stat().st_size,
        "sha256": sha256(PLUGIN_JAR),
        "backup": str(backup_path) if backup_path else "",
    }


def reader_thread(process: subprocess.Popen, output_queue: queue.Queue, log_path: Path) -> None:
    """持续读取服务端 stdout 并写入证据日志。"""
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        assert process.stdout is not None
        for line in process.stdout:
            log.write(line)
            log.flush()
            output_queue.put(line)


def drain(output_queue: queue.Queue, lines: list[str]) -> str:
    """收集当前已输出的服务端日志。"""
    while True:
        try:
            lines.append(output_queue.get_nowait())
        except queue.Empty:
            break
    return "".join(lines)


def wait_for(output_queue: queue.Queue, lines: list[str], patterns: list[str], timeout: int) -> str:
    """等待服务端日志出现任一目标文本。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = drain(output_queue, lines)
        if any(pattern in text for pattern in patterns):
            return text
        time.sleep(0.2)
    return drain(output_queue, lines)


def send_command(process: subprocess.Popen, command_log: Path, command: str) -> None:
    """向服务端 stdin 发送命令并记录命令日志。"""
    with command_log.open("a", encoding="utf-8") as log:
        log.write(command + "\n")
    assert process.stdin is not None
    process.stdin.write(command + "\n")
    process.stdin.flush()


def extract_between(text: str, start_marker: str, end_marker: str) -> str:
    """截取两个 marker 之间的日志片段。"""
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        return ""
    return text[start:end]


def parse_papi_value(block: str) -> str:
    """从 PAPI marker 日志块中提取独立数字输出。"""
    for raw_line in block.splitlines():
        line = raw_line.strip()
        if line.isdigit():
            return line
        if line.startswith(">") and line[1:].strip().isdigit():
            return line[1:].strip()
        match = re.search(r"\bINFO\]:\s*(\d{1,5})$", line)
        if match:
            return match.group(1)
    return ""


def wait_for_papi_value(output_queue: queue.Queue, lines: list[str], timeout: int) -> tuple[str, str]:
    """等待 PAPI 命令输出数字结果。"""
    deadline = time.time() + timeout
    text = drain(output_queue, lines)
    while time.time() < deadline:
        block = extract_between(text, "AI_FOLIA_PAPI_BEGIN", "AI_FOLIA_PAPI_END")
        if not block:
            start = text.find("AI_FOLIA_PAPI_BEGIN")
            block = text[start:] if start >= 0 else text
        value = parse_papi_value(block)
        if value:
            return value, text
        time.sleep(0.2)
        text = drain(output_queue, lines)
    return "", text


def write_readme(run_dir: Path, result: dict) -> None:
    """写入本次 Folia PAPI 验收说明。"""
    status = result.get("status", "UNKNOWN")
    artifact = result.get("artifact", {})
    (run_dir / "README.md").write_text(
        "# Folia PAPI 变量验收\n\n"
        f"- 被测插件: `dist/WorldListTrashCan-universal.jar`\n"
        f"- 服务端: `E:\\server_work\\folia1.21.8`\n"
        f"- 前置: `[PAPI]PlaceholderAPI-2.11.7-DEV-null (1).jar`\n"
        f"- 验收命令: `papi parse --null %Wtc_ClearTime%`\n"
        f"- 结论: `{status}`\n"
        f"- 插件 SHA256: `{artifact.get('sha256', '')}`\n\n"
        "## 关键文件\n\n"
        "- `summary.json`: 机器可读结果。\n"
        "- `logs/server-console.log`: 完整服务端输出。\n"
        "- `logs/console-commands.log`: 本轮发送的命令。\n"
        "- `backup/WorldListTrashCan-universal.before.jar`: 测试前旧 jar 备份。\n",
        encoding="utf-8",
    )


def run_matrix() -> dict:
    """运行 Folia PAPI 变量真实服务端验收。"""
    run_id = "folia-papi-" + datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = EVIDENCE_ROOT / run_id
    log_dir = run_dir / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    if port_open("127.0.0.1", SERVER_PORT):
        raise RuntimeError(f"server port already in use: {SERVER_PORT}")
    artifact = copy_artifact(run_dir)
    java = JAVA25 if JAVA25.exists() else Path("java")
    command = [
        str(java),
        "-Xms1024M",
        "-Xmx4096M",
        "--add-opens",
        "java.base/java.net=ALL-UNNAMED",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:-UseAESCTRIntrinsics",
        "-jar",
        SERVER_JAR.name,
        "nogui",
    ]
    server_log = log_dir / "server-console.log"
    command_log = log_dir / "console-commands.log"
    launch_info = {
        "command": command,
        "cwd": str(SERVER_DIR),
        "java": str(java),
        "serverPort": SERVER_PORT,
    }
    (log_dir / "server-launch.json").write_text(
        json.dumps(launch_info, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    process = subprocess.Popen(
        command,
        cwd=SERVER_DIR,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_queue: queue.Queue = queue.Queue()
    lines: list[str] = []
    thread = threading.Thread(target=reader_thread, args=(process, output_queue, server_log), daemon=True)
    thread.start()
    status = "FAIL"
    try:
        text = wait_for(output_queue, lines, ["Done (", "Done preparing level"], READY_TIMEOUT_SECONDS)
        ready = "Done (" in text or "Done preparing level" in text
        time.sleep(1)
        send_command(process, command_log, "")
        send_command(process, command_log, "plugins")
        send_command(process, command_log, "wtc platform")
        send_command(process, command_log, "wtc stats")
        send_command(process, command_log, "say AI_FOLIA_PAPI_BEGIN")
        time.sleep(1)
        send_command(process, command_log, "papi parse --null %Wtc_ClearTime%")
        value, text = wait_for_papi_value(output_queue, lines, 8)
        if not value:
            send_command(process, command_log, "papi parse RGBolia1218 %Wtc_ClearTime%")
            value, text = wait_for_papi_value(output_queue, lines, 8)
        send_command(process, command_log, "say AI_FOLIA_PAPI_END")
        text = wait_for(output_queue, lines, ["AI_FOLIA_PAPI_END"], COMMAND_TIMEOUT_SECONDS)
        block = extract_between(text, "AI_FOLIA_PAPI_BEGIN", "AI_FOLIA_PAPI_END")
        if not value:
            value = parse_papi_value(block)
        checks = {
            "ready": ready,
            "placeholderApiLoaded": "PlaceholderAPI" in text and "Enabling PlaceholderAPI" in text,
            "wtcExpansionRegistered": "Successfully registered internal expansion: Wtc" in text,
            "pluginLoaded": "WorldListTrashCan" in text and "Universal runtime: folia" in text,
            "papiValue": value,
            "papiValueNumeric": value.isdigit(),
            "noUnknownCommand": "Unknown command" not in block,
            "noPapiError": "Error executing" not in block and "not enabled" not in block,
        }
        status = "PASS" if all(
            [
                checks["ready"],
                checks["placeholderApiLoaded"],
                checks["wtcExpansionRegistered"],
                checks["pluginLoaded"],
                checks["papiValueNumeric"],
                checks["noUnknownCommand"],
                checks["noPapiError"],
            ]
        ) else "FAIL"
        result = {
            "run": run_id,
            "status": status,
            "serverDir": str(SERVER_DIR),
            "artifact": artifact,
            "checks": checks,
            "papiBlock": block,
        }
    finally:
        if process.poll() is None:
            try:
                send_command(process, command_log, "stop")
                process.wait(timeout=STOP_TIMEOUT_SECONDS)
            except Exception:
                process.kill()
                process.wait(timeout=10)
        drain(output_queue, lines)
    result["exitCode"] = process.returncode
    (run_dir / "summary.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_readme(run_dir, result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def main() -> int:
    """脚本入口。"""
    result = run_matrix()
    return 0 if result.get("status") == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
