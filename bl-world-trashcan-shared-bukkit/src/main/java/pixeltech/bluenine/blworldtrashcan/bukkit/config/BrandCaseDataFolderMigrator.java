package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** 在品牌大小写调整后补拷旧数据目录中的缺失文件。 */
public final class BrandCaseDataFolderMigrator {
    private static final String OLD_DATA_FOLDER_NAME = "B" + "LWorldTrashCan";

    /** 阻止实例化迁移工具类。 */
    private BrandCaseDataFolderMigrator() {
    }

    /** 在保存默认配置前迁移旧大小写数据目录。 */
    public static void migrateIfNeeded(JavaPlugin plugin) {
        File targetFolder = plugin.getDataFolder();
        File parentFolder = targetFolder.getParentFile();
        if (parentFolder == null) {
            return;
        }
        File sourceFolder = new File(parentFolder, OLD_DATA_FOLDER_NAME);
        if (!sourceFolder.isDirectory() || isSameFolder(sourceFolder, targetFolder)) {
            return;
        }
        try {
            int copiedFiles = copyMissingFiles(sourceFolder.toPath(), targetFolder.toPath());
            if (copiedFiles > 0) {
                plugin.getLogger().info("[BrandMigration] 已从旧大小写数据目录补拷 "
                        + copiedFiles + " 个缺失文件，原目录保持不变。");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "[BrandMigration] 旧大小写数据目录迁移失败: " + exception.getMessage(), exception);
        }
    }

    /** 递归复制目标目录中尚不存在的文件。 */
    static int copyMissingFiles(Path sourceFolder, Path targetFolder) throws IOException {
        final AtomicInteger copiedFiles = new AtomicInteger();
        Files.walkFileTree(sourceFolder, new SimpleFileVisitor<Path>() {
            /** 创建对应的目标子目录。 */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = sourceFolder.relativize(directory);
                Files.createDirectories(targetFolder.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            /** 只复制目标中缺失的普通文件。 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path targetFile = targetFolder.resolve(sourceFolder.relativize(file));
                if (Files.notExists(targetFile)) {
                    Files.copy(file, targetFile);
                    copiedFiles.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return copiedFiles.get();
    }

    /** 判断两个目录在当前文件系统中是否指向同一路径。 */
    private static boolean isSameFolder(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException ignored) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }
}
