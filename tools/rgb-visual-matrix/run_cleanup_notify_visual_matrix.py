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
    raise RuntimeError("未知清理通知测试用例: " + case_id)


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


def notification_block() -> str:
    """生成清理通知专项验收用 notify 配置块。"""
    return (
        "notify:\n"
        "  # AI 自动化清理通知视觉验收临时配置，测试结束后脚本会恢复原文件。\n"
        "  chat:\n"
        "    # 是否启用聊天提醒。\n"
        "    enabled: true\n"
        "    # 是否同步输出到控制台。\n"
        "    console-log: true\n"
        "    # 清理完成消息点击后执行的命令。\n"
        "    click-command: \"/worldlisttrashcan globaltrash\"\n"
        "    # 倒计时聊天提醒，格式：剩余秒数;内容。\n"
        "    messages:\n"
        "      - \"0;&#FF1493AI_NOTIFY_CHAT_0 &#00E5FF清理完成 &#BAFF00公共:%GlobalTrashAddSum% &#FF4F00实体:%EntitySum%\"\n"
        "      - \"-5;&#FF4F00AI_NOTIFY_CHAT_MINUS5 &#00E5FF%CleanupSkipReason% &#BAFF00%CleanupOnlinePlayers%/%CleanupMinOnlinePlayers% &#FF1493%CleanupTargetEntities%/%CleanupMinTotalEntities%\"\n"
        "\n"
        "  actionbar:\n"
        "    # 是否启用 ActionBar。\n"
        "    enabled: true\n"
        "    # 倒计时 ActionBar 提醒，格式：剩余秒数;内容。\n"
        "    messages:\n"
        "      - \"0;&#00E5FFAI_ACTIONBAR_0 &#FF1493清理完成 &#BAFF00公共:%GlobalTrashAddSum% &#FF4F00实体:%EntitySum%\"\n"
        "      - \"-5;&#FF4F00AI_ACTIONBAR_MINUS5 &#00E5FF%CleanupSkipReason%\"\n"
        "\n"
        "  bossbar:\n"
        "    # 是否启用 BossBar。\n"
        "    enabled: true\n"
        "    # 倒计时 BossBar 提醒，格式：剩余秒数;内容;样式;颜色。\n"
        "    messages:\n"
        "      - \"0;&#FF1493AI_BOSSBAR_0 &#00E5FF清理完成 &#BAFF00公共:%GlobalTrashAddSum% &#FF4F00实体:%EntitySum%;SEGMENTED_20;PURPLE\"\n"
        "      - \"-5;&#FF4F00AI_BOSSBAR_MINUS5 &#00E5FF%CleanupSkipReason% &#BAFF00%CleanupOnlinePlayers%/%CleanupMinOnlinePlayers%;SEGMENTED_20;YELLOW\"\n"
        "\n"
        "  title:\n"
        "    # 是否启用 Title。\n"
        "    enabled: true\n"
        "    # 倒计时 Title 提醒，格式：剩余秒数;主标题;副标题。\n"
        "    messages:\n"
        "      - \"0;&#FF1493AI_TITLE_0;&#00E5FFAI_SUBTITLE_0 &#BAFF00公共:%GlobalTrashAddSum% &#FF4F00实体:%EntitySum%\"\n"
        "      - \"-5;&#FF4F00AI_TITLE_MINUS5;&#00E5FFAI_SUBTITLE_MINUS5 %CleanupSkipReason%\"\n"
        "\n"
        "  sound:\n"
        "    # 是否启用提示音。\n"
        "    enabled: true\n"
        "    # 倒计时声音提醒，格式：剩余秒数;声音名,音量,音调。\n"
        "    messages:\n"
        "      - \"0;entity.experience_orb.pickup,1.0,1.4\"\n"
        "      - \"-5;entity.experience_orb.pickup,1.0,0.5\"\n"
        "\n"
        "  command:\n"
        "    # 是否启用倒计时命令。\n"
        "    enabled: true\n"
        "    # 倒计时命令，格式：剩余秒数;命令1;命令2。\n"
        "    commands:\n"
        "      - \"0;say AI_WTC_NOTIFY_COMMAND_0\"\n"
        "      - \"-5;say AI_WTC_NOTIFY_COMMAND_MINUS5\"\n"
    )


def replace_notify_block(text: str) -> str:
    """替换 cleanup.yml 中的 notify 根块，保留其前面的扫地配置。"""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.strip() == "notify:" and (len(line) - len(line.lstrip(" "))) == 0:
            start = index
            break
    if start is None:
        prefix = text.rstrip() + "\n\n"
    else:
        prefix = "\n".join(lines[:start]).rstrip() + "\n\n"
    return prefix + notification_block()


def write_notify_config(case: dict) -> Path:
    """写入本轮清理通知专项验收配置。"""
    target = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    if not target.is_file():
        raise RuntimeError("cleanup.yml 不存在，无法写入通知测试配置: " + str(target))
    original = target.read_text(encoding="utf-8", errors="replace")
    updated = external.update_yaml_scalars(original, {"interval-seconds": "0"})
    updated = replace_notify_block(updated)
    target.write_text(updated, encoding="utf-8")
    return target


def enable_client_subtitles(case: dict) -> None:
    """在客户端启动前打开声音字幕和主要音量，便于截图辅助判断 Sound。"""
    game_dir = base.CLIENT_CACHE / "game-dirs" / case["id"]
    game_dir.mkdir(parents=True, exist_ok=True)
    path = game_dir / "options.txt"
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                values[key] = value
    values["showSubtitles"] = "true"
    values["soundCategory_master"] = "1.0"
    values["soundCategory_players"] = "1.0"
    values["soundCategory_neutral"] = "1.0"
    path.write_text("\n".join(key + ":" + value for key, value in values.items()) + "\n", encoding="utf-8")


def run_console(process, command_log: Path, command: str, wait: float = 0.25) -> None:
    """发送服务端控制台命令并短暂等待。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家权限、位置和基础游戏规则。"""
    commands = [
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
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


def reload_plugin(process, command_log: Path, server_log: Path) -> None:
    """重载插件配置并等待配置生效。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, "blwtc reload", 0.5)
    external.wait_command_markers(server_log, offset, ["[Message]"], 12, "blwtc reload")


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> Path:
    """截取 F2 截图并复制为稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + name + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def ensure_ingame_view(case: dict) -> None:
    """尽量把客户端从暂停菜单切回正常游戏画面。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.click_game(hwnd, rect, 0.50, 0.29)
    time.sleep(0.2)
    base.click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.4)


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


def render_server_log_screenshot(text: str, target: Path, title: str) -> Path:
    """把服务端关键日志渲染成 PNG 截图证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    useful = []
    for line in external.strip_ansi(text).splitlines():
        if "AI_WTC_NOTIFY" in line or "debugNotify" in line or "BLWorldTrashCan" in line or "AI_NOTIFY" in line:
            useful.append(line)
    lines.extend(useful[-36:] if useful else external.strip_ansi(text).splitlines()[-36:])
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


def wait_client_chat(client_log: Path, offset: int, markers: list[str], timeout: float) -> dict:
    """等待真实客户端日志出现聊天通知标记。"""
    deadline = time.time() + timeout
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        if all(marker in text for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": text[-1600:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": markers, "excerpt": last_text[-1600:]}


def wait_notify_server_markers(server_log: Path, offset: int, count: int, timeout: float) -> dict:
    """等待服务端出现 debugnotify 和 command 通知证据。"""
    command_marker = "AI_WTC_NOTIFY_COMMAND_0" if count == 0 else "AI_WTC_NOTIFY_COMMAND_MINUS5"
    markers = ["debugNotify count=" + str(count), command_marker]
    deadline = time.time() + timeout
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(server_log, offset)
        if text:
            last_text = text
        plain = external.strip_ansi(text)
        if "Unknown or incomplete command" in plain or "Unknown command" in plain:
            return {"status": "FAIL", "markers": markers, "excerpt": plain[-2000:], "reason": "command rejected"}
        if all(marker in plain for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": plain[-2400:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": markers, "excerpt": external.strip_ansi(last_text)[-2400:], "reason": "timeout"}


def trigger_notify(case: dict, process, server_log: Path, command_log: Path, game_dir: Path,
                   run_dir: Path, count: int) -> dict:
    """触发一次正式清理通知链路并保存客户端和服务端证据。"""
    label = "count0" if count == 0 else "minus5"
    ensure_ingame_view(case)
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    client_offset = external.log_text_offset(client_log)
    server_offset = external.log_text_offset(server_log)
    run_console(process, command_log, "blwtc debugnotify " + str(count), 0.8)
    server_check = wait_notify_server_markers(server_log, server_offset, count, 12)
    time.sleep(0.8)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "notify-" + label + "-f2")
    if count == 0:
        chat_check = wait_client_chat(client_log, client_offset, ["AI_NOTIFY_CHAT_0"], 8)
        visual_markers = ["AI_NOTIFY_CHAT_0", "AI_ACTIONBAR_0", "AI_BOSSBAR_0", "AI_TITLE_0"]
    else:
        chat_check = wait_client_chat(client_log, client_offset, ["AI_NOTIFY_CHAT_MINUS5"], 8)
        visual_markers = ["AI_NOTIFY_CHAT_MINUS5", "AI_ACTIONBAR_MINUS5", "AI_BOSSBAR_MINUS5", "AI_TITLE_MINUS5"]
    text = external.read_text_since(server_log, server_offset)
    server_shot = render_server_log_screenshot(
        text,
        run_dir / "server-screenshots" / (case["id"] + "-notify-" + label + "-server-log.png"),
        case["label"] + " / debugnotify " + str(count),
    )
    return {
        "count": count,
        "label": label,
        "serverCheck": server_check,
        "clientChatCheck": chat_check,
        "visualMarkersExpected": visual_markers,
        "soundEvidence": "客户端已打开字幕并触发 sound 配置；若截图没有声音字幕，只能证明触发链路无异常，不能替代人工听感。",
        "clientScreenshot": screenshot_info(screenshot),
        "serverScreenshot": screenshot_info(server_shot),
    }


def copy_runtime_config(case: dict, run_dir: Path) -> Path:
    """归档当前运行时 cleanup.yml。"""
    source = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    target = run_dir / "logs" / "config-after-patch" / "cleanup.yml"
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_file():
        shutil.copy2(source, target)
    return target


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端清理通知真实客户端截图测试。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始清理通知用例 " + case["id"] + " / " + case["label"])
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
        enable_client_subtitles(case)
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(backup_file(Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml", backup_dir))
        write_notify_config(case)
        result["patchedCleanupConfig"] = str(copy_runtime_config(case, run_dir))
        reload_plugin(process, command_log, server_log)
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
        result["checks"].append(trigger_notify(case, process, server_log, command_log, game_dir, run_dir, 0))
        time.sleep(1.2)
        result["checks"].append(trigger_notify(case, process, server_log, command_log, game_dir, run_dir, -5))
        failed = []
        for item in result["checks"]:
            if item["serverCheck"]["status"] != "PASS" or item["clientChatCheck"]["status"] != "PASS":
                failed.append(item["label"])
            if item["clientScreenshot"]["brightness"] <= 3 or item["serverScreenshot"]["brightness"] <= 3:
                failed.append(item["label"] + "-blank-screenshot")
        result["status"] = "PASS" if not failed else "FAIL"
        result["failedChecks"] = failed
    except Exception as error:
        result["error"] = repr(error)
        log("清理通知用例失败 " + case["id"] + ": " + repr(error))
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
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path | None:
    """生成清理通知客户端和服务端截图总览图。"""
    screenshots = []
    for result in results:
        for item in result.get("checks", []):
            screenshots.append((result["label"] + " " + item["label"] + " client", Path(item["clientScreenshot"]["path"])))
            screenshots.append((result["label"] + " " + item["label"] + " server", Path(item["serverScreenshot"]["path"])))
    if not screenshots:
        return None
    thumbs = []
    for label, path in screenshots:
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 290), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 250), label[:58], fill=(226, 232, 240), font=font())
        draw.text((10, 270), path.name[:58], fill=(148, 163, 184), font=font())
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 290), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 290))
    target = evidence_root / "cleanup-notify-contact-sheet.png"
    sheet.save(target)
    return target


def main() -> int:
    """运行清理通知真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "cleanup-notify-visual-" + time.strftime("%Y%m%d-%H%M%S")
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
    log("清理通知矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
