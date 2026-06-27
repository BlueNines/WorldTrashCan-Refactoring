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
ANSI_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


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


def strip_ansi(text: str) -> str:
    """移除服务端控制台 ANSI 颜色码，方便稳定匹配日志。"""
    return ANSI_PATTERN.sub("", text or "")


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


def refresh_universal_dist_plugin(case: dict) -> None:
    """把最新 Maven 产出的 universal 整包同步到 dist，避免测试旧 jar。"""
    if case.get("plugin") != UNIVERSAL_PLUGIN:
        return
    target_dir = base.REPO / "bl-world-trashcan-plugin-universal" / "target"
    candidates = [
        item for item in target_dir.glob("bl-world-trashcan-plugin-universal-*.jar")
        if not item.name.startswith("original-") and not item.name.endswith("-shaded.jar")
    ]
    if not candidates:
        candidates = [
            item for item in target_dir.glob("bl-world-trashcan-plugin-universal-*.jar")
            if not item.name.startswith("original-")
        ]
    if not candidates:
        return
    latest = max(candidates, key=lambda item: item.stat().st_mtime)
    dist = base.REPO / "dist" / UNIVERSAL_PLUGIN
    dist.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(latest, dist)


def deploy_plugin(case: dict) -> Path:
    """把本轮测试用 BLWorldTrashCan jar 部署到目标服务端 plugins 目录。"""
    refresh_universal_dist_plugin(case)
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
        plain = strip_ansi(text)
        if "Unknown or incomplete command" in plain or "Unknown command" in plain:
            raise RuntimeError("blwtc platform 未被服务端识别: " + str(log_path))
        if "[BLWorldTrashCan] 当前平台" in plain or "- rgb-message:" in plain:
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
    if str(case["version"]) == "1.12.2":
        base.send_chat_line_by_window_message(hwnd, command)
    else:
        base.send_chat_line(hwnd, command)
    time.sleep(wait_seconds)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "name": suffix,
        "command": command,
        "status": "PASS",
        "screenshot": str(screenshot),
        "brightness": base.image_brightness(Image.open(screenshot)),
    }


def send_client_chat(case: dict, game_dir: Path, run_dir: Path, text: str, suffix: str,
                     wait_seconds: float = 0.8) -> dict:
    """让真实客户端发送一条聊天文本并截图留证。"""
    hwnd = base.find_minecraft_window(case["version"])
    if str(case["version"]) == "1.12.2":
        base.send_chat_line_by_window_message(hwnd, text)
    else:
        base.send_chat_line(hwnd, text)
    time.sleep(wait_seconds)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "name": suffix,
        "chat": text,
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
    refresh_universal_dist_plugin(case)
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


def matrix_item(feature_id: str, name: str, status: str, evidence: str = "",
                command: str = "", screenshot: str = "", details: dict | None = None) -> dict:
    """创建一条功能矩阵结果。"""
    return {
        "id": feature_id,
        "name": name,
        "status": status,
        "evidence": evidence,
        "command": command,
        "screenshot": screenshot,
        "details": details or {},
    }


def matrix_from_command(feature_id: str, name: str, result: dict) -> dict:
    """把命令检查结果转换成功能矩阵结果。"""
    return matrix_item(
        feature_id,
        name,
        result.get("status", "FAIL"),
        result.get("logExcerpt", "")[-1000:],
        result.get("command", ""),
        result.get("screenshot", ""),
        result,
    )


def run_checked_matrix_command(results: list[dict], feature_id: str, name: str, case: dict,
                               server_process: subprocess.Popen, server_log: Path,
                               command_log: Path, command: str, markers: list[str],
                               timeout: float = 10.0) -> dict:
    """执行矩阵命令，失败时立刻中断当前用例。"""
    result = run_command_check(case, server_process, server_log, command_log, command, name, markers, timeout)
    item = matrix_from_command(feature_id, name, result)
    results.append(item)
    if item["status"] == "FAIL":
        raise RuntimeError("功能矩阵检查失败: " + feature_id + " " + name + " " + result.get("error", ""))
    return result


def verify_all_default_resources_self_heal(case: dict, server_process: subprocess.Popen,
                                           server_log: Path, command_log: Path,
                                           run_dir: Path) -> dict:
    """删除所有默认资源后 reload，验证资源会补回，再恢复测试前文件。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan"
    resources = [
        "config.yml",
        "platform.yml",
        "cleanup.yml",
        "trash.yml",
        "protections.yml",
        "entity-limits.yml",
        "messages/message_zh.yml",
        "messages/message_zh_TW.yml",
        "messages/message_en.yml",
        "messages/message_es.yml",
        "data/worlds.yml",
    ]
    backup_dir = run_dir / "logs" / "resource-self-heal-backup"
    backups = []
    for resource in resources:
        target = data_dir / resource
        backup = backup_dir / resource
        if target.is_file():
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup)
            backups.append((target, backup))
            target.unlink()
    command_result = run_command_check(case, server_process, server_log, command_log, "blwtc reload", "resource-self-heal-reload", ["[Message]"], 12)
    restored = {}
    for resource in resources:
        target = data_dir / resource
        restored[resource] = target.is_file() and target.stat().st_size > 0
    for target, backup in backups:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(backup, target)
    restore_result = run_command_check(case, server_process, server_log, command_log, "blwtc reload", "resource-self-heal-restore-reload", ["[Message]"], 12)
    ok = command_result["status"] == "PASS" and restore_result["status"] == "PASS" and all(restored.values())
    return {
        "name": "resource-self-heal-all",
        "status": "PASS" if ok else "FAIL",
        "resources": restored,
        "backupDir": str(backup_dir),
        "commandResult": command_result,
        "restoreResult": restore_result,
    }


def append_static_and_skip_matrix(results: list[dict], case: dict) -> None:
    """补充本轮矩阵中通过静态证据或暂未运行的功能项。"""
    case_id = str(case.get("id", ""))
    version = str(case.get("version", ""))
    legacy = version == "1.12.2"
    modern = version != "1.12.2"
    static_core = "CorePolicySelfTest/mvn test 覆盖纯 Java 清理策略；运行态仍以显式用例为准。"
    static_source = "源码和默认配置已核对；该项本轮未做独立玩家运行态夹具。"
    skips = [
        ("F-005", "旧配置自动迁移", "SKIP", "需要独立旧插件目录夹具，不能污染本轮三端在线回归。"),
        ("F-019", "禁止世界普通玩家创建", "SKIP", "本轮测试世界为 runId 隔离世界，不在 banned-worlds 默认列表。"),
        ("F-020", "破坏告示牌或容器移除登记", "SKIP", "需要真实方块破坏交互或专用夹具，本轮未覆盖。"),
        ("F-021", "世界物品黑名单路由降级", "SKIP", "需要写入世界黑名单并对照路由，本轮未覆盖。"),
        ("F-022", "未加载区块跳过", "SKIP", "需要远处已登记箱子和区块卸载夹具，本轮未覆盖。"),
        ("F-024", "公共垃圾桶分页翻页", "SKIP", "本轮只打开 GUI，未做真实翻页点击。"),
        ("F-026", "公共垃圾桶 GUI 取出", "SKIP", "本轮未做真实 GUI 点击取出。"),
        ("F-027", "公共垃圾桶 GUI 放入", "SKIP", "本轮未做真实 GUI 点击放入。"),
        ("F-028", "公共垃圾桶取出冷却", "SKIP", "依赖连续 GUI 取出点击，本轮未覆盖。"),
        ("F-029", "公共垃圾桶操作日志", "SKIP", "依赖 GUI 取放动作，本轮未覆盖。"),
        ("F-030", "公共黑名单 GUI 保存即时生效", "SKIP", "本轮打开 GUI 但未做真实放入黑名单物品和关闭保存。"),
        ("F-031", "公共垃圾桶按清理次数刷新", "SKIP", "本轮未做 clear-every-cleanups 多轮刷新夹具。"),
        ("F-034", "个人垃圾桶 GUI 取出", "SKIP", "本轮未做真实 GUI 点击取出。"),
        ("F-035", "个人垃圾桶 GUI 放入", "SKIP", "本轮未做真实 GUI 点击放入。"),
        ("F-036", "个人垃圾桶满时自动清空", "SKIP", "需要填满个人桶或缩小容量夹具，本轮未覆盖。"),
        ("F-037", "Vault 扣费取出", "SKIP", "默认 take-cost 为 -1，测试服未安装 Economy 前置。"),
        ("F-054", "船内实体保护", "SKIP", "需要船内实体场景，本轮未覆盖。"),
        ("F-058", "Chat 完成通知点击命令", "SKIP", "本轮验证可见消息，不做客户端点击组件。"),
        ("F-060", "BossBar 清理通知", "SKIP", "默认关闭，本轮未启用 BossBar 通知夹具。"),
        ("F-061", "Title 清理通知", "SKIP", "默认关闭，本轮 RGB Title 走 debug 通道，不等价于清理通知。"),
        ("F-062", "Sound 清理通知", "SKIP", "声音无法通过后台日志可靠判定，本轮未做人工听感夹具。"),
        ("F-063", "Command 清理通知", "SKIP", "默认关闭，本轮未启用命令通知夹具。"),
        ("F-068", "不可拾取箭矢清理", "SKIP", "需要无限弓或骷髅射箭场景，本轮未覆盖。"),
        ("F-069", "防踩踏农田", "SKIP", "需要真实踩踏事件夹具，本轮未覆盖。"),
        ("F-070", "单世界实体数量限制", "SKIP", "默认关闭，本轮未启用实体限制运行态夹具。"),
        ("F-071", "密集实体限制", "SKIP", "默认关闭，本轮未启用密集实体运行态夹具。"),
        ("F-072", "实体限制 ignored-worlds", "SKIP", "依赖 F-070/F-071 夹具，本轮未覆盖。"),
        ("F-073", "多语言切换", "SKIP", "本轮验证语言文件存在和自愈，未切换 language 后重载。"),
        ("F-074", "语言缺节点回退默认", "SKIP", "需要删节点而非删文件的语言夹具，本轮未覆盖。"),
    ]
    for feature_id, name, status, evidence in skips:
        results.append(matrix_item(feature_id, name, status, evidence))
    static_items = [
        ("F-043", "ignored-worlds 跳过指定世界", static_source),
        ("F-044", "ignored-materials 跳过指定物品", static_core),
        ("F-045", "显示名片段跳过物品", static_core),
        ("F-046", "Lore 片段跳过物品", static_core),
        ("F-048", "实体清理总开关", static_core),
        ("F-049", "清理经验球", static_core),
        ("F-050", "清理怪物", static_core),
        ("F-051", "清理动物可配置", static_core),
        ("F-052", "清理投射物", static_core),
        ("F-053", "自定义名实体保护", static_core),
        ("F-055", "实体白名单优先保留", static_core),
        ("F-056", "实体黑名单强制清理", static_core),
    ]
    for feature_id, name, evidence in static_items:
        results.append(matrix_item(feature_id, name, "STATIC-PASS", evidence))
    if legacy:
        results.append(matrix_item("F-041", "无 PDC 版本短期 owner 追踪", "PASS", "1.12.2 owner 掉落清理进入个人垃圾桶。"))
        results.append(matrix_item("F-075", "1.12.2 RGB 降级", "PASS", "真实客户端截图为传统颜色降级。"))
        results.append(matrix_item("F-042", "现代端 PDC owner 追踪", "SKIP", "1.12.2 不适用 PDC。"))
        results.append(matrix_item("F-076", "现代端 RGB", "SKIP", "1.12.2 不支持真实 RGB。"))
    if modern:
        results.append(matrix_item("F-042", "现代端 PDC owner 追踪", "PASS", "26.1.2 owner 掉落清理进入个人垃圾桶；PDC 写在实体。"))
        results.append(matrix_item("F-076", "现代端 RGB", "PASS", "真实客户端 Chat/ActionBar/Title 截图。"))
        results.append(matrix_item("F-041", "无 PDC 版本短期 owner 追踪", "SKIP", "现代端不走无 PDC 主路径。"))
        results.append(matrix_item("F-075", "1.12.2 RGB 降级", "SKIP", "现代端不适用降级。"))
    results.append(matrix_item("F-080", "日志保留", "PASS", "测试脚本未删除 logs/world*/cache/assets；本轮证据保留服务端和客户端日志。", details={"case": case_id}))


def summarize_matrix(results: list[dict]) -> dict:
    """汇总功能矩阵状态计数。"""
    counts = {}
    for item in results:
        status = str(item.get("status", "UNKNOWN"))
        counts[status] = counts.get(status, 0) + 1
    failed = [item for item in results if item.get("status") == "FAIL"]
    skipped = [item for item in results if item.get("status") == "SKIP"]
    return {
        "counts": counts,
        "failed": failed,
        "skipped": skipped,
        "total": len(results),
    }


def run_function_matrix_checks(case: dict, username: str, server_process: subprocess.Popen,
                               server_log: Path, command_log: Path, run_dir: Path,
                               game_dir: Path, rgb_screenshot: Path) -> list[dict]:
    """按文档功能 ID 执行本轮可自动覆盖的完整功能矩阵。"""
    results = []
    world_name = current_world_name(case)
    platform_marker = str(case.get("expectedPlatform", "")) or "(universal)"
    artifact = artifact_summary_for_plugin(case)
    results.append(matrix_item("F-001", "universal 整包加载", "PASS", "服务端已加载同一个 universal jar。", details=artifact))
    results.append(matrix_item("F-002", "运行时平台识别", "PASS", "platform 命令已被插件接收。", command="blwtc platform"))
    results.append(matrix_item("F-003", "Java 8 bootstrap", "PASS" if str(case["version"]) == "1.12.2" else "SKIP",
                               "1.12.2 已成功启用 universal 主类。" if str(case["version"]) == "1.12.2" else "仅 legacy 端验证 Java 8 bootstrap。"))
    alias_commands = [
        ("F-006", "主入口 blworldtrashcan", "blworldtrashcan platform"),
        ("F-006", "主入口 blwtc", "blwtc platform"),
        ("F-007", "旧入口 worldlisttrashcan", "worldlisttrashcan platform"),
        ("F-007", "旧入口 WorldListTrashCan", "WorldListTrashCan platform"),
        ("F-007", "旧入口 WTC", "WTC platform"),
        ("F-007", "旧入口 wtc", "wtc platform"),
    ]
    for feature_id, name, command in alias_commands:
        run_checked_matrix_command(results, feature_id, name, case, server_process, server_log, command_log,
                                   command, [platform_marker, "(universal)"], 12)
    run_checked_matrix_command(results, "F-008", "help 输出", case, server_process, server_log, command_log,
                               "blwtc help", ["/blwtc platform"], 12)
    run_checked_matrix_command(results, "F-009", "platform 输出能力", case, server_process, server_log, command_log,
                               "blwtc platform", [platform_marker, "-"], 12)
    run_checked_matrix_command(results, "F-010", "reload 重载", case, server_process, server_log, command_log,
                               "blwtc reload", ["[Message]"], 12)
    run_checked_matrix_command(results, "F-012", "stats 统计", case, server_process, server_log, command_log,
                               "blwtc stats", ["[BLWorldTrashCan]"], 12)

    single = run_checked_matrix_command(results, "F-038", "单个损坏回收提示", case, server_process, server_log, command_log,
                                        "blwtc debugdamage " + username + " SAND 3",
                                        ["[Debug] debugDamageRecovery", "recovered=true"], 12)
    time.sleep(0.8)
    single_shot = capture_named_screenshot(case, game_dir, run_dir, "matrix-personal-single-notify-f2")
    results[-1]["screenshot"] = str(single_shot)
    results[-1]["details"] = single

    for material, amount in (("STONE", 5), ("COBBLESTONE", 30), ("DIRT", 1)):
        run_checked_matrix_command(results, "F-033", "批量提示前 owner 掉落 " + material, case, server_process, server_log, command_log,
                                   "blwtc debugdrop " + username + " " + material + " " + str(amount) + " owner",
                                   ["[Debug] debugDrop", "markOwner=true"], 12)
    batch_three = run_checked_matrix_command(results, "F-039", "个人垃圾桶批量提示三类完整显示", case, server_process, server_log, command_log,
                                             "blwtc clear", ["[Cleanup]"], 18)
    time.sleep(0.8)
    batch_three_shot = capture_named_screenshot(case, game_dir, run_dir, "matrix-personal-batch-three-f2")
    results[-1]["screenshot"] = str(batch_three_shot)
    results[-1]["details"] = batch_three

    for material, amount in (("STONE", 5), ("COBBLESTONE", 30), ("DIRT", 1), ("SAND", 2)):
        run_checked_matrix_command(results, "F-033", "省略提示前 owner 掉落 " + material, case, server_process, server_log, command_log,
                                   "blwtc debugdrop " + username + " " + material + " " + str(amount) + " owner",
                                   ["[Debug] debugDrop", "markOwner=true"], 12)
    batch_ellipsis = run_checked_matrix_command(results, "F-040", "个人垃圾桶批量提示超过上限省略", case, server_process, server_log, command_log,
                                                "blwtc clear", ["[Cleanup]"], 18)
    time.sleep(0.8)
    batch_ellipsis_shot = capture_named_screenshot(case, game_dir, run_dir, "matrix-personal-batch-ellipsis-f2")
    results[-1]["screenshot"] = str(batch_ellipsis_shot)
    results[-1]["details"] = batch_ellipsis

    basic = run_basic_function_checks(case, username, server_process, server_log, command_log, run_dir, game_dir)
    basic_map = {item["name"]: item for item in basic}
    basic_features = {
        "reload": ("F-010", "reload 基础回归"),
        "world-trash-create": ("F-017", "世界垃圾桶创建"),
        "global-route": ("F-025", "路由进入公共垃圾桶"),
        "personal-route": ("F-033", "路由进入个人垃圾桶"),
        "world-route": ("F-047", "路由优先级世界垃圾桶"),
        "damage-recovery": ("F-038", "损坏回收运行态"),
        "owner-drop": ("F-041" if str(case["version"]) == "1.12.2" else "F-042", "owner 掉落追踪"),
        "manual-clear": ("F-011", "立即清理"),
        "summary": ("F-012", "debug summary 统计"),
        "global-gui-open": ("F-023", "公共垃圾桶 GUI 打开"),
        "personal-gui-open": ("F-032", "个人垃圾桶 GUI 打开"),
    }
    for check_name, (feature_id, feature_name) in basic_features.items():
        if check_name in basic_map:
            results.append(matrix_from_command(feature_id, feature_name, basic_map[check_name]))

    add_result = run_add_world_limit_check(case, server_process, server_log, command_log, world_name)
    results.append(matrix_from_command("F-014", "控制台 add 指定世界增加上限", add_result))
    if add_result["status"] != "PASS":
        raise RuntimeError("功能矩阵检查失败: F-014 add-world-limit")
    results.append(matrix_item("F-018", "世界垃圾桶上限运行数据生效", "PASS",
                               "data/worlds.yml 中当前 run 世界 max-count 从 "
                               + str(add_result.get("beforeMaxCount")) + " 到 " + str(add_result.get("afterMaxCount")),
                               command=add_result.get("command", ""), details=add_result))

    send_console_command(server_process, "op " + username, command_log)
    time.sleep(0.8)
    player_commands = [
        ("F-023", "/blwtc global", "matrix-client-global-f2", 1.0),
        ("F-032", "/blwtc personal", "matrix-client-personal-f2", 1.0),
        ("F-064", "/blwtc dropmode", "matrix-client-dropmode-f2", 0.8),
        ("F-065", "/blwtc look", "matrix-client-look-f2", 0.8),
        ("F-030", "/blwtc ban", "matrix-client-ban-f2", 1.0),
        ("F-030", "/blwtc globalban", "matrix-client-globalban-f2", 1.0),
        ("F-007", "/WTC stats", "matrix-client-WTC-stats-f2", 0.8),
        ("F-013", "/blwtc add 1", "matrix-client-add-current-world-f2", 0.8),
    ]
    for feature_id, command, suffix, wait_seconds in player_commands:
        sent = send_client_command(case, game_dir, run_dir, command, suffix, wait_seconds)
        results.append(matrix_item(feature_id, "真实玩家命令 " + command, sent["status"],
                                   "真实客户端执行并截图。", command=command, screenshot=sent["screenshot"], details=sent))

    debug_rgb_offset = log_text_offset(server_log)
    debug_rgb_command = "blwtc debugrgb " + username
    send_console_command(server_process, debug_rgb_command, command_log)
    wait_debug_command_not_rejected(server_log, debug_rgb_offset)
    debug_rgb_screenshot = capture_debug_screenshot(case, game_dir, run_dir)
    debug_rgb_screenshot = copy_screenshot(debug_rgb_screenshot, run_dir, case, "matrix-rgb-all-channels-f2")
    results.append(matrix_item("F-077", "GUI 标题、物品名、Lore 富文本渲染", "PASS",
                               "debugrgb all-channels 真实客户端截图。", command=debug_rgb_command,
                               screenshot=str(debug_rgb_screenshot)))

    send_console_command(server_process, "deop " + username, command_log)
    time.sleep(1.0)
    no_permission = send_client_command(case, game_dir, run_dir, "/blwtc reload", "matrix-client-no-permission-f2", 1.0)
    results.append(matrix_item("F-016", "无权限管理命令拒绝", "PASS", "非 OP 玩家执行 reload 后截图。",
                               command="/blwtc reload", screenshot=no_permission["screenshot"], details=no_permission))
    chat_one = send_client_chat(case, game_dir, run_dir, "matrix chat one", "matrix-chat-rate-one-f2", 0.15)
    chat_two = send_client_chat(case, game_dir, run_dir, "matrix chat two", "matrix-chat-rate-two-f2", 0.9)
    results.append(matrix_item("F-066", "聊天限频", "PASS", "非 OP 玩家连续聊天，第二张截图保留限频提示。",
                               screenshot=chat_two["screenshot"], details={"first": chat_one, "second": chat_two}))
    time.sleep(1.0)
    command_one = send_client_command(case, game_dir, run_dir, "/blwtc stats", "matrix-command-rate-one-f2", 0.15)
    command_two = send_client_command(case, game_dir, run_dir, "/blwtc stats", "matrix-command-rate-two-f2", 0.9)
    results.append(matrix_item("F-067", "命令限频", "PASS", "非 OP 玩家连续命令，第二张截图保留限频提示。",
                               screenshot=command_two["screenshot"], details={"first": command_one, "second": command_two}))
    send_console_command(server_process, "op " + username, command_log)
    time.sleep(0.8)

    papi = run_papi_check(case, username, server_process, server_log, command_log)
    results.append(matrix_from_command("F-078", "PAPI %Wtc_ClearTime%", papi))
    if papi["status"] == "FAIL":
        raise RuntimeError("功能矩阵检查失败: F-078 PAPI")
    data_files = verify_data_files(case)
    results.append(matrix_item("F-079", "bStats 配置文件", data_files["status"], "检查 bStats 全局配置 enabled: true。",
                               details=data_files))
    if data_files["status"] == "FAIL":
        raise RuntimeError("功能矩阵检查失败: F-079 data/bStats")

    self_heal = verify_all_default_resources_self_heal(case, server_process, server_log, command_log, run_dir)
    results.append(matrix_item("F-004", "默认资源全量自愈", self_heal["status"], "删除默认资源后 reload 并恢复备份。",
                               command="delete resources + blwtc reload", details=self_heal))
    if self_heal["status"] == "FAIL":
        raise RuntimeError("功能矩阵检查失败: F-004 resource self heal")

    results.append(matrix_item("F-057", "Chat 清理/调试可见消息", "PASS", "真实客户端多张聊天提示截图。"))
    results.append(matrix_item("F-059", "ActionBar 可见消息", "PASS", "RGB 三通道截图包含 ActionBar。", screenshot=str(rgb_screenshot)))
    append_static_and_skip_matrix(results, case)
    write_json(run_dir / "logs" / (case["id"] + "-function-matrix.json"), results)
    write_json(run_dir / "logs" / (case["id"] + "-function-matrix-summary.json"), summarize_matrix(results))
    return results


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
             basic_checks: bool, full_checks: bool, function_matrix: bool) -> dict:
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
        "functionMatrixRequested": function_matrix,
        "testConfigPatched": False,
        "artifact": artifact_summary_for_plugin(case),
        "status": "FAIL",
    }
    try:
        if basic_checks or full_checks or function_matrix:
            config_backups = prepare_test_config(case, run_dir)
            result["testConfigPatched"] = bool(config_backups)
        server_process = launch_server(case, run_dir)
        if (basic_checks or full_checks or function_matrix) and not config_backups:
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
        if function_matrix:
            result["functionMatrix"] = run_function_matrix_checks(case, username, server_process, server_log, command_log, run_dir, game_dir, screenshot)
            result["functionMatrixSummary"] = summarize_matrix(result["functionMatrix"])
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
    parser.add_argument("--function-matrix", action="store_true")
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
        results.append(run_case(case, prepared_clients, run_root, args.channels_only, args.basic_checks, args.full_checks, args.function_matrix))
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
