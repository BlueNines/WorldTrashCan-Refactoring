package pixeltech.bluenine.blworldtrashcan.platform.paper.stacking;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 保存逻辑堆叠是否仍存在以及可能包含堆叠的区块坐标。 */
final class ItemStackingStateStore {
    private final File file;

    /** 创建状态存储。 */
    ItemStackingStateStore(File file) {
        this.file = file;
    }

    /** 读取状态；文件缺失或损坏时返回空状态。 */
    State load() {
        if (!file.isFile()) {
            return new State(false, false, new HashSet<StackingChunkKey>());
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Set<StackingChunkKey> chunks = new HashSet<>();
        for (String value : yaml.getStringList("known-chunks")) {
            StackingChunkKey key = StackingChunkKey.parse(value);
            if (key != null) {
                chunks.add(key);
            }
        }
        return new State(yaml.getBoolean("active", false), yaml.getBoolean("draining", false), chunks);
    }

    /** 原子写入状态文件。 */
    synchronized void save(boolean active, boolean draining, Collection<StackingChunkKey> chunks)
            throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建状态目录: " + parent.getAbsolutePath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", active);
        yaml.set("draining", draining);
        List<String> values = new ArrayList<>();
        if (chunks != null) {
            for (StackingChunkKey key : chunks) {
                if (key != null) {
                    values.add(key.serialize());
                }
            }
        }
        yaml.set("known-chunks", values);
        yaml.save(file);
    }

    /** 状态文件不可变快照。 */
    static final class State {
        private final boolean active;
        private final boolean draining;
        private final Set<StackingChunkKey> chunks;

        /** 保存读取到的状态。 */
        private State(boolean active, boolean draining, Set<StackingChunkKey> chunks) {
            this.active = active;
            this.draining = draining;
            this.chunks = chunks;
        }

        /** 判断是否存在尚未排空的逻辑数据。 */
        boolean isActive() {
            return active;
        }

        /** 判断上次运行是否已进入排空模式。 */
        boolean isDraining() {
            return draining;
        }

        /** 返回已知区块坐标的隔离副本。 */
        Set<StackingChunkKey> getChunks() {
            return new HashSet<>(chunks);
        }
    }
}
