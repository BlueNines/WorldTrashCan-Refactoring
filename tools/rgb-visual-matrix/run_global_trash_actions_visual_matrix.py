import argparse
import hashlib
import json
import re
import shutil
import time
from pathlib import Path

import pyautogui

import run_global_trash_layout_visual_matrix as layout
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base
import run_trash_gui_click_visual_matrix as gui


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
SOURCE_CASE_IDS = ["managed_paper1122", "external_folia1218"]
WORKSPACE_ROOT = base.REPO.parents[2]
AUDIT_REPO = WORKSPACE_ROOT / "待开发插件" / "WorldListTrashCanAudit"
AUDIT_JAR = AUDIT_REPO / "dist" / "WorldListTrashCanAudit.jar"


def log(message: str) -> None:
    """输出带时间戳的动作按钮测试进度。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, value) -> None:
    """按 UTF-8 写入机器可读结果。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def selected_cases(case_id: str | None) -> list[dict]:
    """选择无 PAPI 的 1.12.2 和启用 PAPI 的 Folia 1.21.8。"""
    if not AUDIT_JAR.is_file():
        raise RuntimeError("缺少查水表附属插件 Jar: " + str(AUDIT_JAR))
    cases = []
    for source_id in SOURCE_CASE_IDS:
        source = next(item for item in external.EXTERNAL_MATRIX if item["id"] == source_id)
        case = external.universal_case(source)
        case["expectPapiActions"] = source_id == "external_folia1218"
        if not case["expectPapiActions"]:
            case["extraPlugins"] = []
        extras = list(case.get("extraPlugins", []))
        extras.append(AUDIT_JAR)
        case["extraPlugins"] = extras
        cases.append(case)
    if not case_id:
        return cases
    for case in cases:
        if case_id in (case["id"], case.get("sourceId", ""), case["version"], case["label"]):
            return [case]
    raise RuntimeError("未知公共垃圾桶 actions 测试用例: " + case_id)


def actions_layout_body() -> list[str]:
    """生成两行公共垃圾桶 actions 测试布局。"""
    return [
        "layout:",
        "  position:",
        "    - \"xxxxxxxxx\"",
        "    - \"abbbdbbbc\"",
        "  items:",
        "    x:",
        "      type: \"content\"",
        "    a:",
        "      type: \"previous-page\"",
        "      material:",
        "        - \"ARROW\"",
        "      unavailable-item: \"b\"",
        "    b:",
        "      type: \"background\"",
        "      material:",
        "        - \"BLACK_STAINED_GLASS_PANE\"",
        "        - \"STAINED_GLASS_PANE\"",
        "        - \"GLASS_PANE\"",
        "      name: \" \"",
        "      lore: []",
        "    c:",
        "      type: \"next-page\"",
        "      material:",
        "        - \"ARROW\"",
        "      unavailable-item: \"b\"",
        "    d:",
        "      type: \"actions\"",
        "      model-id: -1",
        "      material:",
        "        - \"BOOK\"",
        "      name: \"&#FFD166ACTIONS %Wtc_ClearTime%\"",
        "      lore:",
        "        - \"&#5AC8FA内置玩家: &f{player}\"",
        "        - \"&#5AC8FAPAPI倒计时: &f%Wtc_ClearTime%\"",
        "        - \"&#79879C页码: {page}/{max-page}\"",
        "      actions:",
        "        - \"[message] &aACTION_MESSAGE built={player} papi=%Wtc_ClearTime% page={page}/{max-page}\"",
        "        - \"[command] wtc stats\"",
        "        - \"[console] say ACTION_CONSOLE built={player} papi=%Wtc_ClearTime% page={page}/{max-page}\"",
    ]


def patch_actions_layout(case: dict) -> Path:
    """写入动作按钮测试布局并保持查水表菜单完全不变。"""
    target = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml"
    if not target.is_file():
        raise RuntimeError("运行时 trash.yml 不存在: " + str(target))
    text = target.read_text(encoding="utf-8", errors="replace")
    text = external.update_yaml_scalars(text, {
        "global-trash.enabled": "true",
        "global-trash.max-pages": "2",
        "global-trash.take-delay-millis": "0",
        "global-trash.allow-player-put": "true",
    })
    text = layout.replace_yaml_block(text, "global-trash.gui", actions_layout_body())
    target.write_text(text, encoding="utf-8")
    return target


def prepare_audit_runtime_message(case: dict, backup_dir: Path) -> list[dict]:
    """准备 SQLite 运行态和空记录消息，用独立 /wtc audit 验证玩家 PAPI。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCanAudit"
    config = data_dir / "config.yml"
    message = data_dir / "messages" / "message_zh.yml"
    database = data_dir / "data" / "global-actions-audit.db"
    database_files = [database, Path(str(database) + "-wal"), Path(str(database) + "-shm")]
    backups = [gui.backup_file(config, backup_dir), gui.backup_file(message, backup_dir)]
    backups.extend(gui.backup_file(path, backup_dir) for path in database_files)
    config.parent.mkdir(parents=True, exist_ok=True)
    message.parent.mkdir(parents=True, exist_ok=True)
    if not config.is_file():
        shutil.copy2(AUDIT_REPO / "src" / "main" / "resources" / "config.yml", config)
    if not message.is_file():
        shutil.copy2(AUDIT_REPO / "src" / "main" / "resources"
                     / "messages" / "message_zh.yml", message)
    config_text = re.sub(
        r"(?m)^enabled:\s*(?:true|false)\s*(?:#.*)?(?:\r?\n|$)", "",
        config.read_text(encoding="utf-8", errors="replace"))
    config_text = external.update_yaml_scalars(config_text, {
        "language": "message_zh.yml",
        "storage.type": "sqlite",
        "storage.sqlite.file": "data/global-actions-audit.db",
    })
    config.write_text(config_text, encoding="utf-8")
    message_text = external.update_yaml_scalars(
        message.read_text(encoding="utf-8", errors="replace"),
        {"no-records": '"{prefix}&cAUDIT_PAPI clear=%Wtc_ClearTime%"'})
    message.write_text(message_text, encoding="utf-8")
    for path in database_files:
        if path.is_file():
            path.unlink()
    return backups


def backup_audit_jars(case: dict, backup_dir: Path) -> list[tuple[Path, Path]]:
    """备份服务端原有审计附属 Jar，避免测试部署污染外部服务端。"""
    plugins = Path(case["serverDir"]) / "plugins"
    backup_dir.mkdir(parents=True, exist_ok=True)
    moved = []
    for source in plugins.glob("WorldListTrashCanAudit*.jar") if plugins.is_dir() else []:
        target = backup_dir / source.name
        shutil.move(str(source), str(target))
        moved.append((source, target))
    return moved


def restore_audit_jars(case: dict, moved: list[tuple[Path, Path]]) -> None:
    """移除测试部署并恢复服务端原有审计附属 Jar。"""
    plugins = Path(case["serverDir"]) / "plugins"
    for current in plugins.glob("WorldListTrashCanAudit*.jar") if plugins.is_dir() else []:
        current.unlink()
    for target, backup in moved:
        if backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(backup), str(target))


def suspend_papi(case: dict, backup_dir: Path) -> list[tuple[Path, Path]]:
    """在无 PAPI 用例启动前临时移走 PAPI Jar，并保留可恢复备份。"""
    if case["expectPapiActions"]:
        return []
    plugins = Path(case["serverDir"]) / "plugins"
    moved = []
    backup_dir.mkdir(parents=True, exist_ok=True)
    for source in plugins.iterdir() if plugins.is_dir() else []:
        if source.is_file() and "placeholderapi" in source.name.lower() and source.suffix.lower() == ".jar":
            target = backup_dir / source.name
            shutil.move(str(source), str(target))
            moved.append((source, target))
    return moved


def restore_papi(moved: list[tuple[Path, Path]]) -> None:
    """把无 PAPI 用例临时移走的 Jar 原样恢复。"""
    for target, backup in moved:
        if backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(backup), str(target))


def wait_text(path: Path, markers: list[str], timeout: float, offset: int = 0) -> str:
    """等待 UTF-8 日志偏移后出现全部标记。"""
    deadline = time.time() + timeout
    latest = ""
    while time.time() < deadline:
        if path.is_file():
            latest = path.read_text(encoding="utf-8", errors="replace")[offset:]
        if all(marker in latest for marker in markers):
            return latest
        time.sleep(0.5)
    raise TimeoutError("等待日志标记超时: " + str(path) + " markers=" + repr(markers))


def post_mouse_click(hwnd: int, rect: tuple[int, int, int, int], x: int, y: int,
                     right_button: bool = False) -> None:
    """向 Minecraft 客户区只投递一次鼠标点击，避免桌面层和消息层重复。"""
    left, top, right, bottom = rect
    client_x = max(0, min(right - left - 1, x - left))
    client_y = max(0, min(bottom - top - 1, y - top))
    lparam = client_x | (client_y << 16)
    base.win32gui.PostMessage(hwnd, base.win32con.WM_MOUSEMOVE, 0, lparam)
    time.sleep(0.05)
    if right_button:
        base.win32gui.PostMessage(
            hwnd, base.win32con.WM_RBUTTONDOWN, base.win32con.MK_RBUTTON, lparam)
        time.sleep(0.08)
        base.win32gui.PostMessage(hwnd, base.win32con.WM_RBUTTONUP, 0, lparam)
    else:
        base.win32gui.PostMessage(
            hwnd, base.win32con.WM_LBUTTONDOWN, base.win32con.MK_LBUTTON, lparam)
        time.sleep(0.08)
        base.win32gui.PostMessage(hwnd, base.win32con.WM_LBUTTONUP, 0, lparam)


def plain_click_action(case: dict, frame_size: tuple[int, int]) -> None:
    """使用一次真实左键点击 actions 槽，避免测试工具重复发包。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = layout.slot_center(rect, frame_size, 2, 1, 4)
    post_mouse_click(hwnd, rect, x, y)
    time.sleep(1.2)


def right_click_action(case: dict, frame_size: tuple[int, int]) -> None:
    """使用一次真实右键点击 actions 槽。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = layout.slot_center(rect, frame_size, 2, 1, 4)
    post_mouse_click(hwnd, rect, x, y, True)
    time.sleep(1.2)


def prohibited_action_inputs(case: dict, frame_size: tuple[int, int]) -> None:
    """发送 Shift 和数字键额外交互，验证不会产生动作。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = layout.slot_center(rect, frame_size, 2, 1, 4)
    pyautogui.keyDown("shift")
    post_mouse_click(hwnd, rect, x, y)
    pyautogui.keyUp("shift")
    pyautogui.moveTo(x, y, duration=0.2)
    pyautogui.press("1")
    time.sleep(1.5)


def physical_double_click(case: dict, frame_size: tuple[int, int]) -> None:
    """发送两个普通左键组成的物理双击，记录客户端实际事件语义。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = layout.slot_center(rect, frame_size, 2, 1, 4)
    post_mouse_click(hwnd, rect, x, y)
    time.sleep(0.1)
    post_mouse_click(hwnd, rect, x, y)
    time.sleep(1.5)


def marker_count(path: Path, marker: str) -> int:
    """统计日志中的稳定标记次数。"""
    if not path.is_file():
        return 0
    return path.read_text(encoding="utf-8", errors="replace").count(marker)


def require_numeric_papi(text: str, prefix: str) -> str:
    """断言 PAPI 变量已解析为数字，并返回实际值。"""
    match = re.search(re.escape(prefix) + r"(\d+)", text)
    if match is None:
        raise RuntimeError("PAPI 数字变量未解析: " + prefix)
    return match.group(1)


def screenshot_info(path: Path) -> dict:
    """复用布局专项的截图基础审计。"""
    return layout.screenshot_info(path)


def send_independent_audit_command(case: dict, username: str, process, command_log: Path,
                                   game_dir: Path, run_dir: Path) -> dict:
    """先确定 GUI 状态，再用窗口消息发送独立 /wtc audit 玩家命令。"""
    layout.open_global(case, username, process, command_log)
    layout.close_inventory(case)
    hwnd = base.find_minecraft_window(case["version"])
    base.send_chat_line_by_window_message(hwnd, "/wtc audit")
    time.sleep(1.5)
    screenshot = gui.capture_named_screenshot(
        case, game_dir, run_dir, "audit-independent-command")
    return {
        "name": "audit-independent-command",
        "command": "/wtc audit",
        "status": "PASS",
        "screenshot": str(screenshot),
        "brightness": screenshot_info(screenshot)["brightness"],
    }


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """执行单个 actions/PAPI 真实客户端用例。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "label": case["label"],
        "clientVersion": case["version"],
        "expectPapiActions": case["expectPapiActions"],
        "status": "FAIL",
        "checks": {},
        "screenshots": {},
    }
    process = None
    client = None
    game_dir = None
    backups = []
    moved_papi = []
    moved_audit_jars = []
    try:
        moved_papi = suspend_papi(case, run_dir / "logs" / "papi-backup")
        moved_audit_jars = backup_audit_jars(case, run_dir / "logs" / "audit-jar-backup")
        backups.extend(prepare_audit_runtime_message(
            case, run_dir / "logs" / "audit-config-backup"))
        process = external.launch_server(case, run_dir)
        trash_file = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "trash.yml"
        backups.append(gui.backup_file(trash_file, run_dir / "logs" / "config-backup"))
        patch_actions_layout(case)
        result["checks"]["reload"] = layout.reload_and_wait(
            case, process, server_log, command_log,
            ["rows=2", "slots=18", "contentSlots=9", "writablePages=2", "visiblePages=2"])

        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        gui.setup_player(case, username, process, command_log)

        layout.open_global(case, username, process, command_log)
        opened = layout.capture(case, game_dir, run_dir, "actions-opened")
        result["screenshots"]["opened"] = opened
        frame_size = tuple(opened["dimensions"])
        layout.move_to_slot(case, frame_size, 2, 1, 4)
        result["screenshots"]["tooltip"] = layout.capture(
            case, game_dir, run_dir, "actions-tooltip-papi")

        client_log = game_dir / "logs" / "latest.log"
        client_offset = len(client_log.read_text(encoding="utf-8", errors="replace")) \
            if client_log.is_file() else 0
        server_offset = len(server_log.read_text(encoding="utf-8", errors="replace"))
        plain_click_action(case, frame_size)
        expected_papi = "" if case["expectPapiActions"] else "%Wtc_ClearTime%"
        result["checks"]["clientActions"] = wait_text(client_log, [
            "ACTION_MESSAGE", "built=" + username, "papi=" + expected_papi,
            "清理统计",
        ], 15, client_offset)
        result["checks"]["consoleAction"] = wait_text(server_log, [
            "ACTION_CONSOLE", "built=" + username, "papi=" + expected_papi,
        ], 15, server_offset)
        if case["expectPapiActions"]:
            result["checks"]["papiResolvedValues"] = {
                "message": require_numeric_papi(
                    result["checks"]["clientActions"], "papi="),
                "console": require_numeric_papi(
                    result["checks"]["consoleAction"], "papi="),
            }
        left_count = marker_count(server_log, "ACTION_CONSOLE")

        right_click_action(case, frame_size)
        right_count = marker_count(server_log, "ACTION_CONSOLE")
        if right_count != left_count + 1:
            raise RuntimeError("右键 actions 执行次数异常: left=" + str(left_count)
                               + " right=" + str(right_count))

        before_prohibited = marker_count(server_log, "ACTION_CONSOLE")
        prohibited_action_inputs(case, frame_size)
        after_prohibited = marker_count(server_log, "ACTION_CONSOLE")
        if after_prohibited != before_prohibited:
            raise RuntimeError("Shift 或数字键产生了 actions: before=" + str(before_prohibited)
                               + " after=" + str(after_prohibited))
        result["checks"]["prohibitedInputs"] = {
            "before": before_prohibited,
            "after": after_prohibited,
            "eventTypes": ["SHIFT_LEFT", "NUMBER_KEY"],
        }
        before_double = marker_count(server_log, "ACTION_CONSOLE")
        physical_double_click(case, frame_size)
        after_double = marker_count(server_log, "ACTION_CONSOLE")
        if after_double != before_double + 2:
            raise RuntimeError("物理双击没有形成两个普通左键: before=" + str(before_double)
                               + " after=" + str(after_double))
        result["checks"]["physicalDoubleClick"] = {
            "before": before_double,
            "after": after_double,
            "clientEventSemantic": "two normal LEFT clicks",
            "bukkitDoubleClickCoveredByUnitTest": True,
        }

        layout.open_global(case, username, process, command_log)
        layout.close_inventory(case)
        result["screenshots"]["chatResult"] = layout.capture(
            case, game_dir, run_dir, "actions-chat-result")
        audit_offset = len(client_log.read_text(encoding="utf-8", errors="replace")) \
            if client_log.is_file() else 0
        audit_command = send_independent_audit_command(
            case, username, process, command_log, game_dir, run_dir)
        result["checks"]["auditCommand"] = audit_command
        result["screenshots"]["auditIndependentCommand"] = screenshot_info(
            Path(audit_command["screenshot"]))
        result["checks"]["auditMessagePapi"] = wait_text(client_log, [
            "AUDIT_PAPI", "clear=" + expected_papi,
        ], 15, audit_offset)
        if case["expectPapiActions"]:
            result["checks"]["papiResolvedValues"]["audit"] = require_numeric_papi(
                result["checks"]["auditMessagePapi"], "clear=")
        layout.verify_screenshots(result["screenshots"])
        result["checks"]["papiRuntime"] = (
            "PAPI resolved Wtc_ClearTime to numeric values" if case["expectPapiActions"]
            else "PAPI absent and original placeholder preserved"
        )
        server_excerpt = "\n\n".join(str(value) for value in result["checks"].values())
        server_shot = gui.render_text_screenshot(
            server_excerpt,
            run_dir / "server-screenshots" / (case["id"] + "-actions-assertions.png"),
            case["label"] + " / global trash actions assertions",
        )
        result["serverScreenshot"] = screenshot_info(server_shot)
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("actions 专项失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                result["failureScreenshot"] = layout.capture(
                    case, game_dir, run_dir, "actions-failure")
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            gui.copy_runtime_evidence(case, run_dir)
            external.stop_process(process, "stop")
            gui.restore_backups(backups)
        else:
            gui.restore_backups(backups)
        restore_audit_jars(case, moved_audit_jars)
        restore_papi(moved_papi)
    write_json(run_dir / "result.json", result)
    return result


def write_readme(evidence_root: Path, summary: dict) -> None:
    """写入 actions/PAPI 专项的简明证据索引。"""
    lines = [
        "# 公共垃圾桶 actions 与 PAPI 真实客户端专项",
        "",
        "- universal Jar SHA-256: `" + summary["jarSha256"] + "`",
        "- audit Jar SHA-256: `" + summary["auditJarSha256"] + "`",
        "- 全部通过: `" + str(summary["allPassed"]).lower() + "`",
        "- 1.12.2 用例临时暂停 PAPI，结束后原样恢复；Folia 1.21.8 使用真实 PAPI。",
        "- 查水表附属菜单未修改布局，公共垃圾桶没有预置查水表跳转。",
        "",
        "| 用例 | 客户端 | PAPI | 结果 |",
        "| --- | --- | --- | --- |",
    ]
    for result in summary["results"]:
        lines.append("| " + result["label"] + " | " + result["clientVersion"] + " | "
                     + ("启用" if result["expectPapiActions"] else "未安装")
                     + " | " + result["status"] + " |")
    lines.extend([
        "",
        "每个用例保留按钮打开、PAPI Tooltip、动作结果客户端截图，以及服务端动作断言截图和完整日志。",
    ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行 actions/PAPI 真实客户端矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default=None)
    args = parser.parse_args()
    cases = selected_cases(args.case)
    prepared_clients = {}
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
    run_id = "global-trash-actions-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    results = [run_case(case, prepared_clients, evidence_root) for case in cases]
    jar_path = base.REPO / "dist" / external.UNIVERSAL_PLUGIN
    summary = {
        "runId": run_id,
        "jar": str(jar_path),
        "jarSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(),
        "auditJar": str(AUDIT_JAR),
        "auditJarSha256": hashlib.sha256(AUDIT_JAR.read_bytes()).hexdigest(),
        "allPassed": all(result["status"] == "PASS" for result in results),
        "results": results,
    }
    summary["contactSheet"] = layout.make_contact_sheet(results, evidence_root)
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["allPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
