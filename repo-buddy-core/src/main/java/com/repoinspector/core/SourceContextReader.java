package com.repoinspector.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a bounded line window after canonical project containment validation. */
public final class SourceContextReader {
    private SourceContextReader() {}
    public record Result(int startLine, int endLine, String code, boolean truncated) {}

    public static Result read(Path projectRoot, String relativePath, int targetLine, int contextLines,
                              int maximumContextLines, int maximumBytes) {
        if (contextLines < 0 || contextLines > maximumContextLines) throw new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                "Context lines must be between 0 and " + maximumContextLines);
        Path root = real(projectRoot);
        Path relative;
        try { relative = Path.of(relativePath); }
        catch (RuntimeException error) { throw outside(); }
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw outside();
        Path unresolved = root.resolve(relative).normalize();
        if (!Files.exists(unresolved)) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_ISSUE_NOT_FOUND,
                "The source file for this issue is unavailable");
        Path file = real(unresolved);
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw outside();
        return readLines(file, Math.max(1, targetLine), contextLines, maximumBytes, StandardCharsets.UTF_8);
    }

    private static Result readLines(Path file, int target, int lines, int maximumBytes, Charset charset) {
        int start = Math.max(1, target - lines);
        int wantedEnd = target + lines;
        StringBuilder code = new StringBuilder();
        int bytes = 0;
        int end = start - 1;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String value;
            int number = 0;
            while ((value = reader.readLine()) != null && number < wantedEnd) {
                number++;
                if (number < start) continue;
                byte[] encoded = (value + System.lineSeparator()).getBytes(charset);
                if (bytes + encoded.length > maximumBytes) { truncated = true; break; }
                code.append(value).append(System.lineSeparator());
                bytes += encoded.length;
                end = number;
            }
        } catch (IOException error) {
            throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_ISSUE_NOT_FOUND,
                    "The source file for this issue is unavailable", error);
        }
        return new Result(start, end, code.toString(), truncated);
    }

    private static Path real(Path path) {
        try { return path.toRealPath(); }
        catch (IOException error) { throw outside(); }
    }

    private static RepoBuddyException outside() {
        return new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PATH_OUTSIDE_PROJECT,
                "Source context is outside the active project");
    }
}
