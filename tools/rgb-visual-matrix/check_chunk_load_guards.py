import argparse
import json
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
ENTITY_LIMIT_FILES = {
    "bl-world-trashcan-shared-bukkit/src/main/java/pixeltech/bluenine/blworldtrashcan/bukkit/feature/EntityLimitFeature.java",
    "bl-world-trashcan-platform-folia-1_20/src/main/java/pixeltech/bluenine/blworldtrashcan/platform/folia/FoliaEntityLimitFeature.java",
}
WORLD_TRASH_ROUTER = "bl-world-trashcan-shared-bukkit/src/main/java/pixeltech/bluenine/blworldtrashcan/bukkit/trash/WorldTrashRouter.java"


def java_files() -> list[Path]:
    """返回需要扫描的 Java 源码文件。"""
    paths = []
    for path in REPO.rglob("*.java"):
        normalized = path.relative_to(REPO).as_posix()
        if (
            "/target/" in normalized
            or normalized.startswith("target/")
            or normalized.startswith("build/")
            or normalized.startswith("manual-build/")
            or normalized.startswith("docs/test-evidence/")
        ):
            continue
        paths.append(path)
    return sorted(paths)


def read_lines(path: Path) -> list[str]:
    """按 UTF-8 读取源码行。"""
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def check_forbidden_chunk_loads(errors: list[str]) -> None:
    """禁止出现明确会强加载或强制常驻 chunk 的调用。"""
    forbidden_tokens = ["loadChunk(", "setChunkForceLoaded(", "addPluginChunkTicket("]
    for path in java_files():
        relative = path.relative_to(REPO).as_posix()
        for index, line in enumerate(read_lines(path), start=1):
            for token in forbidden_tokens:
                if token in line:
                    errors.append(relative + ":" + str(index) + ": 禁止使用可能强加载 chunk 的调用 " + token)


def check_entity_limit_get_chunk_guards(errors: list[str]) -> None:
    """检查实体限制扫描里的 getChunkAt 前有 isChunkLoaded 防护。"""
    known_files = {REPO / item for item in ENTITY_LIMIT_FILES}
    for path in java_files():
        relative = path.relative_to(REPO).as_posix()
        lines = read_lines(path)
        for index, line in enumerate(lines):
            if "getChunkAt(" not in line:
                continue
            if path not in known_files:
                errors.append(relative + ":" + str(index + 1) + ": 新增 getChunkAt 调用需要显式审计是否会加载 chunk")
                continue
            guard_window = "\n".join(lines[max(0, index - 8):index + 1])
            if "isChunkLoaded(" not in guard_window:
                errors.append(relative + ":" + str(index + 1) + ": getChunkAt 前 8 行内缺少 isChunkLoaded 防护")


def check_world_trash_unloaded_guard(errors: list[str]) -> None:
    """检查世界垃圾桶容器访问仍受未加载区块门禁保护。"""
    path = REPO / WORLD_TRASH_ROUTER
    text = path.read_text(encoding="utf-8", errors="replace")
    required = "!isAllowLoadUnloadedChunks() && !isChunkLoaded(world, location)"
    if required not in text:
        errors.append(WORLD_TRASH_ROUTER + ": 世界垃圾桶容器访问缺少未加载 chunk 防护")
    if "world-trash.allow-load-unloaded-chunks 已开启" not in text:
        errors.append(WORLD_TRASH_ROUTER + ": 开启强加载兼容开关时缺少性能风险日志")


def check_default_world_trash_setting(errors: list[str]) -> None:
    """检查四个平台默认配置不允许加载未加载 chunk。"""
    for path in sorted(REPO.glob("bl-world-trashcan-plugin-*/src/main/resources/trash.yml")):
        relative = path.relative_to(REPO).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        if "allow-load-unloaded-chunks: false" not in text:
            errors.append(relative + ": world-trash.allow-load-unloaded-chunks 默认值不是 false")


def run_checks() -> dict:
    """执行区块强加载防护审计。"""
    errors = []
    check_forbidden_chunk_loads(errors)
    check_entity_limit_get_chunk_guards(errors)
    check_world_trash_unloaded_guard(errors)
    check_default_world_trash_setting(errors)
    return {
        "javaFileCount": len(java_files()),
        "entityLimitFileCount": len(ENTITY_LIMIT_FILES),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 BlWorldTrashCan 是否回退为强加载 chunk。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("java files:", result["javaFileCount"])
        print("entity limit files:", result["entityLimitFileCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
