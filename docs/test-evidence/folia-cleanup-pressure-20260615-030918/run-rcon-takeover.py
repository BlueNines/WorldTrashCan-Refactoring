import json, re, socket, struct, time
from pathlib import Path
repo = Path(r"C:\Users\pc\Desktop\ai开发插件\待重构插件\WorldListTrashCan重构\refactor-workspace")
evidence = Path((repo / "build" / "last-folia-pressure-evidence.txt").read_text(encoding="utf-8-sig").strip().lstrip("\ufeff"))
console = evidence / "server-console-rcon.log"
commands = evidence / "commands-rcon-takeover.log"
summary = evidence / "summary-rcon-takeover.json"
def log(msg):
    with commands.open("a", encoding="utf-8") as f:
        f.write(time.strftime("[%H:%M:%S] ") + msg + "\n")
def read_console():
    return console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
def count(pattern):
    return len(re.findall(pattern, read_console(), re.M))
class Rcon:
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1",25575), timeout=10)
        self.i = 200
        rid, typ, payload = self.req(3, "aiwtc")
        if rid == -1:
            raise RuntimeError("auth failed")
    def req(self, typ, payload):
        self.i += 1
        body = struct.pack("<ii", self.i, typ) + payload.encode("utf-8") + b"\0\0"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        size = struct.unpack("<i", self.recv(4))[0]
        data = self.recv(size)
        rid, rtyp = struct.unpack("<ii", data[:8])
        return rid, rtyp, data[8:-2].decode("utf-8", "replace")
    def recv(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n-len(out))
            if not chunk: raise RuntimeError("socket closed")
            out += chunk
        return out
    def cmd(self, command):
        log("> " + command)
        payload = self.req(2, command)[2]
        if payload:
            log("< " + payload.replace("\n", "\\n"))
        return payload
r = Rcon()
checks = {}
try:
    r.cmd("gamerule sendCommandFeedback true")
    r.cmd("say AI_WTC_TAKEOVER_PRESSURE_CLEAR_START")
    before_timeout = count(r"\[FoliaCleanup\].*timedOut=true")
    before_running = count("上一轮 region-safe 清理仍在运行")
    before_started = count("已启动 Folia region-safe 清理")
    r.cmd("blwtc clear")
    time.sleep(0.2)
    r.cmd("blwtc clear")
    deadline = time.time() + 45
    while time.time() < deadline and count("上一轮 region-safe 清理仍在运行") < before_running + 1:
        time.sleep(0.5)
    checks["runningGuard"] = count("上一轮 region-safe 清理仍在运行") >= before_running + 1
    deadline = time.time() + 90
    while time.time() < deadline and count(r"\[FoliaCleanup\].*timedOut=true") < before_timeout + 1:
        time.sleep(0.5)
    checks["timedOut"] = count(r"\[FoliaCleanup\].*timedOut=true") >= before_timeout + 1
    before_started_retry = count("已启动 Folia region-safe 清理")
    r.cmd("say AI_WTC_TAKEOVER_AFTER_TIMEOUT_RETRY")
    r.cmd("blwtc clear")
    deadline = time.time() + 30
    while time.time() < deadline and count("已启动 Folia region-safe 清理") < before_started_retry + 1:
        time.sleep(0.5)
    checks["retryStarted"] = count("已启动 Folia region-safe 清理") >= before_started_retry + 1
    time.sleep(5)
    kill_response = r.cmd("kill @e[tag=ai_wtc_pressure]")
    checks["killResponse"] = kill_response
    r.cmd("stop")
finally:
    r.sock.close()
summary.write_text(json.dumps({"checks":checks,"timedOutTrueCount":count(r"\[FoliaCleanup\].*timedOut=true"),"timedOutFalseCount":count(r"\[FoliaCleanup\].*timedOut=false"),"runningGuardCount":count("上一轮 region-safe 清理仍在运行")}, ensure_ascii=False, indent=2), encoding="utf-8")
