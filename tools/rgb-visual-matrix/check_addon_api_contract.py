import argparse
import json
import struct
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
API_JAR = REPO / "world-list-trashcan-api" / "target" / "world-list-trashcan-api-7.0.0.jar"
API_CLASSES = (
    "pixeltech/worldlisttrashcan/api/audit/AuditRegistration.class",
    "pixeltech/worldlisttrashcan/api/audit/CleanupAuditSession.class",
    "pixeltech/worldlisttrashcan/api/audit/CleanupAuditSink.class",
    "pixeltech/worldlisttrashcan/api/audit/CleanupRunCompletion.class",
    "pixeltech/worldlisttrashcan/api/audit/CleanupRunContext.class",
    "pixeltech/worldlisttrashcan/api/audit/CleanupTrigger.class",
    "pixeltech/worldlisttrashcan/api/audit/WorldListTrashCanAuditBridge.class",
    "pixeltech/worldlisttrashcan/api/command/SubcommandDefinition.class",
    "pixeltech/worldlisttrashcan/api/command/SubcommandRegistration.class",
    "pixeltech/worldlisttrashcan/api/command/WorldListTrashCanCommandRegistry.class",
    "pixeltech/worldlisttrashcan/api/command/WorldListTrashCanSubcommand.class",
)
COMMAND_SOURCES = (
    "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/WorldListTrashCanLegacyCommand.java",
    "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/WorldListTrashCanBukkitCommand.java",
    "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/WorldListTrashCanCommand.java",
    "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/WorldListTrashCanFoliaCommand.java",
    "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/UniversalCommand.java",
)
PLUGIN_SOURCES = (
    "bl-world-trashcan-plugin-legacy-1_12/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/legacy/WorldListTrashCanLegacyPlugin.java",
    "bl-world-trashcan-plugin-bukkit-1_13_1_15/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/bukkit/WorldListTrashCanBukkitPlugin.java",
    "bl-world-trashcan-plugin-paper-1_16_1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/WorldListTrashCanPlugin.java",
    "bl-world-trashcan-plugin-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/folia/WorldListTrashCanFoliaPlugin.java",
    "bl-world-trashcan-plugin-universal/src/main/java/pixeltech/bluenine/blworldtrashcan/plugin/universal/WorldListTrashCanUniversalPlugin.java",
)
FORBIDDEN_DRIVER_PREFIXES = (
    "org/sqlite/",
    "com/mysql/",
    "org/mariadb/",
)


def read_text(relative: str) -> str:
    """按 UTF-8 读取仓库文本。"""
    return (REPO / relative).read_text(encoding="utf-8", errors="replace")


def class_major(data: bytes) -> int:
    """返回 Java class major。"""
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        return -1
    return struct.unpack(">H", data[6:8])[0]


def check_api_jar() -> tuple[list[str], dict]:
    """检查独立 API 编译产物。"""
    errors = []
    details = {"exists": API_JAR.is_file(), "classCount": 0, "maxClassMajor": -1}
    if not API_JAR.is_file():
        errors.append("API jar 不存在: " + API_JAR.relative_to(REPO).as_posix())
        return errors, details
    with zipfile.ZipFile(API_JAR) as archive:
        names = set(archive.namelist())
        missing = sorted(set(API_CLASSES) - names)
        if missing:
            errors.append("API jar 缺少 class: " + ", ".join(missing))
        if "plugin.yml" in names:
            errors.append("API jar 不能包含 plugin.yml")
        majors = [class_major(archive.read(name)) for name in API_CLASSES if name in names]
        details["classCount"] = len(majors)
        details["maxClassMajor"] = max(majors) if majors else -1
        if any(major != 52 for major in majors):
            errors.append("API class 必须全部保持 Java 8 class major 52")
    return errors, details


def check_dist_jars() -> tuple[list[str], list[dict]]:
    """检查五个交付 Jar 都包含同一 API 且不携带数据库驱动。"""
    errors = []
    details = []
    for jar_path in sorted(DIST.glob("WorldListTrashCan-*.jar")):
        with zipfile.ZipFile(jar_path) as archive:
            names = set(archive.namelist())
            missing = sorted(set(API_CLASSES) - names)
            driver_entries = sorted(
                name for name in names if name.startswith(FORBIDDEN_DRIVER_PREFIXES)
            )
            if missing:
                errors.append(jar_path.name + " 缺少 API class: " + ", ".join(missing))
            if driver_entries:
                errors.append(jar_path.name + " 错误打入数据库驱动 class")
            details.append({
                "jar": jar_path.name,
                "size": jar_path.stat().st_size,
                "apiClassCount": len(set(API_CLASSES) & names),
                "driverClassCount": len(driver_entries),
            })
    if len(details) != 5:
        errors.append("dist 交付 Jar 数量不是 5")
    return errors, details


def check_sources() -> list[str]:
    """检查五套入口和两套清理实现都接入稳定 API。"""
    errors = []
    for relative in COMMAND_SOURCES:
        text = read_text(relative)
        for marker in (
            "addonCommands.dispatch",
            "addonCommands.completeFirstLevel",
            "addonCommands.tabComplete",
            "addonCommands.sendHelp",
        ):
            if marker not in text:
                errors.append(relative + " 缺少 " + marker)
    for relative in PLUGIN_SOURCES:
        text = read_text(relative)
        for marker in (
            "WorldListTrashCanApiHost",
            "apiHost.enable()",
            "apiHost.disable()",
            "apiHost.commandRegistry()",
        ):
            if marker not in text:
                errors.append(relative + " 缺少 " + marker)
    cleanup = read_text(
        "bl-world-trashcan-shared-bukkit/src/main/java/pixeltech/bluenine/blworldtrashcan/bukkit/feature/CleanupFeature.java"
    )
    folia = read_text(
        "bl-world-trashcan-platform-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaRegionCleanupFeature.java"
    )
    for label, text in (("CleanupFeature", cleanup), ("FoliaRegionCleanupFeature", folia)):
        for marker in ("auditBridge.beginRun", "recordItem", "CleanupRunCompletion"):
            if marker not in text:
                errors.append(label + " 缺少审计接入标记 " + marker)
    host = read_text(
        "bl-world-trashcan-shared-bukkit/src/main/java/pixeltech/bluenine/blworldtrashcan/bukkit/api/WorldListTrashCanApiHost.java"
    )
    for marker in (
        "ServicesManager().register(WorldListTrashCanAuditBridge.class",
        "ServicesManager().register(WorldListTrashCanCommandRegistry.class",
        "PluginDisableEvent",
    ):
        if marker not in host:
            errors.append("WorldListTrashCanApiHost 缺少 " + marker)
    return errors


def run_checks() -> dict:
    """执行附属插件 API 契约审计。"""
    api_errors, api_details = check_api_jar()
    dist_errors, dist_details = check_dist_jars()
    source_errors = check_sources()
    errors = api_errors + dist_errors + source_errors
    return {
        "api": api_details,
        "dist": dist_details,
        "commandSources": len(COMMAND_SOURCES),
        "pluginSources": len(PLUGIN_SOURCES),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 附属插件 API 契约。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("api classes:", result["api"]["classCount"])
        print("api max class major:", result["api"]["maxClassMajor"])
        print("dist jars:", len(result["dist"]))
        print("command sources:", result["commandSources"])
        print("plugin sources:", result["pluginSources"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
