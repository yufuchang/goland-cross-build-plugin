package com.github.yufuchang.crossbuild;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** No IDE dependencies: scanning and building can be checked with an ordinary JDK. */
public final class CrossBuildCore {
    private static final Set<String> IGNORED = Set.of(
            ".git", ".idea", ".gradle", "dist", "vendor", "node_modules", "build", "out");
    private static final Pattern MAIN_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+main\\b");

    private CrossBuildCore() { }

    public static List<Path> scan(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(normalizedRoot)) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || IGNORED.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && file.getFileName().toString().equals("main.go")) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (MAIN_PACKAGE.matcher(source).find()) {
                        result.add(file.getParent());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()));
        return result;
    }

    public static List<String> platforms(String goExecutable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(goExecutable, "tool", "dist", "list")
                .redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("获取 Go 平台列表超时");
        }
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            throw new IOException("go tool dist list 失败：" + output.trim());
        }
        return Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(item -> item.matches("[a-z0-9]+/[a-z0-9]+"))
                .distinct().sorted().toList();
    }

    public static String targetName(Path projectRoot, Path mainDirectory) {
        Path relative = projectRoot.toAbsolutePath().normalize().relativize(mainDirectory.toAbsolutePath().normalize());
        String readable = relative.toString().isEmpty()
                ? projectRoot.getFileName().toString()
                : relative.toString().replace('\\', '-').replace('/', '-');
        readable = readable.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (readable.isBlank()) readable = "app";
        if (readable.length() > 60) readable = readable.substring(readable.length() - 60);
        return readable + "-" + shortHash(relative.toString());
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BuildResult build(Path projectRoot, Path mainDirectory, String platform,
                                    String goExecutable, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String[] pair = platform.split("/", -1);
        if (pair.length != 2 || !platform.matches("[a-z0-9]+/[a-z0-9]+")) {
            throw new IllegalArgumentException("无效平台：" + platform);
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path main = mainDirectory.toAbsolutePath().normalize();
        if (!main.startsWith(root) || !Files.isRegularFile(main.resolve("main.go"))) {
            throw new IllegalArgumentException("main.go 不在项目目录内：" + main);
        }
        String name = targetName(root, main);
        Path destination = root.resolve("dist").resolve(name).resolve(pair[0] + "_" + pair[1]);
        Files.createDirectories(destination);
        String extension = pair[0].equals("windows") ? ".exe"
                : pair[1].equals("wasm") ? ".wasm" : "";
        Path binary = destination.resolve(name + extension);
        Path archive = destination.resolve(name + "-" + pair[0] + "-" + pair[1] + ".zip");
        Path temporaryBinary = destination.resolve(name + ".building" + extension);
        Path temporaryArchive = destination.resolve(name + ".building.zip");
        Path outputLog = Files.createTempFile(destination, "go-build-", ".log");
        Path moduleRoot = findModuleRoot(root, main);
        Path workingDirectory = moduleRoot == null ? main : moduleRoot;
        String packageArgument = moduleRoot == null ? "."
                : moduleRoot.equals(main) ? "."
                : "./" + moduleRoot.relativize(main).toString().replace('\\', '/');
        ProcessBuilder builder = new ProcessBuilder(goExecutable, "build", "-trimpath", "-o",
                temporaryBinary.toString(), packageArgument);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputLog.toFile());
        builder.environment().put("GOOS", pair[0]);
        builder.environment().put("GOARCH", pair[1]);
        builder.environment().put("CGO_ENABLED", "0");
        if (moduleRoot == null) builder.environment().put("GO111MODULE", "off");
        Process process = null;
        try {
            process = builder.start();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new InterruptedException("构建已取消");
                }
            }
            String output = Files.readString(outputLog, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || !Files.isRegularFile(temporaryBinary)) {
                Files.deleteIfExists(temporaryBinary);
                return new BuildResult(false, output.isEmpty() ? "go build 未生成可执行文件" : output,
                        binary, archive);
            }
            moveReplacing(temporaryBinary, binary);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporaryArchive))) {
                zip.putNextEntry(new ZipEntry(binary.getFileName().toString()));
                Files.copy(binary, zip);
                zip.closeEntry();
            }
            moveReplacing(temporaryArchive, archive);
            return new BuildResult(true, output, binary, archive);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(temporaryBinary);
            Files.deleteIfExists(temporaryArchive);
            Files.deleteIfExists(outputLog);
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path findModuleRoot(Path projectRoot, Path mainDirectory) {
        for (Path cursor = mainDirectory; cursor != null && cursor.startsWith(projectRoot);
             cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("go.mod"))) return cursor;
        }
        return null;
    }

    public record BuildResult(boolean success, String output, Path binary, Path archive) { }
}

