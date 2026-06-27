package pixeltech.bluenine.blworldtrashcan.bukkit.feature.entitylimit;

import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只处理不可变快照的低占用实体限制引擎，不直接调用任何 Bukkit API。 */
public final class LowOverheadEntityLimitEngine {
    private static final String REASON_GATHER = "gather";
    private final Map<ChunkKey, ChunkState> chunks = new HashMap<>();
    private final Map<UUID, EntityRecord> entities = new HashMap<>();
    private final Map<String, Integer> worldTypeCounts = new HashMap<>();
    private final ArrayDeque<ChunkKey> dirtyQueue = new ArrayDeque<>();
    private final Set<ChunkKey> dirtySet = new HashSet<>();
    private final ArrayDeque<RemovalCandidate> candidates = new ArrayDeque<>();
    private final Set<UUID> pendingRemovalUuids = new HashSet<>();
    private long scanCursor;
    private long snapshotVersion;
    private long dirtyMarked;
    private long dirtyDropped;
    private long snapshotsApplied;
    private long chunksSelected;
    private long chunksScanned;
    private long chunksUnloaded;
    private long entitiesIndexedTotal;
    private long candidatesCreated;
    private long candidatesDropped;
    private long candidatesExpired;
    private long candidatesPolled;
    private long candidatesRetried;
    private long candidatesFinished;
    private long removalsCompleted;
    private long removalsSkipped;
    private long staleChunksPruned;
    private long indexCapPruned;
    private int lastLoadedChunks;
    private int lastSelectedChunks;

    /** 标记 chunk 需要优先重扫。 */
    public synchronized void markDirty(ChunkKey key, int maxDirtyChunks) {
        if (key == null || dirtySet.contains(key)) {
            return;
        }
        if (dirtySet.size() >= Math.max(1, maxDirtyChunks)) {
            dirtyDropped++;
            return;
        }
        dirtySet.add(key);
        dirtyQueue.addLast(key);
        dirtyMarked++;
    }

    /** 从当前已加载 chunk 中选择本轮需要扫描的 chunk。 */
    public synchronized List<ChunkKey> selectChunks(List<ChunkKey> loadedChunks, EntityLimitConfig.ScanConfig config) {
        long now = System.currentTimeMillis();
        pruneStaleChunks(now, config);
        List<ChunkKey> loaded = loadedChunks == null ? Collections.<ChunkKey>emptyList() : loadedChunks;
        lastLoadedChunks = loaded.size();
        if (loaded.isEmpty()) {
            lastSelectedChunks = 0;
            return Collections.emptyList();
        }
        Set<ChunkKey> loadedSet = new HashSet<>(loaded);
        int target = computeScanTarget(loaded.size(), config);
        List<ChunkKey> selected = new ArrayList<>(target);
        while (!dirtyQueue.isEmpty() && selected.size() < target) {
            ChunkKey key = dirtyQueue.removeFirst();
            dirtySet.remove(key);
            if (loadedSet.contains(key)) {
                selected.add(key);
            } else {
                removeChunk(key);
            }
        }
        int start = (int) (Math.abs(scanCursor) % loaded.size());
        scanCursor += Math.max(1, target);
        for (int index = 0; index < loaded.size() && selected.size() < target; index++) {
            ChunkKey key = loaded.get((start + index) % loaded.size());
            if (!selected.contains(key)) {
                selected.add(key);
            }
        }
        chunksSelected += selected.size();
        lastSelectedChunks = selected.size();
        return selected;
    }

    /** 应用一次 chunk 实体快照并计算待删除候选。 */
    public synchronized void applySnapshot(ChunkSnapshot snapshot, EntityLimitConfig config) {
        if (snapshot == null || config == null) {
            return;
        }
        if (!snapshot.isLoaded()) {
            removeChunk(snapshot.getKey());
            chunksUnloaded++;
            return;
        }
        long now = System.currentTimeMillis();
        long version = ++snapshotVersion;
        List<EntityRecord> records = capRecords(snapshot.getRecords(), config.getScanConfig().getMaxIndexEntitiesPerChunk());
        removeChunk(snapshot.getKey());
        ChunkState state = new ChunkState(snapshot.getKey(), records, now, version);
        chunks.put(snapshot.getKey(), state);
        for (EntityRecord record : records) {
            removeIndexedEntity(record.getUniqueId());
            entities.put(record.getUniqueId(), record);
            incrementWorldTypeCount(record.getWorldName(), record.getTypeName(), 1);
        }
        chunksScanned++;
        snapshotsApplied++;
        entitiesIndexedTotal += records.size();
        pruneIndexCap(config);
        computeGatherCandidates(state, config);
    }

    /** 按预算取出一批候选，候选必须由调用方最终 finish 或 retry。 */
    public synchronized List<RemovalCandidate> pollCandidates(int maxCount, EntityLimitConfig.ScanConfig config) {
        int limit = Math.max(1, maxCount);
        long now = System.currentTimeMillis();
        long ttlMillis = Math.max(1, config.getCandidateTtlSeconds()) * 1000L;
        List<RemovalCandidate> result = new ArrayList<>(limit);
        while (!candidates.isEmpty() && result.size() < limit) {
            RemovalCandidate candidate = candidates.removeFirst();
            if (now - candidate.getCreatedAtMillis() > ttlMillis) {
                finishCandidateInternal(candidate, true, false, true);
                candidatesExpired++;
                continue;
            }
            result.add(candidate);
            candidatesPolled++;
        }
        return result;
    }

    /** 判断候选在当前索引中是否仍允许删除。 */
    public synchronized boolean shouldRemove(RemovalCandidate candidate, EntityLimitConfig config) {
        if (candidate == null || config == null) {
            return false;
        }
        EntityRecord record = entities.get(candidate.getUniqueId());
        if (record == null) {
            return false;
        }
        if (!record.sameChunk(candidate.getWorldName(), candidate.getChunkX(), candidate.getChunkZ())) {
            return false;
        }
        if (!record.getTypeName().equals(candidate.getTypeName())) {
            return false;
        }
        EntityLimitConfig.GatherLimitConfig gatherConfig = config.getGatherLimit();
        if (!gatherConfig.isEnabled()) {
            return false;
        }
        EntityLimitConfig.GatherRule rule = gatherConfig.getRule(record.getTypeName());
        if (rule == null || gatherConfig.isIgnoredWorld(record.getWorldName())) {
            return false;
        }
        return true;
    }

    /** 返回候选附近同类型实体的索引数量。 */
    public synchronized int countNearbySameType(RemovalCandidate candidate, int radius) {
        if (candidate == null) {
            return 0;
        }
        EntityRecord record = entities.get(candidate.getUniqueId());
        if (record == null) {
            return 0;
        }
        return findNearbySameType(record, radius).size();
    }

    /** 完成候选处理并释放去重标记。 */
    public synchronized void finishCandidate(RemovalCandidate candidate, boolean removeFromIndex, boolean removed) {
        finishCandidateInternal(candidate, removeFromIndex, removed, false);
    }

    /** 候选执行失败时按重试次数重新排队。 */
    public synchronized void retryCandidate(RemovalCandidate candidate, EntityLimitConfig.ScanConfig config) {
        if (candidate == null) {
            return;
        }
        if (candidate.getRetryCount() >= config.getMaxCandidateRetries()) {
            finishCandidateInternal(candidate, true, false, false);
            candidatesDropped++;
            return;
        }
        if (candidates.size() >= config.getMaxPendingRemovals()) {
            finishCandidateInternal(candidate, true, false, false);
            candidatesDropped++;
            return;
        }
        candidates.addLast(candidate.nextRetry());
        candidatesRetried++;
    }

    /** 从索引中移除已知实体。 */
    public synchronized void removeIndexedEntity(UUID uniqueId) {
        removeIndexedEntityInternal(uniqueId);
    }

    /** 返回当前世界指定类型的索引数量。 */
    public synchronized int getWorldTypeCount(String worldName, String typeName) {
        Integer count = worldTypeCounts.get(worldTypeKey(worldName, typeName));
        return count == null ? 0 : Math.max(0, count.intValue());
    }

    /** 返回调试统计文本。 */
    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("§a实体密度扫描统计:");
        lines.add("§7- §f已加载/本轮选择 chunk: §a" + lastLoadedChunks + "§7/§a" + lastSelectedChunks);
        lines.add("§7- §f索引 chunk/实体: §a" + chunks.size() + "§7/§a" + entities.size());
        lines.add("§7- §f脏 chunk 队列: §a" + dirtyQueue.size() + " §7(标记 " + dirtyMarked + ", 丢弃 " + dirtyDropped + ")");
        lines.add("§7- §f候选队列/去重: §a" + candidates.size() + "§7/§a" + pendingRemovalUuids.size());
        lines.add("§7- §f快照/扫描 chunk: §a" + snapshotsApplied + "§7/§a" + chunksScanned + " §7(未加载 " + chunksUnloaded + ")");
        lines.add("§7- §f候选创建/取出/完成: §a" + candidatesCreated + "§7/§a" + candidatesPolled + "§7/§a" + candidatesFinished);
        lines.add("§7- §f候选过期/重试/丢弃: §a" + candidatesExpired + "§7/§a" + candidatesRetried + "§7/§a" + candidatesDropped);
        lines.add("§7- §f删除成功/跳过: §a" + removalsCompleted + "§7/§a" + removalsSkipped);
        lines.add("§7- §f索引修剪 stale/cap: §a" + staleChunksPruned + "§7/§a" + indexCapPruned);
        lines.add("§7- §f累计索引实体写入: §a" + entitiesIndexedTotal);
        return lines;
    }

    /** 清空所有运行态索引和候选。 */
    public synchronized void clear() {
        chunks.clear();
        entities.clear();
        worldTypeCounts.clear();
        dirtyQueue.clear();
        dirtySet.clear();
        candidates.clear();
        pendingRemovalUuids.clear();
        lastLoadedChunks = 0;
        lastSelectedChunks = 0;
    }

    /** 根据加载量和目标周期计算本轮扫描数量。 */
    private int computeScanTarget(int loadedChunkCount, EntityLimitConfig.ScanConfig config) {
        int scansPerCycle = Math.max(1, (config.getTargetFullCycleSeconds() * 20 + config.getScanIntervalTicks() - 1)
                / config.getScanIntervalTicks());
        int required = Math.max(1, (loadedChunkCount + scansPerCycle - 1) / scansPerCycle);
        int target = Math.max(config.getMinChunksPerScan(), required);
        return Math.min(config.getMaxChunksPerScan(), target);
    }

    /** 限制单个 chunk 的索引实体数量。 */
    private List<EntityRecord> capRecords(List<EntityRecord> records, int maxRecords) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, maxRecords);
        if (records.size() <= limit) {
            return new ArrayList<>(records);
        }
        return new ArrayList<>(records.subList(0, limit));
    }

    /** 为一个 chunk 的新快照计算密集实体删除候选。 */
    private void computeGatherCandidates(ChunkState state, EntityLimitConfig config) {
        EntityLimitConfig.GatherLimitConfig gatherConfig = config.getGatherLimit();
        if (!gatherConfig.isEnabled()) {
            return;
        }
        Set<UUID> handledDenseEntities = new HashSet<>();
        for (EntityRecord record : state.getRecords()) {
            if (handledDenseEntities.contains(record.getUniqueId())) {
                continue;
            }
            if (gatherConfig.isIgnoredWorld(record.getWorldName())) {
                continue;
            }
            EntityLimitConfig.GatherRule rule = gatherConfig.getRule(record.getTypeName());
            if (rule == null) {
                continue;
            }
            List<EntityRecord> nearby = findNearbySameType(record, rule.getRadius());
            if (nearby.size() <= rule.getMaxCount()) {
                continue;
            }
            if (hasPendingRemoval(nearby)) {
                for (EntityRecord denseRecord : nearby) {
                    handledDenseEntities.add(denseRecord.getUniqueId());
                }
                continue;
            }
            for (EntityRecord denseRecord : nearby) {
                handledDenseEntities.add(denseRecord.getUniqueId());
            }
            int removeLimit = Math.min(rule.getRemoveCount(), nearby.size());
            int selected = 0;
            for (EntityRecord target : nearby) {
                if (selected >= removeLimit) {
                    break;
                }
                selected++;
                addCandidate(target, state.getVersion(), config.getScanConfig());
            }
        }
    }

    /** 判断同一密集范围内是否已经有待删除候选。 */
    private boolean hasPendingRemoval(List<EntityRecord> records) {
        for (EntityRecord record : records) {
            if (pendingRemovalUuids.contains(record.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /** 尝试加入一个删除候选。 */
    private boolean addCandidate(EntityRecord record, long version, EntityLimitConfig.ScanConfig config) {
        if (pendingRemovalUuids.contains(record.getUniqueId())) {
            return false;
        }
        if (candidates.size() >= config.getMaxPendingRemovals()) {
            candidatesDropped++;
            return false;
        }
        pendingRemovalUuids.add(record.getUniqueId());
        candidates.addLast(new RemovalCandidate(record, REASON_GATHER, version, System.currentTimeMillis(), 0));
        candidatesCreated++;
        return true;
    }

    /** 查找索引中指定实体附近的同类型实体。 */
    private List<EntityRecord> findNearbySameType(EntityRecord source, int radius) {
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        double radiusSquared = radius * (double) radius;
        List<EntityRecord> result = new ArrayList<>();
        for (int x = source.getChunkX() - chunkRadius; x <= source.getChunkX() + chunkRadius; x++) {
            for (int z = source.getChunkZ() - chunkRadius; z <= source.getChunkZ() + chunkRadius; z++) {
                ChunkState state = chunks.get(new ChunkKey(source.getWorldName(), x, z));
                if (state == null) {
                    continue;
                }
                for (EntityRecord candidate : state.getRecords()) {
                    if (!candidate.getTypeName().equals(source.getTypeName())) {
                        continue;
                    }
                    if (candidate.distanceSquared(source) <= radiusSquared) {
                        result.add(candidate);
                    }
                }
            }
        }
        return result;
    }

    /** 按失效时间修剪旧 chunk 索引。 */
    private void pruneStaleChunks(long now, EntityLimitConfig.ScanConfig config) {
        long staleMillis = Math.max(1, config.getStaleChunkSeconds()) * 1000L;
        List<ChunkKey> stale = new ArrayList<>();
        for (Map.Entry<ChunkKey, ChunkState> entry : chunks.entrySet()) {
            if (now - entry.getValue().getLastSeenMillis() > staleMillis) {
                stale.add(entry.getKey());
            }
        }
        for (ChunkKey key : stale) {
            removeChunk(key);
            staleChunksPruned++;
        }
    }

    /** 按实体总数上限修剪最旧 chunk 索引。 */
    private void pruneIndexCap(EntityLimitConfig config) {
        int maxEntities = config.getScanConfig().getMaxIndexEntities();
        while (entities.size() > maxEntities && !chunks.isEmpty()) {
            ChunkKey oldest = findOldestChunk();
            if (oldest == null) {
                return;
            }
            removeChunk(oldest);
            indexCapPruned++;
        }
    }

    /** 查找最旧的 chunk 索引。 */
    private ChunkKey findOldestChunk() {
        ChunkKey oldestKey = null;
        long oldestMillis = Long.MAX_VALUE;
        for (Map.Entry<ChunkKey, ChunkState> entry : chunks.entrySet()) {
            if (entry.getValue().getLastSeenMillis() < oldestMillis) {
                oldestMillis = entry.getValue().getLastSeenMillis();
                oldestKey = entry.getKey();
            }
        }
        return oldestKey;
    }

    /** 移除一个 chunk 及其中实体索引。 */
    private void removeChunk(ChunkKey key) {
        ChunkState state = chunks.remove(key);
        if (state == null) {
            return;
        }
        for (EntityRecord record : state.getRecords()) {
            EntityRecord existing = entities.get(record.getUniqueId());
            if (existing != null && existing.sameChunk(record.getWorldName(), record.getChunkX(), record.getChunkZ())) {
                entities.remove(record.getUniqueId());
                incrementWorldTypeCount(record.getWorldName(), record.getTypeName(), -1);
            }
        }
    }

    /** 从索引中移除单个实体。 */
    private void removeIndexedEntityInternal(UUID uniqueId) {
        if (uniqueId == null) {
            return;
        }
        EntityRecord removed = entities.remove(uniqueId);
        if (removed == null) {
            return;
        }
        incrementWorldTypeCount(removed.getWorldName(), removed.getTypeName(), -1);
        ChunkState state = chunks.get(removed.getChunkKey());
        if (state != null) {
            state.remove(uniqueId);
            if (state.getRecords().isEmpty()) {
                chunks.remove(removed.getChunkKey());
            }
        }
    }

    /** 完成候选处理的内部实现。 */
    private void finishCandidateInternal(RemovalCandidate candidate, boolean removeFromIndex, boolean removed, boolean expired) {
        if (candidate == null) {
            return;
        }
        pendingRemovalUuids.remove(candidate.getUniqueId());
        if (removeFromIndex) {
            removeIndexedEntityInternal(candidate.getUniqueId());
        }
        if (removed) {
            removalsCompleted++;
        } else if (!expired) {
            removalsSkipped++;
        }
        candidatesFinished++;
    }

    /** 调整世界类型计数。 */
    private void incrementWorldTypeCount(String worldName, String typeName, int delta) {
        String key = worldTypeKey(worldName, typeName);
        int next = Math.max(0, getCount(key) + delta);
        if (next <= 0) {
            worldTypeCounts.remove(key);
        } else {
            worldTypeCounts.put(key, next);
        }
    }

    /** 读取世界类型计数。 */
    private int getCount(String key) {
        Integer count = worldTypeCounts.get(key);
        return count == null ? 0 : count.intValue();
    }

    /** 生成世界类型计数键。 */
    private String worldTypeKey(String worldName, String typeName) {
        return normalizeWorld(worldName) + '\u0000' + normalizeType(typeName);
    }

    /** 统一世界名索引格式。 */
    private static String normalizeWorld(String value) {
        return value == null ? "" : value.trim();
    }

    /** 统一实体类型索引格式。 */
    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 不可变 chunk 键。 */
    public static final class ChunkKey {
        private final String worldName;
        private final int chunkX;
        private final int chunkZ;

        /** 创建 chunk 键。 */
        public ChunkKey(String worldName, int chunkX, int chunkZ) {
            this.worldName = normalizeWorld(worldName);
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /** 返回世界名。 */
        public String getWorldName() {
            return worldName;
        }

        /** 返回 chunk X。 */
        public int getChunkX() {
            return chunkX;
        }

        /** 返回 chunk Z。 */
        public int getChunkZ() {
            return chunkZ;
        }

        /** 判断两个键是否相等。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ChunkKey)) {
                return false;
            }
            ChunkKey that = (ChunkKey) other;
            return chunkX == that.chunkX && chunkZ == that.chunkZ && worldName.equals(that.worldName);
        }

        /** 返回哈希值。 */
        @Override
        public int hashCode() {
            int result = worldName.hashCode();
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            return result;
        }

        /** 返回调试文本。 */
        @Override
        public String toString() {
            return worldName + ":" + chunkX + "," + chunkZ;
        }
    }

    /** 单个实体的轻量快照。 */
    public static final class EntityRecord {
        private final UUID uniqueId;
        private final String worldName;
        private final String typeName;
        private final int chunkX;
        private final int chunkZ;
        private final double x;
        private final double y;
        private final double z;

        /** 创建实体轻量快照。 */
        public EntityRecord(UUID uniqueId, String worldName, String typeName, int chunkX, int chunkZ,
                            double x, double y, double z) {
            this.uniqueId = uniqueId;
            this.worldName = normalizeWorld(worldName);
            this.typeName = normalizeType(typeName);
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 返回实体 UUID。 */
        public UUID getUniqueId() {
            return uniqueId;
        }

        /** 返回世界名。 */
        public String getWorldName() {
            return worldName;
        }

        /** 返回实体类型名。 */
        public String getTypeName() {
            return typeName;
        }

        /** 返回 chunk X。 */
        public int getChunkX() {
            return chunkX;
        }

        /** 返回 chunk Z。 */
        public int getChunkZ() {
            return chunkZ;
        }

        /** 返回实体 X 坐标。 */
        public double getX() {
            return x;
        }

        /** 返回实体 Y 坐标。 */
        public double getY() {
            return y;
        }

        /** 返回实体 Z 坐标。 */
        public double getZ() {
            return z;
        }

        /** 返回所在 chunk 键。 */
        public ChunkKey getChunkKey() {
            return new ChunkKey(worldName, chunkX, chunkZ);
        }

        /** 判断实体是否仍在指定 chunk。 */
        public boolean sameChunk(String otherWorldName, int otherChunkX, int otherChunkZ) {
            return chunkX == otherChunkX && chunkZ == otherChunkZ && worldName.equals(normalizeWorld(otherWorldName));
        }

        /** 计算与另一个实体的距离平方。 */
        public double distanceSquared(EntityRecord other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** 单个 chunk 的不可变扫描快照。 */
    public static final class ChunkSnapshot {
        private final ChunkKey key;
        private final boolean loaded;
        private final List<EntityRecord> records;

        /** 创建 chunk 扫描快照。 */
        public ChunkSnapshot(ChunkKey key, boolean loaded, List<EntityRecord> records) {
            this.key = key;
            this.loaded = loaded;
            this.records = records == null ? Collections.<EntityRecord>emptyList() : new ArrayList<>(records);
        }

        /** 创建未加载 chunk 快照。 */
        public static ChunkSnapshot unloaded(ChunkKey key) {
            return new ChunkSnapshot(key, false, Collections.<EntityRecord>emptyList());
        }

        /** 返回 chunk 键。 */
        public ChunkKey getKey() {
            return key;
        }

        /** 判断 chunk 是否仍加载。 */
        public boolean isLoaded() {
            return loaded;
        }

        /** 返回实体快照列表。 */
        public List<EntityRecord> getRecords() {
            return Collections.unmodifiableList(records);
        }
    }

    /** 待执行删除的轻量候选。 */
    public static final class RemovalCandidate {
        private final UUID uniqueId;
        private final String worldName;
        private final String typeName;
        private final int chunkX;
        private final int chunkZ;
        private final String reason;
        private final long snapshotVersion;
        private final long createdAtMillis;
        private final int retryCount;

        /** 从实体记录创建删除候选。 */
        private RemovalCandidate(EntityRecord record, String reason, long snapshotVersion, long createdAtMillis, int retryCount) {
            this.uniqueId = record.getUniqueId();
            this.worldName = record.getWorldName();
            this.typeName = record.getTypeName();
            this.chunkX = record.getChunkX();
            this.chunkZ = record.getChunkZ();
            this.reason = reason;
            this.snapshotVersion = snapshotVersion;
            this.createdAtMillis = createdAtMillis;
            this.retryCount = retryCount;
        }

        /** 返回实体 UUID。 */
        public UUID getUniqueId() {
            return uniqueId;
        }

        /** 返回世界名。 */
        public String getWorldName() {
            return worldName;
        }

        /** 返回实体类型名。 */
        public String getTypeName() {
            return typeName;
        }

        /** 返回 chunk X。 */
        public int getChunkX() {
            return chunkX;
        }

        /** 返回 chunk Z。 */
        public int getChunkZ() {
            return chunkZ;
        }

        /** 返回候选原因。 */
        public String getReason() {
            return reason;
        }

        /** 返回来源快照版本。 */
        public long getSnapshotVersion() {
            return snapshotVersion;
        }

        /** 返回创建时间毫秒。 */
        public long getCreatedAtMillis() {
            return createdAtMillis;
        }

        /** 返回已重试次数。 */
        public int getRetryCount() {
            return retryCount;
        }

        /** 创建重试次数加一的新候选。 */
        private RemovalCandidate nextRetry() {
            EntityRecord record = new EntityRecord(uniqueId, worldName, typeName, chunkX, chunkZ, 0D, 0D, 0D);
            return new RemovalCandidate(record, reason, snapshotVersion, createdAtMillis, retryCount + 1);
        }
    }

    /** 可变 chunk 索引状态。 */
    private static final class ChunkState {
        private final ChunkKey key;
        private final List<EntityRecord> records;
        private final long lastSeenMillis;
        private final long version;

        /** 创建 chunk 索引状态。 */
        private ChunkState(ChunkKey key, List<EntityRecord> records, long lastSeenMillis, long version) {
            this.key = key;
            this.records = new ArrayList<>(records);
            this.lastSeenMillis = lastSeenMillis;
            this.version = version;
        }

        /** 返回 chunk 键。 */
        private ChunkKey getKey() {
            return key;
        }

        /** 返回实体记录。 */
        private List<EntityRecord> getRecords() {
            return records;
        }

        /** 返回最后扫描时间。 */
        private long getLastSeenMillis() {
            return lastSeenMillis;
        }

        /** 返回快照版本。 */
        private long getVersion() {
            return version;
        }

        /** 从 chunk 状态里移除实体。 */
        private void remove(UUID uniqueId) {
            Iterator<EntityRecord> iterator = records.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getUniqueId().equals(uniqueId)) {
                    iterator.remove();
                    return;
                }
            }
        }
    }
}
