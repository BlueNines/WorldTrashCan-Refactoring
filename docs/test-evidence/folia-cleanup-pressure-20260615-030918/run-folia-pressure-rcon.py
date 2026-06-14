import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

SERVER = Path(r"E:\server_work\folia1.21.8")
REPO = Path(r"C:\Users\pc\Desktop\ai开发插件\待重构插件\WorldListTrashCan重构\refactor-workspace")
EVIDENCE = Path((REPO / "build" / "last-folia-pressure-evidence.txt").read_text(encoding="utf-8-sig").strip().lstrip("\ufeff"))
JAVA = REPO / "build" / "tools" / "microsoft-jdk-25.0.3" / "bin" / "java.exe"
if not JAVA.exists():
    JAVA = Path("java.exe")
CONSOLE_LOG = EVIDENCE / "server-console-rcon.log"
COMMANDS_LOG = EVIDENCE / "commands-rcon.log"
SUMMARY_PATH = EVIDENCE / "summary-rcon.json"
SPAWN_COUNT = 32000
RCON_HOST = "127.0.0.1"
RCON_PORT = 25575
RCON_PASSWORD = "aiwtc"

class RconClient:
    """Minimal Minecraft RCON client for local test automation."""
    def __init__(self, host, port, password):
        self.host = host
        self.port = port
        self.password = password
        self.sock = None
        self.request_id = 100

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        response_id, _, payload = self._request(3, self.password)
        if response_id == -1:
            raise RuntimeError("RCON authentication failed: " + payload)

    def close(self):
        if self.sock:
            self.sock.close()
            self.sock = None

    def command(self, command):
        _, _, payload = self._request(2, command)
        return payload

    def _request(self, request_type, payload):
        self.request_id += 1
        body = struct.pack("<ii", self.request_id, request_type) + payload.encode("utf-8") + b"\x00\x00"
        packet = struct.pack("<i", len(body)) + body
        self.sock.sendall(packet)
        return self._response()

    def _response(self):
        size_data = self._recv_exact(4)
        size = struct.unpack("<i", size_data)[0]
        data = self._recv_exact(size)
        response_id, response_type = struct.unpack("<ii", data[:8])
        payload = data[8:-2].decode("utf-8", errors="replace")
        return response_id, response_type, payload

    def _recv_exact(self, size):
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise RuntimeError("RCON socket closed")
            data += chunk
        return data


def now():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def write_command(message):
    with COMMANDS_LOG.open("a", encoding="utf-8") as handle:
        handle.write(f"[{now()}] {message}\n")


def read_console():
    if not CONSOLE_LOG.exists():
        return ""
    return CONSOLE_LOG.read_text(encoding="utf-8", errors="replace")


def count_log(pattern):
    return len(re.findall(pattern, read_console(), flags=re.MULTILINE))


def wait_log_pattern(name, pattern, timeout, process):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited while waiting for {name}")
        if re.search(pattern, read_console(), flags=re.MULTILINE):
            write_command(f"PASS wait {name}")
            return True
        time.sleep(0.5)
    write_command(f"FAIL wait {name}")
    return False


def wait_log_count(name, pattern, baseline, increase, timeout, process):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited while waiting for {name}")
        current = count_log(pattern)
        if current >= baseline + increase:
            write_command(f"PASS wait {name} count={current} baseline={baseline} increase={increase}")
            return True
        time.sleep(0.5)
    current = count_log(pattern)
    write_command(f"FAIL wait {name} count={current} baseline={baseline} increase={increase}")
    return False


def send(client, command, quiet=False):
    if not quiet:
        write_command("> " + command)
    response = client.command(command)
    if response and not quiet:
        write_command("< " + response.replace("\n", "\\n"))
    return response


def wait_rcon(process, timeout=90):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited before RCON was ready")
        try:
            client = RconClient(RCON_HOST, RCON_PORT, RCON_PASSWORD)
            client.connect()
            write_command("PASS rcon connected")
            return client
        except Exception as exc:
            last_error = exc
            time.sleep(1)
    raise RuntimeError(f"RCON not ready: {last_error}")


def pump_stream(stream):
    with CONSOLE_LOG.open("a", encoding="utf-8", errors="replace") as handle:
        for raw in iter(stream.readline, ""):
            if raw == "":
                break
            handle.write(raw)
            handle.flush()


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    CONSOLE_LOG.write_text("", encoding="utf-8")
    COMMANDS_LOG.write_text("", encoding="utf-8")
    checks = {}
    process = subprocess.Popen(
        [str(JAVA), "-Xms1024M", "-Xmx4096M", "--add-opens", "java.base/java.net=ALL-UNNAMED",
         "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseAESCTRIntrinsics", "-jar", "folia-1.21.8-6.jar", "nogui"],
        cwd=str(SERVER), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    (EVIDENCE / "server.pid").write_text(str(process.pid), encoding="utf-8")
    thread = threading.Thread(target=pump_stream, args=(process.stdout,), daemon=True)
    thread.start()
    client = None
    error = None
    try:
        checks["ready"] = wait_log_pattern("server-ready", r"Done \(", 180, process)
        client = wait_rcon(process)
        send(client, "gamerule doMobSpawning false")
        send(client, "gamerule sendCommandFeedback true")
        send(client, "gamerule logAdminCommands false")
        send(client, "kill @e[tag=ai_wtc_pressure]")
        time.sleep(2)
        send(client, "blwtc platform")
        send(client, "blwtc reload")
        time.sleep(2)
        before_normal = count_log(r"\[FoliaCleanup\].*timedOut=false")
        before_no_refresh = count_log("公共垃圾桶不会自动刷新")
        send(client, "say AI_WTC_BASELINE_CLEAR_START")
        send(client, "blwtc clear")
        checks["baselineNoTimeout"] = wait_log_count("baseline timedOut=false", r"\[FoliaCleanup\].*timedOut=false", before_normal, 1, 40, process)
        checks["noRefreshText"] = wait_log_count("public no refresh text", "公共垃圾桶不会自动刷新", before_no_refresh, 1, 20, process)
        send(client, "scoreboard objectives add ai_wtc dummy")
        send(client, "scoreboard players set #pressure ai_wtc 0")
        send(client, "gamerule sendCommandFeedback false")
        write_command(f"spawning armor stands count={SPAWN_COUNT} tag=ai_wtc_pressure")
        summon = 'execute in minecraft:overworld run summon minecraft:armor_stand 0 80 0 {NoGravity:1b,Invisible:1b,Invulnerable:1b,Tags:["ai_wtc_pressure"]}'
        for index in range(1, SPAWN_COUNT + 1):
            send(client, summon, quiet=True)
            if index % 5000 == 0:
                write_command(f"spawn-progress {index}/{SPAWN_COUNT}")
        send(client, "gamerule sendCommandFeedback true")
        send(client, "say AI_WTC_PRESSURE_SPAWN_DONE_32000")
        checks["spawnMarker"] = wait_log_pattern("spawn marker", "AI_WTC_PRESSURE_SPAWN_DONE_32000", 90, process)
        send(client, "scoreboard players set #pressure ai_wtc 0")
        send(client, "execute as @e[tag=ai_wtc_pressure,type=minecraft:armor_stand] run scoreboard players add #pressure ai_wtc 1")
        count_before = send(client, "scoreboard players get #pressure ai_wtc")
        write_command("pressure-count-before-clear=" + count_before.replace("\n", "\\n"))
        time.sleep(3)
        before_timeout = count_log(r"\[FoliaCleanup\].*timedOut=true")
        before_running = count_log("上一轮 region-safe 清理仍在运行")
        send(client, "say AI_WTC_PRESSURE_CLEAR_START")
        send(client, "blwtc clear")
        time.sleep(0.2)
        send(client, "blwtc clear")
        checks["runningGuard"] = wait_log_count("running guard", "上一轮 region-safe 清理仍在运行", before_running, 1, 20, process)
        checks["firstTimeout"] = wait_log_count("first pressure timeout", r"\[FoliaCleanup\].*timedOut=true", before_timeout, 1, 90, process)
        before_timeout2 = count_log(r"\[FoliaCleanup\].*timedOut=true")
        before_started = count_log("已启动 Folia region-safe 清理")
        send(client, "say AI_WTC_AFTER_TIMEOUT_RETRY")
        send(client, "blwtc clear")
        checks["retryStarted"] = wait_log_count("retry clear started", "已启动 Folia region-safe 清理", before_started, 1, 20, process)
        checks["secondTimeout"] = wait_log_count("second pressure timeout", r"\[FoliaCleanup\].*timedOut=true", before_timeout2, 1, 90, process)
        send(client, "scoreboard players set #pressure ai_wtc 0")
        send(client, "execute as @e[tag=ai_wtc_pressure,type=minecraft:armor_stand] run scoreboard players add #pressure ai_wtc 1")
        count_after = send(client, "scoreboard players get #pressure ai_wtc")
        write_command("pressure-count-after-timeouts=" + count_after.replace("\n", "\\n"))
        send(client, "kill @e[tag=ai_wtc_pressure]")
        time.sleep(3)
    except Exception as exc:
        error = repr(exc)
        write_command("ERROR " + error)
    finally:
        if client is not None:
            try:
                send(client, "stop")
            except Exception as exc:
                write_command("stop via rcon failed: " + repr(exc))
            try:
                client.close()
            except Exception:
                pass
        if process.poll() is None:
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                write_command("server did not stop in 60s; terminating")
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
        try:
            latest = SERVER / "logs" / "latest.log"
            if latest.exists():
                (EVIDENCE / "latest-rcon.log").write_text(latest.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")
        except Exception as exc:
            write_command("copy latest.log failed: " + repr(exc))
        summary = {
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "server": str(SERVER),
            "jar": "BLWorldTrashCan-universal.jar",
            "spawnCount": SPAWN_COUNT,
            "checks": checks,
            "timedOutTrueCount": count_log(r"\[FoliaCleanup\].*timedOut=true"),
            "timedOutFalseCount": count_log(r"\[FoliaCleanup\].*timedOut=false"),
            "runningGuardCount": count_log("上一轮 region-safe 清理仍在运行"),
            "noRefreshTextCount": count_log("公共垃圾桶不会自动刷新"),
            "processExitCode": process.returncode,
            "error": error,
        }
        SUMMARY_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        if error:
            sys.exit(1)

if __name__ == "__main__":
    main()
