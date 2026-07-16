package pixeltech.bluenine.blworldtrashcan.bukkit.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 不依赖 Bukkit 运行态的数据目录补拷自测。 */
public final class BrandCaseDataFolderMigratorSelfTest {
    /** 执行缺失文件补拷和现有文件保护自测。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("blwtc-brand-case-");
        try {
            Path source = root.resolve("source");
            Path target = root.resolve("target");
            Files.createDirectories(source.resolve("messages"));
            Files.createDirectories(target);
            Files.write(source.resolve("config.yml"), "source-config".getBytes(StandardCharsets.UTF_8));
            Files.write(source.resolve("messages/message_zh.yml"),
                    "source-message".getBytes(StandardCharsets.UTF_8));
            Files.write(target.resolve("config.yml"), "target-config".getBytes(StandardCharsets.UTF_8));

            int copied = BrandCaseDataFolderMigrator.copyMissingFiles(source, target);
            assertEquals("copied files", 1, copied);
            assertText("existing target preserved", target.resolve("config.yml"), "target-config");
            assertText("missing nested file copied", target.resolve("messages/message_zh.yml"), "source-message");
            assertEquals("second run idempotent", 0,
                    BrandCaseDataFolderMigrator.copyMissingFiles(source, target));
            System.out.println("BrandCaseDataFolderMigratorSelfTest passed");
        } finally {
            deleteTree(root);
        }
    }

    /** 断言文件内容等于预期文本。 */
    private static void assertText(String label, Path file, String expected) throws IOException {
        String actual = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " expected " + expected + " but got " + actual);
        }
    }

    /** 断言整数相等。 */
    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException(label + " expected " + expected + " but got " + actual);
        }
    }

    /** 逆序删除自测临时目录。 */
    private static void deleteTree(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to delete " + path, exception);
                }
            });
        }
    }

    /** 阻止实例化自测类。 */
    private BrandCaseDataFolderMigratorSelfTest() {
    }
}
