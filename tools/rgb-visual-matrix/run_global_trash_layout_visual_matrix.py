import argparse
import hashlib
import json
import re
import time
from pathlib import Path

import pyautogui
from PIL import Image

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base
import run_trash_gui_click_visual_matrix as gui


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
SOURCE_CASE_IDS = ["managed_paper1122", "external_folia1218"]


def log(message: str) -> None:
    """输出带时间戳的执行信息。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, value) -> None:
    """按 UTF-8 写入机器可读结果。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def selected_cases(case_id: str | None) -> list[dict]:
    """选择 1.12.2 与 Folia 1.21.8 的 universal 整包用例。"""
    cases = []
    for source_id in SOURCE_CASE_IDS:
        source = next(item for item in external.EXTERNAL_MATRIX if item["id"] == source_id)
        cases.append(external.universal_case(source))
    if not case_id:
        return cases
    for case in cases:
        if case_id in (case["id"], case.get("sourceId", ""), case["version"], case["label"]):
            return [case]
    raise RuntimeError("未知公共垃圾桶布局测试用例: " + case_id)


def replace_yaml_block(text: str, path: str, body_lines: list[str]) -> str:
    """替换一个简单 YAML 映射节点及其全部子节点。"""
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
        current = [entry[1] for entry in stack] + [key]
        if current == parts:
            target_index = index
            target_indent = indent
            break
        if stripped.endswith(":"):
            stack.append((indent, key))
    if target_index is None:
        raise RuntimeError("找不到 YAML 节点: " + path)
    end_index = target_index + 1
    while end_index < len(lines):
        stripped = lines[end_index].strip()
        if stripped and not stripped.startswith("#"):
            indent = len(lines[end_index]) - len(lines[end_index].lstrip(" "))
            if indent <= target_indent:
                break
        end_index += 1
    replacement = [" " * target_indent + parts[-1] + ":\n"]
    replacement.extend(" " * (target_indent + 2) + line + "\n" for line in body_lines)
    return "".join(lines[:target_index] + replacement + lines[end_index:])


def layout_body(rows: list[str]) -> list[str]:
    """生成带 RGB 名称、Lore 和跨版本材质降级的布局配置。"""
    result = ["layout:", "  position:"]
    result.extend("    - \"" + row + "\"" for row in rows)
    result.extend([
        "  items:",
        "    x:",
        "      type: \"content\"",
        "    a:",
        "      type: \"previous-page\"",
        "      model-id: -1",
        "      material:",
        "        - \"NOT_A_REAL_MATERIAL\"",
        "        - \"ARROW\"",
        "      unavailable-item: \"b\"",
        "      name: \"&#35B8FF上一页 {page}/{max-page}\"",
        "      lore:",
        "        - \"&#FFD166返回第 {previous-page} 页\"",
        "        - \"&8布局专项测试\"",
        "    b:",
        "      type: \"background\"",
        "      model-id: -1",
        "      material:",
        "        - \"BLACK_STAINED_GLASS_PANE\"",
        "        - \"STAINED_GLASS_PANE\"",
        "        - \"GLASS_PANE\"",
        "      name: \" \"",
        "      lore: []",
        "    c:",
        "      type: \"next-page\"",
        "      model-id: -1",
        "      material:",
        "        - \"NOT_A_REAL_MATERIAL\"",
        "        - \"ARROW\"",
        "      unavailable-item: \"b\"",
        "      name: \"&#35B8FF下一页 {page}/{max-page}\"",
        "      lore:",
        "        - \"&#FFD166前往第 {next-page} 页\"",
        "        - \"&7临时溢出页只可取出\"",
    ])
    return result


def patch_layout(case: dict, rows: list[str], max_pages: int) -> Path:
    """写入本轮布局并清空公共垃圾桶黑名单。"""
    target = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "trash.yml"
    if not target.is_file():
        raise RuntimeError("运行时 trash.yml 不存在: " + str(target))
    text = target.read_text(encoding="utf-8", errors="replace")
    text = external.update_yaml_scalars(text, {
        "global-trash.enabled": "true",
        "global-trash.max-pages": str(max_pages),
        "global-trash.take-delay-millis": "0",
        "global-trash.allow-player-put": "true",
        "global-trash.log-enabled": "true",
    })
    text = replace_yaml_block(text, "global-trash.gui", layout_body(rows))
    text = gui.replace_yaml_list(text, "global-trash.banned-materials", [])
    target.write_text(text, encoding="utf-8")
    return target


def reload_and_wait(case: dict, process, server_log: Path, command_log: Path,
                    expected_markers: list[str]) -> str:
    """重载插件并等待布局日志完整出现。"""
    offset = external.log_text_offset(server_log)
    gui.run_console(process, command_log, "blwtc reload", 0.6)
    return external.wait_command_markers(server_log, offset, expected_markers, 20, "blwtc reload")


def run_stock(process, server_log: Path, command_log: Path) -> str:
    """执行库存摘要并返回新增控制台文本。"""
    return gui.run_console_capture(process, server_log, command_log, "blwtc debugstock", 0.8)


def wait_stock(process, server_log: Path, command_log: Path,
               item_amount: int, stack_count: int, page_count: int,
               timeout: float = 15.0) -> str:
    """轮询库存摘要直到物品、堆叠和页数符合预期。"""
    deadline = time.time() + timeout
    last = ""
    stock_pattern = re.compile(r":\s*(?:§.)?" + str(item_amount)
                               + r"\s*\([^0-9]*" + str(stack_count) + r"\)")
    page_pattern = re.compile(r":\s*(?:§.)?" + str(page_count) + r"\s*$")
    while time.time() < deadline:
        last = run_stock(process, server_log, command_log)
        plain = external.strip_ansi(last)
        lines = plain.splitlines()
        for index, line in enumerate(lines):
            if stock_pattern.search(line) and any(page_pattern.search(candidate)
                                                  for candidate in lines[index + 1:index + 4]):
                return plain
        time.sleep(0.5)
    expected = {"items": item_amount, "stacks": stack_count, "pages": page_count}
    raise RuntimeError("公共垃圾桶库存未达到预期: " + repr(expected) + " latest=" + last[-1200:])


def route_stack(case: dict, username: str, process, server_log: Path,
                command_log: Path, material: str, expect_success: bool) -> str:
    """通过后台路由入口放入一整组物品并校验成功或失败。"""
    offset = external.log_text_offset(server_log)
    command = "blwtc debugroute " + username + " global " + material + " 64"
    gui.run_console(process, command_log, command, 0.5)
    deadline = time.time() + 12
    last = ""
    markers = [
        "debugRoute player=" + username,
        "material=" + material,
        "routed=" + str(expect_success).lower(),
    ]
    while time.time() < deadline:
        last = external.strip_ansi(external.read_text_since(server_log, offset))
        if all(marker in last for marker in markers):
            return last
        time.sleep(0.35)
    raise RuntimeError("路由结果不符合预期: " + command + " latest=" + last[-1200:])


def screenshot_info(path: Path) -> dict:
    """返回截图尺寸、亮度和哈希。"""
    image = Image.open(path)
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "dimensions": list(image.size),
        "brightness": base.image_brightness(image),
    }


def slot_center(rect: tuple[int, int, int, int], frame_size: tuple[int, int],
                rows: int, row: int, column: int) -> tuple[int, int]:
    """在游戏帧坐标系计算槽位，再映射到 Windows 客户区。"""
    frame_width, frame_height = frame_size
    gui_scale = max(1, int(min(frame_width / 320.0, frame_height / 240.0)))
    gui_width = 176 * gui_scale
    gui_height = (114 + rows * 18) * gui_scale
    gui_left = (frame_width - gui_width) / 2.0
    gui_top = (frame_height - gui_height) / 2.0
    frame_x = gui_left + (8 + column * 18 + 9) * gui_scale
    frame_y = gui_top + (18 + row * 18 + 9) * gui_scale
    left, top, right, bottom = rect
    x = left + frame_x / frame_width * (right - left)
    y = top + frame_y / frame_height * (bottom - top)
    return int(x), int(y)


def move_to_slot(case: dict, frame_size: tuple[int, int], rows: int,
                 row: int, column: int) -> None:
    """把真实鼠标移动到指定 GUI 槽位以显示 Tooltip。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = slot_center(rect, frame_size, rows, row, column)
    pyautogui.moveTo(x, y, duration=0.2)
    left, top, right, bottom = rect
    client_x = max(0, min(right - left - 1, x - left))
    client_y = max(0, min(bottom - top - 1, y - top))
    lparam = client_x | (client_y << 16)
    base.win32gui.PostMessage(hwnd, base.win32con.WM_MOUSEMOVE, 0, lparam)
    time.sleep(1.0)


def click_slot(case: dict, frame_size: tuple[int, int], rows: int,
               row: int, column: int) -> None:
    """让真实客户端点击动态行数 GUI 的指定槽位。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = slot_center(rect, frame_size, rows, row, column)
    gui.click_point(hwnd, rect, x, y)
    time.sleep(1.0)


def capture(case: dict, game_dir: Path, run_dir: Path, name: str) -> dict:
    """保存真实客户端 F2 截图并返回基础审计信息。"""
    path = gui.capture_named_screenshot(case, game_dir, run_dir, name)
    return screenshot_info(path)


def open_global(case: dict, username: str, process, command_log: Path) -> None:
    """通过后台入口为真实在线玩家打开公共垃圾桶。"""
    gui.run_console(process, command_log, "blwtc debugopen " + username + " global", 1.0)


def close_inventory(case: dict) -> None:
    """只发送一次 Esc 关闭 GUI，避免旧客户端紧接着打开暂停菜单。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.focus_window(hwnd)
    pyautogui.press("esc")
    time.sleep(1.0)


def seed_twelve_stacks(case: dict, username: str, process,
                       server_log: Path, command_log: Path) -> None:
    """向大布局写入十二组满堆叠测试物品。"""
    for _ in range(12):
        route_stack(case, username, process, server_log, command_log, "STONE", True)


def verify_screenshots(screenshots: dict) -> None:
    """拒绝空白或尺寸异常的客户端截图。"""
    for name, info in screenshots.items():
        if info["brightness"] <= 3:
            raise RuntimeError("截图疑似空白: " + name)
        if info["dimensions"][0] < 640 or info["dimensions"][1] < 360:
            raise RuntimeError("截图尺寸异常: " + name + " " + repr(info["dimensions"]))


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """执行单个服务端的布局、缩容和真实客户端交互验收。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "label": case["label"],
        "serverVersion": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "plugin": case["plugin"],
        "status": "FAIL",
        "screenshots": {},
        "checks": {},
    }
    process = None
    client = None
    backups = []
    game_dir = None
    try:
        log("启动布局专项: " + case["id"])
        process = external.launch_server(case, run_dir)
        trash_file = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "trash.yml"
        backups.append(gui.backup_file(trash_file, run_dir / "logs" / "config-backup"))
        patch_layout(case, ["xxxxxxxxx"] * 5 + ["abbbbbbbc"], 5)
        result["checks"]["largeReload"] = reload_and_wait(
            case, process, server_log, command_log,
            ["rows=6", "slots=54", "contentSlots=45", "writablePages=5", "visiblePages=5"])

        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        gui.setup_player(case, username, process, command_log)

        seed_twelve_stacks(case, username, process, server_log, command_log)
        result["checks"]["seedStock"] = wait_stock(process, server_log, command_log, 768, 12, 5)

        patch_layout(case, ["xxxxxxxxx", "abbbbbbbc"], 1)
        result["checks"]["shrinkReload"] = reload_and_wait(
            case, process, server_log, command_log,
            ["rows=2", "slots=18", "contentSlots=9", "writablePages=1", "visiblePages=2"])
        result["checks"]["shrinkStock"] = wait_stock(process, server_log, command_log, 768, 12, 2)

        open_global(case, username, process, command_log)
        result["screenshots"]["page1"] = capture(case, game_dir, run_dir, "layout-page-1")
        frame_size = tuple(result["screenshots"]["page1"]["dimensions"])
        move_to_slot(case, frame_size, 2, 1, 8)
        result["screenshots"]["nextTooltip"] = capture(case, game_dir, run_dir, "layout-next-tooltip")
        click_slot(case, frame_size, 2, 1, 8)
        result["screenshots"]["overflowPage"] = capture(case, game_dir, run_dir, "layout-overflow-page")
        move_to_slot(case, frame_size, 2, 1, 0)
        result["screenshots"]["previousTooltip"] = capture(case, game_dir, run_dir, "layout-previous-tooltip")
        close_inventory(case)

        result["checks"]["overflowReject"] = route_stack(
            case, username, process, server_log, command_log, "COBBLESTONE", False)
        result["checks"]["overflowRejectStock"] = wait_stock(
            process, server_log, command_log, 768, 12, 2)

        open_global(case, username, process, command_log)
        move_to_slot(case, frame_size, 2, 0, 0)
        result["screenshots"]["beforeTake"] = capture(
            case, game_dir, run_dir, "layout-before-take")
        click_slot(case, frame_size, 2, 0, 0)
        result["screenshots"]["afterTake"] = capture(
            case, game_dir, run_dir, "layout-after-take")
        close_inventory(case)
        result["checks"]["afterTakeStock"] = wait_stock(process, server_log, command_log, 704, 11, 2)
        result["checks"]["routeAfterTake"] = route_stack(
            case, username, process, server_log, command_log, "COBBLESTONE", True)
        result["checks"]["refilledStock"] = wait_stock(process, server_log, command_log, 768, 12, 2)
        open_global(case, username, process, command_log)
        result["screenshots"]["refilledNormalPage"] = capture(
            case, game_dir, run_dir, "layout-refilled-normal-page")
        close_inventory(case)

        patch_layout(case, ["xxxxxxxxx"] * 7, 1)
        result["checks"]["invalidFallbackReload"] = reload_and_wait(
            case, process, server_log, command_log,
            ["global-trash.gui.layout.position", "rows=6", "slots=54", "contentSlots=45"])
        result["checks"]["fallbackStock"] = wait_stock(process, server_log, command_log, 768, 12, 1)
        open_global(case, username, process, command_log)
        result["screenshots"]["invalidFallback"] = capture(
            case, game_dir, run_dir, "layout-invalid-fallback-six-rows")
        close_inventory(case)

        verify_screenshots(result["screenshots"])
        server_excerpt = "\n\n".join(str(value) for value in result["checks"].values())
        server_shot = gui.render_text_screenshot(
            server_excerpt,
            run_dir / "server-screenshots" / (case["id"] + "-layout-assertions.png"),
            case["label"] + " / global trash layout assertions",
        )
        result["serverScreenshot"] = screenshot_info(server_shot)
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("布局专项失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                result["failureScreenshot"] = capture(case, game_dir, run_dir, "layout-failure")
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            gui.restore_backups(backups)
            gui.copy_runtime_evidence(case, run_dir)
            external.stop_process(process, "stop")
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> str:
    """复用 GUI 验收工具生成客户端和服务端截图总览。"""
    adapted = []
    for result in results:
        checks = []
        for name, screenshot in result.get("screenshots", {}).items():
            checks.append({"name": name, "screenshot": screenshot})
        if result.get("serverScreenshot"):
            checks.append({"name": "serverAssertions", "serverScreenshot": result["serverScreenshot"]})
        adapted.append({"label": result["label"], "checks": checks})
    path = gui.make_contact_sheet(adapted, evidence_root)
    return str(path) if path else ""


def write_readme(evidence_root: Path, summary: dict) -> None:
    """生成专项证据说明。"""
    lines = [
        "# 公共垃圾桶字符布局真实客户端专项",
        "",
        "- 被测产物: `dist/BlWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 客户端: 真实 1.12.2 与真实 1.21.8 客户端。",
        "- 覆盖: 两行字符布局、材质候选降级、RGB/传统颜色名称与 Lore、页码占位符、真实翻页、缩容零丢失、临时溢出页不接收新物品、正常页释放容量后恢复写入、七行非法布局回退六行。",
        "- 结论: " + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
        "| 服务端 | 客户端 | 状态 |",
        "| --- | --- | --- |",
    ]
    for result in summary["results"]:
        lines.append("| " + result["label"] + " | " + result["clientVersion"] + " | " + result["status"] + " |")
    lines.extend([
        "",
        "## 证据说明",
        "",
        "- `*/screenshots/*layout*.png`: 真实客户端 F2 截图。",
        "- `*/server-screenshots/*layout-assertions.png`: 服务端布局日志、库存和路由断言。",
        "- `*/logs/*server-console.log`: 本轮独立服务端控制台日志。",
        "- `*/logs/*client*.log`: 本轮真实客户端日志。",
        "- `*/result.json` 与 `summary.json`: 机器可读断言。",
        "- `trash-gui-click-contact-sheet.png`: 截图总览。",
        "",
    ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行公共垃圾桶字符布局真实客户端矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default=None)
    args = parser.parse_args()
    cases = selected_cases(args.case)
    prepared_clients = {}
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
    run_id = "global-trash-layout-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    results = [run_case(case, prepared_clients, evidence_root) for case in cases]
    jar_path = base.REPO / "dist" / external.UNIVERSAL_PLUGIN
    summary = {
        "runId": run_id,
        "jar": str(jar_path),
        "jarSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(),
        "allPassed": all(result["status"] == "PASS" for result in results),
        "results": results,
    }
    summary["contactSheet"] = make_contact_sheet(results, evidence_root)
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    print(str(evidence_root))
    return 0 if summary["allPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
