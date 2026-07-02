import argparse
import importlib.util
import json
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
RUNNER = REPO / "tools" / "rgb-visual-matrix" / "run_delivery_audits.py"
README = REPO / "README.md"
HARDENING_DOC = REPO / "docs" / "长期硬化缺口清单.md"
EXECUTION_LOG = REPO / "docs" / "重构执行记录.md"


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def load_runner_module():
    """加载统一预检入口模块。"""
    spec = importlib.util.spec_from_file_location("blwtc_delivery_audits", RUNNER)
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载 run_delivery_audits.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def latest_execution_section() -> str:
    """截取执行记录中的统一交付预检章节。"""
    text = read_text(EXECUTION_LOG)
    marker = "## 2026-07-02 统一交付预检入口"
    start = text.find(marker)
    if start < 0:
        return ""
    next_heading = text.find("\n## ", start + len(marker))
    if next_heading < 0:
        return text[start:]
    return text[start:next_heading]


def command_basename(command: list[str]) -> str:
    """返回审计命令中的脚本文件名。"""
    for item in command:
        if item.endswith(".py"):
            return Path(item).name
    if command and command[0] == "git":
        return " ".join(command[:3])
    return command[0] if command else ""


def expected_counts() -> dict:
    """从统一预检入口读取当前审计数量和名称。"""
    module = load_runner_module()
    default_commands = list(module.PYTHON_AUDITS)
    default_commands.extend(module.GIT_AUDITS)
    full_count = len(default_commands) + 1
    return {
        "defaultCount": len(default_commands),
        "fullCount": full_count,
        "auditNames": [name for name, _command in default_commands],
        "commandBasenames": [command_basename(command) for _name, command in default_commands if command_basename(command)],
    }


def check_count_phrases(result: dict, errors: list[str]) -> None:
    """检查上层文档是否写着当前预检数量。"""
    default_count = result["defaultCount"]
    full_count = result["fullCount"]
    readme_text = read_text(README)
    hardening_text = read_text(HARDENING_DOC)
    execution_section = latest_execution_section()
    if "当前默认 " + str(default_count) + " 项审计和完整 " + str(full_count) + " 项审计均为 `failed: 0`" not in readme_text:
        errors.append("README.md: 统一预检数量不是当前 " + str(default_count) + "/" + str(full_count))
    if "当前默认 " + str(default_count) + " 项和完整 " + str(full_count) + " 项均 `failed: 0`" not in hardening_text:
        errors.append("docs/长期硬化缺口清单.md: 统一预检数量不是当前 " + str(default_count) + "/" + str(full_count))
    if "默认模式当前串联以下 " + str(default_count) + " 项" not in execution_section:
        errors.append("docs/重构执行记录.md: 统一预检章节缺少当前默认数量 " + str(default_count))
    if "audits: " + str(default_count) not in execution_section:
        errors.append("docs/重构执行记录.md: 统一预检章节缺少默认 audits: " + str(default_count))
    if "audits: " + str(full_count) not in execution_section:
        errors.append("docs/重构执行记录.md: 统一预检章节缺少完整 audits: " + str(full_count))


def check_execution_audit_list(result: dict, errors: list[str]) -> None:
    """检查执行记录中的审计列表覆盖当前统一预检命令。"""
    execution_section = latest_execution_section()
    if not execution_section:
        errors.append("docs/重构执行记录.md: 缺少统一交付预检入口章节")
        return
    for basename in result["commandBasenames"]:
        if basename not in execution_section:
            errors.append("docs/重构执行记录.md: 统一预检章节缺少 " + basename)
    for audit_name in result["auditNames"]:
        if "OK " + audit_name not in execution_section:
            errors.append("docs/重构执行记录.md: 统一预检结果缺少 OK " + audit_name)


def run_checks() -> dict:
    """执行统一预检文档口径审计。"""
    result = expected_counts()
    errors = []
    check_count_phrases(result, errors)
    check_execution_audit_list(result, errors)
    return {
        "defaultCount": result["defaultCount"],
        "fullCount": result["fullCount"],
        "auditCount": len(result["auditNames"]),
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查统一交付预检文档是否同步当前审计数量。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("default audits:", result["defaultCount"])
        print("full audits:", result["fullCount"])
        print("named audits:", result["auditCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
