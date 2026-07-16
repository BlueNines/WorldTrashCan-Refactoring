import argparse
import json
import re
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
LANGUAGES = ["message_zh.yml", "message_zh_TW.yml", "message_en.yml", "message_es.yml"]
SOURCE_PLATFORMS = {
    "legacy": "bl-world-trashcan-plugin-legacy-1_12/src/main/resources/messages",
    "bukkit": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/resources/messages",
    "paper": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/resources/messages",
    "folia": "bl-world-trashcan-plugin-folia-1_20/src/main/resources/messages",
}
DIST_JARS = {
    "legacy": "dist/BlWorldTrashCan-legacy-1.12.jar",
    "bukkit": "dist/BlWorldTrashCan-bukkit-1.13-1.15.jar",
    "paper": "dist/BlWorldTrashCan-paper-1.16-1.20.jar",
    "folia": "dist/BlWorldTrashCan-folia-1.20.jar",
    "universal": "dist/BlWorldTrashCan-universal.jar",
}
KEY_PATTERN = re.compile(r"^(\s*)([A-Za-z0-9_.-]+):(?:\s*(.*))?$")


def strip_inline_comment(value: str) -> str:
    """去掉简单标量后面的 YAML 行内注释。"""
    in_single = False
    in_double = False
    for index, char in enumerate(value):
        if char == "'" and not in_double:
            in_single = not in_single
        elif char == '"' and not in_single:
            in_double = not in_double
        elif char == "#" and not in_single and not in_double:
            if index == 0 or value[index - 1].isspace():
                return value[:index].strip()
    return value.strip()


def next_content_type(lines: list[str], start: int, indent: int) -> str:
    """根据下一个更深缩进的有效行判断空值键是 map 还是 list。"""
    for line in lines[start + 1:]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        line_indent = len(line) - len(line.lstrip(" "))
        if line_indent <= indent:
            return "empty"
        if line.lstrip().startswith("-"):
            return "list"
        return "map"
    return "empty"


def yaml_shape_from_lines(lines: list[str]) -> dict[str, str]:
    """解析当前项目消息 YAML 的键路径和节点类型。"""
    shape = {}
    stack: list[tuple[int, str]] = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("-"):
            continue
        match = KEY_PATTERN.match(line)
        if not match:
            continue
        indent = len(match.group(1))
        key = match.group(2)
        value = strip_inline_comment(match.group(3) or "")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([item[1] for item in stack] + [key])
        node_type = "scalar" if value else next_content_type(lines, index, indent)
        shape[path] = node_type
        if node_type == "map":
            stack.append((indent, key))
    return shape


def read_file_shape(path: Path) -> dict[str, str]:
    """按 UTF-8 读取源码消息文件并解析结构。"""
    return yaml_shape_from_lines(path.read_text(encoding="utf-8", errors="replace").splitlines())


def read_jar_shape(path: Path, resource: str) -> dict[str, str]:
    """读取 jar 内消息资源并解析结构。"""
    with zipfile.ZipFile(path) as archive:
        data = archive.read(resource)
    return yaml_shape_from_lines(data.decode("utf-8", errors="replace").splitlines())


def compare_shapes(label: str, actual: dict[str, str], expected: dict[str, str]) -> list[str]:
    """比较两个消息文件的键路径和节点类型。"""
    errors = []
    actual_keys = set(actual)
    expected_keys = set(expected)
    missing = sorted(expected_keys - actual_keys)
    extra = sorted(actual_keys - expected_keys)
    if missing:
        errors.append(label + ": 缺少消息键: " + ", ".join(missing))
    if extra:
        errors.append(label + ": 多出消息键: " + ", ".join(extra))
    for key in sorted(actual_keys & expected_keys):
        if actual[key] != expected[key]:
            errors.append(label + ": 消息键类型不一致 " + key + " actual=" + actual[key] + " expected=" + expected[key])
    return errors


def source_shapes() -> tuple[dict[str, dict[str, dict[str, str]]], list[str]]:
    """读取四个平台源码中的消息文件结构。"""
    errors = []
    shapes: dict[str, dict[str, dict[str, str]]] = {}
    for platform, relative_dir in sorted(SOURCE_PLATFORMS.items()):
        platform_shapes: dict[str, dict[str, str]] = {}
        message_dir = REPO / relative_dir
        for language in LANGUAGES:
            path = message_dir / language
            if not path.is_file():
                errors.append(platform + ": 缺少源码消息文件: " + path.relative_to(REPO).as_posix())
                continue
            platform_shapes[language] = read_file_shape(path)
        shapes[platform] = platform_shapes
    return shapes, errors


def check_source_shapes(shapes: dict[str, dict[str, dict[str, str]]]) -> list[str]:
    """检查源码消息文件在语言和平台之间是否一致。"""
    errors = []
    canonical = shapes.get("paper", {}).get("message_zh.yml", {})
    if not canonical:
        return ["paper: 缺少 message_zh.yml 基准结构"]
    for platform, platform_shapes in sorted(shapes.items()):
        platform_canonical = platform_shapes.get("message_zh.yml", {})
        for language in LANGUAGES:
            shape = platform_shapes.get(language)
            if shape is None:
                continue
            errors.extend(compare_shapes(platform + "/" + language, shape, platform_canonical))
        errors.extend(compare_shapes(platform + "/message_zh.yml", platform_canonical, canonical))
    return errors


def check_jar_shapes(source: dict[str, dict[str, dict[str, str]]]) -> tuple[int, list[str]]:
    """检查 dist jar 内消息资源是否与源码基准一致。"""
    errors = []
    checked_resources = 0
    paper_canonical = source.get("paper", {}).get("message_zh.yml", {})
    for platform, relative_path in sorted(DIST_JARS.items()):
        jar_path = REPO / relative_path
        if not jar_path.is_file():
            errors.append(platform + ": 缺少 dist jar: " + relative_path)
            continue
        with zipfile.ZipFile(jar_path) as archive:
            names = set(archive.namelist())
        expected = paper_canonical if platform == "universal" else source.get(platform, {}).get("message_zh.yml", {})
        if not expected:
            errors.append(platform + ": 缺少可用于比较的源码消息基准")
            continue
        jar_shapes = {}
        for language in LANGUAGES:
            resource = "messages/" + language
            if resource not in names:
                errors.append(platform + ": dist jar 缺少消息资源: " + resource)
                continue
            checked_resources += 1
            jar_shapes[language] = read_jar_shape(jar_path, resource)
        jar_zh = jar_shapes.get("message_zh.yml", {})
        for language, shape in sorted(jar_shapes.items()):
            errors.extend(compare_shapes(platform + " jar/" + language, shape, jar_zh))
            errors.extend(compare_shapes(platform + " jar/" + language + " vs source", shape, expected))
    return checked_resources, errors


def run_checks() -> dict:
    """执行多语言消息文件结构一致性审计。"""
    source, source_errors = source_shapes()
    errors = list(source_errors)
    errors.extend(check_source_shapes(source))
    checked_dist_resources, jar_errors = check_jar_shapes(source)
    errors.extend(jar_errors)
    canonical = source.get("paper", {}).get("message_zh.yml", {})
    return {
        "sourcePlatformCount": len(SOURCE_PLATFORMS),
        "languageCount": len(LANGUAGES),
        "distJarCount": len(DIST_JARS),
        "distResourceCount": checked_dist_resources,
        "canonicalKeyCount": len(canonical),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 BlWorldTrashCan 多语言消息文件键结构一致性。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("source platforms:", result["sourcePlatformCount"])
        print("languages:", result["languageCount"])
        print("dist jars:", result["distJarCount"])
        print("dist resources:", result["distResourceCount"])
        print("canonical keys:", result["canonicalKeyCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
