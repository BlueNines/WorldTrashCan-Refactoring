package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 验证 Bukkit 配置适配器对节点第一层键的读取语义。 */
public final class BukkitConfigurationSourceTest {
    /** 根节点读取应只返回 Material，不把它们的字段展开。 */
    @Test
    public void rootKeysExposeIndependentMaterialEntries() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                "STONE:\n"
                        + "  enabled: true\n"
                        + "  max-stack-size: -1\n"
                        + "DIRT:\n"
                        + "  enabled: false\n");

        Set<String> keys = new BukkitConfigurationSource(yaml).getKeys("");

        assertEquals(2, keys.size());
        assertTrue(keys.contains("STONE"));
        assertTrue(keys.contains("DIRT"));
    }

    /** 子节点读取应只返回该节点直接包含的字段。 */
    @Test
    public void nestedKeysStayAtOneLevel() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                "STONE:\n"
                        + "  enabled: true\n"
                        + "  nested:\n"
                        + "    ignored: true\n");

        Set<String> keys = new BukkitConfigurationSource(yaml).getKeys("STONE");

        assertEquals(2, keys.size());
        assertTrue(keys.contains("enabled"));
        assertTrue(keys.contains("nested"));
    }
}
