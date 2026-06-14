import json
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
CONSOLE_LOG = EVIDENCE / "server-console-dispatch-timeout.log"
COMMANDS_LOG = EVIDENCE / "commands-dispatch-timeout.log"
SUMMARY_PATH = EVIDENCE / "summary-dispatch-timeout.json"

class RconClient:
    """Minimal Minecraft RCON client for deterministic Folia cleanup testing."""
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1", 25575), timeout=10)
        self.request_id = 900
        rid, _, payload = self._request(3, "aiwtc")
        if rid == -1:
            raise RuntimeError("RCON authentication failed: " + payload)

    def close(self):
        self.sock.close()

    def command(self, command):
        return self._request(2, command)[2]

    def _request(self, request_type, payload):
        self.request_id += 1
        body = struct.pack("<ii", self.request_id, request_type) + payload.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        size = struct.unpack("<i", self._recv_exact(4))[0]
        data = self._recv_exact(size)
        rid, rtype = struct.unpack("<ii", data[:8])
        return rid, rtype, data[8:-2].decode("utf-8", "replace")

    def _recv_exact(self, size):
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise RuntimeError("RCON socket closed")
            data += chunk
        return data


def log(message):
    with COMMANDS_LOG.open("a", encoding="utf-8") as handle:
        handle.write(datetime.now().strftime("[%H:%M:%S.%f] ")[:-3] + message + "\n")


def console_text():
    return CONSOLE_LOG.read_text(encoding="utf-8", errors="replace") if CONSOLE_LOG.exists() else ""


def count(pattern):
    return len(re.findall(pattern, console_text(), re.M))


def wait_pattern(name, pattern, timeout, process):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited while waiting for " + name)
        if re.search(pattern, console_text(), re.M):
            log("PASS wait " + name)
            return True
        time.sleep(0.25)
    log("FAIL wait " + name)
    return False


def wait_count(name, pattern, baseline, increase, timeout, process):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited while waiting for " + name)
        current = count(pattern)
        if current >= baseline + increase:
            log(f"PASS wait {name} count={current} baseline={baseline} increase={increase}")
            return True
        time.sleep(0.25)
    current = count(pattern)
    log(f"FAIL wait {name} count={current} baseline={baseline} increase={increase}")
    return False


def send(client, command):
    log("> " + command)
    response = client.command(command)
    if response:
        log("< " + response.replace("\n", "\\n"))
    return response


def pump(stream):
    with CONSOLE_LOG.open("a", encoding="utf-8", errors="replace") as handle:
        for line in iter(stream.readline, ""):
            if line == "":
                break
            handle.write(line)
            handle.flush()


def wait_rcon(process):
    deadline = time.time() + 90
    last = None
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited before RCON")
        try:
            client = RconClient()
            log("PASS rcon connected")
            return client
        except Exception as exc:
            last = exc
            time.sleep(1)
    raise RuntimeError("RCON unavailable: " + repr(last))


def main():
    CONSOLE_LOG.write_text("", encoding="utf-8")
    COMMANDS_LOG.write_text("", encoding="utf-8")
    checks = {}
    error = None
    process = subprocess.Popen(
        [str(JAVA), "-Xms1024M", "-Xmx4096M", "--add-opens", "java.base/java.net=ALL-UNNAMED",
         "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseAESCTRIntrinsics", "-jar", "folia-1.21.8-6.jar", "nogui"],
        cwd=str(SERVER), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    (EVIDENCE / "server-dispatch-timeout.pid").write_text(str(process.pid), encoding="utf-8")
    threading.Thread(target=pump, args=(process.stdout,), daemon=True).start()
    client = None
    try:
        checks["ready"] = wait_pattern("server-ready", r"Done \(", 180, process)
        client = wait_rcon(process)
        send(client, "gamerule sendCommandFeedback true")
        send(client, "kill @e[tag=ai_wtc_pressure]")
        send(client, "blwtc reload")
        time.sleep(1)
        before_timeout = count(r"\[FoliaCleanup\].*timedOut=true")
        before_running = count("上一轮 region-safe 清理仍在运行")
        before_started = count("已启动 Folia region-safe 清理")
        send(client, "say AI_WTC_DISPATCH_TIMEOUT_CLEAR_START")
        send(client, "blwtc clear")
        time.sleep(0.2)
        send(client, "blwtc clear")
        checks["runningGuard"] = wait_count("running guard", "上一轮 region-safe 清理仍在运行", before_running, 1, 20, process)
        checks["firstTimeout"] = wait_count("first timedOut=true", r"\[FoliaCleanup\].*timedOut=true", before_timeout, 1, 40, process)
        before_started_retry = count("已启动 Folia region-safe 清理")
        before_timeout_retry = count(r"\[FoliaCleanup\].*timedOut=true")
        send(client, "say AI_WTC_DISPATCH_TIMEOUT_RETRY")
        send(client, "blwtc clear")
        checks["retryStarted"] = wait_count("retry clear started", "已启动 Folia region-safe 清理", before_started_retry, 1, 20, process)
        checks["retryTimeout"] = wait_count("retry timedOut=true", r"\[FoliaCleanup\].*timedOut=true", before_timeout_retry, 1, 40, process)
    except Exception as exc:
        error = repr(exc)
        log("ERROR " + error)
    finally:
        if client is not None:
            try:
                send(client, "stop")
            except Exception as exc:
                log("stop failed: " + repr(exc))
            try:
                client.close()
            except Exception:
                pass
        if process.poll() is None:
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
        latest = SERVER / "logs" / "latest.log"
        if latest.exists():
            (EVIDENCE / "latest-dispatch-timeout.log").write_text(latest.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")
        summary = {
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "checks": checks,
            "timedOutTrueCount": count(r"\[FoliaCleanup\].*timedOut=true"),
            "timedOutFalseCount": count(r"\[FoliaCleanup\].*timedOut=false"),
            "runningGuardCount": count("上一轮 region-safe 清理仍在运行"),
            "startedCount": count("已启动 Folia region-safe 清理"),
            "processExitCode": process.returncode,
            "error": error,
        }
        SUMMARY_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        if error:
            sys.exit(1)

if __name__ == "__main__":
    main()
