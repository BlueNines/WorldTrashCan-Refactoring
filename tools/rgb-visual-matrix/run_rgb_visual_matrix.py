import argparse
import concurrent.futures
import ctypes
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile
from pathlib import Path

import pyautogui
from PIL import Image, ImageDraw, ImageFont, ImageGrab
import win32con
import win32clipboard
import win32gui
import win32process

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05


USER_AGENT = "Codex-BlWorldTrashCan-RGB-Visual-Test"
MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
PAPER_API = "https://api.papermc.io/v2/projects/paper/versions/{version}/builds"
PAPER_JAR = "https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/{name}"


def set_clipboard_text(text: str) -> None:
    """把文本写入系统剪贴板，绕开中文输入法对自动输入的影响。"""
    last_error = None
    for _ in range(12):
        try:
            win32clipboard.OpenClipboard()
            try:
                win32clipboard.EmptyClipboard()
                win32clipboard.SetClipboardText(text, win32con.CF_UNICODETEXT)
            finally:
                win32clipboard.CloseClipboard()
            return
        except Exception as error:
            last_error = error
            time.sleep(0.12)
    completed = subprocess.run(
        ["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"],
        input=text,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=8,
    )
    if completed.returncode != 0:
        raise RuntimeError("写入剪贴板失败: " + str(last_error) + " / " + completed.stderr.strip())


def paste_text(text: str) -> None:
    """通过剪贴板粘贴文本，避免 pyautogui.write 被当前输入法吞掉。"""
    set_clipboard_text(text)
    pyautogui.hotkey("ctrl", "v")


def repo_root() -> Path:
    """返回 BlWorldTrashCan 重构仓库根目录。"""
    path = Path(__file__).resolve()
    for parent in path.parents:
        if (parent / "pom.xml").is_file() and (parent / "bl-world-trashcan-core").is_dir():
            return parent
    raise RuntimeError("无法定位 refactor-workspace 仓库根目录")


def workspace_root() -> Path:
    """返回 ai 开发插件工作区根目录。"""
    return repo_root().parents[2]


REPO = repo_root()
WORKSPACE = workspace_root()
BUILD_ROOT = REPO / "build" / "rgb-visual-matrix"
CLIENT_CACHE = BUILD_ROOT / "client-cache"
SERVER_HELPER_RCON = WORKSPACE / "paper-1.12.2-test-server" / "test-helpers" / "RconUtf8" / "Invoke-RconUtf8.ps1"
JAVA8 = Path(r"C:\Program Files\Java\jre1.8.0_451\bin\java.exe")
JAVA17 = REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "java.exe"
JAVA21 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
ACTIVE_CLIENT_PID = None


MATRIX = [
    {"id": "paper1122", "version": "1.12.2", "server": "paper-1.12.2-test-server", "port": 25565, "rcon": 25575, "java": JAVA8, "plugin": "BlWorldTrashCan-legacy-1.12.jar", "expect": "downgrade"},
    {"id": "paper1132", "version": "1.13.2", "server": "paper-1.13.2-test-server", "port": 25613, "rcon": 25683, "java": JAVA8, "plugin": "BlWorldTrashCan-bukkit-1.13-1.15.jar", "expect": "downgrade"},
    {"id": "paper1144", "version": "1.14.4", "server": "paper-1.14.4-test-server", "port": 25572, "rcon": 25582, "java": JAVA8, "plugin": "BlWorldTrashCan-bukkit-1.13-1.15.jar", "expect": "downgrade"},
    {"id": "paper1152", "version": "1.15.2", "server": "paper-1.15.2-test-server", "port": 25573, "rcon": 25583, "java": JAVA8, "plugin": "BlWorldTrashCan-bukkit-1.13-1.15.jar", "expect": "downgrade", "direct": False, "multiplayerY": 0.61, "keyboardNav": True},
    {"id": "paper1165", "version": "1.16.5", "server": "paper-1.16.5-test-server", "port": 25567, "rcon": 25577, "java": JAVA8, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb"},
    {"id": "paper1171", "version": "1.17.1", "server": "paper-1.17.1-test-server", "port": 25568, "rcon": 25578, "java": JAVA17, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb"},
    {"id": "paper1182", "version": "1.18.2", "server": "paper-1.18.2-test-server", "port": 25569, "rcon": 25579, "java": JAVA17, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb"},
    {"id": "paper1194", "version": "1.19.4", "server": "paper-1.19.4-test-server", "port": 25570, "rcon": 25580, "java": JAVA17, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb"},
    {"id": "paper1204", "version": "1.20.4", "server": "paper-1.20.4-test-server", "port": 25566, "rcon": 25576, "java": JAVA17, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb", "quickPlay": True, "direct": False},
    {"id": "paper1214", "version": "1.21.4", "server": "paper-1.21.4-test-server", "port": 25574, "rcon": 25584, "java": JAVA21, "plugin": "BlWorldTrashCan-paper-1.16-1.20.jar", "expect": "rgb", "quickPlay": True, "direct": False},
]


def log(message: str) -> None:
    """输出带时间戳的运行日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def read_json(path: Path) -> dict:
    """按 UTF-8 读取 JSON 文件。"""
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def download(url: str, target: Path) -> Path:
    """下载 URL 到目标文件，已存在则直接复用。"""
    if target.is_file() and target.stat().st_size > 0:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_error = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                data = response.read()
            target.write_bytes(data)
            return target
        except Exception as error:
            last_error = error
            time.sleep(1.5 * attempt)
    raise RuntimeError("下载失败: " + url + " error=" + repr(last_error))
    return target


def download_json(url: str, target: Path) -> dict:
    """下载并读取 JSON。"""
    return read_json(download(url, target))


def rule_allows(rule: dict, features: dict) -> bool:
    """判断 Mojang 规则是否匹配当前 Windows 环境。"""
    os_rule = rule.get("os")
    if os_rule and os_rule.get("name") not in (None, "windows"):
        return False
    feature_rule = rule.get("features")
    if feature_rule:
        for key, expected in feature_rule.items():
            if bool(features.get(key, False)) != bool(expected):
                return False
    return True


def rules_allow(item: dict, features: dict | None = None) -> bool:
    """判断带 rules 的库或参数是否允许启用。"""
    features = features or {}
    rules = item.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if rule_allows(rule, features):
            allowed = rule.get("action") == "allow"
    return allowed


def replace_vars(text: str, variables: dict) -> str:
    """替换 Mojang 参数中的变量占位符。"""
    result = text
    for key, value in variables.items():
        result = result.replace("${" + key + "}", str(value))
    return result


def version_manifest() -> dict:
    """返回 Mojang 版本清单。"""
    return download_json(MOJANG_MANIFEST, CLIENT_CACHE / "version_manifest_v2.json")


def version_data(version: str) -> dict:
    """下载并返回指定客户端版本 JSON。"""
    manifest = version_manifest()
    for item in manifest["versions"]:
        if item["id"] == version:
            return download_json(item["url"], CLIENT_CACHE / "versions" / version / (version + ".json"))
    raise RuntimeError("Mojang 清单中找不到版本: " + version)


def asset_index(data: dict) -> tuple[str, dict]:
    """下载并返回资产索引名称和内容。"""
    index = data["assetIndex"]
    path = CLIENT_CACHE / "assets" / "indexes" / (index["id"] + ".json")
    return index["id"], download_json(index["url"], path)


def download_asset(item: tuple[str, dict]) -> None:
    """下载单个 Mojang 资产对象。"""
    name, meta = item
    digest = meta["hash"]
    target = CLIENT_CACHE / "assets" / "objects" / digest[:2] / digest
    if target.is_file() and target.stat().st_size == int(meta.get("size", target.stat().st_size)):
        return
    download("https://resources.download.minecraft.net/" + digest[:2] + "/" + digest, target)


def ensure_assets(data: dict) -> str:
    """确保指定版本所需资产对象存在。"""
    index_id, index = asset_index(data)
    objects = list(index.get("objects", {}).items())
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(download_asset, objects))
    return index_id


def library_path(library: dict, artifact: dict) -> Path:
    """返回库文件在本地缓存中的路径。"""
    return CLIENT_CACHE / "libraries" / artifact["path"]


def ensure_libraries(data: dict, version: str) -> tuple[list[Path], Path]:
    """确保客户端库、客户端 jar 和 natives 已下载。"""
    version_dir = CLIENT_CACHE / "versions" / version
    client_jar = version_dir / (version + ".jar")
    download(data["downloads"]["client"]["url"], client_jar)
    libraries = []
    natives = version_dir / "natives"
    natives.mkdir(parents=True, exist_ok=True)
    for library in data.get("libraries", []):
        if not rules_allow(library):
            continue
        artifact = library.get("downloads", {}).get("artifact")
        if artifact:
            jar_path = library_path(library, artifact)
            download(artifact["url"], jar_path)
            libraries.append(jar_path)
        native_key = library.get("natives", {}).get("windows")
        if native_key:
            classifier = library.get("downloads", {}).get("classifiers", {}).get(native_key)
            if classifier:
                native_jar = library_path(library, classifier)
                download(classifier["url"], native_jar)
                extract_natives(native_jar, natives, library.get("extract", {}).get("exclude", []))
    patch = ensure_authlib_social_patch(libraries)
    return [patch] + libraries + [client_jar], natives


def ensure_authlib_social_patch(libraries: list[Path]) -> Path:
    """构建测试专用 authlib 多人权限补丁 jar。"""
    target = CLIENT_CACHE / "patches" / "authlib-social-patch-v2.jar"
    if target.is_file() and target.stat().st_size > 0:
        return target
    authlib = None
    for library in libraries:
        if "authlib" in library.name:
            authlib = library
            break
    if authlib is None:
        return target
    source = BUILD_ROOT / "tools" / "patch-src" / "com" / "mojang" / "authlib" / "yggdrasil" / "YggdrasilSocialInteractionsService.java"
    classes = BUILD_ROOT / "tools" / "patch-classes"
    source.parent.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    source.write_text("""package com.mojang.authlib.yggdrasil;

import com.mojang.authlib.Environment;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.SocialInteractionsService;
import java.net.Proxy;
import java.util.UUID;

/** 测试客户端补丁：离线验收时允许多人、聊天和 Realms 权限检查继续通过。 */
public final class YggdrasilSocialInteractionsService implements SocialInteractionsService {
    /** 保留 authlib 原构造签名，避免 Minecraft 客户端链接失败。 */
    public YggdrasilSocialInteractionsService(YggdrasilAuthenticationService service, String accessToken, Environment environment) throws AuthenticationException {
    }

    /** 保留 authlib 1.17+ 构造签名，避免 Minecraft 客户端链接失败。 */
    public YggdrasilSocialInteractionsService(String accessToken, Proxy proxy, Environment environment) throws AuthenticationException {
    }

    /** 测试客户端允许多人服务器入口。 */
    @Override
    public boolean serversAllowed() {
        return true;
    }

    /** 测试客户端允许 Realms 检查。 */
    @Override
    public boolean realmsAllowed() {
        return true;
    }

    /** 测试客户端允许聊天。 */
    @Override
    public boolean chatAllowed() {
        return true;
    }

    /** 测试客户端允许遥测权限检查。 */
    public boolean telemetryAllowed() {
        return true;
    }

    /** 测试客户端不屏蔽任何玩家。 */
    @Override
    public boolean isBlockedPlayer(UUID playerId) {
        return false;
    }
}
""", encoding="utf-8")
    javac = JAVA17.parent / "javac.exe"
    jar = JAVA17.parent / "jar.exe"
    subprocess.run([str(javac), "-encoding", "UTF-8", "-source", "8", "-target", "8", "-cp", str(authlib), "-d", str(classes), str(source)], check=True)
    subprocess.run([str(jar), "cf", str(target), "-C", str(classes), "."], check=True)
    return target


def extract_natives(native_jar: Path, natives: Path, excludes: list[str]) -> None:
    """解压 Windows native 库。"""
    with zipfile.ZipFile(native_jar, "r") as archive:
        for entry in archive.namelist():
            if entry.endswith("/"):
                continue
            if any(entry.startswith(prefix) for prefix in excludes):
                continue
            if not entry.lower().endswith((".dll", ".so", ".dylib")):
                continue
            target = natives / Path(entry).name
            if not target.exists():
                target.write_bytes(archive.read(entry))


def ensure_client(version: str) -> dict:
    """确保指定 Minecraft 图形客户端可启动。"""
    log("准备客户端 " + version)
    data = version_data(version)
    asset_id = ensure_assets(data)
    classpath, natives = ensure_libraries(data, version)
    return {"data": data, "assetId": asset_id, "classpath": classpath, "natives": natives}


def choose_subst_drive(target: Path) -> Path:
    """为 Java 8 客户端选择一个 ASCII 映射盘路径。"""
    target.mkdir(parents=True, exist_ok=True)
    sentinel = target / ".blwtc-rgb-client-cache"
    sentinel.write_text("BlWorldTrashCan RGB client cache\n", encoding="utf-8")
    for drive in ["R:", "S:", "T:", "U:"]:
        root = Path(drive + "\\")
        if root.exists():
            if (root / sentinel.name).is_file():
                return root
            continue
        else:
            subprocess.run(["cmd", "/c", "subst", drive, str(target)], check=True)
            return root
    raise RuntimeError("没有可用 subst 盘符")


def as_ascii_path(path: Path, ascii_root: Path) -> Path:
    """把客户端缓存路径转换到 subst 盘符下。"""
    relative = path.resolve().relative_to(CLIENT_CACHE.resolve())
    return ascii_root / relative


def build_game_args(case: dict, prepared: dict, game_dir: Path, ascii_root: Path) -> list[str]:
    """根据版本 JSON 生成客户端 game 参数。"""
    version = case["version"]
    username = "RGB" + case["id"][-8:]
    data = prepared["data"]
    variables = {
        "auth_player_name": username,
        "version_name": version,
        "game_directory": str(game_dir),
        "assets_root": str(ascii_root / "assets"),
        "assets_index_name": prepared["assetId"],
        "auth_uuid": str(uuid.uuid5(uuid.NAMESPACE_DNS, username)).replace("-", ""),
        "auth_access_token": "",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": "854",
        "resolution_height": "480",
        "user_properties": "{}",
        "quickPlayPath": str(game_dir / "quickPlay.txt"),
        "quickPlayMultiplayer": "127.0.0.1:" + str(case["port"]),
    }
    features = {}
    if case.get("quickPlay", False):
        features["has_quick_plays_support"] = True
        features["is_quick_play_multiplayer"] = True
    args = []
    if "arguments" in data:
        for item in data["arguments"].get("game", []):
            if isinstance(item, str):
                args.append(replace_vars(item, variables))
            elif rules_allow(item, features):
                value = item.get("value")
                values = value if isinstance(value, list) else [value]
                args.extend(replace_vars(str(value_item), variables) for value_item in values)
    else:
        args.extend(replace_vars(data["minecraftArguments"], variables).split())
    if case.get("direct", True):
        args.extend(["--server", "127.0.0.1", "--port", str(case["port"])])
    args.extend(["--width", "854", "--height", "480"])
    return args


def java_for_case(case: dict) -> Path:
    """返回当前测试用例需要的 Java。"""
    java = Path(case["java"])
    if not java.is_file():
        raise RuntimeError("Java 不存在: " + str(java))
    return java


def launch_client(case: dict, prepared: dict, run_dir: Path) -> tuple[subprocess.Popen, str, Path]:
    """启动指定版本图形客户端并直连测试服。"""
    ascii_root = choose_subst_drive(CLIENT_CACHE)
    actual_game_dir = CLIENT_CACHE / "game-dirs" / case["id"]
    game_dir = ascii_root / "game-dirs" / case["id"]
    log_dir = run_dir / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(str(as_ascii_path(path, ascii_root)) for path in prepared["classpath"])
    natives = as_ascii_path(prepared["natives"], ascii_root)
    username = "RGB" + case["id"][-8:]
    game_args = build_game_args(case, prepared, game_dir, ascii_root)
    cmd = [
        str(java_for_case(case)),
        "-Xmx1G",
        "-Xms512M",
        "-Dfile.encoding=UTF-8",
        "-Djava.library.path=" + str(natives),
        "-Dorg.lwjgl.librarypath=" + str(natives),
        "-Dminecraft.launcher.brand=BlWTCVisualMatrix",
        "-Dminecraft.launcher.version=1",
        "-cp",
        classpath,
        prepared["data"]["mainClass"],
    ] + game_args
    actual_game_dir.mkdir(parents=True, exist_ok=True)
    game_dir.mkdir(parents=True, exist_ok=True)
    write_client_join_config(case, actual_game_dir)
    stdout = (log_dir / (case["id"] + "-client-stdout.log")).open("w", encoding="utf-8", errors="replace")
    stderr = (log_dir / (case["id"] + "-client-stderr.log")).open("w", encoding="utf-8", errors="replace")
    process = subprocess.Popen(cmd, cwd=str(game_dir), stdout=stdout, stderr=stderr)
    write_json(log_dir / (case["id"] + "-client-launch.json"), {"username": username, "cmd": cmd})
    return process, username, actual_game_dir


def nbt_name(name: str) -> bytes:
    """编码 NBT 标签名或字符串内容。"""
    raw = name.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def nbt_string_tag(name: str, value: str) -> bytes:
    """编码一个 NBT 字符串标签。"""
    return b"\x08" + nbt_name(name) + nbt_name(value)


def nbt_byte_tag(name: str, value: int) -> bytes:
    """编码一个 NBT 字节标签。"""
    return b"\x01" + nbt_name(name) + struct.pack(">b", value)


def write_servers_dat(case: dict, game_dir: Path) -> None:
    """写入只包含当前测试服的 Minecraft servers.dat。"""
    server = (
        nbt_string_tag("name", "BlWTC RGB " + case["version"]) +
        nbt_string_tag("ip", "127.0.0.1:" + str(case["port"])) +
        nbt_byte_tag("hideAddress", 0) +
        b"\x00"
    )
    root = (
        b"\x0a\x00\x00" +
        b"\x09" + nbt_name("servers") + b"\x0a" + struct.pack(">i", 1) +
        server +
        b"\x00"
    )
    (game_dir / "servers.dat").write_bytes(root)


def write_client_options(game_dir: Path) -> None:
    """写入多人警告跳过项，保留客户端自动生成的其它选项。"""
    path = game_dir / "options.txt"
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                values[key] = value
    values["skipMultiplayerWarning"] = "true"
    values["hideServerAddress"] = "false"
    text = "\n".join(key + ":" + value for key, value in values.items()) + "\n"
    path.write_text(text, encoding="utf-8")


def write_client_join_config(case: dict, game_dir: Path) -> None:
    """准备客户端自动进服所需的本地配置。"""
    write_servers_dat(case, game_dir)
    write_client_options(game_dir)


def paper_build(version: str) -> tuple[int, str]:
    """返回指定 Paper 版本的最新构建号和 jar 名。"""
    data = download_json(PAPER_API.format(version=version), BUILD_ROOT / "paper-builds" / (version + ".json"))
    build = data["builds"][-1]
    return int(build["build"]), build["downloads"]["application"]["name"]


def ensure_server_jar(case: dict) -> Path:
    """确保 Paper 测试服 jar 存在。"""
    server_dir = WORKSPACE / case["server"]
    server_dir.mkdir(parents=True, exist_ok=True)
    jars = list(server_dir.glob("paper-*.jar"))
    if jars:
        return jars[0]
    build, name = paper_build(case["version"])
    url = PAPER_JAR.format(version=case["version"], build=build, name=name)
    return download(url, server_dir / name)


def write_server_properties(case: dict, server_dir: Path) -> None:
    """写入测试服必要属性，保留其它已存在属性。"""
    path = server_dir / "server.properties"
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value
    values.update({
        "server-port": str(case["port"]),
        "enable-rcon": "true",
        "rcon.port": str(case["rcon"]),
        "rcon.password": "ai-test",
        "online-mode": "false",
        "level-name": "rgb_visual_" + case["id"],
        "level-type": "flat",
        "generate-structures": "false",
        "view-distance": "2",
        "simulation-distance": "2",
        "spawn-protection": "0",
        "motd": "BlWTC RGB Visual Matrix " + case["version"],
    })
    text = "\n".join(key + "=" + value for key, value in values.items()) + "\n"
    path.write_text(text, encoding="utf-8")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def deploy_plugin(case: dict, server_dir: Path) -> None:
    """部署当前插件产物到测试服 plugins 目录。"""
    source = REPO / "dist" / case["plugin"]
    if not source.is_file():
        raise RuntimeError("插件产物不存在: " + str(source))
    plugins = server_dir / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    for old in plugins.glob("BlWorldTrashCan*.jar"):
        old.unlink()
    shutil.copy2(source, plugins / source.name)


def prepare_server(case: dict) -> Path:
    """准备指定测试服目录、Paper jar、配置和插件。"""
    server_dir = WORKSPACE / case["server"]
    ensure_server_jar(case)
    write_server_properties(case, server_dir)
    deploy_plugin(case, server_dir)
    return server_dir


def kill_processes_containing(text: str) -> None:
    """结束命令行中包含指定文本的 Java 进程。"""
    escaped = text.replace("'", "''")
    command = (
        "Get-CimInstance Win32_Process | "
        "Where-Object { $_.Name -like 'java*' -and $_.CommandLine -like '*" + escaped + "*' } | "
        "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", command], check=False)


def launch_server(case: dict, server_dir: Path, run_dir: Path) -> subprocess.Popen:
    """启动 Paper 测试服。"""
    kill_processes_containing(str(server_dir))
    jar = ensure_server_jar(case)
    log_path = run_dir / "logs" / (case["id"] + "-server-console.log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    stdout = log_path.open("w", encoding="utf-8", errors="replace")
    cmd = [str(java_for_case(case)), "-Xmx1200M", "-Xms512M", "-Dfile.encoding=UTF-8", "-jar", str(jar.name), "nogui"]
    process = subprocess.Popen(cmd, cwd=str(server_dir), stdin=subprocess.PIPE, stdout=stdout, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    wait_server_ready(log_path, case["port"])
    return process


def wait_server_ready(log_path: Path, port: int) -> None:
    """等待服务端 Done 行和端口可连。"""
    deadline = time.time() + 180
    while time.time() < deadline:
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
        if ("Done (" in text or "Timings Reset" in text) and port_open(port):
            return
        time.sleep(1)
    raise RuntimeError("服务端启动超时: " + str(log_path))


def port_open(port: int) -> bool:
    """检查本地端口是否可连接。"""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def rcon_packet(packet_id: int, packet_type: int, body: str) -> bytes:
    """构造 RCON 数据包。"""
    body_bytes = body.encode("utf-8")
    length = 4 + 4 + len(body_bytes) + 2
    return struct.pack("<iii", length, packet_id, packet_type) + body_bytes + b"\x00\x00"


def read_rcon_packet(sock: socket.socket) -> tuple[int, str]:
    """读取一个 RCON 响应包。"""
    length_bytes = sock.recv(4)
    if len(length_bytes) < 4:
        return -2, ""
    length = struct.unpack("<i", length_bytes)[0]
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    packet_id = struct.unpack("<i", data[:4])[0]
    body = data[8:-2].decode("utf-8", errors="replace")
    return packet_id, body


def run_rcon(case: dict, commands: list[str], log_path: Path) -> list[dict]:
    """执行 RCON 命令并记录响应。"""
    results = []
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with socket.create_connection(("127.0.0.1", case["rcon"]), timeout=10) as sock:
        sock.sendall(rcon_packet(1, 3, "ai-test"))
        auth_id, auth_body = read_rcon_packet(sock)
        if auth_id == -1:
            raise RuntimeError("RCON 认证失败: " + auth_body)
        packet_id = 2
        with log_path.open("a", encoding="utf-8") as file:
            for command in commands:
                file.write(">>> " + command + "\n")
                sock.sendall(rcon_packet(packet_id, 2, command))
                response_id, body = read_rcon_packet(sock)
                file.write(body + "\n")
                results.append({"command": command, "id": response_id, "body": body})
                packet_id += 1
                time.sleep(0.7)
    return results


def wait_player_online(case: dict, username: str, run_dir: Path) -> None:
    """等待真实客户端玩家进入服务端。"""
    deadline = time.time() + 120
    log_path = run_dir / "logs" / (case["id"] + "-rcon.log")
    while time.time() < deadline:
        if is_player_online(case, username, log_path):
            return
        time.sleep(2)
    raise RuntimeError("客户端未进入服务端: " + username)


def is_player_online(case: dict, username: str, log_path: Path) -> bool:
    """通过 RCON list 判断玩家是否在线。"""
    results = run_rcon(case, ["list"], log_path)
    return username in results[-1]["body"]


def find_minecraft_window(version: str) -> int:
    """查找当前 Minecraft 客户端窗口句柄。"""
    handles = []

    def visit(hwnd, result):
        if not win32gui.IsWindowVisible(hwnd):
            return
        if ACTIVE_CLIENT_PID is not None:
            _, process_id = win32process.GetWindowThreadProcessId(hwnd)
            if process_id != ACTIVE_CLIENT_PID:
                return
        title = win32gui.GetWindowText(hwnd)
        if "Minecraft" in title or version in title:
            result.append(hwnd)

    win32gui.EnumWindows(visit, handles)
    if not handles:
        raise RuntimeError("找不到 Minecraft 窗口")
    return handles[0]


def focus_window(hwnd: int) -> tuple[int, int, int, int]:
    """聚焦并移动窗口，返回客户区截图区域。"""
    win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    win32gui.SetWindowPos(hwnd, win32con.HWND_TOP, 60, 60, 900, 560, win32con.SWP_SHOWWINDOW)
    force_foreground_window(hwnd)
    window_left, window_top, _, _ = win32gui.GetWindowRect(hwnd)
    pyautogui.click(window_left + 80, window_top + 12)
    force_foreground_window(hwnd)
    try:
        win32gui.SetForegroundWindow(hwnd)
    except Exception:
        pass
    time.sleep(0.8)
    return client_rect(hwnd)


def force_foreground_window(hwnd: int) -> None:
    """用 Windows 线程输入附加方式强制激活目标窗口。"""
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    current_thread = kernel32.GetCurrentThreadId()
    target_thread, _ = win32process.GetWindowThreadProcessId(hwnd)
    foreground = user32.GetForegroundWindow()
    foreground_thread, _ = win32process.GetWindowThreadProcessId(foreground)
    attached_target = False
    attached_foreground = False
    try:
        attached_target = bool(user32.AttachThreadInput(current_thread, target_thread, True))
        if foreground_thread and foreground_thread != target_thread:
            attached_foreground = bool(user32.AttachThreadInput(current_thread, foreground_thread, True))
        win32gui.BringWindowToTop(hwnd)
        win32gui.SetActiveWindow(hwnd)
        win32gui.SetFocus(hwnd)
        user32.SetForegroundWindow(hwnd)
    except Exception:
        pass
    finally:
        if attached_target:
            user32.AttachThreadInput(current_thread, target_thread, False)
        if attached_foreground:
            user32.AttachThreadInput(current_thread, foreground_thread, False)


def client_rect(hwnd: int) -> tuple[int, int, int, int]:
    """返回窗口客户区在屏幕上的绝对区域。"""
    left, top = win32gui.ClientToScreen(hwnd, (0, 0))
    right, bottom = win32gui.ClientToScreen(hwnd, win32gui.GetClientRect(hwnd)[2:])
    return left, top, right, bottom


def click_game(hwnd: int, rect: tuple[int, int, int, int], x_ratio: float, y_ratio: float) -> None:
    """点击 Minecraft 客户端窗口内的相对坐标。"""
    left, top, right, bottom = rect
    x = int(left + (right - left) * x_ratio)
    y = int(top + (bottom - top) * y_ratio)
    pyautogui.click(x, y)
    client_x = max(0, min(right - left - 1, x - left))
    client_y = max(0, min(bottom - top - 1, y - top))
    lparam = client_x | (client_y << 16)
    win32gui.PostMessage(hwnd, win32con.WM_MOUSEMOVE, 0, lparam)
    time.sleep(0.05)
    win32gui.PostMessage(hwnd, win32con.WM_LBUTTONDOWN, win32con.MK_LBUTTON, lparam)
    time.sleep(0.08)
    win32gui.PostMessage(hwnd, win32con.WM_LBUTTONUP, 0, lparam)
    time.sleep(0.8)


def capture_rect(rect: tuple[int, int, int, int], path: Path) -> None:
    """按窗口区域保存一次调试截图。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    ImageGrab.grab(bbox=rect).save(path)


def connect_via_gui(case: dict, username: str, run_dir: Path) -> None:
    """用 GUI 执行多人游戏列表进服，失败后兜底 Direct Connect。"""
    log_path = run_dir / "logs" / (case["id"] + "-rcon.log")
    if is_player_online(case, username, log_path):
        return
    hwnd = find_minecraft_window(case["version"])
    rect = focus_window(hwnd)
    multiplayer_y = float(case.get("multiplayerY", 0.42))
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-00-before-click.png"))
    if case.get("keyboardNav", False):
        keyboard_join_server(hwnd, case, run_dir)
        if is_player_online(case, username, log_path):
            return
    pyautogui.press("esc")
    time.sleep(0.5)
    click_game(hwnd, rect, 0.50, multiplayer_y)
    click_game(hwnd, rect, 0.50, multiplayer_y)
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-01-after-multiplayer.png"))
    time.sleep(2.0)
    click_game(hwnd, rect, 0.50, 0.12)
    click_game(hwnd, rect, 0.50, 0.12)
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-02-after-server-double-click.png"))
    time.sleep(8)
    if is_player_online(case, username, log_path):
        return
    rect = focus_window(hwnd)
    pyautogui.press("esc")
    time.sleep(0.5)
    click_game(hwnd, rect, 0.50, multiplayer_y)
    click_game(hwnd, rect, 0.50, multiplayer_y)
    time.sleep(1.5)
    click_game(hwnd, rect, 0.50, 0.91)
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-03-after-direct-click.png"))
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    paste_text("127.0.0.1:" + str(case["port"]))
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-04-after-address.png"))
    pyautogui.press("enter")
    time.sleep(2)
    capture_rect(rect, run_dir / "screenshots" / (case["id"] + "-gui-05-after-enter.png"))


def keyboard_join_server(hwnd: int, case: dict, run_dir: Path) -> None:
    """用键盘从主菜单进入多人服务器列表并通过 Direct Connect 进服。"""
    for key in (win32con.VK_TAB, win32con.VK_TAB, win32con.VK_RETURN):
        post_key(hwnd, key)
        time.sleep(0.35)
    time.sleep(4)
    capture_rect(client_rect(hwnd), run_dir / "screenshots" / (case["id"] + "-keyboard-01-multiplayer.png"))
    for key in (win32con.VK_TAB, win32con.VK_TAB, win32con.VK_RETURN):
        post_key(hwnd, key)
        time.sleep(0.35)
    time.sleep(1)
    post_text(hwnd, "127.0.0.1:" + str(case["port"]))
    post_key(hwnd, win32con.VK_RETURN)
    time.sleep(7)
    capture_rect(client_rect(hwnd), run_dir / "screenshots" / (case["id"] + "-keyboard-02-after-enter.png"))


def hover_debug_item(case: dict) -> None:
    """把鼠标移动到 debugrgb GUI 中间物品上，方便 F2 截图带出提示。"""
    hwnd = find_minecraft_window(case["version"])
    rect = focus_window(hwnd)
    left, top, right, bottom = rect
    width = right - left
    height = bottom - top
    pyautogui.moveTo(left + width // 2, top + int(height * 0.37), duration=0.2)
    time.sleep(0.8)


def post_key(hwnd: int, vk: int) -> None:
    """直接向指定窗口投递一次按键消息。"""
    scan = ctypes.windll.user32.MapVirtualKeyW(vk, 0)
    down_lparam = 1 | (scan << 16)
    up_lparam = down_lparam | (1 << 30) | (1 << 31)
    win32gui.PostMessage(hwnd, win32con.WM_KEYDOWN, vk, down_lparam)
    time.sleep(0.08)
    win32gui.PostMessage(hwnd, win32con.WM_KEYUP, vk, up_lparam)


def post_text(hwnd: int, text: str) -> None:
    """直接向指定窗口投递文本输入。"""
    for char in text:
        win32gui.PostMessage(hwnd, win32con.WM_CHAR, ord(char), 1)
        time.sleep(0.03)


def send_chat_line(hwnd: int, text: str) -> None:
    """打开 Minecraft 聊天栏并发送一整行文本。"""
    rect = focus_window(hwnd)
    click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.2)
    pyautogui.press("t")
    time.sleep(0.25)
    paste_text(text)
    time.sleep(0.1)
    pyautogui.press("enter")


def send_chat_line_by_window_message(hwnd: int, text: str) -> None:
    """用窗口消息打开聊天栏并发送文本，兼容 1.12.2 剪贴板不稳定场景。"""
    rect = focus_window(hwnd)
    click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.2)
    post_key(hwnd, ord("T"))
    time.sleep(0.25)
    post_text(hwnd, text)
    post_key(hwnd, win32con.VK_RETURN)


def image_brightness(image: Image.Image) -> float:
    """粗略计算截图亮度，避免把全黑图当证据。"""
    small = image.convert("RGB").resize((32, 32))
    total = 0
    for red, green, blue in small.getdata():
        total += red + green + blue
    return total / (32 * 32 * 3)


def capture_window(case: dict, run_dir: Path) -> Path:
    """截取 Minecraft 窗口并保存 PNG。"""
    screenshots = run_dir / "screenshots"
    screenshots.mkdir(parents=True, exist_ok=True)
    hwnd = find_minecraft_window(case["version"])
    rect = focus_window(hwnd)
    image = ImageGrab.grab(bbox=rect)
    if image_brightness(image) < 3:
        time.sleep(1)
        image = ImageGrab.grab(bbox=rect)
    path = screenshots / (case["id"] + "-debugrgb.png")
    image.save(path)
    return path


def png_file_ready(path: Path) -> bool:
    """判断 Minecraft F2 截图文件是否已经完整写成 PNG。"""
    if not path.is_file():
        return False
    first_size = path.stat().st_size
    if first_size <= 8:
        return False
    try:
        header = path.read_bytes()[:8]
    except OSError:
        return False
    if header != b"\x89PNG\r\n\x1a\n":
        return False
    time.sleep(0.25)
    return path.is_file() and path.stat().st_size == first_size


def capture_internal_screenshot(case: dict, game_dir: Path, run_dir: Path) -> Path:
    """按 F2 让 Minecraft 自己生成截图并归档。"""
    screenshots = game_dir / "screenshots"
    before = set(screenshots.glob("*.png")) if screenshots.is_dir() else set()
    hwnd = find_minecraft_window(case["version"])
    focus_window(hwnd)
    post_key(hwnd, win32con.VK_F2)
    time.sleep(0.5)
    pyautogui.press("f2")
    deadline = time.time() + 8
    newest = None
    while time.time() < deadline:
        current = set(screenshots.glob("*.png")) if screenshots.is_dir() else set()
        created = sorted(current - before, key=lambda path: path.stat().st_mtime, reverse=True)
        for path in created:
            if png_file_ready(path):
                newest = path
                break
        if newest is not None:
            break
        time.sleep(0.5)
    if newest is None:
        raise RuntimeError("客户端 F2 截图未生成: " + str(screenshots))
    target = run_dir / "screenshots" / (case["id"] + "-debugrgb-f2.png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(newest, target)
    return target


def stop_process(process: subprocess.Popen, command: str | None = None) -> None:
    """优雅停止进程，超时后强制结束。"""
    if process.poll() is not None:
        return
    try:
        if command and process.stdin:
            process.stdin.write(command + "\n")
            process.stdin.flush()
        process.wait(timeout=25)
    except Exception:
        process.kill()
        try:
            process.wait(timeout=10)
        except Exception:
            pass


def run_case(case: dict, prepared_clients: dict, run_root: Path) -> dict:
    """执行单个 RGB 视觉截图用例。"""
    log("开始用例 " + case["id"] + " / " + case["version"])
    run_dir = run_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_process = None
    client_process = None
    game_dir = None
    result = {"id": case["id"], "version": case["version"], "expect": case["expect"], "status": "FAIL"}
    try:
        server_dir = prepare_server(case)
        server_process = launch_server(case, server_dir, run_dir)
        prepared = prepared_clients[case["version"]]
        client_process, username, game_dir = launch_client(case, prepared, run_dir)
        global ACTIVE_CLIENT_PID
        ACTIVE_CLIENT_PID = client_process.pid
        result["username"] = username
        result["clientPid"] = client_process.pid
        time.sleep(28)
        connect_via_gui(case, username, run_dir)
        wait_player_online(case, username, run_dir)
        if case.get("channelsOnly"):
            hwnd = find_minecraft_window(case["version"])
            rect = focus_window(hwnd)
            click_game(hwnd, rect, 0.50, 0.56)
        commands = ["wtc reload", "blwtc platform"]
        if case.get("channelsOnly"):
            commands.extend([
                "gamemode creative " + username,
                "effect give " + username + " minecraft:resistance 30 255 true",
                "setblock 0 80 0 minecraft:stone",
                "tp " + username + " 0 81 0",
                "blwtc debugrgbchannels " + username,
            ])
        else:
            commands.append("blwtc debugrgb " + username)
        run_rcon(case, commands, run_dir / "logs" / (case["id"] + "-rcon.log"))
        time.sleep(2.5)
        if not case.get("channelsOnly"):
            hover_debug_item(case)
        screenshot = capture_internal_screenshot(case, game_dir, run_dir)
        try:
            result["desktopScreenshot"] = str(capture_window(case, run_dir))
        except Exception as error:
            result["desktopScreenshotError"] = repr(error)
        result["screenshot"] = str(screenshot)
        result["brightness"] = image_brightness(Image.open(screenshot))
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        if client_process and game_dir:
            try:
                result["failureScreenshot"] = str(capture_internal_screenshot(case, game_dir, run_dir))
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
        log("用例失败 " + case["id"] + ": " + repr(error))
    finally:
        if client_process:
            stop_process(client_process)
        ACTIVE_CLIENT_PID = None
        if server_process:
            stop_process(server_process, "stop")
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], run_root: Path) -> Path | None:
    """生成所有截图的总览图。"""
    passed = [item for item in results if item.get("status") == "PASS" and item.get("screenshot")]
    if not passed:
        return None
    thumbs = []
    for item in passed:
        image = Image.open(item["screenshot"]).convert("RGB")
        image.thumbnail((300, 186))
        canvas = Image.new("RGB", (320, 230), (28, 28, 28))
        canvas.paste(image, (10, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 202), item["version"] + " " + item["expect"], fill=(255, 255, 255))
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 320, rows * 230), (18, 18, 18))
    for index, image in enumerate(thumbs):
        sheet.paste(image, ((index % columns) * 320, (index // columns) * 230))
    path = run_root / "rgb-visual-contact-sheet.png"
    sheet.save(path)
    return path


def selected_cases(case_id: str | None) -> list[dict]:
    """返回命令指定的测试用例集合。"""
    if not case_id:
        return MATRIX
    for case in MATRIX:
        if case["id"] == case_id or case["version"] == case_id:
            return [case]
    raise RuntimeError("未知用例: " + case_id)


def main() -> int:
    """运行 RGB 视觉截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--channels-only", action="store_true")
    args = parser.parse_args()
    run_id = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / "runs" / run_id
    cases = [dict(case) for case in selected_cases(args.case or None)]
    if args.channels_only:
        for case in cases:
            case["channelsOnly"] = True
    run_root.mkdir(parents=True, exist_ok=True)
    prepared_clients = {}
    if args.prepare_only:
        for case in cases:
            prepared_clients.setdefault(case["version"], ensure_client(case["version"]))
            prepare_server(case)
        write_json(run_root / "summary.json", {"status": "PREPARED", "cases": cases})
        return 0
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], ensure_client(case["version"]))
        prepare_server(case)
        results.append(run_case(case, prepared_clients, run_root))
        write_json(run_root / "summary.json", {"run": run_id, "results": results, "contactSheet": ""})
        write_json(BUILD_ROOT / "last-summary.json", {"run": run_id, "results": results, "contactSheet": ""})
    contact_sheet = make_contact_sheet(results, run_root)
    summary = {"run": run_id, "results": results, "contactSheet": str(contact_sheet) if contact_sheet else ""}
    write_json(run_root / "summary.json", summary)
    write_json(BUILD_ROOT / "last-summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed)) + " summary=" + str(run_root / "summary.json"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
