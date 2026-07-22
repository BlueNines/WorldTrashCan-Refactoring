import argparse
import json
import re
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
DEBUG_COMMANDS = {
    "debugopen",
    "debugworldtrash",
    "debugroute",
    "debugdrop",
    "debugdamage",
    "debugstock",
    "debugsummary",
    "debugdensity",
    "debugnotify",
    "debugplayer",
    "debugrgb",
    "debugrgbchannels",
}
HELP_ALLOWED_DEBUG_COMMANDS = {"debughelp"}
COMMAND_PATTERN = re.compile(r"/wtc\s+(debug[a-z]+)", re.IGNORECASE)
QUOTED_DEBUG_COMMAND_PATTERN = re.compile(r'"(debug[a-z]+)"', re.IGNORECASE)
COMMAND_SOURCES = (
    "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/WorldListTrashCanLegacyCommand.java",
    "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/WorldListTrashCanBukkitCommand.java",
    "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/WorldListTrashCanCommand.java",
    "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/WorldListTrashCanFoliaCommand.java",
    "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/UniversalCommand.java",
)
COMMAND_NAMES_SOURCE = "bl-world-trashcan-shared-bukkit/src/main/java/pixeltech/bluenine/blworldtrashcan/bukkit/command/WorldListTrashCanCommandNames.java"


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def tracked_command_sources() -> list[Path]:
    """列出正式命令源码文件。"""
    return [REPO / relative for relative in COMMAND_SOURCES]


def source_message_files() -> list[Path]:
    """列出源码默认语言文件。"""
    files = []
    for path in REPO.rglob("message_*.yml"):
        relative = path.relative_to(REPO).as_posix()
        if relative.startswith(("target/", "build/", "manual-build/", "docs/test-evidence/")):
            continue
        if "/src/main/resources/messages/" in relative:
            files.append(path)
    return sorted(files)


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


def debug_commands_in_text(text: str) -> set[str]:
    """提取文本中出现的 /wtc debug* 命令名。"""
    return {match.group(1).lower() for match in COMMAND_PATTERN.finditer(text)}


def quoted_debug_commands_in_text(text: str) -> set[str]:
    """提取 Java 字符串列表里的 debug* 命令名。"""
    return {match.group(1).lower() for match in QUOTED_DEBUG_COMMAND_PATTERN.finditer(text)}


def extract_static_list(text: str, name: str) -> str:
    """提取 Java Arrays.asList 静态列表内容。"""
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*Arrays\.asList\((.*?)\);", text, re.DOTALL)
    return match.group(1) if match else ""


def extract_shared_list(text: str, name: str) -> str:
    """提取共享命令名称类中的不可变 Arrays.asList 内容。"""
    match = re.search(
        r"\b" + re.escape(name) + r"\s*=\s*Collections\.unmodifiableList\(Arrays\.asList\((.*?)\)\);",
        text,
        re.DOTALL,
    )
    return match.group(1) if match else ""


def yaml_list_after_key(text: str, key: str) -> list[str]:
    """提取两空格缩进键下的 YAML 列表行。"""
    lines = text.splitlines()
    result = []
    in_key = False
    for line in lines:
        if re.match(r"^  " + re.escape(key) + r":\s*$", line):
            in_key = True
            continue
        if in_key and re.match(r"^  [A-Za-z0-9_-]+:\s*", line):
            break
        if in_key:
            match = re.match(r"^\s{4}-\s*(.*)$", line)
            if match:
                result.append(match.group(1))
    return result


def check_help_lists(label: str, help_text: str, debug_help_text: str) -> list[str]:
    """检查 help 与 debug-help 的调试命令分离情况。"""
    errors = []
    help_debugs = debug_commands_in_text(help_text)
    illegal_help_debugs = sorted(help_debugs - HELP_ALLOWED_DEBUG_COMMANDS)
    if illegal_help_debugs:
        errors.append(label + ": command.help 混入具体调试命令: " + ", ".join(illegal_help_debugs))
    if "debughelp" not in help_debugs:
        errors.append(label + ": command.help 缺少 /wtc debughelp 入口")

    debug_commands = debug_commands_in_text(debug_help_text)
    missing = sorted(DEBUG_COMMANDS - debug_commands)
    if missing:
        errors.append(label + ": command.debug-help 缺少调试命令: " + ", ".join(missing))
    return errors


def check_java_sources() -> tuple[list[str], int]:
    """检查 Java fallback 帮助列表。"""
    errors = []
    files = tracked_command_sources()
    shared_text = read_text(REPO / COMMAND_NAMES_SOURCE)
    shared_regular = extract_shared_list(shared_text, "REGULAR")
    if not shared_regular:
        errors.append(COMMAND_NAMES_SOURCE + ": 缺少共享 REGULAR 普通补全列表")
    for path in files:
        label = path.relative_to(REPO).as_posix()
        text = read_text(path)
        help_body = extract_method_body(text, "sendHelp")
        debug_help_body = extract_method_body(text, "sendDebugHelp")
        if not help_body:
            errors.append(label + ": 缺少 sendHelp 方法")
            continue
        if not debug_help_body:
            errors.append(label + ": 缺少 sendDebugHelp 方法")
            continue
        errors.extend(check_help_lists(label, help_body, debug_help_body))
        uses_shared_names = "WorldListTrashCanCommandNames.regular()" in text
        regular_list = shared_regular if uses_shared_names else extract_static_list(text, "REGULAR_SUB_COMMANDS")
        if not regular_list:
            errors.append(label + ": 缺少 REGULAR_SUB_COMMANDS 普通补全列表")
            continue
        illegal_regular_debugs = sorted(quoted_debug_commands_in_text(regular_list) - HELP_ALLOWED_DEBUG_COMMANDS)
        if illegal_regular_debugs:
            errors.append(label + ": REGULAR_SUB_COMMANDS 混入具体调试命令: " + ", ".join(illegal_regular_debugs))
        tab_body = extract_method_body(text, "onTabComplete")
        if 'prefix.startsWith("debug") ? SUB_COMMANDS : REGULAR_SUB_COMMANDS' not in tab_body:
            errors.append(label + ": 一参 tab 补全未按 debug 前缀拆分")
        if "addonCommands.sendHelp(sender)" not in help_body:
            errors.append(label + ": 常规帮助没有追加附属插件副指令")
        if "addonCommands.completeFirstLevel" not in tab_body:
            errors.append(label + ": 一参 tab 补全没有合并附属插件副指令")
    return errors, len(files)


def check_source_messages() -> tuple[list[str], int]:
    """检查源码默认语言文件中的帮助列表。"""
    errors = []
    files = source_message_files()
    for path in files:
        label = path.relative_to(REPO).as_posix()
        text = read_text(path)
        help_lines = yaml_list_after_key(text, "help")
        debug_help_lines = yaml_list_after_key(text, "debug-help")
        if not help_lines:
            errors.append(label + ": 缺少 command.help 列表")
            continue
        if not debug_help_lines:
            errors.append(label + ": 缺少 command.debug-help 列表")
            continue
        errors.extend(check_help_lists(label, "\n".join(help_lines), "\n".join(debug_help_lines)))
    return errors, len(files)


def check_dist_messages() -> tuple[list[str], int, int]:
    """检查 dist jar 内语言文件中的帮助列表。"""
    errors = []
    jar_count = 0
    message_count = 0
    for jar_path in sorted(DIST.glob("WorldListTrashCan-*.jar")):
        jar_count += 1
        with zipfile.ZipFile(jar_path) as archive:
            names = sorted(name for name in archive.namelist() if name.startswith("messages/message_") and name.endswith(".yml"))
            for name in names:
                message_count += 1
                text = archive.read(name).decode("utf-8", errors="replace")
                help_lines = yaml_list_after_key(text, "help")
                debug_help_lines = yaml_list_after_key(text, "debug-help")
                label = jar_path.name + "!" + name
                if not help_lines:
                    errors.append(label + ": 缺少 command.help 列表")
                    continue
                if not debug_help_lines:
                    errors.append(label + ": 缺少 command.debug-help 列表")
                    continue
                errors.extend(check_help_lists(label, "\n".join(help_lines), "\n".join(debug_help_lines)))
    return errors, jar_count, message_count


def run_checks() -> dict:
    """执行帮助面板调试命令分离审计。"""
    java_errors, java_count = check_java_sources()
    source_errors, source_message_count = check_source_messages()
    dist_errors, jar_count, dist_message_count = check_dist_messages()
    errors = []
    errors.extend(java_errors)
    errors.extend(source_errors)
    errors.extend(dist_errors)
    return {
        "javaCommandFiles": java_count,
        "sourceMessageFiles": source_message_count,
        "distJars": jar_count,
        "distMessageFiles": dist_message_count,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 普通帮助和 debug 帮助是否分离。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("java command files:", result["javaCommandFiles"])
        print("source message files:", result["sourceMessageFiles"])
        print("dist jars:", result["distJars"])
        print("dist message files:", result["distMessageFiles"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
