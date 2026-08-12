package pixeltech.bluenine.blworldtrashcan.bukkit.trash;

import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ItemIdentityProvider;

/** 保留既有公共桶类型名称的兼容包装，实际逻辑位于通用容器 Store。 */
public final class GlobalTrashStore extends TrashContainerStore {
    /** 创建使用公共审计前缀的容器存储。 */
    public GlobalTrashStore(ItemIdentityProvider identityProvider) {
        super(identityProvider, "global");
    }
}
