import argparse
import json
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw

import run_rgb_visual_matrix as base


SERVER_WORK = Path(r"E:\server_work")
BUILD_ROOT = base.REPO / "build" / "rgb-external-server-matrix"
JAVA8_CAT = Path(r"C:\Program Files\Java\jdk-1.8\bin\java.exe")
JAVA21 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
JAVA17 = base.JAVA17


EXTERNAL_MATRIX = [
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


def deploy_plugin(case: dict) -> Path:
    """把本轮测试用 BLWorldTrashCan jar 部署到目标服务端 plugins 目录。"""
    server_dir = Path(case["serverDir"])
    plugins_dir = server_dir / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    source = base.REPO / "dist" / case["plugin"]
    if not source.is_file():
        raise RuntimeError("缺少待部署插件 jar: " + str(source))
    target = plugins_dir / "BLWorldTrashCan-rgb-test.jar"
    shutil.copy2(source, target)
    return target


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
    wait_server_ready(process, log_path, int(case["port"]), int(case.get("readyTimeout", 150)))
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


def wait_player_online(case: dict, username: str, log_path: Path) -> None:
    """从服务端日志确认真实客户端玩家已进入服务端。"""
    deadline = time.time() + int(case.get("joinTimeout", 100))
    while time.time() < deadline:
        text = read_text(log_path)
        if username in text and ("joined the game" in text or "logged in with entity id" in text or "UUID of player" in text):
            return
        time.sleep(1)
    raise TimeoutError("等待玩家进服超时: " + username + " log=" + str(log_path))


def capture_debug_screenshot(case: dict, game_dir: Path, run_dir: Path) -> Path:
    """悬停 debugrgb 物品并使用 Minecraft F2 截图。"""
    time.sleep(float(case.get("debugWait", 3.5)))
    base.hover_debug_item(case)
    return base.capture_internal_screenshot(case, game_dir, run_dir)


def stop_process(process: subprocess.Popen, command: str | None = None) -> None:
    """优雅停止进程，必要时强制结束并关闭日志句柄。"""
    try:
        base.stop_process(process, command)
    finally:
        log_file = getattr(process, "_blwtc_log_file", None)
        if log_file is not None:
            log_file.close()


def run_case(case: dict, prepared_clients: dict, run_root: Path) -> dict:
    """执行单个外部服务端 RGB 截图用例。"""
    log("开始外部服务端用例 " + case["id"] + " / " + case["label"])
    run_dir = run_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_process = None
    client_process = None
    game_dir = None
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "expect": case["expect"],
        "status": "FAIL",
    }
    try:
        server_process = launch_server(case, run_dir)
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
        send_console_command(server_process, "blwtc debugrgb " + username, command_log)
        wait_debug_command_not_rejected(server_log, debug_offset)
        screenshot = capture_debug_screenshot(case, game_dir, run_dir)
        result["screenshot"] = str(screenshot)
        result["brightness"] = base.image_brightness(Image.open(screenshot))
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
    args = parser.parse_args()
    run_id = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / "runs" / run_id
    run_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    if args.prepare_only:
        for case in cases:
            prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
            deploy_plugin(case)
        write_json(run_root / "summary.json", {"status": "PREPARED", "cases": cases})
        return 0
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, run_root))
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
