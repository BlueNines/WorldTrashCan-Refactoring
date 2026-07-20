import argparse
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
BUILD_ROOT = base.REPO / "build" / "permission-negative-fixture"
JAVAC17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"
TARGET_CASE_IDS = [
    "managed_paper1122",
    "external_spigot2612",
    "external_folia1218",
]


FIXTURE_SOURCE = r'''
package ai.wtc.permission;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** WorldListTrashCan 权限负向验收用临时插件。 */
public final class PermissionDenyFixture extends JavaPlugin implements CommandExecutor {
    private final Map<String, List<PermissionAttachment>> attachments = new HashMap<String, List<PermissionAttachment>>();

    /** 注册测试命令。 */
    @Override
    public void onEnable() {
        getCommand("permfixture").setExecutor(this);
        getLogger().info("AI_PERMFIXTURE_READY");
    }

    /** 清理所有权限附件。 */
    @Override
    public void onDisable() {
        clearAll();
    }

    /** 执行权限测试命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sender.sendMessage("AI_PERMFIXTURE_USAGE deny|clear|status");
            return true;
        }
        if ("deny".equalsIgnoreCase(args[0]) && args.length >= 3) {
            deny(sender, args);
            return true;
        }
        if ("clear".equalsIgnoreCase(args[0]) && args.length >= 2) {
            clear(sender, args[1]);
            return true;
        }
        if ("status".equalsIgnoreCase(args[0]) && args.length >= 2) {
            status(sender, args[1]);
            return true;
        }
        sender.sendMessage("AI_PERMFIXTURE_USAGE deny|clear|status");
        return true;
    }

    /** 给在线玩家添加 false 权限附件。 */
    private void deny(CommandSender sender, String[] args) {
        Player player = Bukkit.getPlayer(args[1]);
        if (player == null) {
            sender.sendMessage("AI_PERMFIXTURE_DENY player=" + args[1] + " online=false");
            return;
        }
        List<PermissionAttachment> list = attachments.get(player.getName().toLowerCase());
        if (list == null) {
            list = new ArrayList<PermissionAttachment>();
            attachments.put(player.getName().toLowerCase(), list);
        }
        PermissionAttachment attachment = player.addAttachment(this);
        for (int index = 2; index < args.length; index++) {
            attachment.setPermission(args[index], false);
        }
        player.recalculatePermissions();
        list.add(attachment);
        sender.sendMessage("AI_PERMFIXTURE_DENY player=" + player.getName() + " permissions=" + (args.length - 2));
    }

    /** 移除指定玩家的权限附件。 */
    private void clear(CommandSender sender, String playerName) {
        int count = clearPlayer(playerName);
        sender.sendMessage("AI_PERMFIXTURE_CLEAR player=" + playerName + " removed=" + count);
    }

    /** 输出指定玩家的关键权限状态。 */
    private void status(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage("AI_PERMFIXTURE_STATUS player=" + playerName + " online=false");
            return;
        }
        sender.sendMessage("AI_PERMFIXTURE_STATUS player=" + player.getName()
                + " op=" + player.isOp()
                + " global=" + player.hasPermission("WorldListTrashCan.GlobalTrashOpen")
                + " personal=" + player.hasPermission("WorldListTrashCan.PlayerTrash")
                + " admin=" + player.hasPermission("WorldListTrashCan.Admin"));
    }

    /** 清理全部玩家附件。 */
    private void clearAll() {
        for (String playerName : new ArrayList<String>(attachments.keySet())) {
            clearPlayer(playerName);
        }
        attachments.clear();
    }

    /** 清理单个玩家附件。 */
    private int clearPlayer(String playerName) {
        List<PermissionAttachment> list = attachments.remove(playerName.toLowerCase());
        if (list == null) {
            return 0;
        }
        int removed = 0;
        for (PermissionAttachment attachment : list) {
            try {
                attachment.remove();
                removed++;
            } catch (RuntimeException ignored) {
                // 测试服关闭或玩家离线时附件可能已被 Bukkit 清理。
            }
        }
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            player.recalculatePermissions();
        }
        return removed;
    }
}
'''


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def to_json_value(value):
    """把 Path 等对象转换成 JSON 可写值。"""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, list):
        return [to_json_value(item) for item in value]
    if isinstance(value, dict):
        return {key: to_json_value(item) for key, item in value.items()}
    return value


def selected_cases(case_id: str | None) -> list[dict]:
    """按参数选择本轮要跑的服务端用例。"""
    cases = []
    for wanted in TARGET_CASE_IDS:
        for item in external.EXTERNAL_MATRIX:
            if item["id"] == wanted:
                cases.append(external.universal_case(item))
                break
    if not case_id:
        return cases
    for item in cases:
        if case_id in (item["id"], item.get("sourceId", ""), item["label"], item["version"]):
            return [item]
    raise RuntimeError("未知权限负向测试用例: " + case_id)


def sha256(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dist_plugin_path(case: dict) -> Path:
    """返回本轮部署用 dist 插件路径。"""
    return base.REPO / "dist" / str(case["plugin"])


def build_permission_fixture() -> Path:
    """编译权限负向测试夹具插件。"""
    if not JAVAC17.is_file() or not JAR17.is_file():
        raise RuntimeError("缺少 JDK17 工具，无法编译权限夹具")
    if not BUKKIT_API_JAR.is_file():
        raise RuntimeError("缺少 Bukkit API jar，无法编译权限夹具: " + str(BUKKIT_API_JAR))
    if BUILD_ROOT.exists():
        shutil.rmtree(BUILD_ROOT)
    source_dir = BUILD_ROOT / "src" / "ai" / "wtc" / "permission"
    classes_dir = BUILD_ROOT / "classes"
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    source = source_dir / "PermissionDenyFixture.java"
    source.write_text(FIXTURE_SOURCE, encoding="utf-8")
    subprocess.check_call([
        str(JAVAC17),
        "-encoding", "UTF-8",
        "--release", "8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source),
    ])
    plugin_yml = (
        "name: PermissionDenyFixture\n"
        "version: 1.0.0\n"
        "main: ai.wtc.permission.PermissionDenyFixture\n"
        "folia-supported: true\n"
        "commands:\n"
        "  permfixture:\n"
        "    description: WorldListTrashCan permission test fixture\n"
    )
    (classes_dir / "plugin.yml").write_text(plugin_yml, encoding="utf-8")
    jar_path = BUILD_ROOT / "PermissionDenyFixture.jar"
    subprocess.check_call([str(JAR17), "cf", str(jar_path), "-C", str(classes_dir), "."])
    return jar_path


def screenshot_info(path: Path) -> dict:
    """读取截图基础信息。"""
    image = Image.open(path).convert("RGB")
    return {
        "path": str(path),
        "width": image.width,
        "height": image.height,
        "brightness": base.image_brightness(image),
    }


def wait_client_markers(client_log: Path, offset: int, markers: list[str], timeout: float) -> dict:
    """等待真实客户端日志出现聊天标记。"""
    deadline = time.time() + timeout
    latest = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            latest = text
        if all(marker in text for marker in markers):
            return {"status": "PASS", "markers": markers, "excerpt": text[-2000:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": markers, "excerpt": latest[-2000:]}


def wait_client_marker_sets(client_log: Path, offset: int, marker_sets: list[list[str]], timeout: float) -> dict:
    """等待真实客户端日志命中任意一组聊天标记。"""
    deadline = time.time() + timeout
    latest = ""
    lowered_sets = [[marker.lower() for marker in markers] for markers in marker_sets]
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            latest = text
        lower_text = text.lower()
        for index, markers in enumerate(lowered_sets):
            if all(marker in lower_text for marker in markers):
                return {"status": "PASS", "markers": marker_sets[index], "excerpt": text[-2000:]}
        time.sleep(0.4)
    return {"status": "FAIL", "markers": marker_sets, "excerpt": latest[-2000:]}


def run_console(process, command_log: Path, command: str, wait: float = 0.3) -> None:
    """向服务端控制台发送命令。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def run_console_capture(process, server_log: Path, command_log: Path, command: str, wait: float = 0.8) -> str:
    """执行控制台命令并返回新增日志。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, wait)
    return external.strip_ansi(external.read_text_since(server_log, offset))


def run_console_expect(process, server_log: Path, command_log: Path, command: str,
                       markers: list[str], timeout: float = 8.0) -> str:
    """执行控制台命令并等待指定输出。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, 0.3)
    return external.wait_command_markers(server_log, offset, markers, timeout, command)


def send_client_command(case: dict, game_dir: Path, run_dir: Path, command: str,
                        suffix: str, wait_seconds: float = 1.0) -> dict:
    """用窗口消息发送真实客户端命令并截图。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.send_chat_line_by_window_message(hwnd, command)
    time.sleep(wait_seconds)
    screenshot = external.capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "name": suffix,
        "command": command,
        "status": "PASS",
        "screenshot": str(screenshot),
        "brightness": base.image_brightness(Image.open(screenshot)),
    }


def render_text_screenshot(text: str, target: Path, title: str) -> dict:
    """把关键日志渲染成 PNG 证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default()
    lines = [title, ""] + text.splitlines()
    width = 1400
    line_height = 18
    height = max(220, 28 + len(lines) * line_height)
    image = Image.new("RGB", (width, height), (18, 24, 32))
    draw = ImageDraw.Draw(image)
    y = 14
    for index, line in enumerate(lines):
        color = (245, 184, 46) if index == 0 else (213, 222, 233)
        draw.text((18, y), line[:180], fill=color, font=font)
        y += line_height
    image.save(target)
    return screenshot_info(target)


def assert_client_check(check: dict, label: str) -> None:
    """断言客户端聊天标记检查通过。"""
    if check.get("status") != "PASS":
        raise AssertionError(label + " 客户端日志未出现标记: " + str(check))


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """执行单个服务端的真实客户端权限负向验收。"""
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-commands.log")
    process = None
    client = None
    username = ""
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case["version"],
        "serverDir": str(case["serverDir"]),
        "status": "FAIL",
        "plugin": str(case["plugin"]),
        "universalJarSha256": "",
    }
    try:
        process = external.launch_server(case, run_dir)
        result["universalJarSha256"] = sha256(dist_plugin_path(case))
        server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        external.wait_player_online(case, username, server_log)
        result["username"] = username
        result["clientPid"] = client.pid

        run_console(process, command_log, "deop " + username, 0.5)
        client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
        denied_offset = external.log_text_offset(client_log)
        denied = send_client_command(case, game_dir, run_dir, "/wtc reload", "permission-denied-reload-f2", 1.0)
        denied_check = wait_client_marker_sets(client_log, denied_offset, [
            ["权限不足"],
            ["没有权限"],
            ["沒有權限"],
            ["do not have permission"],
            ["no tienes permiso"],
        ], 8.0)
        assert_client_check(denied_check, case["id"] + " reload negative")

        global_fixture = run_console_expect(process, server_log, command_log,
                                            "permfixture deny " + username + " WorldListTrashCan.GlobalTrashOpen",
                                            ["AI_PERMFIXTURE_DENY", "permissions=1"])
        open_offset = external.log_text_offset(client_log)
        denied_global = send_client_command(case, game_dir, run_dir, "/wtc global", "permission-denied-global-f2", 1.0)
        global_check = wait_client_marker_sets(client_log, open_offset, [
            ["权限不足", "公共垃圾桶"],
            ["不能打开公共垃圾桶"],
            ["没有权限打开公共垃圾桶"],
            ["沒有權限打開公共垃圾桶"],
            ["permission", "global"],
            ["permiso", "global"],
        ], 8.0)
        assert_client_check(global_check, case["id"] + " global negative")

        run_console_expect(process, server_log, command_log,
                           "permfixture clear " + username,
                           ["AI_PERMFIXTURE_CLEAR"])
        personal_fixture = run_console_expect(process, server_log, command_log,
                                              "permfixture deny " + username + " WorldListTrashCan.PlayerTrash",
                                              ["AI_PERMFIXTURE_DENY", "permissions=1"])
        personal_offset = external.log_text_offset(client_log)
        denied_personal = send_client_command(case, game_dir, run_dir, "/wtc personal", "permission-denied-personal-f2", 1.0)
        personal_check = wait_client_marker_sets(client_log, personal_offset, [
            ["权限不足", "个人垃圾桶"],
            ["不能打开个人垃圾桶"],
            ["没有权限打开个人垃圾桶"],
            ["沒有權限打開個人垃圾桶"],
            ["permission", "personal"],
            ["permiso", "personal"],
        ], 8.0)
        assert_client_check(personal_check, case["id"] + " personal negative")

        run_console_expect(process, server_log, command_log,
                           "permfixture clear " + username,
                           ["AI_PERMFIXTURE_CLEAR"])
        run_console(process, command_log, "op " + username, 0.7)
        allowed_offset = external.log_text_offset(client_log)
        allowed = send_client_command(case, game_dir, run_dir, "/wtc reload", "permission-allowed-reload-f2", 1.4)
        allowed_check = wait_client_marker_sets(client_log, allowed_offset, [
            ["已重载"],
            ["配置已重载"],
            ["已重載"],
            ["reloaded"],
            ["recargado"],
        ], 8.0)
        assert_client_check(allowed_check, case["id"] + " reload op")

        platform_output = run_console_capture(process, server_log, command_log, "wtc platform", 1.0)
        result.update({
            "status": "PASS",
            "deniedReload": {
                "command": denied["command"],
                "screenshot": screenshot_info(Path(denied["screenshot"])),
                "clientLog": denied_check,
            },
            "deniedGlobal": {
                "command": denied_global["command"],
                "screenshot": screenshot_info(Path(denied_global["screenshot"])),
                "clientLog": global_check,
                "fixture": global_fixture[-1200:],
            },
            "deniedPersonal": {
                "command": denied_personal["command"],
                "screenshot": screenshot_info(Path(denied_personal["screenshot"])),
                "clientLog": personal_check,
                "fixture": personal_fixture[-1200:],
            },
            "allowedReload": {
                "command": allowed["command"],
                "screenshot": screenshot_info(Path(allowed["screenshot"])),
                "clientLog": allowed_check,
            },
            "platformOutput": platform_output[-2000:],
        })
        result["commandScreenshot"] = render_text_screenshot(
            command_log.read_text(encoding="utf-8", errors="replace"),
            run_dir / "server-screenshots" / (case["id"] + "-commands.png"),
            case["label"] + " / permission commands",
        )
        result["platformScreenshot"] = render_text_screenshot(
            platform_output,
            run_dir / "server-screenshots" / (case["id"] + "-platform.png"),
            case["label"] + " / platform output",
        )
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = repr(error)
        if client is not None and username:
            try:
                screenshot = external.capture_named_screenshot(case, game_dir, run_dir, "permission-failure-f2")
                result["failureScreenshot"] = screenshot_info(screenshot)
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
        raise
    finally:
        if username and process is not None:
            try:
                run_console(process, command_log, "permfixture clear " + username, 0.2)
                run_console(process, command_log, "op " + username, 0.2)
            except Exception:
                pass
        if client is not None:
            external.stop_process(client)
        if process is not None:
            external.stop_process(process, "stop")
        write_json(run_dir / "summary.json", result)
        copy_runtime_evidence(case, run_dir)
    return result


def copy_runtime_evidence(case: dict, run_dir: Path) -> None:
    """复制本轮运行态证据。"""
    server_dir = Path(case["serverDir"])
    plugin_dir = server_dir / "plugins" / "WorldListTrashCan"
    copy_if_exists(server_dir / "logs" / "latest.log", run_dir / "logs" / "latest.log")
    copy_if_exists(plugin_dir / "messages" / "message_zh.yml", run_dir / "config" / "message_zh.yml")
    copy_if_exists(plugin_dir / "config.yml", run_dir / "config" / "config.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """存在时复制文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_root: Path, results: list[dict]) -> None:
    """生成证据目录 README。"""
    lines = [
        "# 权限负向真实客户端专项",
        "",
        "- 目标: 收敛 F-016 和 README 中保留的真实玩家负向权限缺口。",
        "- 被测 jar: `dist/WorldListTrashCan-universal.jar`",
        "- 验收: 真实客户端非 OP 执行 `/wtc reload` 必须收到无权限提示；通过临时权限夹具显式 deny `global.open`/`personal.open` 后，`/wtc global` 和 `/wtc personal` 必须收到无权限提示；RCON `op` 后 `/wtc reload` 必须成功。",
        "- 证据: 每端保留 F2 截图、客户端 stdout、服务端 console、命令日志、`latest.log` 和 `summary.json`。",
        "",
        "| 服务端 | 版本 | 状态 | 玩家 |",
        "| --- | --- | --- | --- |",
    ]
    for item in results:
        lines.append("| " + item["label"] + " | " + str(item["version"]) + " | " + item["status"] + " | " + item.get("username", "") + " |")
    lines.extend([
        "",
        "## 结论",
        "",
        "本专项只验证权限边界，不改变权限设计。失败时不能用源码或字节码替代真实玩家负向权限证据。",
        "",
    ])
    evidence_root.joinpath("README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行权限负向真实客户端专项矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default=None)
    parser.add_argument("--evidence-name", default="permission-negative-visual-" + time.strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()

    cases = selected_cases(args.case)
    fixture_jar = build_permission_fixture()
    for case in cases:
        extras = list(case.get("extraPlugins", []))
        extras.append(fixture_jar)
        case["extraPlugins"] = extras
    evidence_root = EVIDENCE_ROOT / args.evidence_name
    evidence_root.mkdir(parents=True, exist_ok=True)
    prepared_clients = {}
    results = []
    for case in cases:
        log("运行权限负向专项: " + case["label"])
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        result = run_case(case, prepared_clients, evidence_root)
        results.append(result)
        write_readme(evidence_root, results)
    write_json(evidence_root / "summary.json", {
        "status": "PASS" if all(item["status"] == "PASS" for item in results) else "FAIL",
        "results": results,
    })
    write_readme(evidence_root, results)
    log("权限负向专项完成: " + str(evidence_root))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
