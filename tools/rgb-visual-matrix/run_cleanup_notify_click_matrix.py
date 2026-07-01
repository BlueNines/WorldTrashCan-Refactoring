import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

from PIL import Image

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
    raise RuntimeError("未知清理通知点击测试用例: " + case_id)


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
    """生成 F-058 点击专项使用的 notify 配置块。"""
    return (
        "notify:\n"
        "  # AI 自动化 F-058 点击验收临时配置，测试结束后脚本会恢复原文件。\n"
        "  chat:\n"
        "    # 是否启用聊天提醒。\n"
        "    enabled: true\n"
        "    # 是否同步输出到控制台。\n"
        "    console-log: true\n"
        "    # 清理完成消息点击后执行的命令。本专项用 stats 输出证明点击真的执行。\n"
        "    click-command: \"/blwtc stats\"\n"
        "    # 倒计时聊天提醒，格式：剩余秒数;内容。\n"
        "    messages:\n"
        "      - \"0;&#5AC8FAAI_CLICK_NOTIFY_0 &#FFD166点我执行 /blwtc stats\"\n"
        "      - \"-5;&#FF4F00AI_CLICK_NOTIFY_MINUS5 不参与 F-058 点击验收\"\n"
        "\n"
        "  actionbar:\n"
        "    # 本专项只验证 Chat 可点击组件。\n"
        "    enabled: false\n"
        "    messages: []\n"
        "\n"
        "  bossbar:\n"
        "    # 本专项只验证 Chat 可点击组件。\n"
        "    enabled: false\n"
        "    messages: []\n"
        "\n"
        "  title:\n"
        "    # 本专项只验证 Chat 可点击组件。\n"
        "    enabled: false\n"
        "    messages: []\n"
        "\n"
        "  sound:\n"
        "    # 本专项只验证 Chat 可点击组件。\n"
        "    enabled: false\n"
        "    messages: []\n"
        "\n"
        "  command:\n"
        "    # 本专项不使用服务端 command 通知，避免与点击命令混淆。\n"
        "    enabled: false\n"
        "    commands: []\n"
    )


def replace_notify_block(text: str) -> str:
    """替换 cleanup.yml 中的 notify 根块，保留前面的扫地配置。"""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.strip() == "notify:" and (len(line) - len(line.lstrip(" "))) == 0:
            start = index
            break
    prefix = text.rstrip() + "\n\n" if start is None else "\n".join(lines[:start]).rstrip() + "\n\n"
    return prefix + notification_block()


def write_notify_config(case: dict) -> Path:
    """写入本轮点击专项配置。"""
    target = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    if not target.is_file():
        raise RuntimeError("cleanup.yml 不存在，无法写入通知点击配置: " + str(target))
    original = target.read_text(encoding="utf-8", errors="replace")
    updated = external.update_yaml_scalars(original, {"interval-seconds": "0"})
    updated = replace_notify_block(updated)
    target.write_text(updated, encoding="utf-8")
    return target


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


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> Path:
    """截取 F2 截图并复制为稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + name + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def wait_client_markers(client_log: Path, offset: int, markers: list[str], timeout: float) -> dict:
    """等待客户端日志出现任意一组目标标记。"""
    deadline = time.time() + timeout
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        plain = external.strip_ansi(text)
        if all(marker in plain for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": plain[-1800:]}
        time.sleep(0.35)
    return {"status": "FAIL", "markers": markers, "excerpt": external.strip_ansi(last_text)[-1800:]}


def wait_stats_output(client_log: Path, offset: int, timeout: float) -> dict:
    """等待点击命令产生 /blwtc stats 输出。"""
    deadline = time.time() + timeout
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        plain = external.strip_ansi(text)
        if ("清理统计" in plain or "Cleanup statistics" in plain) and ("公共垃圾桶" in plain or "global trash" in plain.lower()):
            return {"status": "PASS", "excerpt": plain[-2200:]}
        time.sleep(0.35)
    return {"status": "FAIL", "excerpt": external.strip_ansi(last_text)[-2200:]}


def open_chat(case: dict) -> tuple[int, tuple[int, int, int, int]]:
    """打开客户端聊天界面，准备点击历史聊天行。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.click_game(hwnd, rect, 0.50, 0.34)
    base.post_key(hwnd, ord("T"))
    time.sleep(0.35)
    return hwnd, rect


def find_notify_pixel_target(path: Path) -> tuple[float, float] | None:
    """从聊天截图中定位 AI_CLICK_NOTIFY 的蓝色文字中心。"""
    image = Image.open(path).convert("RGB")
    width, height = image.size
    rows = []
    pixels_by_y = {}
    for y in range(int(height * 0.45), int(height * 0.88)):
        xs = []
        for x in range(0, int(width * 0.72)):
            red, green, blue = image.getpixel((x, y))
            if red < 105 and green > 115 and blue > 155 and blue - red > 80:
                xs.append(x)
        if len(xs) >= 2:
            rows.append(y)
            pixels_by_y[y] = xs
    if not rows:
        return None
    clusters = []
    current = []
    last = None
    for y in rows:
        if last is None or y - last <= 2:
            current.append(y)
        else:
            clusters.append(current)
            current = [y]
        last = y
    if current:
        clusters.append(current)
    target = clusters[-1]
    xs = [x for y in target for x in pixels_by_y[y]]
    x_min = min(xs)
    x_max = max(xs)
    y_min = min(target)
    y_max = max(target)
    click_x = (x_min + x_max) / 2.0 / width
    click_y = (y_min + y_max) / 2.0 / height
    return click_x, click_y


def click_chat_notification(case: dict, client_log: Path, stats_offset: int, run_dir: Path) -> dict:
    """真实点击聊天通知并等待点击命令输出。"""
    hwnd, rect = open_chat(case)
    chat_open = run_dir / "screenshots" / (case["id"] + "-chat-open-before-click.png")
    base.capture_rect(rect, chat_open)
    attempts = []
    target = find_notify_pixel_target(chat_open)
    if target is not None:
        x_ratio, y_ratio = target
        base.click_game(hwnd, rect, x_ratio, y_ratio)
        attempts.append({"xRatio": x_ratio, "yRatio": y_ratio, "source": "pixel-target"})
        check = wait_stats_output(client_log, stats_offset, 1.8)
        if check["status"] == "PASS":
            return {"status": "PASS", "attempts": attempts, "statsCheck": check}
        hwnd, rect = open_chat(case)
    # 聊天输入框上方的最近消息在窗口底部偏上，跨 GUI 缩放用多点尝试。
    for y_ratio in (0.76, 0.74, 0.78, 0.72, 0.80, 0.84, 0.85, 0.88, 0.91, 0.69):
        for x_ratio in (0.08, 0.16, 0.24, 0.32, 0.44):
            base.click_game(hwnd, rect, x_ratio, y_ratio)
            attempts.append({"xRatio": x_ratio, "yRatio": y_ratio, "source": "grid"})
            check = wait_stats_output(client_log, stats_offset, 1.2)
            if check["status"] == "PASS":
                return {"status": "PASS", "attempts": attempts, "statsCheck": check}
            # 如果点击没有命中且聊天被关闭，重新打开聊天界面继续尝试。
            hwnd, rect = open_chat(case)
    return {"status": "FAIL", "attempts": attempts, "statsCheck": wait_stats_output(client_log, stats_offset, 1.0)}


def copy_runtime_config(case: dict, run_dir: Path) -> str:
    """归档当前运行时 cleanup.yml。"""
    source = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    target = run_dir / "logs" / "config-after-patch" / "cleanup.yml"
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        return str(target)
    return ""


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端 F-058 真实点击测试。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始清理通知点击用例 " + case["id"] + " / " + case["label"])
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
    }
    try:
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(backup_file(Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml", backup_dir))
        write_notify_config(case)
        result["patchedCleanupConfig"] = copy_runtime_config(case, run_dir)
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
        client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
        notify_offset = external.log_text_offset(client_log)
        stats_offset = notify_offset
        server_offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc debugnotify 0", 0.8)
        external.wait_command_markers(server_log, server_offset, ["debugNotify count=0"], 12, "blwtc debugnotify 0")
        notify_check = wait_client_markers(client_log, notify_offset, ["AI_CLICK_NOTIFY_0"], 8)
        result["notifyCheck"] = notify_check
        before = capture_named_screenshot(case, game_dir, run_dir, "notify-before-click-f2")
        result["beforeClickScreenshot"] = screenshot_info(before)
        click_check = click_chat_notification(case, client_log, stats_offset, run_dir)
        result["clickCheck"] = click_check
        after = capture_named_screenshot(case, game_dir, run_dir, "stats-after-click-f2")
        result["afterClickScreenshot"] = screenshot_info(after)
        if notify_check["status"] == "PASS" and click_check["status"] == "PASS" and result["afterClickScreenshot"]["brightness"] > 3:
            result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("清理通知点击用例失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                failure = capture_named_screenshot(case, game_dir, run_dir, "failure-f2")
                result["failureScreenshot"] = screenshot_info(failure)
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


def main() -> int:
    """运行 F-058 清理通知点击矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "cleanup-notify-click-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        result = run_case(case, prepared_clients, evidence_root)
        results.append(result)
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results})
    summary = {
        "run": run_id,
        "results": results,
        "allPassed": all(item.get("status") == "PASS" for item in results),
    }
    write_json(evidence_root / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("清理通知点击矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
