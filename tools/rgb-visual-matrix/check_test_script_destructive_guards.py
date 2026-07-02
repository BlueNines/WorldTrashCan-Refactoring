import argparse
import ast
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
SCRIPT_ROOT = REPO / "tools" / "rgb-visual-matrix"
SAFE_RMTREE_NAMES = {
    "BUILD_ROOT",
    "build_root",
    "classes_dir",
    "resources_dir",
    "case_dir",
    "target",
    "server_dir",
}
SAFE_RMTREE_MARKERS = (
    "BUILD_ROOT",
    "build/",
    "manual-build/",
    "run_root",
    "case_dir",
    "EVIDENCE_ROOT",
    "evidence_dir",
    "classes_dir",
    "resources_dir",
)
EXTERNAL_SERVER_MARKERS = (
    "Path(case[\"serverDir\"])",
    "Path(case['serverDir'])",
    "SERVER_WORK",
    "server_work",
)
PROTECTED_PATH_SEGMENT_PATTERN = re.compile(
    r"""["'](?:logs|cache|assets|world|world_[^"']*|world\*)["']""",
    re.IGNORECASE,
)
PROTECTED_GLOB_PATTERN = re.compile(
    r"""\.(?:glob|rglob)\(\s*["'](?:logs\*?|cache\*?|assets\*?|world\*|world_\*)["']""",
    re.IGNORECASE,
)
DESTRUCTIVE_SHELL_PATTERN = re.compile(
    r"\b(?:Remove-Item|Delete-Item|rmdir|del\s+/|rm\s+-r|rm\s+-rf)\b",
    re.IGNORECASE,
)


def read_lines(path: Path) -> list[str]:
    """按 UTF-8 读取脚本行。"""
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def python_scripts() -> list[Path]:
    """返回需要审计的测试与交付脚本。"""
    return sorted(SCRIPT_ROOT.glob("*.py"))


def source_segment(text: str, node: ast.AST) -> str:
    """获取 AST 节点对应源码片段。"""
    segment = ast.get_source_segment(text, node)
    return segment or ""


def call_name(node: ast.Call) -> str:
    """返回调用名称，用于识别 unlink 和 rmtree。"""
    func = node.func
    if isinstance(func, ast.Attribute):
        if isinstance(func.value, ast.Name):
            return func.value.id + "." + func.attr
        return func.attr
    if isinstance(func, ast.Name):
        return func.id
    return ""


def receiver_name(node: ast.Call) -> str:
    """返回 attr 调用的接收者变量名。"""
    if not isinstance(node.func, ast.Attribute):
        return ""
    value = node.func.value
    if isinstance(value, ast.Name):
        return value.id
    return ""


def first_arg_name(node: ast.Call) -> str:
    """返回调用首个参数变量名。"""
    if not node.args:
        return ""
    first = node.args[0]
    if isinstance(first, ast.Name):
        return first.id
    return ""


def surrounding(lines: list[str], lineno: int, radius: int) -> str:
    """返回指定行附近源码。"""
    start = max(0, lineno - radius - 1)
    end = min(len(lines), lineno + radius)
    return "\n".join(lines[start:end])


def variable_context(lines: list[str], lineno: int, name: str) -> str:
    """回溯变量最近的赋值或循环来源。"""
    if not name:
        return ""
    hits = []
    assign_pattern = re.compile(r"^\s*" + re.escape(name) + r"\s*=")
    loop_pattern = re.compile(r"^\s*for\s+" + re.escape(name) + r"\s+in\s+")
    for index in range(lineno - 2, max(-1, lineno - 40), -1):
        line = lines[index]
        if assign_pattern.search(line) or loop_pattern.search(line):
            hits.append(line.strip())
            break
    return "\n".join(hits)


def has_external_server_marker(text: str) -> bool:
    """判断源码片段是否引用真实外部测试服目录。"""
    return any(marker in text for marker in EXTERNAL_SERVER_MARKERS)


def has_protected_segment(text: str) -> bool:
    """判断源码片段是否包含受保护目录段。"""
    return bool(PROTECTED_PATH_SEGMENT_PATTERN.search(text))


def has_protected_glob(text: str) -> bool:
    """判断源码片段是否遍历受保护目录。"""
    return bool(PROTECTED_GLOB_PATTERN.search(text))


def rmtree_is_allowlisted(argument_name: str, context: str) -> bool:
    """判断 rmtree 是否只作用于临时构建或证据目录。"""
    if argument_name in SAFE_RMTREE_NAMES:
        return any(marker in context for marker in SAFE_RMTREE_MARKERS) or argument_name in {"classes_dir", "resources_dir"}
    return any(marker in context for marker in SAFE_RMTREE_MARKERS)


def check_rmtree(path: Path, text: str, lines: list[str], node: ast.Call, errors: list[str]) -> None:
    """审计 shutil.rmtree 调用是否可能删除真实测试服目录。"""
    relative = path.relative_to(REPO).as_posix()
    line = source_segment(text, node)
    argument_name = first_arg_name(node)
    context = surrounding(lines, node.lineno, 14)
    argument_context = variable_context(lines, node.lineno, argument_name)
    combined = "\n".join([line, argument_context, context])
    if has_external_server_marker(combined):
        errors.append(relative + ":" + str(node.lineno) + ": 禁止对真实测试服目录或 SERVER_WORK 派生路径调用 shutil.rmtree")
        return
    if has_protected_glob(combined) or (has_protected_segment(combined) and not rmtree_is_allowlisted(argument_name, combined)):
        errors.append(relative + ":" + str(node.lineno) + ": 禁止删除 logs/world*/cache/assets 受保护目录")
        return
    if not rmtree_is_allowlisted(argument_name, combined):
        errors.append(relative + ":" + str(node.lineno) + ": shutil.rmtree 未证明只作用于 build/evidence 临时目录")


def check_unlink(path: Path, text: str, lines: list[str], node: ast.Call, errors: list[str]) -> None:
    """审计 unlink 调用是否可能删除测试服保留目录内容。"""
    relative = path.relative_to(REPO).as_posix()
    line = source_segment(text, node)
    name = receiver_name(node)
    context = variable_context(lines, node.lineno, name)
    local = "\n".join([line, context])
    nearby = surrounding(lines, node.lineno, 8)
    if has_protected_glob(local) or has_protected_glob(nearby):
        errors.append(relative + ":" + str(node.lineno) + ": 禁止遍历删除 logs/world*/cache/assets")
        return
    if has_external_server_marker(local) and has_protected_segment(local):
        errors.append(relative + ":" + str(node.lineno) + ": 禁止删除真实测试服 logs/world*/cache/assets 内容")
        return
    if "Path(case[\"serverDir\"])" in nearby or "Path(case['serverDir'])" in nearby:
        if has_protected_segment(local):
            errors.append(relative + ":" + str(node.lineno) + ": 禁止删除真实测试服受保护路径")


def check_shell_destructive_commands(path: Path, lines: list[str], errors: list[str]) -> None:
    """审计脚本中是否拼接了高风险 shell 删除命令。"""
    relative = path.relative_to(REPO).as_posix()
    for index, line in enumerate(lines, start=1):
        if DESTRUCTIVE_SHELL_PATTERN.search(line) and has_protected_segment(line):
            errors.append(relative + ":" + str(index) + ": 禁止对 logs/world*/cache/assets 拼接 shell 删除命令")


def check_script(path: Path) -> dict:
    """审计单个 Python 脚本。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    errors = []
    rmtree_count = 0
    unlink_count = 0
    try:
        tree = ast.parse(text, filename=str(path))
    except SyntaxError as exc:
        return {
            "script": path.relative_to(REPO).as_posix(),
            "rmtreeCalls": 0,
            "unlinkCalls": 0,
            "errors": [str(exc)],
        }
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not hasattr(node, "lineno"):
            continue
        name = call_name(node)
        if name == "shutil.rmtree":
            rmtree_count += 1
            check_rmtree(path, text, lines, node, errors)
        elif name.endswith(".unlink") or name == "unlink":
            unlink_count += 1
            check_unlink(path, text, lines, node, errors)
    check_shell_destructive_commands(path, lines, errors)
    return {
        "script": path.relative_to(REPO).as_posix(),
        "rmtreeCalls": rmtree_count,
        "unlinkCalls": unlink_count,
        "errors": errors,
    }


def run_checks() -> dict:
    """执行测试脚本破坏性操作审计。"""
    script_results = [check_script(path) for path in python_scripts()]
    errors = []
    for result in script_results:
        errors.extend(result["errors"])
    return {
        "scriptCount": len(script_results),
        "rmtreeCallCount": sum(item["rmtreeCalls"] for item in script_results),
        "unlinkCallCount": sum(item["unlinkCalls"] for item in script_results),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查测试脚本是否会删除测试服 logs/world*/cache/assets。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("scripts:", result["scriptCount"])
        print("rmtree calls:", result["rmtreeCallCount"])
        print("unlink calls:", result["unlinkCallCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
