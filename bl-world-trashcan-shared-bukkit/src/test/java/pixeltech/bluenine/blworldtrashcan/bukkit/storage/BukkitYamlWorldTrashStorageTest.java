package pixeltech.bluenine.blworldtrashcan.bukkit.storage;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pixeltech.bluenine.blworldtrashcan.storage.TrashLocation;
import pixeltech.bluenine.blworldtrashcan.storage.WorldTrashData;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 验证世界垃圾桶创建者新格式和旧坐标格式可以同时读取。 */
public final class BukkitYamlWorldTrashStorageTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 新位置保存后应完整还原创建者 UUID、名字和坐标。 */
    @Test
    public void preservesWorldTrashOwner() throws Exception {
        File file = temporaryFolder.newFile("world-trash.yml");
        BukkitYamlWorldTrashStorage storage = new BukkitYamlWorldTrashStorage(file);
        UUID ownerUuid = UUID.randomUUID();
        TrashLocation expected = new TrashLocation("world", 12, 64, -8, ownerUuid, "Creator");
        Set<TrashLocation> locations = new HashSet<>();
        locations.add(expected);

        storage.save(new WorldTrashData("world", locations,
                Collections.<String>emptySet(), 3));

        TrashLocation actual = storage.loadAll().iterator().next().getLocations().iterator().next();
        Assert.assertEquals(expected, actual);
        Assert.assertEquals(ownerUuid, actual.getOwnerUuid());
        Assert.assertEquals("Creator", actual.getOwnerName());
    }

    /** 旧版只有 x,y,z 的位置必须保留并明确 owner 未知。 */
    @Test
    public void loadsLegacyCoordinateWithoutInventingOwner() throws Exception {
        File file = temporaryFolder.newFile("legacy-world-trash.yml");
        Files.write(file.toPath(), ("worlds:\n"
                + "  world:\n"
                + "    max-count: 3\n"
                + "    locations:\n"
                + "      - '1,64,-2'\n"
                + "    banned-materials: []\n").getBytes(StandardCharsets.UTF_8));
        BukkitYamlWorldTrashStorage storage = new BukkitYamlWorldTrashStorage(file);

        Collection<WorldTrashData> loaded = storage.loadAll();
        TrashLocation location = loaded.iterator().next().getLocations().iterator().next();

        Assert.assertEquals(new TrashLocation("world", 1, 64, -2), location);
        Assert.assertNull(location.getOwnerUuid());
        Assert.assertEquals("", location.getOwnerName());
    }
}
