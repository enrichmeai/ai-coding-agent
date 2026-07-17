package com.example.agent.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves paths relative to the agent's workspace and prevents path-traversal escapes.
 */
public final class WorkspacePath {

    private WorkspacePath() {}

    /**
     * Resolve {@code input} against the workspace. Both absolute and relative paths are allowed,
     * but the resulting path MUST remain inside the workspace.
     *
     * @throws SecurityException if the path escapes the workspace.
     */
    public static Path resolve(Path workspace, String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path raw = Paths.get(input);
        Path resolved = (raw.isAbsolute() ? raw : workspace.resolve(raw)).normalize().toAbsolutePath();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("Path escapes workspace: " + input);
        }
        return resolved;
    }

    /** Path relative to the workspace, for display. */
    public static String displayRelative(Path workspace, Path absolute) {
        Path rel = workspace.relativize(absolute);
        return rel.toString().isEmpty() ? "." : rel.toString();
    }
}
