import argparse
import json
import re
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw

import run_cleanup_guard_visual_matrix as guard
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TARGET_CASE_IDS = ["managed_paper1122", "external_folia1218"]
DIRECT_AMOUNT = 7
CONTROL_AMOUNT = 5


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
    """选择 Paper 1.12.2 与 Folia 1.21.8 通用整包用例。"""
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
    raise RuntimeError("未知强制直删世界测试用例: " + case_id)


def set_top_level_list(text: str, key: str, values: list[str]) -> str:
    """把顶层 YAML 列表改为单行写法，并清理旧的缩进列表项。"""
    lines = text.splitlines()
    replacement = key + ": " + json.dumps(values, ensure_ascii=False)
    for index, line in enumerate(lines):
        if not re.match(r"^" + re.escape(key) + r"\s*:", line):
            continue
        end = index + 1
        if line.split(":", 1)[1].strip() == "":
            while end < len(lines) and (lines[end].startswith(" ") or lines[end].startswith("\t")):
                end += 1
        lines[index:end] = [replacement]
        return "\n".join(lines) + "\n"
    return text.rstrip() + "\n\n" + replacement + "\n"


def write_test_config(case: dict, direct_worlds: list[str]) -> None:
    """写入本轮强制直删世界与三类垃圾桶可用配置。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
    cleanup = data_dir / "cleanup.yml"
    trash = data_dir / "trash.yml"
    if not cleanup.is_file() or not trash.is_file():
        raise RuntimeError("缺少 cleanup.yml 或 trash.yml: " + str(data_dir))
    cleanup_text = set_top_level_list(
        cleanup.read_text(encoding="utf-8", errors="replace"),
        "direct-remove-worlds",
        direct_worlds,
    )
    cleanup_text = external.update_yaml_scalars(cleanup_text, {
        "interval-seconds": "0",
        "guards.min-online-players": "1",
        "guards.min-total-entities": "0",
    })
    cleanup.write_text(cleanup_text, encoding="utf-8")
    trash_text = external.update_yaml_scalars(
        trash.read_text(encoding="utf-8", errors="replace"),
        {
            "world-trash.enabled": "true",
            "world-trash.allow-load-unloaded-chunks": "false",
            "personal-trash.enabled": "true",
            "personal-trash.track-player-dropped-items": "true",
            "global-trash.enabled": "true",
            "global-trash.clear-every-cleanups": "0",
        },
    )
    trash.write_text(trash_text, encoding="utf-8")


def client_stdout_path(case: dict, run_dir: Path) -> Path:
    """返回当前客户端标准输出日志。"""
    return run_dir / "logs" / (case["id"] + "-client-stdout.log")


def parse_stats(text: str) -> dict | None:
    """从客户端命令输出解析本轮四类路由与删除数量。"""
    routed_pattern = re.compile(
        r"回收物品[:：]\s*(\d+).*?世界\s*(\d+).*?个人\s*(\d+).*?公共\s*(\d+)",
        re.DOTALL,
    )
    removed_pattern = re.compile(r"删除物品[:：]\s*(\d+)")
    routed = routed_pattern.findall(text)
    removed = removed_pattern.findall(text)
    if not routed or not removed:
        return None
    latest = routed[-1]
    return {
        "routed": int(latest[0]),
        "world": int(latest[1]),
        "personal": int(latest[2]),
        "global": int(latest[3]),
        "removed": int(removed[-1]),
    }


def wait_client_stats(stdout_path: Path, offset: int, expected: dict,
                      timeout: float = 15.0) -> tuple[str, dict | None]:
    """等待客户端日志出现符合预期的清理统计。"""
    deadline = time.time() + timeout
    latest = ""
    parsed = None
    while time.time() < deadline:
        latest = external.read_text_since(stdout_path, offset)
        parsed = parse_stats(latest)
        if parsed == expected:
            return latest, parsed
        time.sleep(0.4)
    return latest, parsed


def capture_phase(case: dict, username: str, process, game_dir: Path, run_dir: Path,
                  server_log: Path, command_log: Path, phase: str, material: str,
                  amount: int, expected: dict) -> dict:
    """执行一轮玩家可见的掉落、扫地和统计截图验收。"""
    guard.run_console(
        process,
        command_log,
        "wtc debugdrop " + username + " " + material + " " + str(amount) + " owner",
        1.0,
    )
    if guard.is_folia(case):
        time.sleep(1.5)
    server_offset = external.log_text_offset(server_log)
    clear_shot = guard.send_command_and_screenshot(
        case,
        game_dir,
        run_dir,
        "/wtc clear true",
        phase + "-clear",
        4.5 if guard.is_folia(case) else 2.2,
    )
    if guard.is_folia(case):
        time.sleep(4.0)
    server_text = guard.wait_server_marker(
        server_log,
        server_offset,
        ["itemsRouted=" + str(expected["routed"]), "itemsRemoved=" + str(expected["removed"])],
        25.0,
    )
    stdout_path = client_stdout_path(case, run_dir)
    stats_offset = external.log_text_offset(stdout_path)
    stats_shot = guard.send_command_and_screenshot(
        case,
        game_dir,
        run_dir,
        "/wtc stats",
        phase + "-stats",
        2.0,
    )
    client_text, parsed = wait_client_stats(stdout_path, stats_offset, expected)
    passed = parsed == expected
    return {
        "phase": phase,
        "material": material,
        "amount": amount,
        "status": "PASS" if passed else "FAIL",
        "expected": expected,
        "actual": parsed,
        "serverExcerpt": server_text[-2200:],
        "clientExcerpt": client_text[-1800:],
        "clientScreenshots": [guard.screenshot_info(clear_shot), guard.screenshot_info(stats_shot)],
    }


def parse_debug_chest(server_log: Path) -> dict | None:
    """解析本轮调试世界垃圾桶的位置。"""
    text = server_log.read_text(encoding="utf-8", errors="replace") if server_log.is_file() else ""
    pattern = re.compile(
        r"debugWorldTrash player=.*?, world=([^,]+), x=(-?\d+), y=(-?\d+), z=(-?\d+), saved=true"
    )
    matches = pattern.findall(text)
    if not matches:
        return None
    world, x, y, z = matches[-1]
    return {"world": world, "x": int(x), "y": int(y), "z": int(z)}


def remove_debug_chest(process, command_log: Path, chest: dict | None) -> None:
    """移除本轮创建的测试箱子方块。"""
    if chest is None:
        return
    guard.run_console(
        process,
        command_log,
        "setblock {x} {y} {z} air".format(**chest),
        0.8,
    )


def render_server_screenshot(case: dict, result: dict, config_text: str, target: Path) -> Path:
    """把配置、两轮统计和服务端关键日志渲染为证据图。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        case["label"] + " 强制直删世界服务端证据",
        "",
        "配置快照:",
    ]
    lines.extend(config_text.splitlines())
    for phase in result.get("phases", []):
        lines.extend([
            "",
            phase["phase"] + " expected=" + json.dumps(phase["expected"], ensure_ascii=False),
            phase["phase"] + " actual=" + json.dumps(phase["actual"], ensure_ascii=False),
            phase["serverExcerpt"],
        ])
    wrapped = []
    for line in lines:
        rest = line
        while len(rest) > 112:
            wrapped.append(rest[:112])
            rest = "  " + rest[112:]
        wrapped.append(rest)
    width = 1560
    line_height = 25
    image = Image.new("RGB", (width, max(360, (len(wrapped) + 2) * line_height)), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    used_font = guard.font()
    y = 18
    for index, line in enumerate(wrapped):
        draw.text((22, y), line, fill=(250, 204, 21) if index == 0 else (226, 232, 240), font=used_font)
        y += line_height
    image.save(target)
    return target


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端的强制直删与正常路由对照。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    process = None
    client = None
    backups = []
    chest = None
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
        "phases": [],
    }
    try:
        log("开始强制直删世界用例 " + case["id"] + " / " + case["label"])
        process = external.launch_server(case, run_dir)
        data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
        backup_dir = run_dir / "logs" / "file-backup"
        for relative in ("cleanup.yml", "trash.yml", "data/worlds.yml"):
            backups.append(guard.backup_file(data_dir / relative, backup_dir / Path(relative).parent))
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        guard.setup_player(case, username, process, command_log)

        write_test_config(case, [])
        guard.reload_plugin(process, command_log)
        guard.run_console(process, command_log, "wtc clear true", 3.5 if guard.is_folia(case) else 1.8)
        guard.run_console(process, command_log, "wtc debugworldtrash " + username, 2.0)
        if guard.is_folia(case):
            time.sleep(2.5)
        chest = parse_debug_chest(server_log)
        if chest is None:
            raise RuntimeError("没有创建出可用世界垃圾桶")

        write_test_config(case, [chest["world"]])
        direct_config = (data_dir / "cleanup.yml").read_text(encoding="utf-8", errors="replace")
        guard.reload_plugin(process, command_log)
        direct = capture_phase(
            case,
            username,
            process,
            game_dir,
            run_dir,
            server_log,
            command_log,
            "direct-remove-enabled",
            "STONE",
            DIRECT_AMOUNT,
            {"routed": 0, "world": 0, "personal": 0, "global": 0, "removed": DIRECT_AMOUNT},
        )
        result["phases"].append(direct)

        write_test_config(case, [])
        guard.reload_plugin(process, command_log)
        control = capture_phase(
            case,
            username,
            process,
            game_dir,
            run_dir,
            server_log,
            command_log,
            "direct-remove-disabled-control",
            "GOLD_INGOT",
            CONTROL_AMOUNT,
            {"routed": CONTROL_AMOUNT, "world": CONTROL_AMOUNT,
             "personal": 0, "global": 0, "removed": 0},
        )
        result["phases"].append(control)
        result["status"] = "PASS" if all(item["status"] == "PASS" for item in result["phases"]) else "FAIL"
        config_excerpt = "\n".join(
            line for line in direct_config.splitlines()
            if "direct-remove-worlds" in line or "ignored-worlds" in line or line.strip() == '- "testWorld"'
        )
        server_shot = render_server_screenshot(
            case,
            result,
            config_excerpt,
            run_dir / "server-screenshots" / (case["id"] + "-direct-remove-server.png"),
        )
        result["serverScreenshot"] = guard.screenshot_info(server_shot)
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        log("强制直删世界用例失败 " + case["id"] + ": " + repr(exc))
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            remove_debug_chest(process, command_log, chest)
            guard.restore_backups(backups)
            external.stop_process(process, "stop")
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path:
    """生成客户端与服务端证据联系表。"""
    screenshots = []
    for result in results:
        for phase in result.get("phases", []):
            screenshots.extend(Path(item["path"]) for item in phase.get("clientScreenshots", []))
        if result.get("serverScreenshot"):
            screenshots.append(Path(result["serverScreenshot"]["path"]))
    if not screenshots:
        return Path("")
    thumbs = []
    for path in screenshots:
        image = Image.open(path).convert("RGB")
        image.thumbnail((480, 278))
        canvas = Image.new("RGB", (500, 320), (15, 23, 42))
        canvas.paste(image, ((500 - image.width) // 2, 8))
        ImageDraw.Draw(canvas).text((10, 292), path.name[:64], fill=(226, 232, 240), font=guard.font())
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 500, rows * 320), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 500, (index // columns) * 320))
    target = evidence_root / "direct-remove-world-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, results: list[dict], contact_sheet: Path) -> None:
    """写入本轮证据说明。"""
    lines = [
        "# 强制直删世界真实客户端专项验收",
        "",
        "- 被测产物：`dist/WorldListTrashCan-universal.jar`",
        "- 配置：`cleanup.yml -> direct-remove-worlds`",
        "- 直删轮：三类垃圾桶全部启用且当前世界存在世界垃圾桶，要求回收四项计数均为 0、删除 7。",
        "- 对照轮：通过 `/wtc reload` 移出世界后，同位置 5 个物品要求进入世界垃圾桶、删除为 0。",
        "- 联系表：`" + contact_sheet.name + "`",
        "",
    ]
    for result in results:
        lines.extend([
            "## " + result["label"],
            "",
            "- 结果：`" + result["status"] + "`",
            "- 客户端与服务端原始证据：`" + result["id"] + "/`",
            "",
        ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行强制直删世界的跨平台真实客户端矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "direct-remove-world-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, evidence_root))
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results})
    contact_sheet = make_contact_sheet(results, evidence_root)
    write_readme(evidence_root, results, contact_sheet)
    summary = {"run": run_id, "results": results, "contactSheet": str(contact_sheet)}
    write_json(evidence_root / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("强制直删世界矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
