import argparse
import json
import re
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
EXPECTED_COMMAND_ALIASES = {
    "blworldtrashcan": {"blwtc"},
    "worldlisttrashcan": {"WorldListTrashCan", "WTC", "wtc"},
}
REQUIRED_RUNTIME_REGISTRATIONS = {"blworldtrashcan", "worldlisttrashcan"}
ENTRY_SOURCES = {
    "legacy-1.12": "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/BLWorldTrashCanLegacyPlugin.java",
    "bukkit-1.13-1.15": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/BLWorldTrashCanBukkitPlugin.java",
    "paper-1.16-1.20": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/BLWorldTrashCanPlugin.java",
    "folia-1.20": "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/BLWorldTrashCanFoliaPlugin.java",
    "universal": "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/BLWorldTrashCanUniversalPlugin.java",
}


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def source_plugin_yml_files() -> list[Path]:
    """列出五个平台源码 plugin.yml。"""
    files = []
    for path in REPO.rglob("plugin.yml"):
        relative = path.relative_to(REPO).as_posix()
        if relative.startswith(("target/", "build/", "manual-build/", "docs/test-evidence/")):
            continue
        if "/src/main/resources/" in relative:
            files.append(path)
    return sorted(files)


def parse_commands(plugin_text: str) -> dict[str, set[str]]:
    """解析 plugin.yml commands 下的命令和 aliases。"""
    commands = {}
    current = ""
    in_commands = False
    in_aliases = False
    for line in plugin_text.splitlines():
        if re.match(r"^[A-Za-z0-9_.-]+:\s*", line):
            in_commands = line.startswith("commands:")
            in_aliases = False
            current = ""
            continue
        if not in_commands:
            continue
        command_match = re.match(r"^  ([A-Za-z0-9_.-]+):\s*$", line)
        if command_match:
            current = command_match.group(1)
            commands.setdefault(current, set())
            in_aliases = False
            continue
        if current and re.match(r"^    aliases:\s*$", line):
            in_aliases = True
            continue
        if current and in_aliases:
            alias_match = re.match(r"^      -\s*([A-Za-z0-9_.-]+)\s*$", line)
            if alias_match:
                commands[current].add(alias_match.group(1))
            elif line.startswith("    ") and not line.startswith("      "):
                in_aliases = False
    return commands


def check_plugin_yml(label: str, plugin_text: str) -> list[str]:
    """检查 plugin.yml 的新旧命令入口和别名。"""
    errors = []
    commands = parse_commands(plugin_text)
    for command, expected_aliases in sorted(EXPECTED_COMMAND_ALIASES.items()):
        if command not in commands:
            errors.append(label + ": commands 缺少 " + command)
            continue
        missing_aliases = sorted(expected_aliases - commands[command])
        if missing_aliases:
            errors.append(label + ": " + command + " aliases 缺少 " + ", ".join(missing_aliases))
    return errors


def registered_commands(source_text: str) -> set[str]:
    """提取入口源码中 registerCommand 调用的命令名。"""
    return set(re.findall(r'registerCommand\("([^"]+)"', source_text))


def check_entry_source(label: str, path: Path) -> list[str]:
    """检查插件入口源码是否注册命令并绑定补全。"""
    errors = []
    if not path.is_file():
        return [label + ": 插件入口源码不存在: " + path.relative_to(REPO).as_posix()]
    text = read_text(path)
    registrations = registered_commands(text)
    missing = sorted(REQUIRED_RUNTIME_REGISTRATIONS - registrations)
    if missing:
        errors.append(label + ": 入口源码未注册主命令: " + ", ".join(missing))
    if "command.setExecutor(executor)" not in text:
        errors.append(label + ": registerCommand 未绑定 executor")
    if "command.setTabCompleter(executor)" not in text:
        errors.append(label + ": registerCommand 未绑定 tab completer")
    return errors


def check_source_plugin_ymls() -> tuple[list[str], int]:
    """检查源码 plugin.yml。"""
    errors = []
    files = source_plugin_yml_files()
    for path in files:
        label = path.relative_to(REPO).as_posix()
        errors.extend(check_plugin_yml(label, read_text(path)))
    return errors, len(files)


def check_dist_plugin_ymls() -> tuple[list[str], int]:
    """检查 dist jar 内 plugin.yml。"""
    errors = []
    jar_count = 0
    for jar_path in sorted(DIST.glob("BLWorldTrashCan-*.jar")):
        jar_count += 1
        with zipfile.ZipFile(jar_path) as archive:
            if "plugin.yml" not in archive.namelist():
                errors.append(jar_path.name + ": 缺少 plugin.yml")
                continue
            plugin_text = archive.read("plugin.yml").decode("utf-8", errors="replace")
            errors.extend(check_plugin_yml(jar_path.name + "!plugin.yml", plugin_text))
    return errors, jar_count


def check_entry_sources() -> tuple[list[str], int]:
    """检查五个平台入口源码命令注册。"""
    errors = []
    for label, relative_path in sorted(ENTRY_SOURCES.items()):
        errors.extend(check_entry_source(label, REPO / relative_path))
    return errors, len(ENTRY_SOURCES)


def run_checks() -> dict:
    """执行命令入口兼容审计。"""
    source_errors, source_plugin_yml_count = check_source_plugin_ymls()
    dist_errors, dist_jar_count = check_dist_plugin_ymls()
    entry_errors, entry_source_count = check_entry_sources()
    errors = []
    errors.extend(source_errors)
    errors.extend(dist_errors)
    errors.extend(entry_errors)
    return {
        "sourcePluginYmlFiles": source_plugin_yml_count,
        "distJars": dist_jar_count,
        "entrySources": entry_source_count,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 BLWorldTrashCan 新旧命令入口声明和运行时注册。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("source plugin.yml files:", result["sourcePluginYmlFiles"])
        print("dist jars:", result["distJars"])
        print("entry sources:", result["entrySources"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
