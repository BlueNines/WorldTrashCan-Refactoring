package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 验证 Bukkit 配置适配器对字符串映射的读取语义。 */
public final class BukkitConfigurationSourceTest {
    /** 点号键被 Bukkit 展开为嵌套节点后仍应恢复为完整覆盖键。 */
    @Test
    public void dottedOverrideKeysAreFlattened() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                "display:\n"
                        + "  custom-name:\n"
                        + "    overrides:\n"
                        + "      'minecraft:stone': '命名空间覆盖'\n"
                        + "      'block.minecraft.stone': '翻译键覆盖'\n");

        Map<String, String> values = new BukkitConfigurationSource(yaml)
                .getStringMap("display.custom-name.overrides");

        assertEquals("命名空间覆盖", values.get("minecraft:stone"));
        assertEquals("翻译键覆盖", values.get("block.minecraft.stone"));
    }
}
