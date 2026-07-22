package pixeltech.bluenine.blworldtrashcan.bukkit.api;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import pixeltech.worldlisttrashcan.api.command.SubcommandDefinition;
import pixeltech.worldlisttrashcan.api.command.SubcommandRegistration;
import pixeltech.worldlisttrashcan.api.command.WorldListTrashCanCommandRegistry;
import pixeltech.worldlisttrashcan.api.command.WorldListTrashCanSubcommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 基于不可变快照的轻量一级副指令注册器。 */
public final class DefaultWorldListTrashCanCommandRegistry implements WorldListTrashCanCommandRegistry {
    private static final String NAME_PATTERN = "[a-z0-9_-]{1,32}";
    private static final long ERROR_LOG_INTERVAL_MILLIS = 30000L;

    private final Plugin plugin;
    private final Set<String> reservedNames;
    private final AtomicReference<CommandSnapshot> snapshot = new AtomicReference<>(CommandSnapshot.empty());
    private final AtomicLong nextId = new AtomicLong(1L);

    /** 创建副指令注册器。 */
    public DefaultWorldListTrashCanCommandRegistry(Plugin plugin, Set<String> reservedNames) {
        this.plugin = plugin;
        this.reservedNames = Collections.unmodifiableSet(new HashSet<>(reservedNames));
    }

    /** 返回当前 API 版本。 */
    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    /** 注册副指令。 */
    @Override
    public synchronized SubcommandRegistration register(Plugin owner, SubcommandDefinition definition,
                                                        WorldListTrashCanSubcommand subcommand) {
        if (owner == null || definition == null || subcommand == null) {
            throw new IllegalArgumentException("owner, definition and subcommand cannot be null");
        }
        validateDefinition(definition);
        CommandSnapshot current = snapshot.get();
        ensureNamesAvailable(current, definition);
        CommandEntry entry = new CommandEntry(nextId.getAndIncrement(), owner, definition, subcommand);
        snapshot.set(current.withEntry(entry));
        return new RegistryRegistration(this, entry.id);
    }

    /** 尝试执行附属副指令。 */
    public DispatchResult dispatch(CommandSender sender, String subcommand, String[] args) {
        CommandEntry entry = snapshot.get().byName.get(normalize(subcommand));
        if (entry == null || !entry.owner.isEnabled()) {
            return DispatchResult.NOT_FOUND;
        }
        if (!hasPermission(sender, entry.definition.getPermission())) {
            return DispatchResult.NO_PERMISSION;
        }
        try {
            entry.subcommand.execute(sender, tail(args));
            return DispatchResult.HANDLED;
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            logFailure(entry, "执行副指令失败", throwable);
            return DispatchResult.FAILED;
        }
    }

    /** 返回发送者可见的一级副指令名称。 */
    public List<String> visibleNames(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (CommandEntry entry : snapshot.get().entries) {
            if (entry.owner.isEnabled() && hasPermission(sender, entry.definition.getPermission())) {
                names.add(entry.definition.getName());
            }
        }
        return names;
    }

    /** 返回附属副指令的参数补全。 */
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args == null || args.length < 2) {
            return Collections.emptyList();
        }
        CommandEntry entry = snapshot.get().byName.get(normalize(args[0]));
        if (entry == null || !entry.owner.isEnabled() || !hasPermission(sender, entry.definition.getPermission())) {
            return Collections.emptyList();
        }
        try {
            List<String> values = entry.subcommand.tabComplete(sender, tail(args));
            return values == null ? Collections.<String>emptyList() : new ArrayList<>(values);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            logFailure(entry, "副指令补全失败", throwable);
            return Collections.emptyList();
        }
    }

    /** 返回发送者可见的附属命令帮助项。 */
    public List<HelpEntry> helpEntries(CommandSender sender) {
        List<HelpEntry> result = new ArrayList<>();
        for (CommandEntry entry : snapshot.get().entries) {
            if (!entry.owner.isEnabled() || !hasPermission(sender, entry.definition.getPermission())) {
                continue;
            }
            try {
                String usage = nonNull(entry.subcommand.getUsage(sender));
                String description = nonNull(entry.subcommand.getDescription(sender));
                result.add(new HelpEntry(entry.definition.getName(), usage, description));
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable throwable) {
                logFailure(entry, "读取副指令帮助失败", throwable);
            }
        }
        return result;
    }

    /** 移除指定插件拥有的全部副指令。 */
    public synchronized void removeOwner(Plugin owner) {
        if (owner == null) {
            return;
        }
        snapshot.set(snapshot.get().withoutOwner(owner));
    }

    /** 关闭注册器并释放全部附属对象引用。 */
    public synchronized void close() {
        snapshot.set(CommandSnapshot.empty());
    }

    /** 注销指定 ID 的副指令。 */
    private synchronized void unregister(long id) {
        snapshot.set(snapshot.get().withoutId(id));
    }

    /** 校验副指令定义。 */
    private void validateDefinition(SubcommandDefinition definition) {
        Set<String> seen = new HashSet<>();
        validateName(definition.getName(), seen);
        for (String alias : definition.getAliases()) {
            validateName(alias, seen);
        }
    }

    /** 校验单个名称。 */
    private void validateName(String name, Set<String> seen) {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("Invalid subcommand name: " + name);
        }
        if (reservedNames.contains(name)) {
            throw new IllegalArgumentException("Reserved subcommand name: " + name);
        }
        if (!seen.add(name)) {
            throw new IllegalArgumentException("Duplicate subcommand name or alias: " + name);
        }
    }

    /** 确认名称和别名未被其它附属插件占用。 */
    private void ensureNamesAvailable(CommandSnapshot current, SubcommandDefinition definition) {
        List<String> names = new ArrayList<>();
        names.add(definition.getName());
        names.addAll(definition.getAliases());
        for (String name : names) {
            if (current.byName.containsKey(name)) {
                throw new IllegalStateException("Subcommand name is already registered: " + name);
            }
        }
    }

    /** 返回不含一级副指令名称的参数。 */
    private String[] tail(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /** 判断发送者是否拥有副指令权限。 */
    private boolean hasPermission(CommandSender sender, String permission) {
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }

    /** 规范化命令名称。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 把 null 文本转为空文本。 */
    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    /** 限频记录单个附属命令异常。 */
    private void logFailure(CommandEntry entry, String message, Throwable throwable) {
        long now = System.currentTimeMillis();
        long previous = entry.lastErrorLogMillis.get();
        if (now - previous < ERROR_LOG_INTERVAL_MILLIS
                || !entry.lastErrorLogMillis.compareAndSet(previous, now)) {
            return;
        }
        plugin.getLogger().warning("[AddonCommand] " + entry.owner.getName() + " " + message
                + (throwable == null || throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
    }

    /** 副指令执行结果。 */
    public enum DispatchResult {
        NOT_FOUND,
        NO_PERMISSION,
        HANDLED,
        FAILED
    }

    /** 帮助面板需要的不可变副指令信息。 */
    public static final class HelpEntry {
        private final String name;
        private final String usage;
        private final String description;

        /** 创建帮助项。 */
        private HelpEntry(String name, String usage, String description) {
            this.name = name;
            this.usage = usage;
            this.description = description;
        }

        /** 返回规范副指令名称。 */
        public String getName() {
            return name;
        }

        /** 返回参数用法。 */
        public String getUsage() {
            return usage;
        }

        /** 返回简短描述。 */
        public String getDescription() {
            return description;
        }
    }

    /** 保存一次注册的附属对象。 */
    private static final class CommandEntry {
        private final long id;
        private final Plugin owner;
        private final SubcommandDefinition definition;
        private final WorldListTrashCanSubcommand subcommand;
        private final AtomicLong lastErrorLogMillis = new AtomicLong(0L);

        /** 创建命令条目。 */
        private CommandEntry(long id, Plugin owner, SubcommandDefinition definition,
                             WorldListTrashCanSubcommand subcommand) {
            this.id = id;
            this.owner = owner;
            this.definition = definition;
            this.subcommand = subcommand;
        }
    }

    /** 执行热路径只读的不可变命令快照。 */
    private static final class CommandSnapshot {
        private final Map<String, CommandEntry> byName;
        private final List<CommandEntry> entries;

        /** 创建命令快照。 */
        private CommandSnapshot(Map<String, CommandEntry> byName, List<CommandEntry> entries) {
            this.byName = Collections.unmodifiableMap(byName);
            this.entries = Collections.unmodifiableList(entries);
        }

        /** 返回空快照。 */
        private static CommandSnapshot empty() {
            return new CommandSnapshot(new HashMap<String, CommandEntry>(), new ArrayList<CommandEntry>());
        }

        /** 返回增加条目后的新快照。 */
        private CommandSnapshot withEntry(CommandEntry entry) {
            List<CommandEntry> values = new ArrayList<>(entries);
            values.add(entry);
            return rebuild(values);
        }

        /** 返回移除指定注册后的新快照。 */
        private CommandSnapshot withoutId(long id) {
            List<CommandEntry> values = new ArrayList<>();
            for (CommandEntry entry : entries) {
                if (entry.id != id) {
                    values.add(entry);
                }
            }
            return rebuild(values);
        }

        /** 返回移除指定插件全部注册后的新快照。 */
        private CommandSnapshot withoutOwner(Plugin owner) {
            List<CommandEntry> values = new ArrayList<>();
            for (CommandEntry entry : entries) {
                if (entry.owner != owner) {
                    values.add(entry);
                }
            }
            return rebuild(values);
        }

        /** 从稳定注册顺序重建名称和别名索引。 */
        private static CommandSnapshot rebuild(List<CommandEntry> entries) {
            Map<String, CommandEntry> byName = new HashMap<>();
            for (CommandEntry entry : entries) {
                byName.put(entry.definition.getName(), entry);
                for (String alias : entry.definition.getAliases()) {
                    byName.put(alias, entry);
                }
            }
            return new CommandSnapshot(byName, new ArrayList<>(entries));
        }
    }

    /** 可重复关闭的副指令注册句柄。 */
    private static final class RegistryRegistration implements SubcommandRegistration {
        private final DefaultWorldListTrashCanCommandRegistry registry;
        private final AtomicLong id;

        /** 创建注册句柄。 */
        private RegistryRegistration(DefaultWorldListTrashCanCommandRegistry registry, long id) {
            this.registry = registry;
            this.id = new AtomicLong(id);
        }

        /** 注销副指令。 */
        @Override
        public void close() {
            long value = id.getAndSet(0L);
            if (value != 0L) {
                registry.unregister(value);
            }
        }
    }
}
