import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

import pyautogui
from PIL import Image, ImageDraw, ImageFont

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TARGET_CASE_IDS = [
    "external_spigot2612",
    "managed_paper1122",
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
    raise RuntimeError("未知 GUI 点击测试用例: " + case_id)


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


def replace_yaml_list(text: str, path: str, values: list[str]) -> str:
    """替换简单 YAML 列表，保持其它配置不变。"""
    parts = path.split(".")
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
        key = stripped.split(":", 1)[0].strip()
        current = [item[1] for item in stack] + [key]
        if current == parts:
            target_index = index
            target_indent = indent
            break
        if stripped.endswith(":"):
            stack.append((indent, key))
    if target_index is None:
        return text.rstrip() + "\n" + path + ":\n"
    end_index = target_index + 1
    while end_index < len(lines):
        line = lines[end_index]
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            indent = len(line) - len(line.lstrip(" "))
            if indent <= target_indent:
                break
        end_index += 1
    replacement = [" " * target_indent + parts[-1] + ":\n"]
    for value in values:
        replacement.append(" " * (target_indent + 2) + "- \"" + value + "\"\n")
    return "".join(lines[:target_index] + replacement + lines[end_index:])


def patch_trash_config(case: dict) -> Path:
    """写入 GUI 点击专项需要的运行时 trash.yml 配置。"""
    target = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml"
    if not target.is_file():
        raise RuntimeError("trash.yml 不存在，无法写入 GUI 点击测试配置: " + str(target))
    text = target.read_text(encoding="utf-8", errors="replace")
    text = external.update_yaml_scalars(text, {
        "global-trash.max-pages": "5",
        "global-trash.take-delay-millis": "60000",
        "global-trash.allow-player-put": "true",
        "global-trash.log-enabled": "true",
        "personal-trash.enabled": "true",
        "personal-trash.auto-clear-when-full": "true",
        "personal-trash.take-cost": "-1",
    })
    text = replace_yaml_list(text, "global-trash.banned-materials", [])
    target.write_text(text, encoding="utf-8")
    return target


def run_console(process, command_log: Path, command: str, wait: float = 0.25) -> None:
    """发送服务端控制台命令并短暂等待。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def wait_markers(server_log: Path, offset: int, markers: list[str], timeout: float, command: str) -> str:
    """等待服务端日志出现指定标记。"""
    return external.wait_command_markers(server_log, offset, markers, timeout, command)


def run_checked_console(process, server_log: Path, command_log: Path, command: str,
                        markers: list[str], timeout: float = 12.0) -> dict:
    """执行控制台命令并返回日志校验结果。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, 0.4)
    text = wait_markers(server_log, offset, markers, timeout, command) if markers else external.read_text_since(server_log, offset)
    return {
        "command": command,
        "markers": markers,
        "excerpt": external.strip_ansi(text)[-2400:],
    }


def run_console_capture(process, server_log: Path, command_log: Path, command: str,
                        wait: float = 0.8) -> str:
    """执行控制台命令并返回新增日志。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, wait)
    return external.strip_ansi(external.read_text_since(server_log, offset))


def wait_global_log(case: dict, username: str, markers: list[str], timeout: float = 8.0) -> str:
    """等待公共垃圾桶操作日志出现指定玩家和动作标记。"""
    deadline = time.time() + timeout
    text = ""
    while time.time() < deadline:
        text = read_global_trash_log(case, username)
        if all(marker in text for marker in markers):
            return text
        time.sleep(0.4)
    return text


def wait_client_log_markers(client_log: Path, offset: int, markers: list[str], timeout: float = 8.0) -> dict:
    """等待真实客户端日志出现聊天消息标记。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last = text
        if all(marker in text for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": text[-1600:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": markers, "excerpt": last[-1600:]}


def wait_client_log_marker_sets(client_log: Path, offset: int, marker_sets: list[list[str]], timeout: float = 8.0) -> dict:
    """等待真实客户端日志命中任意一组聊天消息标记。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last = text
        for markers in marker_sets:
            if all(marker in text for marker in markers):
                return {"status": "PASS", "markers": markers, "excerpt": text[-1600:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": marker_sets, "excerpt": last[-1600:]}


def personal_summary_amount(text: str, expected_amount: int) -> bool:
    """判断 debugsummary 输出是否包含预期个人垃圾桶物品数量。"""
    return debug_summary_amounts(text)["personal"] == expected_amount


def debug_summary_amounts(text: str) -> dict:
    """解析 debugsummary 中的公共和个人垃圾桶物品数。"""
    plain = external.strip_ansi(text)
    result = {"global": -1, "personal": -1}
    for line in plain.splitlines():
        if "公共垃圾桶物品:" in line:
            result["global"] = parse_first_int_after_marker(line, "公共垃圾桶物品:")
        if "个人垃圾桶物品:" in line:
            result["personal"] = parse_first_int_after_marker(line, "个人垃圾桶物品:")
    if result["global"] < 0 or result["personal"] < 0:
        fallback = debug_summary_amounts_by_order(plain)
        if result["global"] < 0:
            result["global"] = fallback["global"]
        if result["personal"] < 0:
            result["personal"] = fallback["personal"]
    return result


def debug_summary_amounts_by_order(text: str) -> dict:
    """兼容 1.12 控制台中文乱码时按 debugsummary 固定行序解析库存。"""
    values = []
    in_summary = False
    for line in text.splitlines():
        if "WorldListTrashCan debug summary" in line:
            in_summary = True
            values = []
            continue
        if not in_summary or "- " not in line:
            continue
        values.append(parse_first_int_after_last_colon(line))
    if len(values) >= 5:
        return {"global": values[-2], "personal": values[-1]}
    return {"global": -1, "personal": -1}


def parse_first_int_after_marker(line: str, marker: str) -> int:
    """解析指定标记后的第一个整数。"""
    text = line.split(marker, 1)[1] if marker in line else line
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        elif digits:
            break
    return int(digits) if digits else -1


def parse_first_int_after_last_colon(line: str) -> int:
    """解析最后一个冒号后的第一个整数，避开日志时间戳。"""
    text = line.rsplit(":", 1)[1] if ":" in line else line
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        elif digits:
            break
    return int(digits) if digits else -1


def fill_route_stacks(process, server_log: Path, command_log: Path, username: str,
                      route: str, material: str, stacks: int) -> dict:
    """用多次 64 堆路由填充垃圾桶，规避 debugroute 单次数量上限。"""
    offset = external.log_text_offset(server_log)
    for _ in range(stacks):
        run_console(process, command_log, "wtc debugroute " + username + " " + route + " " + material + " 64", 0.04)
    deadline = time.time() + max(12.0, stacks * 0.4)
    text = ""
    while time.time() < deadline:
        text = external.strip_ansi(external.read_text_since(server_log, offset))
        if text.count("debugRoute player=" + username) >= stacks and "routed=false" not in text:
            return {"status": "PASS", "stacks": stacks, "excerpt": text[-2400:]}
        time.sleep(0.4)
    return {"status": "FAIL", "stacks": stacks, "excerpt": text[-2400:]}


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家权限、位置和基础游戏规则。"""
    gamemode = "minecraft:gamemode 0 " + username if is_legacy(case) else "minecraft:gamemode survival " + username
    commands = [
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule keepInventory true",
        "minecraft:gamerule fallDamage false",
        "minecraft:time set day",
        "minecraft:weather clear",
        "minecraft:fill -3 90 -11 3 90 -5 stone",
        gamemode,
        "minecraft:tp " + username + " 0 91 -8 0 15",
        "minecraft:effect " + username + " minecraft:resistance 1000000 255 true",
        "minecraft:effect " + username + " minecraft:saturation 1000000 1 true",
        "minecraft:clear " + username,
    ]
    for command in commands:
        run_console(process, command_log, command, 0.25)
    time.sleep(1.0)


def reload_plugin(process, command_log: Path, server_log: Path) -> None:
    """重载插件配置并等待配置生效。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, "wtc reload", 0.5)
    external.wait_command_markers(server_log, offset, ["[Message]"], 12, "wtc reload")


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


def chest_geometry(rect: tuple[int, int, int, int], rows: int = 6) -> dict:
    """按 Minecraft 原版箱子 GUI 尺寸计算槽位坐标。"""
    left, top, right, bottom = rect
    width = right - left
    height = bottom - top
    scale = max(1, int(min(width / 320.0, height / 240.0)))
    gui_width = 176 * scale
    gui_height = (114 + rows * 18) * scale
    gui_left = left + (width - gui_width) / 2.0
    gui_top = top + (height - gui_height) / 2.0
    return {
        "scale": scale,
        "left": gui_left,
        "top": gui_top,
        "rows": rows,
    }


def slot_center(rect: tuple[int, int, int, int], group: str, row: int, column: int) -> tuple[int, int]:
    """返回指定箱子 GUI 槽位中心点的屏幕坐标。"""
    geometry = chest_geometry(rect, 6)
    scale = geometry["scale"]
    x = geometry["left"] + (8 + column * 18 + 9) * scale
    if group == "top":
        y = geometry["top"] + (18 + row * 18 + 9) * scale
    elif group == "player":
        y = geometry["top"] + (6 * 18 + 31 + row * 18 + 9) * scale
    elif group == "hotbar":
        y = geometry["top"] + (6 * 18 + 89 + 9) * scale
    else:
        raise ValueError("未知槽位分组: " + group)
    return int(x), int(y)


def click_point(hwnd: int, rect: tuple[int, int, int, int], x: int, y: int) -> None:
    """点击窗口内指定屏幕坐标。"""
    left, top, right, bottom = rect
    x_ratio = (x - left) / max(1, right - left)
    y_ratio = (y - top) / max(1, bottom - top)
    base.click_game(hwnd, rect, x_ratio, y_ratio)


def click_slot(case: dict, group: str, row: int, column: int) -> None:
    """点击当前打开 GUI 的指定槽位。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = slot_center(rect, group, row, column)
    click_point(hwnd, rect, x, y)


def close_gui(case: dict) -> None:
    """关闭当前 GUI 并等待服务端处理关闭事件。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.focus_window(hwnd)
    base.post_key(hwnd, 0x1B)
    time.sleep(0.1)
    pyautogui.press("esc")
    time.sleep(1.0)


def send_client_command(case: dict, game_dir: Path, run_dir: Path, command: str,
                        suffix: str, wait: float = 1.0) -> dict:
    """让真实客户端执行玩家命令并截图。"""
    return external.send_client_command(case, game_dir, run_dir, command, suffix, wait)


def open_trash_gui(case: dict, username: str, process, command_log: Path,
                   run_dir: Path, game_dir: Path, kind: str, screenshot_name: str) -> dict:
    """通过后台测试入口打开公共或个人垃圾桶 GUI，并用真实客户端截图。"""
    run_console(process, command_log, "wtc debugopen " + username + " " + kind, 1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, screenshot_name)
    info = screenshot_info(screenshot)
    return {
        "name": screenshot_name,
        "command": "wtc debugopen " + username + " " + kind,
        "status": "PASS" if info["brightness"] > 3 else "FAIL",
        "screenshot": info,
    }


def open_player_debug_gui(case: dict, username: str, process, command_log: Path,
                          run_dir: Path, game_dir: Path, action: str, screenshot_name: str) -> dict:
    """通过后台玩家入口打开需要玩家对象的 GUI，并用真实客户端截图。"""
    run_console(process, command_log, "wtc debugplayer " + username + " " + action, 1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, screenshot_name)
    info = screenshot_info(screenshot)
    return {
        "name": screenshot_name,
        "command": "wtc debugplayer " + username + " " + action,
        "status": "PASS" if info["brightness"] > 3 else "FAIL",
        "screenshot": info,
    }


def give_item(case: dict, process, command_log: Path, username: str, material: str, amount: int) -> None:
    """使用原版 give 命令给玩家准备背包物品。"""
    run_console(process, command_log, "minecraft:clear " + username, 0.25)
    command = "minecraft:give " + username + " " + material + " " + str(amount)
    run_console(process, command_log, command, 0.8)


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def render_text_screenshot(text: str, target: Path, title: str) -> Path:
    """把关键日志或配置摘录渲染为 PNG 截图证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    lines.extend(external.strip_ansi(text).splitlines()[-38:])
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


def read_global_trash_log(case: dict, username: str) -> str:
    """读取公共垃圾桶操作日志中当前玩家相关行。"""
    log_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "logs"
    lines = []
    for path in sorted(log_dir.glob("global-trash-*.log")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            if username in line:
                lines.append(path.name + " " + line)
    return "\n".join(lines[-80:])


def copy_runtime_file(source: Path, target: Path) -> str:
    """归档一个运行时文件，文件不存在时返回空字符串。"""
    if not source.is_file():
        return ""
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return str(target)


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_runtime_evidence(case: dict, run_dir: Path) -> None:
    """复制本轮运行态日志和配置证据。"""
    server_dir = Path(case["serverDir"])
    plugin_dir = server_dir / "plugins" / "WorldListTrashCan"
    copy_runtime_file(server_dir / "logs" / "latest.log", run_dir / "logs" / "latest.log")
    copy_runtime_file(plugin_dir / "trash.yml", run_dir / "config" / "trash-after-restore.yml")
    copy_runtime_file(plugin_dir / "messages" / "message_zh.yml", run_dir / "config" / "message_zh.yml")
    copy_runtime_file(plugin_dir / "config.yml", run_dir / "config" / "config.yml")


def verify_global_ban(case: dict, server_log: Path, process, command_log: Path, username: str) -> dict:
    """验证公共黑名单保存后立即影响公共垃圾桶路由。"""
    trash_file = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml"
    trash_text = trash_file.read_text(encoding="utf-8", errors="replace") if trash_file.is_file() else ""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, "wtc debugroute " + username + " global STONE 1", 0.6)
    text = external.read_text_since(server_log, offset)
    deadline = time.time() + 12
    while time.time() < deadline and "debugRoute" not in text:
        time.sleep(0.4)
        text = external.read_text_since(server_log, offset)
    plain = external.strip_ansi(text)
    return {
        "trashContainsStone": "STONE" in trash_text,
        "routeRejected": "routed=false" in plain,
        "excerpt": plain[-2400:],
    }


def run_public_gui_checks(case: dict, username: str, process, server_log: Path,
                          command_log: Path, run_dir: Path, game_dir: Path) -> list[dict]:
    """运行公共垃圾桶 GUI 放入、取出、冷却、分页和日志检查。"""
    checks = []
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    give_item(case, process, command_log, username, "cobblestone", 7)
    checks.append(open_trash_gui(case, username, process, command_log, run_dir, game_dir, "global", "global-put-before-f2"))
    click_slot(case, "hotbar", 0, 0)
    put_after = capture_named_screenshot(case, game_dir, run_dir, "global-put-after-click-f2")
    put_log = wait_global_log(case, username, ["+global", "COBBLESTONEx7"], 8)
    checks.append({
        "name": "F-027",
        "status": "PASS" if "+global" in put_log and "COBBLESTONEx7" in put_log else "FAIL",
        "screenshot": screenshot_info(put_after),
        "logExcerpt": put_log[-1600:],
    })
    click_slot(case, "top", 0, 0)
    take_after = capture_named_screenshot(case, game_dir, run_dir, "global-take-after-click-f2")
    take_log = wait_global_log(case, username, ["+global", "-global", "COBBLESTONEx7"], 8)
    checks.append({
        "name": "F-026",
        "status": "PASS" if "-global" in take_log and "COBBLESTONEx7" in take_log else "FAIL",
        "screenshot": screenshot_info(take_after),
        "logExcerpt": take_log[-1600:],
    })
    client_offset = external.log_text_offset(client_log)
    click_slot(case, "top", 0, 1)
    cooldown = capture_named_screenshot(case, game_dir, run_dir, "global-take-cooldown-f2")
    cooldown_log = wait_client_log_marker_sets(client_log, client_offset, [
        ["公共垃圾桶拿取冷却"],
        ["公共垃圾桶冷却"],
    ], 8)
    checks.append({
        "name": "F-028",
        "status": cooldown_log["status"],
        "screenshot": screenshot_info(cooldown),
        "clientLog": cooldown_log,
    })
    close_gui(case)
    stock_text = run_console_capture(process, server_log, command_log, "wtc debugstock", 0.8)
    stock_shot = render_text_screenshot(
        stock_text,
        run_dir / "server-screenshots" / (case["id"] + "-global-stock-after-put-take.png"),
        case["label"] + " / global stock after put and take",
    )
    checks.append({
        "name": "global-stock-after-put-take",
        "status": "PASS" if stock_text.strip() else "FAIL",
        "serverScreenshot": screenshot_info(stock_shot),
        "excerpt": stock_text[-1600:],
    })

    fill_result = fill_route_stacks(process, server_log, command_log, username, "global", "COBBLESTONE", 46)
    checks.append({"name": "global-fill-46-stacks", "status": fill_result["status"], "details": fill_result})
    checks.append(open_trash_gui(case, username, process, command_log, run_dir, game_dir, "global", "global-page-1-f2"))
    click_slot(case, "top", 5, 7)
    page_two = capture_named_screenshot(case, game_dir, run_dir, "global-page-2-after-next-f2")
    checks.append({"name": "F-024", "status": fill_result["status"], "screenshot": screenshot_info(page_two)})
    close_gui(case)

    global_log = read_global_trash_log(case, username)
    log_shot = render_text_screenshot(
        global_log,
        run_dir / "server-screenshots" / (case["id"] + "-global-trash-operation-log.png"),
        case["label"] + " / global-trash operation log",
    )
    checks.append({
        "name": "F-029",
        "status": "PASS" if "+global" in global_log and "-global" in global_log else "FAIL",
        "client": "公共垃圾桶取放截图已保存",
        "serverScreenshot": screenshot_info(log_shot),
        "logExcerpt": global_log[-2400:],
    })
    return checks


def run_personal_gui_checks(case: dict, username: str, process, server_log: Path,
                            command_log: Path, run_dir: Path, game_dir: Path) -> list[dict]:
    """运行个人垃圾桶 GUI 放入、取出和满桶自动清空检查。"""
    checks = []
    give_item(case, process, command_log, username, "stone", 5)
    checks.append(open_trash_gui(case, username, process, command_log, run_dir, game_dir, "personal", "personal-put-before-f2"))
    click_slot(case, "hotbar", 0, 0)
    put_after = capture_named_screenshot(case, game_dir, run_dir, "personal-put-after-click-f2")
    put_summary = run_console_capture(process, server_log, command_log, "wtc debugsummary " + username, 0.8)
    checks.append({
        "name": "F-035",
        "status": "PASS" if personal_summary_amount(put_summary, 5) else "FAIL",
        "screenshot": screenshot_info(put_after),
        "summaryExcerpt": put_summary[-1600:],
    })
    click_slot(case, "top", 0, 0)
    take_after = capture_named_screenshot(case, game_dir, run_dir, "personal-take-after-click-f2")
    take_summary = run_console_capture(process, server_log, command_log, "wtc debugsummary " + username, 0.8)
    checks.append({
        "name": "F-034",
        "status": "PASS" if personal_summary_amount(take_summary, 0) else "FAIL",
        "screenshot": screenshot_info(take_after),
        "summaryExcerpt": take_summary[-1600:],
    })
    close_gui(case)

    fill_result = fill_route_stacks(process, server_log, command_log, username, "personal", "STONE", 54)
    run_checked_console(process, server_log, command_log,
                        "wtc debugroute " + username + " personal DIRT 1",
                        ["[Debug] debugRoute", "routed=true"], 14)
    auto_clear_text = run_console_capture(process, server_log, command_log, "wtc debugsummary " + username, 0.8)
    summary_shot = render_text_screenshot(
        auto_clear_text,
        run_dir / "server-screenshots" / (case["id"] + "-personal-full-auto-clear-summary.png"),
        case["label"] + " / personal full auto clear summary",
    )
    checks.append(open_trash_gui(case, username, process, command_log, run_dir, game_dir, "personal",
                                 "personal-full-auto-clear-gui-f2"))
    close_gui(case)
    checks.append({
        "name": "F-036",
        "status": "PASS" if fill_result["status"] == "PASS" and personal_summary_amount(auto_clear_text, 1) else "FAIL",
        "summaryScreenshot": screenshot_info(summary_shot),
        "fillResult": fill_result,
        "summaryExcerpt": auto_clear_text[-1600:],
        "reason": "debugroute 先填满 54 个 STONE 堆叠，再放入 DIRT 1；个人桶自动清空旧内容后只保留新物品。",
    })
    return checks


def run_global_ban_gui_check(case: dict, username: str, process, server_log: Path,
                             command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """运行公共黑名单 GUI 保存并立即生效检查。"""
    give_item(case, process, command_log, username, "stone", 1)
    before = open_player_debug_gui(case, username, process, command_log, run_dir, game_dir, "globalban", "globalban-before-f2")
    click_slot(case, "hotbar", 0, 0)
    placed = capture_named_screenshot(case, game_dir, run_dir, "globalban-stone-placed-f2")
    close_gui(case)
    saved = capture_named_screenshot(case, game_dir, run_dir, "globalban-after-close-save-f2")
    verify = verify_global_ban(case, server_log, process, command_log, username)
    trash_copy = copy_runtime_file(
        Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml",
        run_dir / "logs" / "trash-after-globalban.yml",
    )
    server_shot = render_text_screenshot(
        verify["excerpt"],
        run_dir / "server-screenshots" / (case["id"] + "-globalban-route-rejected.png"),
        case["label"] + " / globalban route rejected",
    )
    return {
        "name": "F-030",
        "status": "PASS" if before["status"] == "PASS" and verify["trashContainsStone"] and verify["routeRejected"] else "FAIL",
        "beforeScreenshot": before["screenshot"],
        "placedScreenshot": screenshot_info(placed),
        "savedScreenshot": screenshot_info(saved),
        "serverScreenshot": screenshot_info(server_shot),
        "trashSnapshot": trash_copy,
        "verify": verify,
    }


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端 GUI 点击真实客户端截图测试。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始 GUI 点击用例 " + case["id"] + " / " + case["label"])
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
        trash_file = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml"
        backups.append(backup_file(trash_file, backup_dir))
        result["patchedTrashConfig"] = str(patch_trash_config(case))
        copy_runtime_file(trash_file, run_dir / "logs" / "trash-after-patch.yml")
        reload_plugin(process, command_log, server_log)
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        result["clientPid"] = client.pid
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)
        platform_offset = external.log_text_offset(server_log)
        run_console(process, command_log, "wtc platform", 0.5)
        external.wait_platform_command_accepted(server_log, platform_offset)
        result["checks"].extend(run_public_gui_checks(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].extend(run_personal_gui_checks(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_global_ban_gui_check(case, username, process, server_log, command_log, run_dir, game_dir))
        failed = [item.get("name", "?") for item in result["checks"] if item.get("status") != "PASS"]
        blank = []
        for item in result["checks"]:
            for key in ("screenshot", "clientScreenshot", "beforeScreenshot", "placedScreenshot", "savedScreenshot", "summaryScreenshot", "serverScreenshot"):
                value = item.get(key)
                if isinstance(value, dict) and value.get("brightness", 4) <= 3:
                    blank.append(item.get("name", "?") + ":" + key)
        result["status"] = "PASS" if not failed and not blank else "FAIL"
        result["failedChecks"] = failed
        result["blankScreenshots"] = blank
    except Exception as error:
        result["error"] = repr(error)
        log("GUI 点击用例失败 " + case["id"] + ": " + repr(error))
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
            copy_runtime_evidence(case, run_dir)
            external.stop_process(process, "stop")
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path | None:
    """生成 GUI 点击截图总览图。"""
    screenshots = []
    for result in results:
        for item in result.get("checks", []):
            for key in ("screenshot", "clientScreenshot", "beforeScreenshot", "placedScreenshot", "savedScreenshot", "summaryScreenshot", "serverScreenshot"):
                value = item.get(key)
                if isinstance(value, dict) and value.get("path"):
                    screenshots.append((result["label"] + " " + item.get("name", "") + " " + key, Path(value["path"])))
                elif isinstance(value, str):
                    screenshots.append((result["label"] + " " + item.get("name", "") + " " + key, Path(value)))
    if not screenshots:
        return None
    thumbs = []
    used_font = font()
    for label, path in screenshots:
        if not path.is_file():
            continue
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 300), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 252), label[:58], fill=(226, 232, 240), font=used_font)
        draw.text((10, 274), path.name[:58], fill=(148, 163, 184), font=used_font)
        thumbs.append(canvas)
    if not thumbs:
        return None
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 300), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 300))
    target = evidence_root / "trash-gui-click-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, summary: dict) -> None:
    """生成 GUI 点击证据目录 README。"""
    environments = "、".join(
        item["label"] + " + 真实 " + str(item["clientVersion"]) + " 客户端"
        for item in summary["results"]
    )
    lines = [
        "# GUI 正向点击真实客户端专项",
        "",
        "- 被测 jar: `dist/WorldListTrashCan-universal.jar`",
        "- SHA256: `" + summary.get("jarSha256", "") + "`",
        "- 验收方式: " + environments + "。",
        "- 覆盖: 公共垃圾桶放入/取出/冷却/分页/操作日志，个人垃圾桶放入/取出/满桶自动清空，公共黑名单 GUI 保存并立即影响路由。",
        "- 通过标准: GUI 必须由真实客户端打开并截图，关键槽位必须由真实客户端点击，库存摘要、公共日志和路由结果必须匹配预期。",
        "- 结论: " + ("PASS" if summary.get("allPassed") else "FAIL"),
        "",
        "| 服务端 | 版本 | 状态 | 玩家 |",
        "| --- | --- | --- | --- |",
    ]
    for item in summary["results"]:
        lines.append("| " + item["label"] + " | " + str(item["clientVersion"]) + " | " + item["status"] + " | " + item.get("username", "") + " |")
    lines.extend([
        "",
        "## 证据",
        "",
        "- `summary.json`: 机器可读总结果。",
        "- `*/result.json`: 单端详细断言。",
        "- `*/screenshots/*-f2.png`: 真实客户端 GUI 打开与点击后的 F2 截图。",
        "- `*/server-screenshots/*.png`: 库存摘要、公共日志和路由拒绝等服务端可视化证据。",
        "- `*/logs/*server-console.log`、`*/logs/*console-commands.log`、`*/logs/latest.log`: 服务端运行日志和命令记录。",
        "- `trash-gui-click-contact-sheet.png`: 截图总览。",
        "",
    ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行垃圾桶 GUI 点击真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "trash-gui-click-visual-" + time.strftime("%Y%m%d-%H%M%S")
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
    jar_path = base.REPO / "dist" / "WorldListTrashCan-universal.jar"
    summary = {
        "run": run_id,
        "jar": str(jar_path),
        "jarSha256": sha256_file(jar_path),
        "results": results,
        "contactSheet": str(contact_sheet) if contact_sheet else "",
        "allPassed": all(item.get("status") == "PASS" for item in results),
    }
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("GUI 点击矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
