import argparse
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
COMMAND_SOURCES = {
    "legacy": "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/WorldListTrashCanLegacyCommand.java",
    "bukkit": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/WorldListTrashCanBukkitCommand.java",
    "paper": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/WorldListTrashCanCommand.java",
    "folia": "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/WorldListTrashCanFoliaCommand.java",
    "universal": "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/UniversalCommand.java",
}
EXPECTED_DEBUG_NOTIFY_VALUES = ["10", "5", "0", "-1", "-2", "-3", "-4", "-5"]
EXPECTED_DEBUG_ROUTE_VALUES = ["world", "personal", "global"]
FALLBACK_HANDLED_SUB_COMMANDS = {"help"}


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def extract_static_list(text: str, name: str) -> list[str]:
    """提取 Java Arrays.asList 静态列表中的字符串值。"""
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*Arrays\.asList\((.*?)\);", text, re.DOTALL)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def extract_method_body(text: str, method_name: str) -> str:
    """用括号平衡提取 Java 方法体。"""
    match = re.search(r"\b" + re.escape(method_name) + r"\s*\([^)]*\)\s*\{", text)
    if not match:
        return ""
    start = match.end() - 1
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    return ""


def handled_sub_commands(on_command_body: str) -> set[str]:
    """提取 onCommand 中显式处理的 sub 命令。"""
    values = set(re.findall(r'"([a-z]+)"\.equals\(sub\)', on_command_body))
    values.update(FALLBACK_HANDLED_SUB_COMMANDS)
    return values


def extract_completion_values(tab_body: str, command: str, arg_index: int) -> list[str]:
    """提取指定补全分支的 Arrays.asList 值。"""
    pattern = (
        r'if \(args\.length == \d+ && "'
        + re.escape(command)
        + r'"\.equalsIgnoreCase\(args\[0\]\)\) \{\s*'
        + r"return filter\(Arrays\.asList\((.*?)\), args\[" + str(arg_index) + r"\]\);"
    )
    match = re.search(pattern, tab_body, re.DOTALL)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def source_snapshot(label: str, path: Path) -> tuple[dict, list[str]]:
    """读取单个命令类快照并返回局部错误。"""
    errors = []
    if not path.is_file():
        return {}, [label + ": 命令源码不存在: " + path.relative_to(REPO).as_posix()]
    text = read_text(path)
    regular = extract_static_list(text, "REGULAR_SUB_COMMANDS")
    all_commands = extract_static_list(text, "SUB_COMMANDS")
    on_command_body = extract_method_body(text, "onCommand")
    tab_body = extract_method_body(text, "onTabComplete")
    if not regular:
        errors.append(label + ": 缺少 REGULAR_SUB_COMMANDS")
    if not all_commands:
        errors.append(label + ": 缺少 SUB_COMMANDS")
    if not on_command_body:
        errors.append(label + ": 缺少 onCommand")
    if not tab_body:
        errors.append(label + ": 缺少 onTabComplete")
    return {
        "regular": regular,
        "all": all_commands,
        "handled": sorted(handled_sub_commands(on_command_body)),
        "debugNotify": extract_completion_values(tab_body, "debugnotify", 1),
        "debugRoute": extract_completion_values(tab_body, "debugroute", 2),
        "hasClearBooleanCompletion": "ClearCommandOptions.booleanValues()" in tab_body,
        "hasRegularDebugSplit": 'prefix.startsWith("debug") ? SUB_COMMANDS : REGULAR_SUB_COMMANDS' in tab_body,
    }, errors


def compare_to_canonical(label: str, snapshot: dict, canonical: dict) -> list[str]:
    """检查单个平台命令类是否与基准命令类一致。"""
    errors = []
    for key, description in (
        ("regular", "REGULAR_SUB_COMMANDS"),
        ("all", "SUB_COMMANDS"),
        ("debugNotify", "debugnotify 补全值"),
        ("debugRoute", "debugroute 补全值"),
    ):
        if snapshot.get(key) != canonical.get(key):
            errors.append(label + ": " + description + " 与 paper 基准不一致")
    if snapshot.get("hasClearBooleanCompletion") != canonical.get("hasClearBooleanCompletion"):
        errors.append(label + ": clear 布尔补全与 paper 基准不一致")
    if snapshot.get("hasRegularDebugSplit") != canonical.get("hasRegularDebugSplit"):
        errors.append(label + ": 普通/debug 一参补全拆分与 paper 基准不一致")
    return errors


def validate_snapshot(label: str, snapshot: dict) -> list[str]:
    """检查单个平台命令类内部一致性。"""
    errors = []
    regular = snapshot.get("regular", [])
    all_commands = snapshot.get("all", [])
    regular_set = set(regular)
    all_set = set(all_commands)
    handled = set(snapshot.get("handled", []))
    if len(regular) != len(regular_set):
        errors.append(label + ": REGULAR_SUB_COMMANDS 存在重复值")
    if len(all_commands) != len(all_set):
        errors.append(label + ": SUB_COMMANDS 存在重复值")
    if not regular_set.issubset(all_set):
        errors.append(label + ": REGULAR_SUB_COMMANDS 不是 SUB_COMMANDS 子集")
    missing_handlers = sorted(all_set - handled)
    if missing_handlers:
        errors.append(label + ": SUB_COMMANDS 缺少处理分支: " + ", ".join(missing_handlers))
    if snapshot.get("debugNotify") != EXPECTED_DEBUG_NOTIFY_VALUES:
        errors.append(label + ": debugnotify 补全值不是 " + ", ".join(EXPECTED_DEBUG_NOTIFY_VALUES))
    if snapshot.get("debugRoute") != EXPECTED_DEBUG_ROUTE_VALUES:
        errors.append(label + ": debugroute 补全值不是 " + ", ".join(EXPECTED_DEBUG_ROUTE_VALUES))
    if not snapshot.get("hasClearBooleanCompletion"):
        errors.append(label + ": 缺少 clear true/false 补全")
    if not snapshot.get("hasRegularDebugSplit"):
        errors.append(label + ": 一参补全没有按 debug 前缀切换范围")
    return errors


def run_checks() -> dict:
    """执行五个平台命令类一致性审计。"""
    snapshots = {}
    errors = []
    for label, relative_path in sorted(COMMAND_SOURCES.items()):
        snapshot, source_errors = source_snapshot(label, REPO / relative_path)
        snapshots[label] = snapshot
        errors.extend(source_errors)
        errors.extend(validate_snapshot(label, snapshot))

    canonical = snapshots.get("paper", {})
    for label, snapshot in sorted(snapshots.items()):
        if label == "paper":
            continue
        errors.extend(compare_to_canonical(label, snapshot, canonical))

    return {
        "commandSourceCount": len(COMMAND_SOURCES),
        "canonical": "paper",
        "regularCommandCount": len(canonical.get("regular", [])),
        "allCommandCount": len(canonical.get("all", [])),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 五个平台命令类是否保持一致。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("command sources:", result["commandSourceCount"])
        print("canonical:", result["canonical"])
        print("regular commands:", result["regularCommandCount"])
        print("all commands:", result["allCommandCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
