import argparse
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
SOURCE_PLUGIN_YMLS = {
    "legacy": {
        "path": "bl-world-trashcan-plugin-legacy-1_12/src/main/resources/plugin.yml",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.legacy.WorldListTrashCanLegacyPlugin",
        "apiVersion": None,
        "foliaSupported": None,
    },
    "bukkit": {
        "path": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/resources/plugin.yml",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.bukkit.WorldListTrashCanBukkitPlugin",
        "apiVersion": "1.13",
        "foliaSupported": None,
    },
    "paper": {
        "path": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/resources/plugin.yml",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.WorldListTrashCanPlugin",
        "apiVersion": "1.16",
        "foliaSupported": None,
    },
    "folia": {
        "path": "bl-world-trashcan-plugin-folia-1_20/src/main/resources/plugin.yml",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.folia.WorldListTrashCanFoliaPlugin",
        "apiVersion": "1.20",
        "foliaSupported": "true",
    },
    "universal": {
        "path": "bl-world-trashcan-plugin-universal/src/main/resources/plugin.yml",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.universal.WorldListTrashCanUniversalPlugin",
        "apiVersion": "1.13",
        "foliaSupported": "true",
    },
}
DIST_PLUGIN_YMLS = {
    "legacy": ("dist/WorldListTrashCan-legacy-1.12.jar", SOURCE_PLUGIN_YMLS["legacy"]),
    "bukkit": ("dist/WorldListTrashCan-bukkit-1.13-1.15.jar", SOURCE_PLUGIN_YMLS["bukkit"]),
    "paper": ("dist/WorldListTrashCan-paper-1.16-1.20.jar", SOURCE_PLUGIN_YMLS["paper"]),
    "folia": ("dist/WorldListTrashCan-folia-1.20.jar", SOURCE_PLUGIN_YMLS["folia"]),
    "universal": ("dist/WorldListTrashCan-universal.jar", SOURCE_PLUGIN_YMLS["universal"]),
}
EXPECTED_COMMAND_ALIASES = {
    "worldlisttrashcan": ["wtc"],
}
EXPECTED_PERMISSIONS = {
    "WorldListTrashCan.Admin": "op",
    "WorldListTrashCan.Main": "true",
    "WorldListTrashCan.BanGui": "true",
    "WorldListTrashCan.GlobalTrashOpen": "true",
    "WorldListTrashCan.GlobalTrashTakeItem": "true",
    "WorldListTrashCan.GlobalTrashPutItem": "true",
    "WorldListTrashCan.PersonalTrashTakeItem": "true",
    "WorldListTrashCan.PersonalTrashPutItem": "true",
    "WorldListTrashCan.help": "true",
    "WorldListTrashCan.GlobalBan": "false",
    "WorldListTrashCan.Look": "false",
    "WorldListTrashCan.DropMode": "true",
    "WorldListTrashCan.PlayerTrash": "true",
}


def project_version() -> str:
    """从根 pom.xml 读取当前项目版本。"""
    root = ET.parse(REPO / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = root.findtext("m:version", namespaces=namespace)
    if version:
        return version.strip()
    parent_version = root.findtext("m:parent/m:version", namespaces=namespace)
    if parent_version:
        return parent_version.strip()
    raise RuntimeError("无法从根 pom.xml 读取项目版本")


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本。"""
    return path.read_text(encoding="utf-8", errors="replace")


def top_values(text: str) -> dict[str, str]:
    """解析 plugin.yml 顶层标量字段。"""
    values = {}
    for line in text.splitlines():
        if not line or line.startswith(" ") or line.startswith("\t") or line.lstrip().startswith("#"):
            continue
        match = re.match(r"^([A-Za-z0-9_.-]+):\s*(.*)$", line)
        if not match:
            continue
        raw_value = match.group(2).strip()
        if len(raw_value) >= 2 and raw_value[0] == raw_value[-1] and raw_value[0] in {"'", '"'}:
            raw_value = raw_value[1:-1]
        values[match.group(1)] = raw_value
    return values


def section_blocks(text: str, section: str) -> dict[str, list[str]]:
    """读取 YAML 二级节点和对应块。"""
    blocks: dict[str, list[str]] = {}
    in_section = False
    current_key = ""
    for line in text.splitlines():
        if line.startswith(section + ":"):
            in_section = True
            current_key = ""
            continue
        if not in_section:
            continue
        if line and not line.startswith(" "):
            break
        match = re.match(r"^  ([A-Za-z0-9_.-]+):(?:\s|$)", line)
        if match:
            current_key = match.group(1)
            blocks[current_key] = []
            continue
        if current_key:
            blocks[current_key].append(line)
    return blocks


def list_values(block: list[str], key: str) -> list[str]:
    """读取块中指定 YAML 列表值。"""
    values = []
    in_list = False
    key_prefix = "    " + key + ":"
    for line in block:
        if line.startswith(key_prefix):
            in_list = True
            raw = line.split(":", 1)[1].strip()
            if raw.startswith("[") and raw.endswith("]"):
                return [item.strip().strip("'\"") for item in raw[1:-1].split(",") if item.strip()]
            continue
        if in_list:
            if line.startswith("      - "):
                values.append(line.split("- ", 1)[1].strip().strip("'\""))
                continue
            if line.startswith("    ") and not line.startswith("      "):
                break
    return values


def scalar_value(block: list[str], key: str) -> str:
    """读取块中指定 YAML 标量值。"""
    key_prefix = "    " + key + ":"
    for line in block:
        if line.startswith(key_prefix):
            raw = line.split(":", 1)[1].strip()
            return raw.strip("'\"")
    return ""


def softdepends(text: str) -> list[str]:
    """读取 softdepend 列表。"""
    values = []
    in_softdepend = False
    for line in text.splitlines():
        if line.startswith("softdepend:"):
            in_softdepend = True
            continue
        if in_softdepend:
            if line.startswith("  - "):
                values.append(line.split("- ", 1)[1].strip())
                continue
            if line and not line.startswith(" "):
                break
    return values


def check_plugin_text(label: str, text: str, expected: dict, expected_version: str) -> list[str]:
    """检查单份 plugin.yml 的稳定交付接口。"""
    errors = []
    values = top_values(text)
    if values.get("name") != "WorldListTrashCan":
        errors.append(label + ": name 不是 WorldListTrashCan")
    if values.get("version") != expected_version:
        errors.append(label + ": version 不是 " + expected_version)
    if values.get("main") != expected["main"]:
        errors.append(label + ": main 不匹配")
    api_version = expected["apiVersion"]
    if api_version is None:
        if "api-version" in values:
            errors.append(label + ": legacy 不应声明 api-version")
    elif values.get("api-version") != api_version:
        errors.append(label + ": api-version 不是 " + api_version)
    folia_supported = expected["foliaSupported"]
    if folia_supported is None:
        if "folia-supported" in values:
            errors.append(label + ": 非 Folia/Universal 产物不应声明 folia-supported")
    elif values.get("folia-supported") != folia_supported:
        errors.append(label + ": folia-supported 不是 " + folia_supported)
    depends = softdepends(text)
    for dependency in ("Vault", "PlaceholderAPI"):
        if dependency not in depends:
            errors.append(label + ": softdepend 缺少 " + dependency)
    command_blocks = section_blocks(text, "commands")
    for command, expected_aliases in EXPECTED_COMMAND_ALIASES.items():
        if command not in command_blocks:
            errors.append(label + ": commands 缺少 " + command)
            continue
        actual_aliases = list_values(command_blocks[command], "aliases")
        if actual_aliases != expected_aliases:
            errors.append(label + ": " + command + " aliases 不匹配: " + ", ".join(actual_aliases))
    permission_blocks = section_blocks(text, "permissions")
    actual_permissions = set(permission_blocks)
    expected_permissions = set(EXPECTED_PERMISSIONS)
    missing = sorted(expected_permissions - actual_permissions)
    extra = sorted(actual_permissions - expected_permissions)
    if missing:
        errors.append(label + ": permissions 缺少 " + ", ".join(missing))
    if extra:
        errors.append(label + ": permissions 多出 " + ", ".join(extra))
    for permission, expected_default in sorted(EXPECTED_PERMISSIONS.items()):
        if permission not in permission_blocks:
            continue
        actual_default = scalar_value(permission_blocks[permission], "default")
        if actual_default != expected_default:
            errors.append(label + ": " + permission + " default 应为 " + expected_default + "，实际 " + actual_default)
    return errors


def source_errors(version_placeholder: str) -> list[str]:
    """检查源码 plugin.yml。"""
    errors = []
    for label, expected in sorted(SOURCE_PLUGIN_YMLS.items()):
        path = REPO / expected["path"]
        if not path.is_file():
            errors.append(label + ": 缺少源码 plugin.yml: " + expected["path"])
            continue
        errors.extend(check_plugin_text(label + " source", read_text(path), expected, version_placeholder))
    return errors


def dist_errors(version: str) -> list[str]:
    """检查 dist jar 内 plugin.yml。"""
    errors = []
    for label, value in sorted(DIST_PLUGIN_YMLS.items()):
        jar_relative, expected = value
        jar_path = REPO / jar_relative
        if not jar_path.is_file():
            errors.append(label + ": 缺少 dist jar: " + jar_relative)
            continue
        with zipfile.ZipFile(jar_path) as archive:
            if "plugin.yml" not in archive.namelist():
                errors.append(label + ": dist jar 缺少 plugin.yml")
                continue
            text = archive.read("plugin.yml").decode("utf-8", errors="replace")
        errors.extend(check_plugin_text(label + " dist", text, expected, version))
    return errors


def run_checks() -> dict:
    """执行 plugin.yml 源码和 dist 交付接口审计。"""
    version = project_version()
    errors = []
    errors.extend(source_errors("${project.version}"))
    errors.extend(dist_errors(version))
    return {
        "sourcePluginCount": len(SOURCE_PLUGIN_YMLS),
        "distPluginCount": len(DIST_PLUGIN_YMLS),
        "commandCount": len(EXPECTED_COMMAND_ALIASES),
        "permissionCount": len(EXPECTED_PERMISSIONS),
        "version": version,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan plugin.yml 源码和 dist 交付接口一致性。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("source plugin.yml:", result["sourcePluginCount"])
        print("dist plugin.yml:", result["distPluginCount"])
        print("commands:", result["commandCount"])
        print("permissions:", result["permissionCount"])
        print("version:", result["version"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
