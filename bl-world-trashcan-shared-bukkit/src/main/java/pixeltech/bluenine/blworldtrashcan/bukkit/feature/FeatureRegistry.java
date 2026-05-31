package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 功能模块注册表，统一管理 enable、reload 和 disable 顺序。 */
public final class FeatureRegistry {
    private final List<Feature> features = new ArrayList<>();

    /** 注册功能模块。 */
    public void register(Feature feature) {
        if (feature != null) {
            features.add(feature);
        }
    }

    /** 启用所有功能。 */
    public void enableAll() {
        for (Feature feature : features) {
            feature.enable();
        }
    }

    /** 重载所有功能。 */
    public void reloadAll() {
        for (Feature feature : features) {
            feature.reload();
        }
    }

    /** 按反向顺序禁用所有功能。 */
    public void disableAll() {
        List<Feature> copy = new ArrayList<>(features);
        Collections.reverse(copy);
        for (Feature feature : copy) {
            feature.disable();
        }
    }
}
