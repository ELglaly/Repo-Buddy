package com.repoinspector.ipc;

import com.repoinspector.core.RepoBuddyException;
import com.repoinspector.core.RepoBuddyErrorCode;
import com.repoinspector.core.RepoBuddyJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.EnumSet;

public final class SessionDiscovery {
    private SessionDiscovery() {}

    public static Path directory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local == null || local.isBlank() ? System.getProperty("java.io.tmpdir") : local,
                    "RepoBuddy", "sessions");
        }
        if (os.contains("mac")) return Path.of(System.getProperty("user.home"), "Library", "Caches", "RepoBuddy", "sessions");
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        return Path.of(runtime == null || runtime.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache").toString() : runtime, "repobuddy", "sessions");
    }

    public static Path write(SessionDescriptor descriptor) throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        restrict(directory, true);
        Path file = directory.resolve(descriptor.pid() + "-" + descriptor.projectId() + ".json");
        Files.writeString(file, RepoBuddyJson.gson(false).toJson(descriptor));
        restrict(file, false);
        return file;
    }

    public static List<SessionDescriptor> readAll() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) return List.of();
        List<SessionDescriptor> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> read(path, result));
        } catch (IOException ignored) {
            return List.of();
        }
        return result;
    }

    public static SessionDescriptor select(Path workingDirectory, String selector) {
        List<SessionDescriptor> candidates = readAll().stream()
                .filter(value -> IpcRequest.VERSION.equals(value.protocolVersion()))
                .filter(value -> ProcessHandle.of(value.pid()).map(ProcessHandle::isAlive).orElse(false))
                .filter(value -> selector == null || matches(value, selector))
                .filter(value -> selector != null || isWithin(workingDirectory, Path.of(value.projectRoot())))
                .toList();
        if (candidates.isEmpty()) throw new RepoBuddyException(
                selector == null ? RepoBuddyErrorCode.REPOBUDDY_IDE_NOT_RUNNING : RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_FOUND,
                selector == null ? "No enabled RepoBuddy IntelliJ integration matches the current directory"
                        : "No enabled RepoBuddy IntelliJ integration matches project selector '" + selector + "'. Use a project id from repobuddy_list_projects.");
        if (candidates.size() > 1) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_AMBIGUOUS,
                "Multiple RepoBuddy projects match selector '" + selector + "'; use the id from repobuddy_list_projects");
        return candidates.get(0);
    }

    static boolean matches(SessionDescriptor value, String selector) {
        String normalized = selector.trim();
        if (value.projectId().equals(normalized) || value.projectName().equals(normalized)) return true;
        try { return Path.of(value.projectRoot()).toRealPath().equals(Path.of(normalized).toRealPath()); }
        catch (IOException ignored) { return Path.of(value.projectRoot()).toAbsolutePath().normalize().toString()
                .equals(Path.of(normalized).toAbsolutePath().normalize().toString()); }
        catch (java.nio.file.InvalidPathException ignored) { return false; }
    }

    private static void read(Path path, List<SessionDescriptor> sink) {
        try {
            SessionDescriptor descriptor = RepoBuddyJson.gson(false).fromJson(Files.readString(path), SessionDescriptor.class);
            if (descriptor != null && ProcessHandle.of(descriptor.pid()).map(ProcessHandle::isAlive).orElse(false)) sink.add(descriptor);
            else Files.deleteIfExists(path);
        }
        catch (RuntimeException | IOException ignored) { }
    }

    private static boolean isWithin(Path child, Path root) {
        try { return child.toRealPath().startsWith(root.toRealPath()); }
        catch (IOException ignored) { return child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()); }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        try {
            Set<PosixFilePermission> permissions = directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) throw new IOException("Filesystem does not support owner-only session permissions");
            var owner = Files.getOwner(path);
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            view.setAcl(List.of(builder.build()));
        }
    }
}
