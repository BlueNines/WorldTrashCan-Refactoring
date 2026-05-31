package pixeltech.bluenine.blworldtrashcan.storage;

import java.io.IOException;
import java.util.Collection;

/** 世界垃圾桶数据存储接口。 */
public interface WorldTrashStorage {
    /** 读取所有世界垃圾桶数据。 */
    Collection<WorldTrashData> loadAll() throws IOException;

    /** 保存单个世界垃圾桶数据。 */
    void save(WorldTrashData data) throws IOException;
}
