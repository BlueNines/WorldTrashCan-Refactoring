import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

import run_rgb_visual_matrix as base


SERVER_WORK = Path(r"E:\server_work")
BUILD_ROOT = base.REPO / "build" / "rgb-external-server-matrix"
JAVA8_CAT = Path(r"C:\Program Files\Java\jdk-1.8\bin\java.exe")
JAVA21 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
JAVA17 = base.JAVA17
JAVA25 = base.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
UNIVERSAL_PLUGIN = "BLWorldTrashCan-universal.jar"
PAPI_1122 = base.WORKSPACE / "paper-1.12.2-test-server" / "plugins" / "placeholderapi-2.11.6.jar"
PAPI_MODERN = SERVER_WORK / "1.21.11spigot" / "plugins" / "PlaceholderAPI-2.12.2.jar"


EXTERNAL_MATRIX = [
    {
        "id": "managed_paper1122",
        "label": "paper-1.12.2-managed-universal",
        "version": "1.12.2",
        "serverDir": SERVER_WORK / "paper-1.12.2-universal-test-server",
        "serverJar": "paper-1.12.2-1620.jar",
        "serverSourceJar": base.WORKSPACE / "paper-1.12.2-test-server" / "paper-1.12.2-1620.jar",
        "port": 30012,
        "java": base.JAVA8,
        "plugin": UNIVERSAL_PLUGIN,
        "expect": "downgrade",
        "managedConfig": True,
        "modernJvmArgs": False,
        "levelType": "FLAT",
        "readyTimeout": 360,
        "joinTimeout": 120,
        "extraPlugins": [PAPI_1122],
        "expectPapi": True,
        "expectedPlatform": "legacy-1.12",
    },
    {
        "id": "external_paper1218",
        "label": "server_1.21.8_0",
        "version": "1.21.8",
        "serverDir": SERVER_WORK / "server_1.21.8_0",
        "serverJar": "paper-1.21.8-60.jar",
        "port": 30001,
        "java": JAVA21,
        "plugin": "BLWorldTrashCan-paper-1.16-1.20.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
    },
    {
        "id": "external_cat1122",
        "label": "server_cat_1.12.2",
        "version": "1.12.2",
        "serverDir": SERVER_WORK / "server_cat_1.12.2",
        "serverJar": "CatServer-4168d848-universal.jar",
        "port": 25565,
        "java": JAVA8_CAT,
        "plugin": "BLWorldTrashCan-legacy-1.12.jar",
        "expect": "downgrade",
        "modernJvmArgs": False,
    },
    {
        "id": "external_folia1218",
        "label": "folia1.21.8",
        "version": "1.21.8",
        "serverDir": SERVER_WORK / "folia1.21.8",
        "serverJar": "folia-1.21.8-6.jar",
        "port": 30004,
        "java": JAVA21,
        "plugin": "BLWorldTrashCan-folia-1.20.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
    },
    {
        "id": "external_paper12111",
        "label": "1.21.11spigot",
        "version": "1.21.11",
        "serverDir": SERVER_WORK / "1.21.11spigot",
        "serverJar": "paper-1.21.11-127.jar",
        "port": 30001,
        "java": JAVA21,
        "plugin": "BLWorldTrashCan-paper-1.16-1.20.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
    },
    {
        "id": "external_arclight1211",
        "label": "1.21.11arclight-neoforge",
        "version": "1.21.1",
        "displayVersion": "1.21.1-arclight-neoforge",
        "serverDir": SERVER_WORK / "1.21.11arclight-neoforge",
        "serverJar": "arclight-neoforge-1.21.1-1.0.2-SNAPSHOT-668f9f3.jar",
        "port": 30001,
        "java": JAVA21,
        "plugin": "BLWorldTrashCan-universal.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
        "readyTimeout": 180,
        "joinTimeout": 120,
    },
    {
        "id": "external_banner1201",
        "label": "1.20.1fabric.banner",
        "version": "1.20.1",
        "serverDir": SERVER_WORK / "1.20.1fabric.banner",
        "serverJar": "taiyitist-server-1.20.1-84706762.jar",
        "port": 25565,
        "java": JAVA21,
        "plugin": "BLWorldTrashCan-universal.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
        "fileEncoding": "GBK",
        "readyTimeout": 180,
        "joinTimeout": 120,
    },
    {
        "id": "external_paper2612",
        "label": "paper-26.1.2-managed",
        "version": "26.1.2",
        "serverDir": SERVER_WORK / "paper-26.1.2-test-server",
        "serverJar": "paper-26.1.2-69.jar",
        "serverDownloadUrl": "https://fill-data.papermc.io/v1/objects/d30fae0c74092b10855f0412ca6b265c60301a013d34bc28a2a41bf5682dd80b/paper-26.1.2-69.jar",
        "serverSha256": "d30fae0c74092b10855f0412ca6b265c60301a013d34bc28a2a41bf5682dd80b",
        "port": 30026,
        "java": JAVA25,
        "plugin": UNIVERSAL_PLUGIN,
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
        "managedConfig": True,
        "readyTimeout": 240,
        "joinTimeout": 150,
        "extraPlugins": [PAPI_MODERN],
        "expectPapi": True,
        "expectedPlatform": "paper-1.16-1.20",
    },
    {
        "id": "external_spigot2612",
        "label": "spigot-26.1.2-managed",
        "version": "26.1.2",
        "serverDir": SERVER_WORK / "spigot-26.1.2-test-server",
        "serverJar": "spigot-26.1.2.jar",
        "port": 30027,
        "java": JAVA25,
        "plugin": UNIVERSAL_PLUGIN,
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
        "managedConfig": True,
        "readyTimeout": 240,
        "joinTimeout": 150,
        "extraPlugins": [PAPI_MODERN],
        "expectPapi": True,
        "expectedPlatform": "paper-1.16-1.20",
    },
]


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


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


def selected_cases(case_id: str | None) -> list[dict]:
    """按命令行参数返回要执行的外部服务端用例。"""
    if not case_id:
        return EXTERNAL_MATRIX
    for case in EXTERNAL_MATRIX:
        if case_id in (case["id"], case["label"], case["version"]):
            return [case]
    raise RuntimeError("未知外部服务端用例: " + case_id)


def universal_case(case: dict) -> dict:
    """把外部服务端用例转换成 universal 整包测试用例。"""
    copied = dict(case)
    source_id = str(copied["id"])
    if source_id.startswith("external_"):
        copied["id"] = "universal_" + source_id[len("external_"):]
    else:
        copied["id"] = "universal_" + source_id
    copied["sourceId"] = source_id
    copied["plugin"] = UNIVERSAL_PLUGIN
    return copied


def port_open(port: int) -> bool:
    """判断本机端口是否已经可以连接。"""
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=1):
            return True
    except OSError:
        return False


def read_text(path: Path) -> str:
    """按 UTF-8 容错读取日志文件。"""
    if not path.is_file():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def log_text_offset(path: Path) -> int:
    """返回当前日志文本长度，用于只检查新追加的命令输出。"""
    return len(read_text(path))


def read_text_since(path: Path, offset: int) -> str:
    """读取指定日志偏移后的新增文本。"""
    text = read_text(path)
    if offset >= len(text):
        return ""
    return text[offset:]


def update_yaml_scalars(text: str, replacements: dict[str, str]) -> str:
    """按简单 YAML 路径替换标量值，保留注释和其它配置。"""
    lines = text.splitlines(True)
    stack = []
    result = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in line:
            result.append(line)
            continue
        indent = len(line) - len(line.lstrip(" "))
        key = line.lstrip(" ").split(":", 1)[0].strip()
        if not key or key.startswith("-"):
            result.append(line)
            continue
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        path = ".".join(item[1] for item in stack)
        if path in replacements:
            newline = "\r\n" if line.endswith("\r\n") else "\n" if line.endswith("\n") else ""
            result.append(" " * indent + key + ": " + replacements[path] + newline)
        else:
            result.append(line)
    return "".join(result)


def prepare_test_config(case: dict, run_dir: Path) -> list[tuple[Path, Path]]:
    """临时写入稳定测试配置并把原文件备份到证据目录。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan"
    backups = []
    config_plan = {
        "trash.yml": {
            "world-trash.default-max-count": "3",
            "personal-trash.enabled": "true",
            "personal-trash.track-player-dropped-items": "true",
            "personal-trash.damage-recovery.mode": "personal-trash",
        },
        "cleanup.yml": {
            "interval-seconds": "0",
        },
    }
    for file_name, replacements in config_plan.items():
        target = data_dir / file_name
        if not target.is_file():
            continue
        backup = run_dir / "logs" / "config-backup" / file_name
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, backup)
        original = target.read_text(encoding="utf-8", errors="replace")
        updated = update_yaml_scalars(original, replacements)
        if updated != original:
            target.write_text(updated, encoding="utf-8")
        backups.append((target, backup))
    return backups


def restore_test_config(backups: list[tuple[Path, Path]]) -> None:
    """停服后恢复被测试配置临时覆盖的文件。"""
    for target, backup in backups:
        if backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)


def deploy_plugin(case: dict) -> Path:
    """把本轮测试用 BLWorldTrashCan jar 部署到目标服务端 plugins 目录。"""
    server_dir = Path(case["serverDir"])
    plugins_dir = server_dir / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    source = base.REPO / "dist" / case["plugin"]
    if not source.is_file():
        raise RuntimeError("缺少待部署插件 jar: " + str(source))
    for old in plugins_dir.glob("BLWorldTrashCan*.jar"):
        old.unlink()
    target = plugins_dir / case["plugin"]
    shutil.copy2(source, target)
    for extra in case.get("extraPlugins", []):
        extra_path = Path(extra)
        if extra_path.is_file():
            shutil.copy2(extra_path, plugins_dir / extra_path.name)
    return target


def ensure_managed_server_jar(case: dict) -> None:
    """按用例配置下载 managed 服务端 jar，并校验 SHA-256。"""
    server_dir = Path(case["serverDir"])
    server_dir.mkdir(parents=True, exist_ok=True)
    target = server_dir / str(case["serverJar"])
    source = case.get("serverSourceJar")
    if not target.is_file() and source:
        source_path = Path(source)
        if not source_path.is_file():
            raise RuntimeError("managed 服务端源 jar 不存在: " + str(source_path))
        shutil.copy2(source_path, target)
    if not target.is_file() and case.get("serverDownloadUrl"):
        base.download(str(case["serverDownloadUrl"]), target)
    if not target.is_file():
        raise RuntimeError("managed 服务端 jar 不存在: " + str(target))
    expected_sha256 = str(case.get("serverSha256", "")).lower()
    if expected_sha256:
        import hashlib
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest.lower() != expected_sha256:
            raise RuntimeError("managed 服务端 jar SHA-256 不匹配: " + str(target) + " actual=" + digest)


def write_managed_server_properties(case: dict) -> None:
    """写入 managed 26.1 测试服启动所需的最小配置。"""
    server_dir = Path(case["serverDir"])
    path = server_dir / "server.properties"
    level_name = current_world_name(case)
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value
    values.update({
        "server-port": str(case["port"]),
        "online-mode": "false",
        "level-name": level_name,
        "level-type": str(case.get("levelType", "minecraft:flat")),
        "generate-structures": "false",
        "view-distance": "2",
        "simulation-distance": "2",
        "spawn-protection": "0",
        "white-list": "false",
        "enforce-secure-profile": "false",
        "motd": "BLWTC RGB 26.1 " + str(case.get("label", case["id"])),
    })
    text = "\n".join(key + "=" + value for key, value in values.items()) + "\n"
    path.write_text(text, encoding="utf-8")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def current_world_name(case: dict) -> str:
    """返回当前 run 应该使用和验证的测试世界名。"""
    run_id = str(case.get("runId", "static")).replace("-", "_")
    if case.get("managedConfig", False):
        return "rgb_visual_" + str(case["id"]) + "_" + run_id
    return "rgb_visual_" + str(case["id"])


def prepare_managed_server(case: dict) -> None:
    """准备由本脚本托管的 26.1 测试服务端。"""
    if not case.get("managedConfig", False):
        return
    ensure_managed_server_jar(case)
    write_managed_server_properties(case)


def server_command(case: dict) -> list[str]:
    """生成目标服务端的 Java 启动命令。"""
    java = str(Path(case["java"]))
    if "serverArgs" in case:
        return [java] + list(case["serverArgs"])
    command = [
        java,
        "-Xms1024M",
        "-Xmx4096M",
    ]
    if "fileEncoding" in case:
        command.append("-Dfile.encoding=" + str(case["fileEncoding"]))
    if case.get("modernJvmArgs", True):
        command.extend([
            "--add-opens",
            "java.base/java.net=ALL-UNNAMED",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-UseAESCTRIntrinsics",
        ])
    command.extend(["-jar", str(case["serverJar"]), "nogui"])
    return command


def launch_server(case: dict, run_dir: Path) -> subprocess.Popen:
    """启动外部服务端并等待 ready。"""
    prepare_managed_server(case)
    deploy_plugin(case)
    server_dir = Path(case["serverDir"])
    log_path = run_dir / "logs" / (case["id"] + "-server-console.log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8", errors="replace")
    command = server_command(case)
    write_json(run_dir / "logs" / (case["id"] + "-server-launch.json"), {
        "cwd": server_dir,
        "command": command,
        "plugin": case["plugin"],
        "port": case["port"],
    })
    process = subprocess.Popen(
        command,
        cwd=str(server_dir),
        stdin=subprocess.PIPE,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    process._blwtc_log_file = log_file
    try:
        wait_server_ready(process, log_path, int(case["port"]), int(case.get("readyTimeout", 150)))
    except Exception:
        stop_process(process, "stop")
        raise
    return process


def wait_server_ready(process: subprocess.Popen, log_path: Path, port: int, timeout: int) -> None:
    """等待服务端日志出现 ready 标记且端口可连接。"""
    deadline = time.time() + timeout
    ready_markers = ("Done (", "For help, type", "Timings Reset")
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("服务端提前退出: " + str(log_path))
        text = read_text(log_path)
        if any(marker in text for marker in ready_markers) and port_open(port):
            return
        time.sleep(1)
    raise TimeoutError("等待服务端 ready 超时: " + str(log_path))


def send_console_command(process: subprocess.Popen, command: str, command_log: Path) -> None:
    """向服务端 stdin 发送一条控制台命令并记录。"""
    command_log.parent.mkdir(parents=True, exist_ok=True)
    with command_log.open("a", encoding="utf-8") as handle:
        handle.write(time.strftime("[%H:%M:%S] ") + command + "\n")
    if process.stdin is None:
        raise RuntimeError("服务端 stdin 不可用")
    process.stdin.write(command + "\n")
    process.stdin.flush()


def wait_platform_command_accepted(log_path: Path, offset: int) -> None:
    """等待 platform 指令被插件接收，避免把未加载插件的服务端误判为通过。"""
    deadline = time.time() + 12
    while time.time() < deadline:
        text = read_text_since(log_path, offset)
        if "Unknown or incomplete command" in text or "Unknown command" in text:
            raise RuntimeError("blwtc platform 未被服务端识别: " + str(log_path))
        if "[BLWorldTrashCan] 当前平台" in text or "- rgb-message:" in text:
            return
        time.sleep(0.5)
    raise TimeoutError("未看到 blwtc platform 的插件输出: " + str(log_path))


def wait_debug_command_not_rejected(log_path: Path, offset: int) -> None:
    """确认 debugrgb 指令没有被服务端作为未知命令拒绝。"""
    deadline = time.time() + 4
    while time.time() < deadline:
        text = read_text_since(log_path, offset)
        if "Unknown or incomplete command" in text or "Unknown command" in text:
            raise RuntimeError("blwtc debugrgb 未被服务端识别: " + str(log_path))
        time.sleep(0.5)


def wait_command_markers(log_path: Path, offset: int, markers: list[str], timeout: float, command: str) -> str:
    """等待命令输出指定标记并返回新增日志片段。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = read_text_since(log_path, offset)
        if "Unknown or incomplete command" in text or "Unknown command" in text:
            raise RuntimeError(command + " 未被服务端识别: " + str(log_path))
        if all(marker in text for marker in markers):
            return text
        time.sleep(0.5)
    raise TimeoutError("等待命令输出超时: " + command + " markers=" + ",".join(markers))


def wait_command_not_rejected(log_path: Path, offset: int, timeout: float, command: str) -> str:
    """确认命令没有被服务端作为未知命令拒绝并返回新增日志。"""
    deadline = time.time() + timeout
    latest = ""
    while time.time() < deadline:
        latest = read_text_since(log_path, offset)
        if "Unknown or incomplete command" in latest or "Unknown command" in latest:
            raise RuntimeError(command + " 未被服务端识别: " + str(log_path))
        time.sleep(0.5)
    return latest


def wait_player_online(case: dict, username: str, log_path: Path) -> None:
    """从服务端日志确认真实客户端玩家已进入服务端。"""
    deadline = time.time() + int(case.get("joinTimeout", 100))
    while time.time() < deadline:
        lines = read_text(log_path).splitlines()
        if any(username in line and ("joined the game" in line or "logged in with entity id" in line) for line in lines):
            time.sleep(1.0)
            return
        time.sleep(1)
    raise TimeoutError("等待玩家进服超时: " + username + " log=" + str(log_path))


def capture_debug_screenshot(case: dict, game_dir: Path, run_dir: Path) -> Path:
    """悬停 debugrgb 物品并使用 Minecraft F2 截图。"""
    time.sleep(float(case.get("debugWait", 3.5)))
    base.hover_debug_item(case)
    return base.capture_internal_screenshot(case, game_dir, run_dir)


def capture_channel_screenshot(case: dict, game_dir: Path, run_dir: Path) -> Path:
    """等待聊天、ActionBar 和 Title 可见后使用 Minecraft F2 截图。"""
    time.sleep(float(case.get("channelWait", 1.0)))
    return base.capture_internal_screenshot(case, game_dir, run_dir)


def copy_screenshot(source: Path, run_dir: Path, case: dict, suffix: str) -> Path:
    """把最近一次 F2 截图复制成稳定命名，避免后续截图覆盖证据。"""
    target = run_dir / "screenshots" / (case["id"] + "-" + suffix + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, suffix: str) -> Path:
    """截取当前客户端画面并归档为指定后缀。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    return copy_screenshot(source, run_dir, case, suffix)


def send_client_command(case: dict, game_dir: Path, run_dir: Path, command: str, suffix: str,
                        wait_seconds: float = 1.0) -> dict:
    """让真实客户端输入一条玩家命令并截图留证。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.focus_window(hwnd)
    base.pyautogui.press("esc")
    time.sleep(0.25)
    base.pyautogui.press("t")
    time.sleep(0.2)
    base.pyautogui.write(command, interval=0.01)
    base.pyautogui.press("enter")
    time.sleep(wait_seconds)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "name": suffix,
        "command": command,
        "status": "PASS",
        "screenshot": str(screenshot),
        "brightness": base.image_brightness(Image.open(screenshot)),
    }


def stop_process(process: subprocess.Popen, command: str | None = None) -> None:
    """优雅停止进程，必要时强制结束并关闭日志句柄。"""
    try:
        base.stop_process(process, command)
    finally:
        log_file = getattr(process, "_blwtc_log_file", None)
        if log_file is not None:
            log_file.close()


def run_basic_function_checks(case: dict, username: str, server_process: subprocess.Popen,
                              server_log: Path, command_log: Path, run_dir: Path,
                              game_dir: Path | None = None) -> list[dict]:
    """执行每个外部服务端都应覆盖的基础功能检查。"""
    checks = [
        {
            "name": "reload",
            "command": "blwtc reload",
            "markers": ["[Message]"],
            "timeout": 8,
        },
        {
            "name": "world-trash-create",
            "command": "blwtc debugworldtrash {player}",
            "markers": ["[Debug] debugWorldTrash", "saved=true"],
            "timeout": 12,
        },
        {
            "name": "global-route",
            "command": "blwtc debugroute {player} global COBBLESTONE 5",
            "markers": ["[Debug] debugRoute", "route=GLOBAL_TRASH", "routed=true"],
            "timeout": 12,
        },
        {
            "name": "personal-route",
            "command": "blwtc debugroute {player} personal STONE 6",
            "markers": ["[Debug] debugRoute", "route=PERSONAL_TRASH", "routed=true"],
            "timeout": 12,
        },
        {
            "name": "world-route",
            "command": "blwtc debugroute {player} world SAND 4",
            "markers": ["[Debug] debugRoute", "route=WORLD_TRASH", "routed=true"],
            "timeout": 12,
        },
        {
            "name": "damage-recovery",
            "command": "blwtc debugdamage {player} SAND 3",
            "markers": ["[Debug] debugDamageRecovery"],
            "timeout": 12,
        },
        {
            "name": "owner-drop",
            "command": "blwtc debugdrop {player} GRAVEL 2 owner",
            "markers": ["[Debug] debugDrop", "markOwner=true"],
            "timeout": 12,
        },
        {
            "name": "manual-clear",
            "command": "blwtc clear",
            "markers": [case.get("clearMarker", "[Cleanup]")],
            "timeout": float(case.get("clearTimeout", 18)),
        },
        {
            "name": "summary",
            "command": "blwtc debugsummary {player}",
            "markers": ["BLWorldTrashCan debug summary"],
            "timeout": 8,
        },
        {
            "name": "global-gui-open",
            "command": "blwtc debugopen {player} global",
            "markers": [],
            "timeout": 4,
        },
        {
            "name": "personal-gui-open",
            "command": "blwtc debugopen {player} personal",
            "markers": [],
            "timeout": 4,
        },
    ]
    if "folia" in str(case.get("label", "")).lower() or case.get("displayVersion", "").lower().find("folia") >= 0:
        for check in checks:
            if check["name"] == "manual-clear":
                check["markers"] = ["[FoliaCleanup]"]
                check["timeout"] = 30
    results = []
    for check in checks:
        command = str(check["command"]).replace("{player}", username)
        offset = log_text_offset(server_log)
        send_console_command(server_process, command, command_log)
        try:
            if check["markers"]:
                text = wait_command_markers(server_log, offset, list(check["markers"]), float(check["timeout"]), command)
            else:
                text = wait_command_not_rejected(server_log, offset, float(check["timeout"]), command)
            status = "PASS"
            error = ""
        except Exception as exception:
            text = read_text_since(server_log, offset)
            status = "FAIL"
            error = repr(exception)
        item = {
            "name": check["name"],
            "command": command,
            "markers": list(check["markers"]),
            "status": status,
            "error": error,
            "logExcerpt": text[-2000:],
        }
        if status == "PASS" and game_dir is not None and check["name"] in ("global-gui-open", "personal-gui-open"):
            time.sleep(0.6)
            item["screenshot"] = str(capture_named_screenshot(case, game_dir, run_dir, check["name"] + "-f2"))
        results.append(item)
        if status != "PASS":
            write_json(run_dir / "logs" / (case["id"] + "-basic-checks.json"), results)
            raise RuntimeError("基础功能检查失败: " + check["name"] + " " + error)
    write_json(run_dir / "logs" / (case["id"] + "-basic-checks.json"), results)
    return results


def artifact_summary_for_plugin(case: dict) -> dict:
    """读取本轮部署的 universal jar 元信息。"""
    source = base.REPO / "dist" / case["plugin"]
    data = source.read_bytes()
    result = {
        "path": str(source),
        "name": source.name,
        "size": source.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "pluginYmlVersion": "",
        "main": "",
    }
    with zipfile.ZipFile(source) as jar:
        plugin_yml = jar.read("plugin.yml").decode("utf-8", errors="replace")
    for line in plugin_yml.splitlines():
        if line.startswith("version:"):
            result["pluginYmlVersion"] = line.split(":", 1)[1].strip()
        if line.startswith("main:"):
            result["main"] = line.split(":", 1)[1].strip()
    return result


def run_command_check(case: dict, server_process: subprocess.Popen, server_log: Path,
                      command_log: Path, command: str, name: str, markers: list[str],
                      timeout: float = 10.0) -> dict:
    """执行一条后台命令并按日志标记断言结果。"""
    offset = log_text_offset(server_log)
    send_console_command(server_process, command, command_log)
    try:
        text = wait_command_markers(server_log, offset, markers, timeout, command) if markers else wait_command_not_rejected(server_log, offset, timeout, command)
        return {
            "name": name,
            "command": command,
            "markers": markers,
            "status": "PASS",
            "error": "",
            "logExcerpt": text[-2000:],
        }
    except Exception as exception:
        return {
            "name": name,
            "command": command,
            "markers": markers,
            "status": "FAIL",
            "error": repr(exception),
            "logExcerpt": read_text_since(server_log, offset)[-2000:],
        }


def verify_reload_self_heal(case: dict, server_process: subprocess.Popen, server_log: Path,
                            command_log: Path, run_dir: Path) -> dict:
    """删除一个默认语言文件后执行 reload，验证资源会自动补回。"""
    target = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "messages" / "message_es.yml"
    backup = run_dir / "logs" / "self-heal-backup" / "message_es.yml"
    backup.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file():
        shutil.copy2(target, backup)
        target.unlink()
    command_result = run_command_check(case, server_process, server_log, command_log, "blwtc reload", "reload-self-heal-command", ["[Message]"], 10)
    exists = target.is_file() and target.stat().st_size > 0
    return {
        "name": "reload-self-heal",
        "command": "delete messages/message_es.yml + blwtc reload",
        "status": "PASS" if command_result["status"] == "PASS" and exists else "FAIL",
        "fileRestored": exists,
        "deletedFile": str(target),
        "backup": str(backup) if backup.is_file() else "",
        "commandResult": command_result,
    }


def verify_data_files(case: dict) -> dict:
    """检查世界垃圾桶数据和 bStats 全局配置是否已落盘。"""
    data_file = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "data" / "worlds.yml"
    bstats_file = Path(case["serverDir"]) / "plugins" / "bStats" / "config.yml"
    data_text = read_text(data_file)
    bstats_text = read_text(bstats_file)
    data_ok = data_file.is_file() and "locations" in data_text and "max-count" in data_text
    bstats_ok = bstats_file.is_file() and re.search(r"(?m)^enabled:\s*true\s*$", bstats_text) is not None
    return {
        "name": "data-and-bstats-files",
        "status": "PASS" if data_ok and bstats_ok else "FAIL",
        "worldsFile": str(data_file),
        "worldsFileHasLocations": "locations" in data_text,
        "worldsFileHasMaxCount": "max-count" in data_text,
        "bStatsConfig": str(bstats_file),
        "bStatsEnabledTrue": bstats_ok,
    }


def read_world_max_count(case: dict, world_name: str) -> int | None:
    """从 worlds.yml 读取指定世界的 max-count。"""
    data_file = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "data" / "worlds.yml"
    if not data_file.is_file():
        return None
    in_target_world = False
    for line in read_text(data_file).splitlines():
        if line.startswith("  ") and not line.startswith("    "):
            in_target_world = line.strip() == world_name + ":"
            continue
        if in_target_world and line.startswith("    max-count:"):
            raw = line.split(":", 1)[1].strip()
            try:
                return int(raw)
            except ValueError:
                return None
    return None


def run_add_world_limit_check(case: dict, server_process: subprocess.Popen, server_log: Path,
                              command_log: Path, world_name: str, delta: int = 2) -> dict:
    """验证 add 命令确实写入当前测试世界的 max-count。"""
    before = read_world_max_count(case, world_name)
    command = "blwtc add " + world_name + " " + str(delta)
    offset = log_text_offset(server_log)
    send_console_command(server_process, command, command_log)
    try:
        text = wait_command_not_rejected(server_log, offset, 10, command)
        after = read_world_max_count(case, world_name)
        ok = before is not None and after == before + delta
        return {
            "name": "add-world-limit",
            "command": command,
            "status": "PASS" if ok else "FAIL",
            "world": world_name,
            "beforeMaxCount": before,
            "afterMaxCount": after,
            "expectedAfterMaxCount": None if before is None else before + delta,
            "logExcerpt": text[-2000:],
        }
    except Exception as exception:
        return {
            "name": "add-world-limit",
            "command": command,
            "status": "FAIL",
            "world": world_name,
            "beforeMaxCount": before,
            "afterMaxCount": read_world_max_count(case, world_name),
            "error": repr(exception),
            "logExcerpt": read_text_since(server_log, offset)[-2000:],
        }


def run_papi_check(case: dict, username: str, server_process: subprocess.Popen,
                   server_log: Path, command_log: Path) -> dict:
    """在安装 PlaceholderAPI 的测试端验证 %Wtc_ClearTime%。"""
    if not case.get("expectPapi", False):
        return {"name": "papi-Wtc_ClearTime", "status": "SKIP", "reason": "case does not install PlaceholderAPI"}
    command = "papi parse " + username + " %Wtc_ClearTime%"
    offset = log_text_offset(server_log)
    send_console_command(server_process, command, command_log)
    try:
        text = wait_command_not_rejected(server_log, offset, 8, command)
        has_number = (re.search(r"(?m)^\s*[0-9]+\s*$", text) is not None
                      or re.search(r"(?m)\]:\s*[0-9]+\s*$", text) is not None
                      or re.search(r"Wtc_ClearTime.*[0-9]+", text) is not None)
        disabled = "PlaceholderAPI" in text and ("not enabled" in text or "Unknown" in text)
        return {
            "name": "papi-Wtc_ClearTime",
            "command": command,
            "status": "PASS" if has_number and not disabled else "FAIL",
            "hasNumericResult": has_number,
            "logExcerpt": text[-2000:],
        }
    except Exception as exception:
        return {
            "name": "papi-Wtc_ClearTime",
            "command": command,
            "status": "FAIL",
            "error": repr(exception),
            "logExcerpt": read_text_since(server_log, offset)[-2000:],
        }


def run_full_function_checks(case: dict, username: str, server_process: subprocess.Popen,
                             server_log: Path, command_log: Path, run_dir: Path,
                             game_dir: Path) -> list[dict]:
    """执行覆盖正式入口、旧入口、PAPI、reload 自愈和落盘状态的全功能检查。"""
    world_name = current_world_name(case)
    platform_marker = str(case.get("expectedPlatform", "")) or "(universal)"
    results = []
    console_checks = [
        ("help", "blwtc help", ["/blwtc platform"]),
        ("alias-wtc-platform", "wtc platform", [platform_marker, "(universal)"]),
        ("alias-worldlist-platform", "WorldListTrashCan platform", [platform_marker, "(universal)"]),
        ("stats", "blwtc stats", ["[BLWorldTrashCan]"]),
        ("debugstock", "blwtc debugstock", []),
        ("debugplayer-dropmode", "blwtc debugplayer " + username + " dropmode", []),
        ("debugplayer-look", "blwtc debugplayer " + username + " look", []),
        ("debugplayer-ban", "blwtc debugplayer " + username + " ban", []),
        ("debugplayer-globalban", "blwtc debugplayer " + username + " globalban", []),
    ]
    for name, command, markers in console_checks:
        item = run_command_check(case, server_process, server_log, command_log, command, name, markers, 12)
        if item["status"] == "PASS" and name in ("debugplayer-ban", "debugplayer-globalban"):
            time.sleep(0.8)
            item["screenshot"] = str(capture_named_screenshot(case, game_dir, run_dir, name + "-f2"))
        results.append(item)
        if item["status"] != "PASS":
            write_json(run_dir / "logs" / (case["id"] + "-full-checks.json"), results)
            raise RuntimeError("全功能检查失败: " + name + " " + item.get("error", ""))
    add_result = run_add_world_limit_check(case, server_process, server_log, command_log, world_name)
    results.append(add_result)
    if add_result["status"] != "PASS":
        write_json(run_dir / "logs" / (case["id"] + "-full-checks.json"), results)
        raise RuntimeError("全功能检查失败: add-world-limit " + add_result.get("error", ""))
    send_console_command(server_process, "op " + username, command_log)
    time.sleep(0.8)
    client_commands = [
        ("/blwtc global", "client-command-global", 1.0),
        ("/blwtc personal", "client-command-personal", 1.0),
        ("/blwtc dropmode", "client-command-dropmode", 0.8),
        ("/blwtc look", "client-command-look", 0.8),
        ("/blwtc ban", "client-command-ban", 1.0),
        ("/blwtc globalban", "client-command-globalban", 1.0),
        ("/wtc stats", "client-command-legacy-stats", 0.8),
    ]
    for command, suffix, wait_seconds in client_commands:
        results.append(send_client_command(case, game_dir, run_dir, command, suffix + "-f2", wait_seconds))
    results.append(run_papi_check(case, username, server_process, server_log, command_log))
    results.append(verify_reload_self_heal(case, server_process, server_log, command_log, run_dir))
    results.append(verify_data_files(case))
    failed = [item for item in results if item.get("status") == "FAIL"]
    write_json(run_dir / "logs" / (case["id"] + "-full-checks.json"), results)
    if failed:
        raise RuntimeError("全功能检查失败: " + ",".join(item["name"] for item in failed))
    return results


def run_case(case: dict, prepared_clients: dict, run_root: Path, channels_only: bool,
             basic_checks: bool, full_checks: bool) -> dict:
    """执行单个外部服务端 RGB 截图用例。"""
    case = dict(case)
    case["runId"] = run_root.name
    log("开始外部服务端用例 " + case["id"] + " / " + case["label"])
    run_dir = run_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_process = None
    client_process = None
    game_dir = None
    config_backups = []
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "expect": case["expect"],
        "rgbEvidence": "chat-actionbar-title" if channels_only else "all-visible-channels",
        "basicChecksRequested": basic_checks,
        "fullChecksRequested": full_checks,
        "testConfigPatched": False,
        "artifact": artifact_summary_for_plugin(case),
        "status": "FAIL",
    }
    try:
        if basic_checks or full_checks:
            config_backups = prepare_test_config(case, run_dir)
            result["testConfigPatched"] = bool(config_backups)
        server_process = launch_server(case, run_dir)
        if (basic_checks or full_checks) and not config_backups:
            config_backups = prepare_test_config(case, run_dir)
            result["testConfigPatched"] = bool(config_backups)
        prepared = prepared_clients[case["version"]]
        client_process, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client_process.pid
        result["username"] = username
        result["clientPid"] = client_process.pid
        wait_player_online(case, username, server_log)
        command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
        platform_offset = log_text_offset(server_log)
        send_console_command(server_process, "blwtc platform", command_log)
        wait_platform_command_accepted(server_log, platform_offset)
        debug_offset = log_text_offset(server_log)
        debug_command = "blwtc debugrgbchannels " + username if channels_only else "blwtc debugrgb " + username
        send_console_command(server_process, debug_command, command_log)
        if channels_only:
            wait_command_markers(server_log, debug_offset, ["[DebugRGB] channels", username], 8, debug_command)
        else:
            wait_debug_command_not_rejected(server_log, debug_offset)
        if channels_only:
            screenshot = capture_channel_screenshot(case, game_dir, run_dir)
            screenshot = copy_screenshot(screenshot, run_dir, case, "rgb-channels-f2")
        else:
            screenshot = capture_debug_screenshot(case, game_dir, run_dir)
            screenshot = copy_screenshot(screenshot, run_dir, case, "rgb-all-channels-f2")
        result["screenshot"] = str(screenshot)
        result["brightness"] = base.image_brightness(Image.open(screenshot))
        if basic_checks:
            result["basicChecks"] = run_basic_function_checks(case, username, server_process, server_log, command_log, run_dir, game_dir)
        if full_checks:
            result["fullChecks"] = run_full_function_checks(case, username, server_process, server_log, command_log, run_dir, game_dir)
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("外部服务端用例失败 " + case["id"] + ": " + repr(error))
        if client_process is not None and game_dir is not None:
            try:
                result["failureScreenshot"] = str(base.capture_internal_screenshot(case, game_dir, run_dir))
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        if client_process is not None:
            stop_process(client_process)
        base.ACTIVE_CLIENT_PID = None
        if server_process is not None:
            stop_process(server_process, "stop")
        if config_backups:
            restore_test_config(config_backups)
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], run_root: Path) -> Path | None:
    """生成外部服务端截图总览图。"""
    passed = [item for item in results if item.get("status") == "PASS" and item.get("screenshot")]
    if not passed:
        return None
    thumbs = []
    for item in passed:
        image = Image.open(item["screenshot"]).convert("RGB")
        image.thumbnail((360, 210))
        canvas = Image.new("RGB", (390, 275), (24, 24, 24))
        canvas.paste(image, (15, 15))
        draw = ImageDraw.Draw(canvas)
        draw.text((15, 232), item["label"], fill=(255, 255, 255))
        draw.text((15, 250), item["version"] + " " + item["expect"], fill=(210, 210, 210))
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 390, rows * 275), (16, 16, 16))
    for index, image in enumerate(thumbs):
        sheet.paste(image, ((index % columns) * 390, (index // columns) * 275))
    path = run_root / "rgb-external-server-contact-sheet.png"
    sheet.save(path)
    return path


def main() -> int:
    """运行外部服务端 RGB 真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--universal", action="store_true")
    parser.add_argument("--channels-only", action="store_true")
    parser.add_argument("--basic-checks", action="store_true")
    parser.add_argument("--full-checks", action="store_true")
    args = parser.parse_args()
    run_id = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / "runs" / run_id
    run_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    if args.universal:
        cases = [universal_case(case) for case in cases]
    prepared_clients = {}
    if args.prepare_only:
        for case in cases:
            prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
            prepare_managed_server(case)
            deploy_plugin(case)
        write_json(run_root / "summary.json", {"status": "PREPARED", "cases": cases})
        return 0
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, run_root, args.channels_only, args.basic_checks, args.full_checks))
        write_json(run_root / "summary.json", {"run": run_id, "results": results, "contactSheet": ""})
        write_json(BUILD_ROOT / "last-summary.json", {"run": run_id, "results": results, "contactSheet": ""})
    contact_sheet = make_contact_sheet(results, run_root)
    summary = {"run": run_id, "results": results, "contactSheet": str(contact_sheet) if contact_sheet else ""}
    write_json(run_root / "summary.json", summary)
    write_json(BUILD_ROOT / "last-summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("外部服务端矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed)) + " summary=" + str(run_root / "summary.json"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
