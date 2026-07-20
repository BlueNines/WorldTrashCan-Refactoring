import argparse
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
REQUIRED_RESOURCES = {
    "config.yml",
    "cleanup.yml",
    "trash.yml",
    "entity-limits.yml",
    "protections.yml",
    "platform.yml",
    "data/worlds.yml",
    "messages/message_zh.yml",
    "messages/message_zh_TW.yml",
    "messages/message_en.yml",
    "messages/message_es.yml",
}
REQUIRED_COMMANDS = {
    "worldlisttrashcan",
}
REQUIRED_PERMISSIONS = {
    "WorldListTrashCan.Admin",
    "WorldListTrashCan.Main",
    "WorldListTrashCan.BanGui",
    "WorldListTrashCan.GlobalTrashOpen",
    "WorldListTrashCan.GlobalTrashTakeItem",
    "WorldListTrashCan.GlobalTrashPutItem",
    "WorldListTrashCan.PersonalTrashTakeItem",
    "WorldListTrashCan.PersonalTrashPutItem",
    "WorldListTrashCan.help",
    "WorldListTrashCan.GlobalBan",
    "WorldListTrashCan.Look",
    "WorldListTrashCan.DropMode",
    "WorldListTrashCan.PlayerTrash",
}
RELOCATED_PRISMATIC_PREFIX = "pixeltech/bluenine/blworldtrashcan/libs/croabeast/"
RAW_PRISMATIC_PREFIX = "me/croabeast/"
BSTATS_CLASSES = {
    "pixeltech/bluenine/blworldtrashcan/bukkit/bstats/Metrics.class",
    "pixeltech/bluenine/blworldtrashcan/bukkit/bstats/BStatsMetricsService.class",
}
BSTATS_ENTRY_SOURCES = {
    "legacy-1.12": "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/WorldListTrashCanLegacyPlugin.java",
    "bukkit-1.13-1.15": "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/WorldListTrashCanBukkitPlugin.java",
    "paper-1.16-1.20": "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/WorldListTrashCanPlugin.java",
    "folia-1.20": "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/WorldListTrashCanFoliaPlugin.java",
    "universal": "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/WorldListTrashCanUniversalPlugin.java",
}
PLUGIN_CONFIG_RESOURCE_NAMES = {
    "config.yml",
    "cleanup.yml",
    "trash.yml",
    "entity-limits.yml",
    "protections.yml",
    "platform.yml",
    "data/worlds.yml",
}
EXPECTED_ARTIFACTS = [
    {
        "name": "legacy-1.12",
        "jar": "WorldListTrashCan-legacy-1.12.jar",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.legacy.WorldListTrashCanLegacyPlugin",
        "apiVersion": None,
        "foliaSupported": None,
        "mainMajor": 52,
        "platformClasses": [
            "pixeltech/bluenine/blworldtrashcan/platform/legacy/LegacyPlatform.class",
        ],
    },
    {
        "name": "bukkit-1.13-1.15",
        "jar": "WorldListTrashCan-bukkit-1.13-1.15.jar",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.bukkit.WorldListTrashCanBukkitPlugin",
        "apiVersion": "1.13",
        "foliaSupported": None,
        "mainMajor": 52,
        "platformClasses": [
            "pixeltech/bluenine/blworldtrashcan/platform/bukkit/BukkitPlatform.class",
        ],
    },
    {
        "name": "paper-1.16-1.20",
        "jar": "WorldListTrashCan-paper-1.16-1.20.jar",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.WorldListTrashCanPlugin",
        "apiVersion": "1.16",
        "foliaSupported": None,
        "mainMajor": 52,
        "platformClasses": [
            "pixeltech/bluenine/blworldtrashcan/platform/paper/PaperPlatform.class",
        ],
    },
    {
        "name": "folia-1.20",
        "jar": "WorldListTrashCan-folia-1.20.jar",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.folia.WorldListTrashCanFoliaPlugin",
        "apiVersion": "1.20",
        "foliaSupported": "true",
        "mainMajor": 61,
        "platformClasses": [
            "pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaPlatform.class",
            "pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaSchedulerAdapter.class",
        ],
    },
    {
        "name": "universal",
        "jar": "WorldListTrashCan-universal.jar",
        "main": "pixeltech.bluenine.blworldtrashcan.plugin.universal.WorldListTrashCanUniversalPlugin",
        "apiVersion": "1.13",
        "foliaSupported": "true",
        "mainMajor": 52,
        "platformClasses": [
            "pixeltech/bluenine/blworldtrashcan/platform/legacy/LegacyPlatform.class",
            "pixeltech/bluenine/blworldtrashcan/platform/bukkit/BukkitPlatform.class",
            "pixeltech/bluenine/blworldtrashcan/platform/paper/PaperPlatform.class",
            "pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaPlatform.class",
            "pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaSchedulerAdapter.class",
        ],
    },
]


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


def sha256(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def class_path(class_name: str) -> str:
    """把 Java 类名转换为 jar 内 class 路径。"""
    return class_name.replace(".", "/") + ".class"


def class_major(data: bytes) -> int:
    """读取 class 文件的 major version。"""
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("不是有效 class 文件")
    return struct.unpack(">H", data[6:8])[0]


def parse_plugin_yml(text: str) -> dict[str, str]:
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


def has_yaml_child(text: str, section: str, child: str) -> bool:
    """检查 YAML 文本中指定二级键是否存在。"""
    pattern = r"(?m)^  " + re.escape(child) + r":(?:\s|$)"
    if section + ":" not in text:
        return False
    return re.search(pattern, text) is not None


def check_bstats_service_id(errors: list[str]) -> None:
    """检查源码中 bStats 服务 ID 是否保持为官方页面对应 ID。"""
    source = REPO / "bl-world-trashcan-shared-bukkit" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "bukkit" / "bstats" / "BStatsMetricsService.java"
    text = source.read_text(encoding="utf-8", errors="replace")
    if "SERVICE_ID = 24350" not in text:
        errors.append(source.relative_to(REPO).as_posix() + ": bStats SERVICE_ID 不是 24350")


def check_bstats_entrypoints(errors: list[str]) -> None:
    """检查五个插件入口是否都会启动 bStats。"""
    for label, relative_path in sorted(BSTATS_ENTRY_SOURCES.items()):
        path = REPO / relative_path
        if not path.is_file():
            errors.append(relative_path + ": bStats 入口源码不存在")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "BStatsMetricsService.start" not in text:
            errors.append(relative_path + ": " + label + " 未调用 BStatsMetricsService.start")


def check_no_plugin_bstats_toggle(errors: list[str]) -> None:
    """检查插件默认配置中没有添加 bStats 关闭开关。"""
    for plugin_dir in sorted(REPO.glob("bl-world-trashcan-plugin-*")):
        resource_dir = plugin_dir / "src" / "main" / "resources"
        if not resource_dir.is_dir():
            continue
        for resource_name in sorted(PLUGIN_CONFIG_RESOURCE_NAMES):
            path = resource_dir / resource_name
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8", errors="replace").lower()
            if "bstats" in text:
                errors.append(path.relative_to(REPO).as_posix() + ": 默认配置不应包含 bStats 关闭开关")


def check_universal_region_threaded_detection(errors: list[str]) -> None:
    """检查 universal 运行时识别 Folia/Luminol 分支，避免误走 Bukkit scheduler。"""
    path = REPO / BSTATS_ENTRY_SOURCES["universal"]
    text = path.read_text(encoding="utf-8", errors="replace")
    required_tokens = [
        "hasRuntimeClass(\"io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler\")",
        "hasRuntimeClass(\"io.papermc.paper.threadedregions.RegionizedServer\")",
        "containsRegionThreadedMarker(Bukkit.getName())",
        "containsRegionThreadedMarker(Bukkit.getVersion())",
        "normalized.contains(\"folia\")",
        "normalized.contains(\"luminol\")",
    ]
    for token in required_tokens:
        if token not in text:
            errors.append(path.relative_to(REPO).as_posix() + ": universal 运行时识别缺少 " + token)


def check_plugin_yml(label: str, plugin_text: str, expected: dict, version: str, errors: list[str]) -> dict[str, str]:
    """检查 plugin.yml 的关键交付字段。"""
    values = parse_plugin_yml(plugin_text)
    if values.get("name") != "WorldListTrashCan":
        errors.append(label + ": plugin.yml name 不是 WorldListTrashCan")
    if values.get("version") != version:
        errors.append(label + ": plugin.yml version 不是 " + version)
    if values.get("main") != expected["main"]:
        errors.append(label + ": plugin.yml main 不匹配: " + values.get("main", "<缺失>"))
    expected_api = expected["apiVersion"]
    if expected_api is None:
        if "api-version" in values:
            errors.append(label + ": legacy 产物不应声明 api-version")
    elif values.get("api-version") != expected_api:
        errors.append(label + ": api-version 不是 " + expected_api)
    expected_folia = expected["foliaSupported"]
    if expected_folia is None:
        if "folia-supported" in values:
            errors.append(label + ": 非 Folia 产物不应声明 folia-supported")
    elif values.get("folia-supported") != expected_folia:
        errors.append(label + ": folia-supported 不是 " + expected_folia)
    for command in sorted(REQUIRED_COMMANDS):
        if not has_yaml_child(plugin_text, "commands", command):
            errors.append(label + ": commands 缺少 " + command)
    for permission in sorted(REQUIRED_PERMISSIONS):
        if not has_yaml_child(plugin_text, "permissions", permission):
            errors.append(label + ": permissions 缺少 " + permission)
    if "worldlisttrashcan" not in plugin_text or "wtc" not in plugin_text:
        errors.append(label + ": 缺少 worldlisttrashcan/wtc 命令入口")
    return values


def check_archive(expected: dict, version: str) -> dict:
    """检查单个 dist jar 的资源、类和 plugin.yml。"""
    jar_path = DIST / expected["jar"]
    errors = []
    if not jar_path.is_file():
        return {
            "name": expected["name"],
            "jar": expected["jar"],
            "exists": False,
            "errors": [expected["jar"] + ": dist jar 不存在"],
        }
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        name_set = set(names)
        plugin_entries = [name for name in names if name == "plugin.yml"]
        if len(plugin_entries) != 1:
            errors.append(expected["jar"] + ": plugin.yml 数量不是 1")
            plugin_text = ""
        else:
            plugin_text = archive.read("plugin.yml").decode("utf-8", errors="replace")
            check_plugin_yml(expected["jar"], plugin_text, expected, version, errors)
        for resource in sorted(REQUIRED_RESOURCES):
            if resource not in name_set:
                errors.append(expected["jar"] + ": 缺少默认资源 " + resource)
        main_class = class_path(expected["main"])
        if main_class not in name_set:
            errors.append(expected["jar"] + ": 缺少主类 " + main_class)
            actual_major = None
        else:
            actual_major = class_major(archive.read(main_class))
            if actual_major != expected["mainMajor"]:
                errors.append(expected["jar"] + ": 主类 class major 应为 " + str(expected["mainMajor"]) + "，实际 " + str(actual_major))
            if expected["name"] == "universal":
                main_class_bytes = archive.read(main_class)
                if b"FoliaRegionScheduler" not in main_class_bytes:
                    errors.append(expected["jar"] + ": universal 主类未包含 FoliaRegionScheduler 运行时识别常量")
                if b"RegionizedServer" not in main_class_bytes:
                    errors.append(expected["jar"] + ": universal 主类未包含 RegionizedServer 运行时识别常量")
                if b"luminol" not in main_class_bytes.lower():
                    errors.append(expected["jar"] + ": universal 主类未包含 Luminol 文本兜底识别常量")
        for platform_class in expected["platformClasses"]:
            if platform_class not in name_set:
                errors.append(expected["jar"] + ": 缺少平台类 " + platform_class)
        raw_prismatic = [name for name in names if name.startswith(RAW_PRISMATIC_PREFIX)]
        if raw_prismatic:
            errors.append(expected["jar"] + ": 存在未重定位 PrismaticAPI 类 " + raw_prismatic[0])
        relocated_prismatic_count = sum(1 for name in names if name.startswith(RELOCATED_PRISMATIC_PREFIX))
        if relocated_prismatic_count == 0:
            errors.append(expected["jar"] + ": 缺少重定位后的 PrismaticAPI 类")
        for bstats_class in sorted(BSTATS_CLASSES):
            if bstats_class not in name_set:
                errors.append(expected["jar"] + ": 缺少 bStats 类 " + bstats_class)
    return {
        "name": expected["name"],
        "jar": expected["jar"],
        "exists": True,
        "sha256": sha256(jar_path),
        "mainClass": expected["main"],
        "mainMajor": actual_major,
        "relocatedPrismaticClasses": relocated_prismatic_count,
        "errors": errors,
    }


def run_checks() -> dict:
    """执行 dist 交付包完整性审计。"""
    version = project_version()
    errors = []
    check_bstats_service_id(errors)
    check_bstats_entrypoints(errors)
    check_no_plugin_bstats_toggle(errors)
    check_universal_region_threaded_detection(errors)
    artifacts = []
    for expected in EXPECTED_ARTIFACTS:
        result = check_archive(expected, version)
        artifacts.append(result)
        errors.extend(result["errors"])
    return {
        "version": version,
        "artifactCount": len(artifacts),
        "errorCount": len(errors),
        "artifacts": artifacts,
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan dist 交付 jar 的包完整性。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("version:", result["version"])
        print("artifacts:", result["artifactCount"])
        print("errors:", result["errorCount"])
        for item in result["artifacts"]:
            print(item["jar"] + " " + item.get("sha256", "<missing>"))
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
