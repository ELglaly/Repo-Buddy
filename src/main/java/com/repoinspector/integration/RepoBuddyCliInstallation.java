package com.repoinspector.integration;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the bundled CLI into a stable, per-user application-data directory. */
final class RepoBuddyCliInstallation {
    static final String RESOURCE = "/cli/repobuddy-cli.zip";
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final Set<String> REQUIRED = Set.of(
            "VERSION", "bin/repobuddy", "bin/repobuddy.bat", "lib/repobuddy.jar");

    enum State { NOT_INSTALLED, CURRENT, OUTDATED, BROKEN }
    record Info(State state, Path directory, String installedVersion, String bundledVersion) {}

    private final Path directory;
    private final Supplier<InputStream> distribution;

    RepoBuddyCliInstallation() {
        this(defaultDirectory(System.getenv(), System.getProperty("os.name", ""),
                        Path.of(System.getProperty("user.home"))),
                () -> RepoBuddyCliInstallation.class.getResourceAsStream(RESOURCE));
    }

    RepoBuddyCliInstallation(Path directory, Supplier<InputStream> distribution) {
        this.directory = directory.toAbsolutePath().normalize();
        this.distribution = distribution;
    }

    @NotNull Info inspect() {
        String bundled = bundledVersion();
        if (!Files.exists(directory)) return new Info(State.NOT_INSTALLED, directory, null, bundled);
        String installed = readVersion(directory.resolve("VERSION"));
        boolean complete = REQUIRED.stream().allMatch(name -> Files.isRegularFile(directory.resolve(name)));
        if (!complete || installed == null) return new Info(State.BROKEN, directory, installed, bundled);
        return new Info(installed.equals(bundled) ? State.CURRENT : State.OUTDATED,
                directory, installed, bundled);
    }

    @NotNull Info install() throws IOException {
        Path parent = directory.getParent();
        if (parent == null) throw new IOException("CLI installation directory has no parent");
        Files.createDirectories(parent);
        Path lockPath = parent.resolve("." + directory.getFileName() + ".install.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Info current = inspect();
            if (current.state() == State.CURRENT) return current;
            String suffix = UUID.randomUUID().toString();
            Path staging = parent.resolve("." + directory.getFileName() + ".staging-" + suffix);
            Path backup = parent.resolve("." + directory.getFileName() + ".backup-" + suffix);
            try {
                extract(staging);
                validate(staging);
                if (Files.exists(directory)) move(directory, backup);
                try {
                    move(staging, directory);
                } catch (IOException failure) {
                    if (Files.exists(backup) && !Files.exists(directory)) move(backup, directory);
                    throw failure;
                }
                deleteTree(backup);
                return inspect();
            } finally {
                deleteTree(staging);
                if (Files.exists(backup) && Files.exists(directory)) deleteTree(backup);
            }
        }
    }

    Path jar() { return directory.resolve("lib/repobuddy.jar"); }
    Path bin() { return directory.resolve("bin"); }

    private String bundledVersion() {
        try (InputStream raw = distribution.get()) {
            if (raw == null) return null;
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if ("VERSION".equals(entry.getName())) {
                        byte[] bytes = zip.readNBytes(256);
                        return new String(bytes, StandardCharsets.UTF_8).trim();
                    }
                }
            }
        } catch (IOException ignored) { }
        return null;
    }

    private void extract(Path staging) throws IOException {
        Files.createDirectories(staging);
        long total = 0;
        try (InputStream raw = distribution.get()) {
            if (raw == null) throw new IOException("Bundled RepoBuddy CLI was not found");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    String name = entry.getName().replace('\\', '/');
                    if (name.isBlank()) continue;
                    Path target = staging.resolve(name).normalize();
                    if (!target.startsWith(staging) || Path.of(name).isAbsolute())
                        throw new IOException("Unsafe path in bundled CLI: " + name);
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    long written = copyBounded(zip, target, MAX_ENTRY_BYTES);
                    total += written;
                    if (total > MAX_TOTAL_BYTES) throw new IOException("Bundled CLI exceeds the extraction limit");
                }
            }
        } catch (IOException failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    private void validate(Path root) throws IOException {
        for (String required : REQUIRED) {
            if (!Files.isRegularFile(root.resolve(required)))
                throw new IOException("Bundled CLI is incomplete: " + required + " is missing");
        }
        String expected = bundledVersion();
        String actual = readVersion(root.resolve("VERSION"));
        if (expected == null || !expected.equals(actual))
            throw new IOException("Bundled CLI version does not match its manifest");
        Path unix = root.resolve("bin/repobuddy");
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(unix));
            permissions.addAll(PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(unix, permissions);
        } catch (UnsupportedOperationException ignored) { }
    }

    private static long copyBounded(InputStream input, Path target, long maximum) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > maximum) throw new IOException("Bundled CLI entry exceeds the extraction limit");
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private static String readVersion(Path path) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? null : value;
        } catch (IOException ignored) { return null; }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static Path defaultDirectory(Map<String, String> environment, String osName, Path home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = environment.get("LOCALAPPDATA");
            return (local == null || local.isBlank() ? home.resolve("AppData/Local") : Path.of(local))
                    .resolve("RepoBuddy/cli");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/RepoBuddy/cli");
        String xdg = environment.get("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg) : home.resolve(".local/share");
        return data.resolve("RepoBuddy/cli");
    }
}
