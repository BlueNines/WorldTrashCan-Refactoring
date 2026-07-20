import argparse
import json
import re
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
LANGUAGES = [
    "message_zh.yml",
    "message_zh_TW.yml",
    "message_en.yml",
    "message_es.yml",
]
SOURCE_MESSAGES = sorted(REPO.glob("bl-world-trashcan-plugin-*/src/main/resources/messages/message_*.yml"))
DIST_JARS = [
    "WorldListTrashCan-legacy-1.12.jar",
    "WorldListTrashCan-bukkit-1.13-1.15.jar",
    "WorldListTrashCan-paper-1.16-1.20.jar",
    "WorldListTrashCan-folia-1.20.jar",
    "WorldListTrashCan-universal.jar",
]
RGB_PATTERN = re.compile(r"&#[0-9A-Fa-f]{6}")
LEGACY_COLOR_PATTERN = re.compile(r"&[0-9A-FK-ORa-fk-or]")
RENDERER = REPO / "bl-world-trashcan-shared-bukkit" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "bukkit" / "message" / "RichTextRenderer.java"


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本。"""
    return path.read_text(encoding="utf-8", errors="replace")


def check_message_text(label: str, text: str, errors: list[str]) -> int:
    """检查默认语言消息是否使用 RGB 且不残留老式颜色。"""
    rgb_count = len(RGB_PATTERN.findall(text))
    if rgb_count < 20:
        errors.append(label + ": RGB 颜色数量过少，疑似不是 RGB 默认消息")
    for match in LEGACY_COLOR_PATTERN.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        errors.append(label + ":" + str(line) + ": 默认语言 message 不应残留老式颜色 " + match.group(0))
    return rgb_count


def check_source_messages(errors: list[str]) -> dict[str, int]:
    """检查四个平台源码默认语言消息。"""
    counts = {}
    expected = 4 * len(LANGUAGES)
    if len(SOURCE_MESSAGES) != expected:
        errors.append("源码 message_*.yml 数量不是 " + str(expected) + "，实际 " + str(len(SOURCE_MESSAGES)))
    for path in SOURCE_MESSAGES:
        if path.name not in LANGUAGES:
            errors.append(path.relative_to(REPO).as_posix() + ": 非预期默认语言文件")
            continue
        relative = path.relative_to(REPO).as_posix()
        counts[relative] = check_message_text(relative, read_text(path), errors)
    return counts


def check_dist_messages(errors: list[str]) -> dict[str, int]:
    """检查 dist 交付包内默认语言消息。"""
    counts = {}
    for jar_name in DIST_JARS:
        jar_path = DIST / jar_name
        if not jar_path.is_file():
            errors.append(jar_name + ": dist jar 不存在")
            continue
        with zipfile.ZipFile(jar_path) as archive:
            names = set(archive.namelist())
            for language in LANGUAGES:
                resource = "messages/" + language
                if resource not in names:
                    errors.append(jar_name + ": 缺少 " + resource)
                    continue
                text = archive.read(resource).decode("utf-8", errors="replace")
                counts[jar_name + "!" + resource] = check_message_text(jar_name + "!" + resource, text, errors)
    return counts


def check_renderer_fallback(errors: list[str]) -> None:
    """检查渲染器仍同时支持 RGB 降级和 &a 老式颜色。"""
    text = read_text(RENDERER)
    required_tokens = [
        "PrismaticAPI.legacy().colorize(raw)",
        "PrismaticAPI.legacy().colorize(player, raw)",
        "legacyFallback(raw)",
        "downgradeHexColors(raw)",
        "nearestLegacyColor",
        "ChatColor.translateAlternateColorCodes('&'",
    ]
    for token in required_tokens:
        if token not in text:
            errors.append(RENDERER.relative_to(REPO).as_posix() + ": 缺少渲染兼容逻辑 " + token)


def run_checks() -> dict:
    """执行默认语言 RGB 消息审计。"""
    errors = []
    source_counts = check_source_messages(errors)
    dist_counts = check_dist_messages(errors)
    check_renderer_fallback(errors)
    return {
        "sourceCount": len(source_counts),
        "distCount": len(dist_counts),
        "sourceRgbCounts": source_counts,
        "distRgbCounts": dist_counts,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查默认多语言 message 是否保持 RGB，并兼容低版本降级。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("source messages:", result["sourceCount"])
        print("dist messages:", result["distCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
