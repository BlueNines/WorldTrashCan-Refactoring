import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base
import run_trash_gui_click_visual_matrix as gui


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
BUILD_ROOT = base.REPO / "build" / "vault-payment-matrix"
JAVAC17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"
VAULT_API_JARS = [
    Path.home() / ".m2" / "repository" / "net" / "milkbowl" / "vault" / "VaultAPI" / "1.7" / "VaultAPI-1.7.jar",
    Path.home() / ".m2" / "repository" / "com" / "github" / "MilkBowl" / "VaultAPI" / "1.7" / "VaultAPI-1.7.jar",
]
DEFAULT_CASE_IDS = ["external_spigot2612"]


FIXTURE_SOURCE = r'''
package ai.blwtc.fixture;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** BlWorldTrashCan Vault 扣费验收用临时 Vault 插件。 */
public final class FakeVaultPlugin extends JavaPlugin implements CommandExecutor, Economy {
    private final Map<String, Double> balances = new HashMap<String, Double>();
    private final Map<String, Integer> withdrawals = new HashMap<String, Integer>();

    /** 注册 fake Economy 服务和测试命令。 */
    @Override
    public void onEnable() {
        Bukkit.getServicesManager().register(Economy.class, this, this, ServicePriority.Highest);
        getCommand("fakevault").setExecutor(this);
        getLogger().info("AI_FAKEVAULT_READY provider=FakeVaultEconomy");
    }

    /** 注销 fake Economy 服务。 */
    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(Economy.class, this);
    }

    /** 执行 fakevault 测试命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sender.sendMessage("AI_FAKEVAULT_USAGE reset|set|balance|withdrawals|fill|clearinv");
            return true;
        }
        if ("reset".equalsIgnoreCase(args[0])) {
            balances.clear();
            withdrawals.clear();
            sender.sendMessage("AI_FAKEVAULT_RESET done=true");
            return true;
        }
        if ("set".equalsIgnoreCase(args[0]) && args.length >= 3) {
            setBalance(args[1], parseAmount(args[2]));
            sender.sendMessage("AI_FAKEVAULT_SET player=" + args[1] + " balance=" + money(getBalance(args[1])));
            return true;
        }
        if ("balance".equalsIgnoreCase(args[0]) && args.length >= 2) {
            sender.sendMessage("AI_FAKEVAULT_BALANCE player=" + args[1]
                    + " balance=" + money(getBalance(args[1]))
                    + " withdrawals=" + withdrawalCount(args[1]));
            return true;
        }
        if ("withdrawals".equalsIgnoreCase(args[0]) && args.length >= 2) {
            sender.sendMessage("AI_FAKEVAULT_WITHDRAWALS player=" + args[1]
                    + " count=" + withdrawalCount(args[1]));
            return true;
        }
        if ("fill".equalsIgnoreCase(args[0]) && args.length >= 2) {
            fillInventory(sender, args);
            return true;
        }
        if ("clearinv".equalsIgnoreCase(args[0]) && args.length >= 2) {
            clearInventory(sender, args[1]);
            return true;
        }
        sender.sendMessage("AI_FAKEVAULT_USAGE reset|set|balance|withdrawals|fill|clearinv");
        return true;
    }

    /** 把玩家主背包填满指定材料。 */
    private void fillInventory(CommandSender sender, String[] args) {
        Player player = Bukkit.getPlayer(args[1]);
        if (player == null) {
            sender.sendMessage("AI_FAKEVAULT_FILL player=" + args[1] + " online=false");
            return;
        }
        Material material = material(args.length >= 3 ? args[2] : "COBBLESTONE");
        player.getInventory().clear();
        int slots = Math.min(36, player.getInventory().getStorageContents().length);
        for (int slot = 0; slot < slots; slot++) {
            player.getInventory().setItem(slot, new ItemStack(material, material.getMaxStackSize()));
        }
        sender.sendMessage("AI_FAKEVAULT_FILL player=" + player.getName()
                + " online=true slots=" + slots + " material=" + material.name());
    }

    /** 清空指定在线玩家背包。 */
    private void clearInventory(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage("AI_FAKEVAULT_CLEARINV player=" + playerName + " online=false");
            return;
        }
        player.getInventory().clear();
        sender.sendMessage("AI_FAKEVAULT_CLEARINV player=" + player.getName() + " online=true");
    }

    /** 根据材料名返回 Bukkit Material。 */
    private Material material(String name) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        return material == null ? Material.COBBLESTONE : material;
    }

    /** 解析命令里的金额。 */
    private double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    /** 统一玩家名 key。 */
    private String key(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }

    /** 统一离线玩家 key。 */
    private String key(OfflinePlayer player) {
        return player == null ? "" : key(player.getName());
    }

    /** 设置玩家余额。 */
    private void setBalance(String playerName, double amount) {
        balances.put(key(playerName), Double.valueOf(Math.max(0D, amount)));
    }

    /** 返回玩家余额。 */
    private double getBalanceByKey(String playerKey) {
        Double value = balances.get(playerKey);
        return value == null ? 0D : value.doubleValue();
    }

    /** 返回玩家取款次数。 */
    private int withdrawalCount(String playerName) {
        Integer value = withdrawals.get(key(playerName));
        return value == null ? 0 : value.intValue();
    }

    /** 格式化测试金额。 */
    private String money(double amount) {
        return String.format(Locale.US, "$%.2f", Double.valueOf(amount));
    }

    /** 创建 Vault EconomyResponse。 */
    private EconomyResponse response(double amount, double balance, EconomyResponse.ResponseType type, String message) {
        return new EconomyResponse(amount, balance, type, message);
    }

    /** 对指定玩家扣费并记录扣费次数。 */
    private EconomyResponse withdraw(String playerName, double amount) {
        String playerKey = key(playerName);
        double current = getBalanceByKey(playerKey);
        if (amount < 0D) {
            return response(amount, current, EconomyResponse.ResponseType.FAILURE, "negative amount");
        }
        if (current < amount) {
            getLogger().info("AI_FAKEVAULT_WITHDRAW player=" + playerName
                    + " amount=" + money(amount) + " success=false balance=" + money(current)
                    + " withdrawals=" + withdrawalCount(playerName));
            return response(amount, current, EconomyResponse.ResponseType.FAILURE, "insufficient funds");
        }
        double next = current - amount;
        balances.put(playerKey, Double.valueOf(next));
        Integer count = withdrawals.get(playerKey);
        withdrawals.put(playerKey, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        getLogger().info("AI_FAKEVAULT_WITHDRAW player=" + playerName
                + " amount=" + money(amount) + " success=true balance=" + money(next)
                + " withdrawals=" + withdrawalCount(playerName));
        return response(amount, next, EconomyResponse.ResponseType.SUCCESS, "");
    }

    /** 返回未实现银行功能响应。 */
    private EconomyResponse notImplemented() {
        return response(0D, 0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "bank not implemented");
    }

    /** 判断是否支持银行。 */
    @Override
    public boolean hasBankSupport() {
        return false;
    }

    /** 返回小数位。 */
    @Override
    public int fractionalDigits() {
        return 2;
    }

    /** 格式化金额。 */
    @Override
    public String format(double amount) {
        return money(amount);
    }

    /** 返回复数货币名。 */
    @Override
    public String currencyNamePlural() {
        return "coins";
    }

    /** 返回单数货币名。 */
    @Override
    public String currencyNameSingular() {
        return "coin";
    }

    /** 判断 String 玩家是否有账户。 */
    @Override
    public boolean hasAccount(String playerName) {
        return balances.containsKey(key(playerName));
    }

    /** 判断 OfflinePlayer 玩家是否有账户。 */
    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return balances.containsKey(key(player));
    }

    /** 判断世界内 String 玩家是否有账户。 */
    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    /** 判断世界内 OfflinePlayer 玩家是否有账户。 */
    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    /** 返回 String 玩家余额。 */
    @Override
    public double getBalance(String playerName) {
        return getBalanceByKey(key(playerName));
    }

    /** 返回 OfflinePlayer 玩家余额。 */
    @Override
    public double getBalance(OfflinePlayer player) {
        return getBalanceByKey(key(player));
    }

    /** 返回世界内 String 玩家余额。 */
    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    /** 返回世界内 OfflinePlayer 玩家余额。 */
    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    /** 判断 String 玩家余额是否足够。 */
    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    /** 判断 OfflinePlayer 玩家余额是否足够。 */
    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    /** 判断世界内 String 玩家余额是否足够。 */
    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    /** 判断世界内 OfflinePlayer 玩家余额是否足够。 */
    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    /** 对 String 玩家扣费。 */
    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(playerName, amount);
    }

    /** 对 OfflinePlayer 玩家扣费。 */
    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player == null ? "" : player.getName(), amount);
    }

    /** 对世界内 String 玩家扣费。 */
    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    /** 对世界内 OfflinePlayer 玩家扣费。 */
    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    /** 给 String 玩家存款。 */
    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        double next = getBalance(playerName) + Math.max(0D, amount);
        setBalance(playerName, next);
        return response(amount, next, EconomyResponse.ResponseType.SUCCESS, "");
    }

    /** 给 OfflinePlayer 玩家存款。 */
    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return depositPlayer(player == null ? "" : player.getName(), amount);
    }

    /** 给世界内 String 玩家存款。 */
    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    /** 给世界内 OfflinePlayer 玩家存款。 */
    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    /** 创建 String 银行账户。 */
    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    /** 创建 OfflinePlayer 银行账户。 */
    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    /** 删除银行账户。 */
    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    /** 查询银行余额。 */
    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    /** 判断银行余额。 */
    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    /** 银行取款。 */
    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    /** 银行存款。 */
    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    /** 判断 String 玩家是否为银行拥有者。 */
    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    /** 判断 OfflinePlayer 玩家是否为银行拥有者。 */
    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    /** 判断 String 玩家是否为银行成员。 */
    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    /** 判断 OfflinePlayer 玩家是否为银行成员。 */
    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    /** 返回银行列表。 */
    @Override
    public List<String> getBanks() {
        return new ArrayList<String>();
    }

    /** 创建 String 玩家账户。 */
    @Override
    public boolean createPlayerAccount(String playerName) {
        if (!hasAccount(playerName)) {
            setBalance(playerName, 0D);
        }
        return true;
    }

    /** 创建 OfflinePlayer 玩家账户。 */
    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return createPlayerAccount(player == null ? "" : player.getName());
    }

    /** 创建世界内 String 玩家账户。 */
    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    /** 创建世界内 OfflinePlayer 玩家账户。 */
    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}
'''


PLUGIN_YML = """name: Vault
version: 1.7.3-test
main: ai.blwtc.fixture.FakeVaultPlugin
load: STARTUP
commands:
  fakevault:
    description: Fake Vault economy fixture for BlWorldTrashCan validation
"""


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(external.to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def vault_api_jar() -> Path:
    """返回本机可用的 VaultAPI jar。"""
    for path in VAULT_API_JARS:
        if path.is_file():
            return path
    raise RuntimeError("缺少 VaultAPI-1.7.jar: " + "; ".join(str(path) for path in VAULT_API_JARS))


def ensure_inputs() -> None:
    """确认本轮 Vault 验收所需工具和依赖存在。"""
    missing = []
    for path in (JAVAC17, JAR17, BUKKIT_API_JAR, vault_api_jar()):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少 Vault 验收输入: " + "; ".join(missing))


def selected_cases(case_id: str | None) -> list[dict]:
    """按参数选择 Vault 验收服务端用例。"""
    targets = DEFAULT_CASE_IDS if not case_id else [case_id]
    cases = []
    for wanted in targets:
        matched = None
        for item in external.EXTERNAL_MATRIX:
            if wanted in (item["id"], item.get("sourceId", ""), item["label"], item["version"]):
                matched = external.universal_case(item)
                break
        if matched is None:
            raise RuntimeError("未知 Vault 验收用例: " + wanted)
        cases.append(matched)
    return cases


def build_fixture(run_root: Path) -> Path:
    """编译并打包临时 Vault/Economy 夹具插件。"""
    source_dir = run_root / "fixture-src" / "ai" / "blwtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "Vault-FakeEconomy.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "FakeVaultPlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR) + ";" + str(vault_api_jar()),
        "-d", str(classes_dir),
        str(source_dir / "FakeVaultPlugin.java"),
    ], check=True)
    if fixture_jar.exists():
        fixture_jar.unlink()
    subprocess.run([
        str(JAR17),
        "cf", str(fixture_jar),
        "-C", str(classes_dir), ".",
        "-C", str(resources_dir), ".",
    ], check=True)
    append_vault_api_classes(fixture_jar)
    return fixture_jar


def append_vault_api_classes(fixture_jar: Path) -> None:
    """把 VaultAPI 类一并放入临时 Vault 插件。"""
    with zipfile.ZipFile(vault_api_jar(), "r") as source, zipfile.ZipFile(fixture_jar, "a") as target:
        for name in source.namelist():
            if name.startswith("net/milkbowl/vault/") and name.endswith(".class"):
                target.writestr(name, source.read(name))


def backup_file(path: Path, backup_dir: Path) -> dict:
    """备份一个文件，供测试结束恢复。"""
    backup = backup_dir / path.name
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup)
    return {"target": path, "backup": backup}


def install_fixture(case: dict, fixture_jar: Path, run_dir: Path) -> dict:
    """安装 fake Vault 插件并备份已有 Vault jar。"""
    external.prepare_managed_server(case)
    plugins_dir = Path(case["serverDir"]) / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    backup_dir = run_dir / "logs" / "vault-plugin-backup"
    backups = []
    for old in plugins_dir.glob("Vault*.jar"):
        backups.append(backup_file(old, backup_dir))
        old.unlink()
    target = plugins_dir / fixture_jar.name
    shutil.copy2(fixture_jar, target)
    return {"target": target, "backups": backups}


def restore_fixture(install_state: dict | None) -> None:
    """卸载 fake Vault 并恢复测试前已有 Vault jar。"""
    if not install_state:
        return
    target = Path(install_state["target"])
    if target.is_file():
        target.unlink()
    for item in install_state.get("backups", []):
        backup = Path(item["backup"])
        target_path = Path(item["target"])
        if backup.is_file():
            target_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target_path)


def backup_runtime_config(case: dict, run_dir: Path) -> list[dict]:
    """备份本轮会临时修改的运行时配置文件。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan"
    backups = []
    for file_name in ("trash.yml",):
        target = data_dir / file_name
        if target.is_file():
            backups.append(backup_file(target, run_dir / "logs" / "config-backup"))
    return backups


def restore_runtime_config(backups: list[dict]) -> None:
    """恢复本轮临时修改过的运行时配置。"""
    for item in backups:
        backup = Path(item["backup"])
        target = Path(item["target"])
        if backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)


def patch_trash_config(case: dict) -> Path:
    """写入 Vault 扣费验收需要的 trash.yml 配置。"""
    target = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "trash.yml"
    if not target.is_file():
        raise RuntimeError("trash.yml 不存在，无法配置 Vault 扣费测试: " + str(target))
    text = target.read_text(encoding="utf-8", errors="replace")
    text = external.update_yaml_scalars(text, {
        "personal-trash.enabled": "true",
        "personal-trash.auto-clear-when-full": "true",
        "personal-trash.take-cost": "10",
    })
    target.write_text(text, encoding="utf-8")
    return target


def run_console(process, command_log: Path, command: str, wait: float = 0.35) -> None:
    """向服务端控制台发送命令。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def run_console_capture(process, server_log: Path, command_log: Path, command: str, wait: float = 0.8) -> str:
    """执行控制台命令并返回新增日志。"""
    return gui.run_console_capture(process, server_log, command_log, command, wait)


def run_checked_console(process, server_log: Path, command_log: Path, command: str,
                        markers: list[str], timeout: float = 12.0) -> dict:
    """执行控制台命令并等待指定日志标记。"""
    return gui.run_checked_console(process, server_log, command_log, command, markers, timeout)


def reload_plugin(process, command_log: Path, server_log: Path) -> None:
    """重载插件配置并等待语言文件加载标记。"""
    gui.reload_plugin(process, command_log, server_log)


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化真实客户端玩家状态。"""
    gui.setup_player(case, username, process, command_log)
    run_console(process, command_log, "minecraft:clear " + username, 0.4)


def close_gui(case: dict) -> None:
    """关闭当前客户端 GUI。"""
    hwnd = base.find_minecraft_window(case["version"])
    base.focus_window(hwnd)
    base.post_key(hwnd, 0x1B)
    time.sleep(0.8)


def open_personal(case: dict, game_dir: Path, run_dir: Path, suffix: str) -> dict:
    """让真实客户端打开个人垃圾桶并截图。"""
    return gui.send_client_command(case, game_dir, run_dir, "/blwtc personal", suffix, 1.0)


def click_personal_first_slot(case: dict, game_dir: Path, run_dir: Path, suffix: str) -> dict:
    """点击个人垃圾桶第一个上方槽位，关闭 GUI 后截图聊天提示。"""
    gui.click_slot(case, "top", 0, 0)
    time.sleep(0.8)
    close_gui(case)
    screenshot = gui.capture_named_screenshot(case, game_dir, run_dir, suffix)
    return {"screenshot": gui.screenshot_info(screenshot)}


def fakevault_balance(process, server_log: Path, command_log: Path, username: str) -> str:
    """查询 fake Vault 当前余额和扣款次数。"""
    return run_console_capture(process, server_log, command_log, "fakevault balance " + username, 0.8)


def debugsummary(process, server_log: Path, command_log: Path, username: str) -> str:
    """查询插件后台个人垃圾桶摘要。"""
    return run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)


def personal_amount(text: str) -> int | None:
    """从 debugsummary 文本中提取个人垃圾桶物品数量。"""
    match = re.search(r"个人垃圾桶物品:\s*(\d+)", external.strip_ansi(text or ""))
    if match is None:
        return None
    return int(match.group(1))


def assert_markers(text: str, markers: list[str], name: str) -> None:
    """断言文本包含全部标记。"""
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise AssertionError(name + " 缺少标记: " + ", ".join(missing) + "\n" + text[-2400:])


def run_success_scenario(case: dict, username: str, process, server_log: Path,
                         command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证余额充足时取出物品会扣费且个人桶清空。"""
    run_checked_console(process, server_log, command_log, "fakevault reset", ["AI_FAKEVAULT_RESET"], 8)
    run_checked_console(process, server_log, command_log, "fakevault set " + username + " 100", ["AI_FAKEVAULT_SET", "$100.00"], 8)
    run_checked_console(process, server_log, command_log, "fakevault clearinv " + username, ["AI_FAKEVAULT_CLEARINV", "online=true"], 8)
    route = run_checked_console(process, server_log, command_log,
                                "blwtc debugroute " + username + " personal STONE 1",
                                ["[Debug] debugRoute", "route=PERSONAL_TRASH", "routed=true"], 12)
    before = open_personal(case, game_dir, run_dir, "vault-success-before-click-f2")
    click = click_personal_first_slot(case, game_dir, run_dir, "vault-success-after-click-chat-f2")
    balance = fakevault_balance(process, server_log, command_log, username)
    summary = debugsummary(process, server_log, command_log, username)
    assert_markers(balance, ["AI_FAKEVAULT_BALANCE", "$90.00", "withdrawals=1"], "success balance")
    if not gui.personal_summary_amount(summary, 0):
        raise AssertionError("success debugsummary 未显示个人垃圾桶物品为 0\n" + summary[-2400:])
    return {
        "name": "F-037-success",
        "status": "PASS",
        "route": route,
        "beforeScreenshot": before,
        "afterScreenshot": click["screenshot"],
        "balanceExcerpt": balance[-1600:],
        "summaryExcerpt": summary[-1600:],
    }


def run_insufficient_scenario(case: dict, username: str, process, server_log: Path,
                              command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证余额不足时不会取出物品且不会扣费。"""
    run_checked_console(process, server_log, command_log, "fakevault reset", ["AI_FAKEVAULT_RESET"], 8)
    run_checked_console(process, server_log, command_log, "fakevault set " + username + " 5", ["AI_FAKEVAULT_SET", "$5.00"], 8)
    run_checked_console(process, server_log, command_log, "fakevault clearinv " + username, ["AI_FAKEVAULT_CLEARINV", "online=true"], 8)
    route = run_checked_console(process, server_log, command_log,
                                "blwtc debugroute " + username + " personal DIRT 1",
                                ["[Debug] debugRoute", "route=PERSONAL_TRASH", "routed=true"], 12)
    before = open_personal(case, game_dir, run_dir, "vault-insufficient-before-click-f2")
    click = click_personal_first_slot(case, game_dir, run_dir, "vault-insufficient-after-click-chat-f2")
    balance = fakevault_balance(process, server_log, command_log, username)
    summary = debugsummary(process, server_log, command_log, username)
    assert_markers(balance, ["AI_FAKEVAULT_BALANCE", "$5.00", "withdrawals=0"], "insufficient balance")
    if not gui.personal_summary_amount(summary, 1):
        raise AssertionError("insufficient debugsummary 未显示个人垃圾桶物品为 1\n" + summary[-2400:])
    return {
        "name": "F-037-insufficient",
        "status": "PASS",
        "route": route,
        "beforeScreenshot": before,
        "afterScreenshot": click["screenshot"],
        "balanceExcerpt": balance[-1600:],
        "summaryExcerpt": summary[-1600:],
    }


def run_full_inventory_scenario(case: dict, username: str, process, server_log: Path,
                                command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证背包满时不会取出物品且不会扣费。"""
    run_checked_console(process, server_log, command_log, "fakevault reset", ["AI_FAKEVAULT_RESET"], 8)
    run_checked_console(process, server_log, command_log, "fakevault set " + username + " 100", ["AI_FAKEVAULT_SET", "$100.00"], 8)
    run_checked_console(process, server_log, command_log, "fakevault fill " + username + " COBBLESTONE", ["AI_FAKEVAULT_FILL", "slots=36"], 8)
    route = run_checked_console(process, server_log, command_log,
                                "blwtc debugroute " + username + " personal STONE 1",
                                ["[Debug] debugRoute", "route=PERSONAL_TRASH", "routed=true"], 12)
    before_summary = debugsummary(process, server_log, command_log, username)
    before_amount = personal_amount(before_summary)
    before = open_personal(case, game_dir, run_dir, "vault-full-inventory-before-click-f2")
    click = click_personal_first_slot(case, game_dir, run_dir, "vault-full-inventory-after-click-chat-f2")
    balance = fakevault_balance(process, server_log, command_log, username)
    summary = debugsummary(process, server_log, command_log, username)
    assert_markers(balance, ["AI_FAKEVAULT_BALANCE", "$100.00", "withdrawals=0"], "full inventory balance")
    if before_amount is None or not gui.personal_summary_amount(summary, before_amount):
        raise AssertionError("full inventory debugsummary 点击前后数量不一致，before="
                             + str(before_amount) + "\n" + summary[-2400:])
    return {
        "name": "F-037-full-inventory",
        "status": "PASS",
        "route": route,
        "beforeScreenshot": before,
        "afterScreenshot": click["screenshot"],
        "balanceExcerpt": balance[-1600:],
        "beforeSummaryExcerpt": before_summary[-1600:],
        "summaryExcerpt": summary[-1600:],
    }


def render_text_screenshot(text: str, target: Path, title: str) -> Path:
    """把服务端关键日志渲染成 PNG 证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = evidence_font()
    lines = [title, ""]
    lines.extend(external.strip_ansi(text).splitlines()[-42:])
    width = 1680
    line_height = 26
    height = max(260, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line[:210], fill=color, font=used_font)
        y += line_height
    image.save(target)
    return target


def evidence_font() -> ImageFont.ImageFont:
    """返回证据截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def copy_runtime_evidence(case: dict, run_dir: Path, server_log: Path, command_log: Path, result: dict) -> None:
    """归档运行时日志、配置和服务端文本截图。"""
    plugin_dir = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan"
    gui.copy_runtime_file(plugin_dir / "trash.yml", run_dir / "logs" / "trash-after-vault-test.yml")
    gui.copy_runtime_file(Path(case["serverDir"]) / "logs" / "latest.log", run_dir / "logs" / "latest.log")
    if command_log.is_file():
        text = command_log.read_text(encoding="utf-8", errors="replace")
        shot = render_text_screenshot(text, run_dir / "server-screenshots" / (case["id"] + "-commands.png"), case["label"] + " / commands")
        result["commandScreenshot"] = gui.screenshot_info(shot)
    if server_log.is_file():
        text = server_log.read_text(encoding="utf-8", errors="replace")
        filtered = "\n".join(line for line in text.splitlines() if "Vault" in line or "AI_FAKEVAULT" in line or "个人垃圾桶" in line)
        shot = render_text_screenshot(filtered, run_dir / "server-screenshots" / (case["id"] + "-vault-server-log.png"), case["label"] + " / Vault server log")
        result["serverLogScreenshot"] = gui.screenshot_info(shot)


def run_case(case: dict, prepared_clients: dict, evidence_root: Path, fixture_jar: Path) -> dict:
    """运行单个 Vault 扣费真实客户端验收用例。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始 Vault 扣费用例 " + case["id"] + " / " + case["label"])
    process = None
    client = None
    install_state = None
    config_backups = []
    game_dir = None
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "sourceId": case.get("sourceId", ""),
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "status": "FAIL",
        "checks": [],
        "artifact": external.artifact_summary_for_plugin(case),
        "fixtureJar": str(fixture_jar),
        "fixtureSha256": sha256_file(fixture_jar),
    }
    try:
        install_state = install_fixture(case, fixture_jar, run_dir)
        process = external.launch_server(case, run_dir)
        config_backups = backup_runtime_config(case, run_dir)
        result["patchedTrashConfig"] = str(patch_trash_config(case))
        reload_plugin(process, command_log, server_log)
        all_log = external.read_text(server_log)
        assert_markers(all_log, ["AI_FAKEVAULT_READY", "[Vault] 已连接经济服务"], "startup vault registration")
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        result["clientPid"] = client.pid
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)
        platform_offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc platform", 0.4)
        external.wait_platform_command_accepted(server_log, platform_offset)
        result["checks"].append(run_success_scenario(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_insufficient_scenario(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_full_inventory_scenario(case, username, process, server_log, command_log, run_dir, game_dir))
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("Vault 扣费用例失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                result["failureScreenshot"] = str(gui.capture_named_screenshot(case, game_dir, run_dir, "failure-f2"))
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        try:
            copy_runtime_evidence(case, run_dir, server_log, command_log, result)
        except Exception as evidence_error:
            result["evidenceError"] = repr(evidence_error)
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            external.stop_process(process, "stop")
        if config_backups:
            restore_runtime_config(config_backups)
        restore_fixture(install_state)
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path | None:
    """生成 Vault 扣费截图总览图。"""
    screenshots = []
    for result in results:
        for item in result.get("checks", []):
            for key in ("beforeScreenshot", "afterScreenshot", "commandScreenshot", "serverLogScreenshot"):
                value = item.get(key)
                if isinstance(value, dict) and value.get("path"):
                    screenshots.append((result["label"] + " " + item.get("name", "") + " " + key, Path(value["path"])))
        for key in ("commandScreenshot", "serverLogScreenshot"):
            value = result.get(key)
            if isinstance(value, dict) and value.get("path"):
                screenshots.append((result["label"] + " " + key, Path(value["path"])))
    if not screenshots:
        return None
    thumbs = []
    used_font = evidence_font()
    for label, path in screenshots:
        if not path.is_file():
            continue
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 300), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 252), label[:58], fill=(226, 232, 240), font=used_font)
        draw.text((10, 274), path.name[:58], fill=(148, 163, 184), font=used_font)
        thumbs.append(canvas)
    if not thumbs:
        return None
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 300), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 300))
    target = evidence_root / "vault-payment-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, summary: dict) -> None:
    """写入 Vault 扣费证据说明。"""
    lines = [
        "# F-037 Vault 扣费专项验收",
        "",
        "- 被测插件: `dist/BlWorldTrashCan-universal.jar`",
        "- 夹具插件: 临时 `Vault-FakeEconomy.jar`，插件名为 `Vault`，注册 VaultAPI `Economy` 服务",
        "- 验收方式: 真实服务端 + 真实客户端打开个人垃圾桶 GUI 并点击取出槽位",
        "- 覆盖场景: 余额充足扣费成功；余额不足不取出不扣费；背包满不取出不扣费",
        "- 结论: " + ("PASS" if summary.get("allPassed") else "FAIL"),
        "- Contact sheet: `" + str(Path(summary.get("contactSheet", "")).name) + "`",
        "",
    ]
    for result in summary.get("results", []):
        lines.extend([
            "## " + result["id"],
            "",
            "- 服务端: `" + result["label"] + "`",
            "- 客户端版本: `" + result["clientVersion"] + "`",
            "- 插件 SHA256: `" + result.get("artifact", {}).get("sha256", "") + "`",
            "- 夹具 SHA256: `" + result.get("fixtureSha256", "") + "`",
            "- 结果: `" + result.get("status", "FAIL") + "`",
            "- 关键日志: `" + result["id"] + "/logs/" + result["id"] + "-server-console.log`、`" + result["id"] + "/logs/" + result["id"] + "-console-commands.log`",
            "",
        ])
    (evidence_root / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """运行 F-037 Vault 扣费真实客户端验收。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    ensure_inputs()
    run_id = "vault-payment-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    run_root = BUILD_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    run_root.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(run_root)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        result = run_case(case, prepared_clients, evidence_root, fixture_jar)
        results.append(result)
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results, "contactSheet": ""})
    contact_sheet = make_contact_sheet(results, evidence_root)
    summary = {
        "run": run_id,
        "results": results,
        "contactSheet": str(contact_sheet) if contact_sheet else "",
        "allPassed": all(item.get("status") == "PASS" for item in results),
    }
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("Vault 扣费矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
