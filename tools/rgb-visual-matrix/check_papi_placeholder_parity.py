import argparse
import json
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
EXPANSION_SOURCES = {
    "legacy": "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/BlWorldTrashCanLegacyExpansion.java",
    "bukkit": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/BlWorldTrashCanBukkitExpansion.java",
    "paper": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/BlWorldTrashCanExpansion.java",
    "folia": "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/BlWorldTrashCanFoliaExpansion.java",
    "universal": "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/UniversalPlaceholderExpansion.java",
}
ENTRY_SOURCES = {
    "legacy": "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/BlWorldTrashCanLegacyPlugin.java",
    "bukkit": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/BlWorldTrashCanBukkitPlugin.java",
    "paper": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/BlWorldTrashCanPlugin.java",
    "folia": "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/BlWorldTrashCanFoliaPlugin.java",
    "universal": "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/BlWorldTrashCanUniversalPlugin.java",
}
DIST_EXPANSIONS = {
    "legacy": (
        "dist/BlWorldTrashCan-legacy-1.12.jar",
        "pixeltech/bluenine/blworldtrashcan/plugin/legacy/BlWorldTrashCanLegacyExpansion.class",
    ),
    "bukkit": (
        "dist/BlWorldTrashCan-bukkit-1.13-1.15.jar",
        "pixeltech/bluenine/blworldtrashcan/plugin/bukkit/BlWorldTrashCanBukkitExpansion.class",
    ),
    "paper": (
        "dist/BlWorldTrashCan-paper-1.16-1.20.jar",
        "pixeltech/bluenine/blworldtrashcan/plugin/BlWorldTrashCanExpansion.class",
    ),
    "folia": (
        "dist/BlWorldTrashCan-folia-1.20.jar",
        "pixeltech/bluenine/blworldtrashcan/plugin/folia/BlWorldTrashCanFoliaExpansion.class",
    ),
    "universal": (
        "dist/BlWorldTrashCan-universal.jar",
        "pixeltech/bluenine/blworldtrashcan/plugin/universal/UniversalPlaceholderExpansion.class",
    ),
}
EXPANSION_REQUIRED_SNIPPETS = {
    "extends PlaceholderExpansion": "继承 PlaceholderExpansion",
    "public boolean persist()": "实现 persist",
    "return true;": "persist 返回 true",
    'return "BlueNine";': "作者保持 BlueNine",
    'return "Wtc";': "变量前缀保持 Wtc",
    'equalsIgnoreCase("ClearTime")': "变量名保持 ClearTime 且大小写不敏感",
    "getRemainingClearSeconds()": "变量值来自剩余清理秒数",
    'return "";': "未知变量返回空字符串",
}
ENTRY_REQUIRED_SNIPPETS = {
    "registerPlaceholderApi();": "启用流程调用 PAPI 注册",
    'getPlugin("PlaceholderAPI")': "检测 PlaceholderAPI 前置",
    "expansion.register()": "调用 expansion.register",
    "%Wtc_ClearTime%": "日志写明公开变量名",
}
DIST_REQUIRED_CONSTANTS = [b"Wtc", b"ClearTime", b"BlueNine"]


def read_text(path: Path) -> str:
    """按 UTF-8 读取源码文本。"""
    return path.read_text(encoding="utf-8", errors="replace")


def check_snippets(label: str, text: str, snippets: dict[str, str]) -> list[str]:
    """检查源码是否包含必要片段。"""
    errors = []
    for snippet, description in snippets.items():
        if snippet not in text:
            errors.append(label + ": 缺少 " + description + " (" + snippet + ")")
    return errors


def check_expansion_source(label: str, path: Path) -> list[str]:
    """检查单个平台 PlaceholderExpansion 源码。"""
    if not path.is_file():
        return [label + ": PAPI expansion 源码不存在: " + path.relative_to(REPO).as_posix()]
    return check_snippets(label, read_text(path), EXPANSION_REQUIRED_SNIPPETS)


def check_entry_source(label: str, path: Path, expansion_class_name: str) -> list[str]:
    """检查单个平台插件入口是否注册 PAPI expansion。"""
    if not path.is_file():
        return [label + ": 插件入口源码不存在: " + path.relative_to(REPO).as_posix()]
    text = read_text(path)
    errors = check_snippets(label, text, ENTRY_REQUIRED_SNIPPETS)
    if expansion_class_name not in text:
        errors.append(label + ": 插件入口没有创建 " + expansion_class_name)
    return errors


def expansion_class_name(path: str) -> str:
    """从 expansion 源码路径提取类名。"""
    return Path(path).stem


def check_dist_expansion(label: str, jar_relative: str, class_name: str) -> list[str]:
    """检查 dist jar 内 PAPI expansion class 和关键常量。"""
    jar_path = REPO / jar_relative
    if not jar_path.is_file():
        return [label + ": dist jar 不存在: " + jar_relative]
    with zipfile.ZipFile(jar_path) as archive:
        names = set(archive.namelist())
        if class_name not in names:
            return [label + ": dist jar 缺少 PAPI expansion class: " + class_name]
        data = archive.read(class_name)
    errors = []
    for constant in DIST_REQUIRED_CONSTANTS:
        if constant not in data:
            errors.append(label + ": expansion class 常量池缺少 " + constant.decode("ascii"))
    return errors


def run_checks() -> dict:
    """执行 PAPI 变量一致性审计。"""
    errors = []
    for label, relative_path in sorted(EXPANSION_SOURCES.items()):
        errors.extend(check_expansion_source(label, REPO / relative_path))
    for label, relative_path in sorted(ENTRY_SOURCES.items()):
        expansion_name = expansion_class_name(EXPANSION_SOURCES[label])
        errors.extend(check_entry_source(label, REPO / relative_path, expansion_name))
    for label, value in sorted(DIST_EXPANSIONS.items()):
        jar_relative, class_name = value
        errors.extend(check_dist_expansion(label, jar_relative, class_name))
    return {
        "expansionSourceCount": len(EXPANSION_SOURCES),
        "entrySourceCount": len(ENTRY_SOURCES),
        "distJarCount": len(DIST_EXPANSIONS),
        "placeholder": "%Wtc_ClearTime%",
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 BlWorldTrashCan PAPI 变量注册和交付一致性。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("expansion sources:", result["expansionSourceCount"])
        print("entry sources:", result["entrySourceCount"])
        print("dist jars:", result["distJarCount"])
        print("placeholder:", result["placeholder"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
