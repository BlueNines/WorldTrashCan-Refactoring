import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TARGET_CASE_IDS = [
    "managed_paper1122",
    "external_spigot2612",
    "external_folia1218",
]


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_log_file_for_git(path: Path) -> None:
    """移除 PTY 日志行尾的回车和空白，不改变可见日志内容。"""
    if not path.is_file():
        return
    original = path.read_text(encoding="utf-8", errors="replace")
    normalized = "\n".join(line.rstrip(" \t\r") for line in original.splitlines())
    if original:
        normalized += "\n"
    path.write_text(normalized, encoding="utf-8")


def to_json_value(value):
    """把 Path 等对象转换成 JSON 可写值。"""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, list):
        return [to_json_value(item) for item in value]
    if isinstance(value, dict):
        return {key: to_json_value(item) for key, item in value.items()}
    return value


def selected_cases(case_id: str | None) -> list[dict]:
    """按参数选择本轮要跑的服务端用例。"""
    cases = []
    for wanted in TARGET_CASE_IDS:
        for item in external.EXTERNAL_MATRIX:
            if item["id"] == wanted:
                cases.append(external.universal_case(item))
                break
    if not case_id:
        return cases
    for item in cases:
        if case_id in (item["id"], item.get("sourceId", ""), item["label"], item["version"]):
            return [item]
    raise RuntimeError("未知语言测试用例: " + case_id)


def is_legacy(case: dict) -> bool:
    """判断当前用例是否为 1.12.2。"""
    return str(case["version"]) == "1.12.2"


def backup_file(path: Path, backup_dir: Path) -> dict:
    """备份一个可能存在的文件。"""
    backup = backup_dir / (path.name + ".before")
    backup.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        shutil.copy2(path, backup)
        return {"target": path, "backup": backup, "existed": True}
    return {"target": path, "backup": backup, "existed": False}


def restore_backups(backups: list[dict]) -> None:
    """恢复本轮测试改动过的文件。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def config_file(case: dict) -> Path:
    """返回运行时 config.yml 路径。"""
    return Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "config.yml"


def message_file(case: dict, file_name: str) -> Path:
    """返回运行时语言文件路径。"""
    return Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "messages" / file_name


def set_language(case: dict, language_file: str) -> Path:
    """修改运行时 config.yml 中的语言文件名。"""
    target = config_file(case)
    if not target.is_file():
        raise RuntimeError("config.yml 不存在，无法切换语言: " + str(target))
    original = target.read_text(encoding="utf-8", errors="replace")
    updated = external.update_yaml_scalars(original, {"language": "\"" + language_file + "\""})
    if updated == original and ("language:" not in original):
        updated = original.rstrip() + "\nlanguage: \"" + language_file + "\"\n"
    target.write_text(updated, encoding="utf-8")
    return target


def remove_yaml_key(text: str, dotted_path: str) -> tuple[str, bool]:
    """移除一个简单 YAML 键及其子块。"""
    parts = dotted_path.split(".")
    lines = text.splitlines(True)
    stack = []
    target_index = None
    target_indent = 0
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in stripped:
            continue
        indent = len(line) - len(line.lstrip(" "))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = stripped.split(":", 1)[0].strip().strip("'\"")
        current = [item[1] for item in stack] + [key]
        if current == parts:
            target_index = index
            target_indent = indent
            break
        if stripped.endswith(":"):
            stack.append((indent, key))
    if target_index is None:
        return text, False
    end_index = target_index + 1
    while end_index < len(lines):
        line = lines[end_index]
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            indent = len(line) - len(line.lstrip(" "))
            if indent <= target_indent:
                break
        end_index += 1
    return "".join(lines[:target_index] + lines[end_index:]), True


def remove_help_from_zh(case: dict) -> dict:
    """删除外部中文语言文件中的 command.help，用于验证 jar 默认节点回退。"""
    target = message_file(case, "message_zh.yml")
    if not target.is_file():
        raise RuntimeError("message_zh.yml 不存在，无法删除 help 节点: " + str(target))
    original = target.read_text(encoding="utf-8", errors="replace")
    updated, removed = remove_yaml_key(original, "command.help")
    target.write_text(updated, encoding="utf-8")
    return {"path": target, "removed": removed}


def run_console(process, command_log: Path, command: str, wait: float = 0.25) -> None:
    """发送服务端控制台命令并短暂等待。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def reload_plugin(process, command_log: Path, server_log: Path, expected_file: str) -> dict:
    """重载插件配置并等待指定语言文件加载。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, "blwtc reload", 0.5)
    markers = ["[Message]", "messages/" + expected_file]
    text = external.wait_command_markers(server_log, offset, markers, 12, "blwtc reload")
    return {"markers": markers, "excerpt": external.strip_ansi(text)[-2000:]}


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家权限、位置和基础游戏规则。"""
    commands = [
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule keepInventory true",
        "minecraft:gamerule fallDamage false",
        "minecraft:time set day",
        "minecraft:weather clear",
    ]
    if is_legacy(case):
        commands.extend([
            "minecraft:gamemode 1 " + username,
            "minecraft:tp " + username + " 0 91 -8 0 15",
        ])
    else:
        commands.extend([
            "minecraft:gamemode creative " + username,
            "minecraft:tp " + username + " 0 91 -8 0 15",
        ])
    for command in commands:
        run_console(process, command_log, command, 0.12)
    time.sleep(1.0)


def ensure_ingame_view(case: dict) -> None:
    """尽量把客户端从暂停菜单切回正常游戏画面。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.click_game(hwnd, rect, 0.50, 0.29)
    time.sleep(0.2)
    base.click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.4)


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> Path:
    """截取 F2 截图并复制为稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + name + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def screenshot_info(path: Path) -> dict:
    """返回截图文件基础校验信息。"""
    data = path.read_bytes()
    image = Image.open(path)
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "dimensions": list(image.size),
        "brightness": base.image_brightness(image),
    }


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def render_log_screenshot(text: str, target: Path, title: str) -> Path:
    """把关键日志渲染成 PNG 截图证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    useful = []
    for line in external.strip_ansi(text).splitlines():
        if "[Message]" in line or "BlWorldTrashCan" in line or "[CHAT]" in line:
            useful.append(line)
    lines.extend(useful[-34:] if useful else external.strip_ansi(text).splitlines()[-34:])
    width = 1600
    line_height = 26
    height = max(240, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line[:190], fill=color, font=used_font)
        y += line_height
    image.save(target)
    return target


def wait_client_markers(client_log: Path, offset: int, markers: list[str], timeout: float) -> dict:
    """等待真实客户端日志出现指定聊天标记。"""
    deadline = time.time() + timeout
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        if all(marker in text for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": text[-1800:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": markers, "excerpt": last_text[-1800:]}


def send_chat_by_window_message(case: dict, command: str) -> None:
    """用窗口消息向真实客户端发送聊天命令。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.send_chat_line_by_window_message(hwnd, command)


def send_chat_by_clipboard(case: dict, command: str) -> None:
    """用剪贴板粘贴方式向真实客户端发送聊天命令。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.send_chat_line(hwnd, command)


def send_help_and_capture(case: dict, game_dir: Path, run_dir: Path, suffix: str,
                          markers: list[str], wait_seconds: float = 1.2) -> dict:
    """由真实客户端执行 /blwtc help、等待日志并保存截图。"""
    ensure_ingame_view(case)
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    offset = external.log_text_offset(client_log)
    attempts = []
    send_chat_by_window_message(case, "/blwtc help")
    time.sleep(wait_seconds)
    marker_check = wait_client_markers(client_log, offset, markers, 4)
    attempts.append({"method": "window-message", "status": marker_check["status"]})
    if marker_check["status"] != "PASS":
        send_chat_by_clipboard(case, "/blwtc help")
        time.sleep(wait_seconds)
        marker_check = wait_client_markers(client_log, offset, markers, 7)
        attempts.append({"method": "clipboard", "status": marker_check["status"]})
    screenshot = capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "command": "/blwtc help",
        "suffix": suffix,
        "markers": markers,
        "attempts": attempts,
        "clientCheck": marker_check,
        "clientScreenshot": screenshot_info(screenshot),
    }


def send_reload_and_capture(case: dict, game_dir: Path, run_dir: Path) -> dict:
    """由真实客户端重载插件并记录公开品牌前缀。"""
    ensure_ingame_view(case)
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    offset = external.log_text_offset(client_log)
    attempts = []
    send_chat_by_window_message(case, "/blwtc reload")
    time.sleep(1.2)
    markers = ["BlWorldTrashCan", "reloaded."]
    marker_check = wait_client_markers(client_log, offset, markers, 4)
    attempts.append({"method": "window-message", "status": marker_check["status"]})
    if marker_check["status"] != "PASS":
        send_chat_by_clipboard(case, "/blwtc reload")
        time.sleep(1.2)
        marker_check = wait_client_markers(client_log, offset, markers, 7)
        attempts.append({"method": "clipboard", "status": marker_check["status"]})
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "brand-case-reload-f2")
    return {
        "command": "/blwtc reload",
        "markers": markers,
        "attempts": attempts,
        "clientCheck": marker_check,
        "clientScreenshot": screenshot_info(screenshot),
    }


def copy_runtime_files(case: dict, run_dir: Path, label: str) -> dict:
    """归档当前运行时 config 和语言文件。"""
    target_dir = run_dir / "logs" / ("runtime-" + label)
    target_dir.mkdir(parents=True, exist_ok=True)
    copied = {}
    for source in [
        config_file(case),
        message_file(case, "message_zh.yml"),
        message_file(case, "message_en.yml"),
    ]:
        if source.is_file():
            target = target_dir / source.name
            shutil.copy2(source, target)
            copied[source.name] = target
    return copied


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端语言切换和回退真实客户端截图测试。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始语言用例 " + case["id"] + " / " + case["label"])
    process = None
    client = None
    game_dir = None
    backups = []
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "sourceId": case.get("sourceId", ""),
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
        "checks": [],
    }
    try:
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(backup_file(config_file(case), backup_dir))
        backups.append(backup_file(message_file(case, "message_zh.yml"), backup_dir))
        backups.append(backup_file(message_file(case, "message_en.yml"), backup_dir))
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        result["clientPid"] = client.pid
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)
        platform_offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc platform", 0.5)
        external.wait_platform_command_accepted(server_log, platform_offset)

        set_language(case, "message_en.yml")
        english_reload = reload_plugin(process, command_log, server_log, "message_en.yml")
        result["checks"].append({
            "name": "F-073-language-switch-to-english",
            "reload": english_reload,
            "runtimeFiles": copy_runtime_files(case, run_dir, "english"),
            "brand": send_reload_and_capture(case, game_dir, run_dir),
            "help": send_help_and_capture(
                case,
                game_dir,
                run_dir,
                "language-english-help-f2",
                ["Show help", "Open global trash", "Show cleanup and trash statistics"],
            ),
        })

        set_language(case, "message_zh.yml")
        removed = remove_help_from_zh(case)
        zh_reload = reload_plugin(process, command_log, server_log, "message_zh.yml")
        result["checks"].append({
            "name": "F-074-missing-external-node-fallback",
            "removedNode": removed,
            "reload": zh_reload,
            "runtimeFiles": copy_runtime_files(case, run_dir, "zh-fallback"),
            "help": send_help_and_capture(
                case,
                game_dir,
                run_dir,
                "language-zh-fallback-help-f2",
                ["查看帮助", "打开公共垃圾桶", "查看清理和垃圾桶统计"],
            ),
        })

        combined_log = external.read_text(server_log)
        server_shot = render_log_screenshot(
            combined_log,
            run_dir / "server-screenshots" / (case["id"] + "-language-server-log.png"),
            case["label"] + " / language reload evidence",
        )
        result["serverScreenshot"] = screenshot_info(server_shot)
        failed = []
        for check in result["checks"]:
            help_check = check["help"]
            if help_check["clientCheck"]["status"] != "PASS":
                failed.append(check["name"] + "-client-log")
            if help_check["clientScreenshot"]["brightness"] <= 3:
                failed.append(check["name"] + "-blank-client-screenshot")
            brand_check = check.get("brand")
            if brand_check and brand_check["clientCheck"]["status"] != "PASS":
                failed.append(check["name"] + "-brand-client-log")
            if brand_check and brand_check["clientScreenshot"]["brightness"] <= 3:
                failed.append(check["name"] + "-blank-brand-screenshot")
        if result["serverScreenshot"]["brightness"] <= 3:
            failed.append("blank-server-screenshot")
        result["failedChecks"] = failed
        result["status"] = "PASS" if not failed else "FAIL"
    except Exception as error:
        result["error"] = repr(error)
        log("语言用例失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                result["failureScreenshot"] = str(capture_named_screenshot(case, game_dir, run_dir, "failure-f2"))
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            restore_backups(backups)
            external.stop_process(process, "stop")
        normalize_log_file_for_git(server_log)
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path | None:
    """生成语言测试截图总览图。"""
    screenshots = []
    for result in results:
        for item in result.get("checks", []):
            screenshots.append((result["label"] + " " + item["name"], Path(item["help"]["clientScreenshot"]["path"])))
            if item.get("brand"):
                screenshots.append((result["label"] + " brand-case", Path(item["brand"]["clientScreenshot"]["path"])))
        if result.get("serverScreenshot"):
            screenshots.append((result["label"] + " server-log", Path(result["serverScreenshot"]["path"])))
    if not screenshots:
        return None
    thumbs = []
    used_font = font()
    for label, path in screenshots:
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 290), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 250), label[:58], fill=(226, 232, 240), font=used_font)
        draw.text((10, 270), path.name[:58], fill=(148, 163, 184), font=used_font)
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 290), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 290))
    target = evidence_root / "language-contact-sheet.png"
    sheet.save(target)
    return target


def main() -> int:
    """运行多语言真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "language-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        result = run_case(case, prepared_clients, evidence_root)
        results.append(result)
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results, "contactSheet": ""})
    contact_sheet = make_contact_sheet(results, evidence_root)
    summary = {
        "run": run_id,
        "results": results,
        "contactSheet": str(contact_sheet) if contact_sheet else "",
        "allPassed": all(item.get("status") == "PASS" for item in results),
    }
    write_json(evidence_root / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("语言矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
